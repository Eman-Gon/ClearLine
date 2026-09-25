import asyncio
import json
from datetime import datetime,timedelta,timezone
from uuid import uuid5,NAMESPACE_URL
from fastapi import HTTPException
from backend.calls.routes import CallRequest
from backend.calls.vapi import VapiError
from backend.integrations.common import IntegrationError
from .models import next_daily
from .storage import initialize,call_record
from .memory import PhoneMemory
from .analysis import PhoneAnalyst
from .notifications import PushSender

def utcnow(): return datetime.now(timezone.utc)
def stamp(value=None): return (value or utcnow()).isoformat()

class PhoneWorker:
    def __init__(self,store,dispatch,memory=None,analyst=None,push=None,clock=utcnow):
        self.store=store; self.dispatch=dispatch; self.memory=memory or PhoneMemory(); self.analyst=analyst or PhoneAnalyst(); self.push=push or PushSender(); self.clock=clock; self.stopping=False
        initialize(store)
    async def close(self):
        for service in (self.memory,self.analyst):
            if hasattr(service,'aclose'): await service.aclose()
    async def run(self):
        while not self.stopping:
            try: await self.tick()
            except asyncio.CancelledError: raise
            except Exception:
                # Per-step errors are recorded below. Unexpected loop errors must
                # not expose private payloads in logs or kill future scheduling.
                import logging
                logging.getLogger(__name__).error('phone_worker_tick_failed')
            await asyncio.sleep(1)
    def enqueue_notifications(self,sid,profile):
        with self.store.transaction() as c:
            for device in c.execute('SELECT device_id FROM phone_devices WHERE profile_id=?',(profile,)).fetchall():
                nid=str(uuid5(NAMESPACE_URL,sid+':'+device['device_id']))
                c.execute("INSERT OR IGNORE INTO phone_notifications(notification_id,session_id,device_id,state) VALUES(?,?,?,'pending')",(nid,sid,device['device_id']))
    def fail_call(self,sid,profile,code):
        with self.store.transaction() as c:
            c.execute("INSERT OR IGNORE INTO phone_reports(session_id,profile_id,stage,report_json,error_code,updated_at) VALUES(?,?,'failed',?,?,?)",(sid,profile,json.dumps({'status':'call_failed','error_code':code}),code,stamp(self.clock())))
        self.enqueue_notifications(sid,profile)
    async def tick(self):
        await self.schedule_tick()
        await self.report_tick()
        await self.notification_tick()
    async def schedule_tick(self):
        now=self.clock()
        with self.store.transaction() as c:
            due=c.execute('SELECT * FROM phone_schedules WHERE enabled=1 AND next_due IS NOT NULL AND next_due<=? ORDER BY next_due LIMIT 1',(stamp(now),)).fetchone()
            if due:
                config=json.loads(due['config_json']); when=datetime.fromisoformat(due['next_due'])
                oid=str(uuid5(NAMESPACE_URL,due['schedule_id']+':'+due['next_due']))
                late=(now-when).total_seconds()>900
                c.execute('INSERT OR IGNORE INTO phone_occurrences(occurrence_id,schedule_id,due_at,state,error_code) VALUES(?,?,?,?,?)',(oid,due['schedule_id'],due['next_due'],'missed' if late else 'pending','schedule_missed_window' if late else None))
                upcoming=stamp(next_daily(when,config['timezone'],now)) if config['recurrence']=='daily' else None
                c.execute('UPDATE phone_schedules SET next_due=? WHERE schedule_id=?',(upcoming,due['schedule_id']))
            pending=c.execute("SELECT o.*,s.profile_id,s.config_json,s.enabled FROM phone_occurrences o JOIN phone_schedules s USING(schedule_id) WHERE o.state='pending' ORDER BY o.due_at LIMIT 1").fetchone()
        if due and late: self.fail_call(oid,due['profile_id'],'schedule_missed_window')
        if not pending: return
        if not pending['enabled']:
            with self.store.transaction() as c: c.execute("UPDATE phone_occurrences SET state='cancelled' WHERE occurrence_id=?",(pending['occurrence_id'],))
            return
        config=json.loads(pending['config_json']); oid=pending['occurrence_id']
        body=CallRequest(request_id=oid,**{k:config[k] for k in CallRequest.model_fields if k!='request_id' and k in config})
        try:
            result=await self.dispatch(body)
            state=result['state']; code=result.get('error_code')
        except HTTPException as exc:
            state='failed'; code=exc.detail.get('code','call_dispatch_failed') if isinstance(exc.detail,dict) else 'call_dispatch_failed'
        except Exception:
            # No automatic re-dispatch following unknown failure. The calls ledger
            # may have sent the request; a human must inspect the provider.
            state='dispatch_unknown'; code='call_dispatch_uncertain'
        with self.store.transaction() as c: c.execute('UPDATE phone_occurrences SET state=?,error_code=? WHERE occurrence_id=?',(state,code,oid))
        if state in ('failed','dispatch_unknown'): self.fail_call(oid,config['profile_id'],code or state)
    async def report_tick(self):
        now=self.clock()
        with self.store.transaction() as c:
            # Create one durable pipeline per completed report; duplicate webhooks
            # cannot create another session or analysis chain.
            for call in c.execute("SELECT * FROM telephone_calls WHERE state IN ('completed','consent_unconfirmed','transcript_unavailable','failed','dispatch_unknown') AND request_id NOT IN (SELECT session_id FROM phone_reports) LIMIT 10").fetchall():
                stage='history' if call['state']=='completed' else 'failed'
                options=json.loads(call['call_options_json'])
                code=None if stage=='history' else (call['error_code'] or call['state'])
                c.execute('INSERT INTO phone_reports(session_id,profile_id,stage,report_json,error_code,updated_at) VALUES(?,?,?,?,?,?)',(call['request_id'],call['profile_id'],stage,json.dumps({'data_origin':options.get('data_origin','unknown'),'status':'pending' if not code else 'call_failed'}),code,stamp(now)))
            failed=c.execute("SELECT session_id,profile_id FROM phone_reports WHERE stage='failed'").fetchall()
            row=c.execute("SELECT r.*,c.transcript,c.created_at,c.call_options_json,c.request_id FROM phone_reports r JOIN telephone_calls c ON c.request_id=r.session_id WHERE r.stage IN ('history','analysis','search','store') AND (r.next_attempt IS NULL OR r.next_attempt<=?) ORDER BY r.updated_at LIMIT 1",(stamp(now),)).fetchone()
            stale=c.execute("SELECT request_id,profile_id FROM telephone_calls WHERE state IN ('queued','dispatching') AND created_at<?",(stamp(now-timedelta(minutes=15)),)).fetchall()
        for r in failed: self.enqueue_notifications(r['session_id'],r['profile_id'])
        for r in stale: self.fail_call(r['request_id'],r['profile_id'],'completed_report_overdue')
        if not row: return
        call=call_record(row); report=json.loads(row['report_json']); stage=row['stage']
        try:
            if not call.get('rawtree_storage_approved'): raise IntegrationError('rawtree_consent_required','Session storage not authorized')
            if stage=='history':
                report['rawtree']=await self.memory.history(call['profile_id'],call['session_id'],call['data_origin']); next_stage='analysis'
            elif stage=='analysis':
                report['liquid']=await self.analyst.analyse(call,report['rawtree']); next_stage='search'
            elif stage=='search':
                report['nimble']=await self.analyst.search(report['liquid'],call.get('city',''),call.get('resource_search_approved',False)); next_stage='store'
            else:
                report['rawtree_write']=await self.memory.save(call,report); next_stage='complete'; report['status']='complete'
            with self.store.transaction() as c:
                c.execute('UPDATE phone_reports SET stage=?,report_json=?,attempts=0,next_attempt=NULL,error_code=NULL,updated_at=? WHERE session_id=?',(next_stage,json.dumps(report),stamp(now),call['session_id']))
            if next_stage=='complete': self.enqueue_notifications(call['session_id'],call['profile_id'])
        except Exception as exc:
            code=exc.code if isinstance(exc,IntegrationError) else 'phone_pipeline_failed'
            attempt=row['attempts']+1; retry=isinstance(exc,IntegrationError) and exc.retryable and attempt<3
            report['resume_stage']=stage; report['status']='retrying' if retry else 'failed'; report['error_code']=code
            with self.store.transaction() as c:
                c.execute('UPDATE phone_reports SET stage=?,report_json=?,attempts=?,next_attempt=?,error_code=?,updated_at=? WHERE session_id=?',(stage if retry else 'failed',json.dumps(report),attempt,stamp(now+timedelta(seconds=30*attempt)) if retry else None,code,stamp(now),call['session_id']))
            if not retry: self.enqueue_notifications(call['session_id'],call['profile_id'])
    async def notification_tick(self):
        now=self.clock()
        with self.store.connection() as c:
            row=c.execute("SELECT n.*,d.token FROM phone_notifications n JOIN phone_devices d USING(device_id) WHERE n.state='pending' AND (n.next_attempt IS NULL OR n.next_attempt<=?) LIMIT 1",(stamp(now),)).fetchone()
        if not row: return
        try:
            await self.push.send(row['token'],row['session_id']); state='accepted_by_provider'; code=None
        except Exception as exc:
            code=exc.code if isinstance(exc,IntegrationError) else 'push_send_failed'
            state='pending' if isinstance(exc,IntegrationError) and exc.retryable and row['attempts']<2 else 'failed'
        with self.store.transaction() as c:
            c.execute('UPDATE phone_notifications SET state=?,error_code=?,attempts=attempts+1,next_attempt=? WHERE notification_id=?',(state,code,stamp(now+timedelta(seconds=60)) if state=='pending' else None,row['notification_id']))
