"""One serial durable worker. SQLite is the queue and action source of truth."""
import asyncio
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import sqlite3
from uuid import uuid4

from backend.audio import AudioError, aggregate_metrics
from backend.worker.interfaces import AgentUnavailable


def utcnow():
    return datetime.now(timezone.utc).isoformat()


def retry_time(seconds=10):
    return (datetime.now(timezone.utc)+timedelta(seconds=seconds)).isoformat()


def loads(value, default=None):
    return json.loads(value) if value else default


class Worker:
    def __init__(self, store, audio, agent, exporter, settings):
        self.store,self.audio,self.agent,self.exporter,self.settings=store,audio,agent,exporter,settings
        self.stop_requested=False

    async def run(self):
        while not self.stop_requested:
            try:
                worked=await self.run_once()
                await self.deliver_outbox_once()
            except Exception:
                # Durable jobs remain recoverable. Do not leak API keys or tool text.
                import logging
                logging.getLogger(__name__).error('Worker iteration failed; pending work remains in SQLite.')
                worked=False
            if not worked:
                await asyncio.sleep(0.25)

    def connection(self):
        return self.store.connection()

    def cleanup_media(self):
        with self.connection() as conn:
            retained={str(Path(row[0]).resolve()) for row in conn.execute("SELECT media_path FROM clips WHERE status IN ('queued','processing')") if row[0]}
        for path in Path(self.settings.upload_dir).glob('clip-*.media'):
            if str(path.resolve()) not in retained:
                path.unlink(missing_ok=True)

    async def run_once(self):
        job=self.store.claim_job()
        if not job:
            return False
        job=dict(job)
        job['payload']=job.get('payload') or loads(job.get('payload_json'),{})
        try:
            if job['kind']=='audio':
                await self.process_audio(job)
            elif job['kind']=='summarize':
                self.summarize(job)
            elif job['kind']=='agent':
                await self.advance_agent(job)
            else:
                raise ValueError('Unsupported durable job kind')
        except Exception as exc:
            retryable=bool(getattr(exc,'retryable',False))
            code=getattr(exc,'code','worker_error')
            # Store codes, not arbitrary exceptions that may contain secrets or text.
            with self.store.transaction() as conn:
                session=self.store.get_session(job['session_id'],conn=conn)
                if job['kind']=='agent' and session.get('resource_revision' if job['payload'].get('scope')=='resources' else 'comparison_revision',0)!=job['payload'].get('revision'):
                    self.store.complete_job(conn,job['job_id'])
                    return True
                phase='waiting_retry' if retryable else 'agent_unavailable'
                patch={'phase':phase,'resume_phase':'researching' if job['payload'].get('scope')=='resources' else 'comparing','errors':[{'code':code,'retryable':retryable,'message':'The pending step could not complete. Check local service configuration and resume.'}]}
                if session.get('pause_requested'):
                    patch.update(phase='paused',resume_phase=phase)
                self.store.update_session(conn,job['session_id'],patch)
                self.store.retry_job(conn,job['job_id'],code,retry_time() if retryable else None)
                if not retryable:
                    conn.execute("UPDATE jobs SET status='failed' WHERE job_id=?",(job['job_id'],))
        return True

    def commit(self, conn, job, patch):
        session=self.store.get_session(job['session_id'],conn=conn)
        if session.get('pause_requested'):
            patch.update(resume_phase=patch.get('phase',session['phase']),phase='paused')
        self.store.update_session(conn,job['session_id'],patch)
        self.store.complete_job(conn,job['job_id'])

    async def process_audio(self,job):
        sid,cid=job['session_id'],job['payload']['clip_id']
        with self.connection() as conn:
            clip=conn.execute('SELECT * FROM clips WHERE session_id=? AND clip_id=?',(sid,cid)).fetchone()
        if not clip:
            raise ValueError('Missing clip ledger row')
        if clip['status'] in {'accepted','rejected'}:
            with self.store.transaction() as conn:
                self.store.complete_job(conn,job['job_id'])
            return
        session=self.store.get_session(sid)
        error=None
        try:
            if not clip['media_path'] or not Path(clip['media_path']).is_file():
                raise AudioError('missing_audio','Please record a replacement clip.')
            result=await asyncio.to_thread(self.audio.process,clip['media_path'],data_origin=session['data_origin'])
        except AudioError as exc:
            error={'code':exc.code,'message':str(exc),'retryable':exc.retryable}
        if error and error['code'] in {'whisper_unavailable','ffmpeg_unavailable','audio_dependency_unavailable','transcription_failed'}:
            # Preserve accepted input while a missing local dependency is repaired.
            with self.store.transaction() as conn:
                patch={'phase':'awaiting_input','errors':[error],'pending_input':{'reason_code':error['code'],'question':'Configure the local audio dependency on the laptop, then resume this session.'}}
                self.commit(conn,job,patch)
                conn.execute("UPDATE jobs SET status='failed' WHERE job_id=?",(job['job_id'],))
            return
        with self.store.transaction() as conn:
            session=self.store.get_session(sid,conn=conn)
            if error:
                conn.execute("UPDATE clips SET status='rejected',error_json=?,media_path=NULL,updated_at=? WHERE session_id=? AND clip_id=?",(json.dumps(error),utcnow(),sid,cid))
                patch={'phase':'awaiting_input','errors':[error],'pending_input':{'reason_code':'replacement_recording','question':'Please record a replacement clip.'}}
            else:
                conn.execute("UPDATE clips SET status='accepted',metrics_json=?,transcript=?,method_version=?,media_path=NULL,updated_at=? WHERE session_id=? AND clip_id=?",(json.dumps(result['metrics']),result['transcript'],result['method_version'],utcnow(),sid,cid))
                if clip['supersedes_clip_id']:
                    conn.execute('UPDATE clips SET superseded_by=? WHERE session_id=? AND clip_id=?',(cid,sid,clip['supersedes_clip_id']))
                refs=[row[0] for row in conn.execute("SELECT clip_id FROM clips WHERE session_id=? AND status='accepted' AND superseded_by IS NULL",(sid,))]
                patch={'phase':'processing' if session.get('capture_finished') else 'recording','accepted_clip_refs':refs,'pending_input':None,'errors':[],'method_version':result['method_version']}
            self.commit(conn,job,patch)
            if session.get('capture_finished'):
                self.store.enqueue_job(conn,sid,'summarize',f'{sid}:summary:clip:{cid}',{})
        if clip['media_path']:
            Path(clip['media_path']).unlink(missing_ok=True)

    def summarize(self,job):
        sid=job['session_id']
        with self.store.transaction() as conn:
            session=self.store.get_session(sid,conn=conn)
            if conn.execute("SELECT 1 FROM clips WHERE session_id=? AND status IN ('queued','processing')",(sid,)).fetchone():
                self.store.retry_job(conn,job['job_id'],'audio_pending',retry_time(1))
                return
            clips=conn.execute("SELECT * FROM clips WHERE session_id=? AND status='accepted' AND superseded_by IS NULL ORDER BY created_at",(sid,)).fetchall()
            metrics=aggregate_metrics([loads(row['metrics_json']) for row in clips])
            if not metrics:
                self.commit(conn,job,{'phase':'awaiting_input','pending_input':{'reason_code':'replacement_recording','question':'No usable recording is available. Please record a replacement.'}})
                return
            # Multiple finishing/audio jobs can converge; do not create another
            # version when the set of accepted measurements has not changed.
            fingerprint=hashlib.sha256(json.dumps([(r['clip_id'],r['metrics_json'],r['transcript']) for r in clips]).encode()).hexdigest()
            if session.get('summary_fingerprint')==fingerprint:
                self.store.complete_job(conn,job['job_id'])
                return
            summary=self.store.save_summary(conn,sid,metrics,clips[0]['method_version'])
            revision=session.get('comparison_revision',0)+1
            self.commit(conn,job,{'metrics':metrics,'comparison':None,'baseline':None,'baseline_ref':None,'current_measurements_ref':summary['summary_ref'],'summary_fingerprint':fingerprint,'comparison_revision':revision,'method_version':clips[0]['method_version'],'completed_at':utcnow(),'phase':'comparing','pending_input':None,'errors':[]})
            self.store.enqueue_job(conn,sid,'agent',f'{sid}:agent:comparison:{revision}:0',{'scope':'comparison','revision':revision,'step':0})

    def checkpoint(self,session,scope):
        keys=('session_id','profile_id','state_version','input_revision','phase','metrics','comparison','baseline','baseline_ref','current_measurements_ref','resource_request','resources','evidence_refs','pending_action','recent_completed_action_refs','completed_action_watermark','unresolved_requirements','execution_mode','last_model_call','last_tool_result','recording_task','accepted_clip_refs')
        checkpoint={key:session.get(key) for key in keys}
        checkpoint.update(schema_version=1,workflow_scope=scope, goal='Find user-requested public caregiver resources' if scope=='resources' else 'Compare descriptive recording measurements', measurement_version=session.get('method_version'),method_version=session.get('method_version'),provenance={'data_origin':session['data_origin']},data_origin=session['data_origin'],cloud_export=bool(session['consent'].get('cloud_export')),permitted_tools=self.permitted(session,scope),sources=session.get('sources',[])[:3])
        scoped=(session.get('scope_checkpoints') or {}).get(scope,{})
        revision=session.get('resource_revision' if scope=='resources' else 'comparison_revision',0)
        checkpoint['last_model_call']=scoped.get('last_model_call') if scoped.get('revision')==revision else None
        checkpoint['last_tool_result']=scoped.get('last_tool_result') if scoped.get('revision')==revision else None
        if session.get('user_answer'):
            checkpoint['user_answer']=session['user_answer'][:500]
        return checkpoint

    def permitted(self,session,scope):
        if scope=='comparison':
            tools=['request_user_input']
            if not session.get('baseline'):
                tools.append('get_baseline_summary')
            elif not session.get('comparison'):
                tools.append('compare_recording_metrics')
            else:
                tools.append('finish_task')
            return tools
        if not (session.get('resource_request') or {}).get('approved'):
            return ['request_user_input']
        tools=['request_user_input']
        if not session.get('sources'):
            tools.append('search_public_resources')
        else:
            tools.append('extract_public_page')
        if session.get('evidence_refs'):
            tools.append('finish_task')
        return tools

    async def advance_agent(self,job):
        sid,payload=job['session_id'],job['payload']
        scope=payload['scope']
        session=self.store.get_session(sid)
        revision_key='resource_revision' if scope=='resources' else 'comparison_revision'
        if session.get(revision_key,0)!=payload['revision']:
            with self.store.transaction() as conn:
                self.store.complete_job(conn,job['job_id'])
            return
        if payload['step']>=self.settings.max_action_count:
            with self.store.transaction() as conn:
                self.commit(conn,job,{'phase':'awaiting_input','pending_input':{'reason_code':'task_budget','question':'The tool budget was reached. Refine the request to continue.'}})
            return
        checkpoint=self.checkpoint(session,scope)
        action=payload.get('action')
        if not action:
            proposal=await self.agent.advance(checkpoint)
            if not isinstance(proposal,dict) or proposal.get('name') not in checkpoint['permitted_tools'] or not isinstance(proposal.get('arguments'),dict):
                raise AgentUnavailable('Invalid model-selected tool call.')
            self.validate_action(proposal,session,scope)
            with self.store.transaction() as conn:
                planned=self.store.plan_action(conn,sid,proposal['name'],proposal['arguments'],job['job_id'],input_revision=payload['revision'])
                action={**proposal,'action_id':planned['action_id'],'scope':scope,'revision':payload['revision']}
                payload['action']=action
                conn.execute('UPDATE jobs SET payload_json=? WHERE job_id=?',(json.dumps(payload),job['job_id']))
                self.store.update_session(conn,sid,{'pending_action':action,'execution_mode':'liquid_local'})
        self.validate_action(action,session,scope)
        with self.store.transaction() as conn:
            ledger=self.store.start_action(conn,action['action_id'])
        try:
            if ledger['status']=='succeeded':
                result=ledger['result']
            elif action['name']=='compare_recording_metrics':
                result=self.compare(session['metrics'],session['baseline'])
            elif action['name'] in {'request_user_input','finish_task'}:
                result=action['arguments']
            else:
                result=await self.agent.execute(action,checkpoint)
            if not isinstance(result,dict):
                raise AgentUnavailable('Invalid tool result shape.')
        except Exception as exc:
            self.tool_failure(job, action, exc)
            return
        with self.store.transaction() as conn:
            latest=self.store.get_session(sid,conn=conn)
            if latest.get(revision_key,0)!=payload['revision']:
                self.store.finish_action(conn,action['action_id'],result)
                self.store.complete_job(conn,job['job_id'])
                return
            patch=self.result_patch(action,result,latest,scope)
            refs=(latest.get('recent_completed_action_refs') or [])[-7:]+[action['action_id']]
            scoped=dict(latest.get('scope_checkpoints') or {})
            scoped[scope]={'revision':payload['revision'],'last_model_call':action.get('model_call'),'last_tool_result':result}
            patch.update(scope_checkpoints=scoped,pending_action=None,recent_completed_action_refs=refs,completed_action_watermark=latest.get('completed_action_watermark',0)+1,last_model_call=action.get('model_call'),last_tool_result=result,context_usage=action.get('context_usage'),errors=[])
            self.store.finish_action(conn,action['action_id'],result)
            if action['name']=='extract_public_page':
                fields=('source_ref','evidence_ref','version','url','retrieved_at','content_hash','verification_status','facts','request_id','task_id')
                projection={key:result[key] for key in fields if key in result}
                self.store.enqueue_export(conn,latest,'resource_version',projection)
            self.commit(conn,job,patch)
            if action['name'] not in {'finish_task','request_user_input'}:
                step=payload['step']+1
                self.store.enqueue_job(conn,sid,'agent',f'{sid}:agent:{scope}:{payload["revision"]}:{step}',{k:v for k,v in {**payload,'step':step}.items() if k!='action'})

    def tool_failure(self, job, action, exc):
        payload=job['payload']
        scope=payload['scope']
        retryable=bool(getattr(exc,'retryable',False))
        code=getattr(exc,'code','tool_failed')
        result={'code':code,'error':'The source request failed. Choose a permitted next action or request missing input.','retryable':retryable}
        with self.store.transaction() as conn:
            latest=self.store.get_session(job['session_id'],conn=conn)
            self.store.fail_action(conn,action['action_id'],code)
            if latest.get('resource_revision' if scope=='resources' else 'comparison_revision',0)!=payload['revision']:
                self.store.complete_job(conn,job['job_id'])
                return
            scoped=dict(latest.get('scope_checkpoints') or {})
            scoped[scope]={'revision':payload['revision'],'last_model_call':action.get('model_call'),'last_tool_result':result}
            phase='waiting_retry' if retryable else 'agent_unavailable'
            self.commit(conn,job,{'phase':phase,'resume_phase':'researching' if scope=='resources' else 'comparing','scope_checkpoints':scoped,'last_model_call':action.get('model_call'),'last_tool_result':result,'pending_action':{**action,'status':'failed'},'errors':[{'code':code,'retryable':retryable,'message':'The pending source request failed.'}]})
            step=payload['step']+1
            next_job=self.store.enqueue_job(conn,job['session_id'],'agent',f'{job["session_id"]}:agent:{scope}:{payload["revision"]}:{step}',{'scope':scope,'revision':payload['revision'],'step':step})
            if retryable:
                self.store.retry_job(conn,next_job['job_id'],code,retry_time())
            else:
                self.store.fail_job(conn,next_job['job_id'],code)

    def validate_action(self,action,session,scope):
        args=action.get('arguments',{})
        name=action['name']
        if name not in self.permitted(session,scope):
            raise AgentUnavailable('Tool not permitted in this phase.')
        if args.get('session_id',session['session_id'])!=session['session_id'] or args.get('profile_id',session['profile_id'])!=session['profile_id']:
            raise AgentUnavailable('Tool identity mismatch.')
        if name=='search_public_resources':
            request=session['resource_request']
            if not request.get('approved') or any(args.get(k)!=request[k] for k in ('category','city')):
                raise AgentUnavailable('Search does not match approved request.')
        if name=='extract_public_page' and args.get('source_ref') not in [s.get('source_ref') for s in session.get('sources',[])]:
            raise AgentUnavailable('Source was not returned for this request.')
        if name=='request_user_input' and (not isinstance(args.get('question'),str) or not 1<=len(args['question'])<=500):
            raise AgentUnavailable('Invalid input request.')
        if name=='finish_task':
            if scope=='comparison' and not session.get('comparison'):
                raise AgentUnavailable('Comparison remains unfinished.')
            if scope=='resources' and (not session.get('evidence_refs') or not args.get('evidence_refs') or not set(args['evidence_refs']).issubset(set(session['evidence_refs']))):
                raise AgentUnavailable('Source-backed evidence remains unfinished.')

    def result_patch(self,action,result,session,scope):
        name=action['name']
        if name=='get_baseline_summary':
            return {'baseline':result,'baseline_ref':result.get('baseline_ref'),'phase':'comparing'}
        if name=='compare_recording_metrics':
            return {'comparison':result,'phase':'comparing'}
        if name=='search_public_resources':
            sources=result.get('sources',result.get('results',[]))
            if not isinstance(sources,list) or len(sources)>3:
                raise AgentUnavailable('Invalid source result.')
            return {'sources':sources,'phase':'researching'}
        if name=='extract_public_page':
            if not result.get('evidence_ref') or result.get('verification_status')!='source_backed':
                raise AgentUnavailable('Source extraction is incomplete.')
            resources=[r for r in session.get('resources',[]) if r.get('source_ref')!=result.get('source_ref')]+[result]
            return {'resources':resources[-3:],'evidence_refs':list(dict.fromkeys([*session.get('evidence_refs',[]),result['evidence_ref']]))[-8:],'phase':'researching'}
        if name=='request_user_input':
            return {'pending_input':{'reason_code':result.get('reason_code','missing_input'),'question':result['question'],'scope':scope},'phase':'awaiting_input'}
        return {'phase':'ready' if scope=='resources' else 'awaiting_user_choice','pending_input':None,'unresolved_requirements':[]}

    @staticmethod
    def compare(metrics,baseline):
        count=baseline.get('session_count',0)
        result={'status':'ok' if count>=2 else 'insufficient_history','session_count':count,'baseline_source':baseline.get('baseline_source'), 'metrics':{},'interpretation':'No health interpretation is provided.'}
        for key in ('duration_s','word_count','recording_wpm','pause_count','energy_rms','pitch_mean_hz'):
            summary=baseline.get('metrics',{}).get(key) or {}
            current,mean=metrics.get(key),summary.get('mean')
            std=summary.get('std',summary.get('stddev'))
            delta=current-mean if current is not None and mean is not None and count>=2 else None
            result['metrics'][key]={'current':current,'mean':mean,'delta':delta,'standardized_difference':delta/std if delta is not None and std is not None and std>0 else None}
        return result

    async def deliver_outbox_once(self):
        if self.exporter is None:
            return False
        item=self.store.claim_outbox()
        if not item:
            return False
        try:
            await self.exporter.append_event(item['table_name'],item['payload'])
        except Exception:
            with self.store.transaction() as conn:
                self.store.retry_outbox(conn,item['event_id'],'export_failed',retry_time())
        else:
            with self.store.transaction() as conn:
                self.store.ack_outbox(conn,item['event_id'])
        return True
