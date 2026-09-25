import hashlib
import hmac
import json
import os
from pathlib import Path
import secrets
import sqlite3
import tempfile
from uuid import UUID, uuid4

from fastapi import APIRouter, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import JSONResponse

from backend.api.models import CreateSession, ResourceRequest, UserInput
from backend.audio import AudioError, corrected_metrics


class UploadTooLarge(Exception):
    pass


class UploadLimitMiddleware:
    def __init__(self, app, max_bytes):
        self.app, self.max_bytes = app, max_bytes

    async def __call__(self, scope, receive, send):
        if scope['type'] != 'http' or scope.get('method') != 'POST':
            return await self.app(scope, receive, send)
        total = 0
        async def limited_receive():
            nonlocal total
            message = await receive()
            total += len(message.get('body', b''))
            if total > self.max_bytes:
                raise UploadTooLarge()
            return message
        try:
            await self.app(scope, limited_receive, send)
        except UploadTooLarge:
            await JSONResponse({'detail': {'code': 'upload_too_large'}}, status_code=413)(scope, receive, send)


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def read_conn(store):
    return store.connection()


def authorize(store, session_id, request):
    try:
        session = store.get_session(str(session_id))
    except (KeyError, ValueError):
        session = None
    if not session:
        raise HTTPException(404, detail={'code': 'session_not_found'})
    token = request.headers.get('authorization', '').removeprefix('Bearer ')
    if not token or not hmac.compare_digest(digest(token), session['token_hash']):
        raise HTTPException(401, detail={'code': 'invalid_resume_token'})
    return session


def public_status(store, session_id):
    session = store.get_session(session_id)
    keys = ('session_id','profile_id','state_version','input_revision','phase','execution_mode','metrics','comparison','resources','pending_action','errors','provenance','cloud_sync','consent','pending_input','capture_finished','resource_request','pause_requested')
    result = {key: session.get(key) for key in keys}
    with read_conn(store) as conn:
        result['clips'] = [dict(row) for row in conn.execute("SELECT clip_id,status,superseded_by,CASE WHEN status='accepted' AND superseded_by IS NULL THEN transcript ELSE NULL END AS transcript FROM clips WHERE session_id=? ORDER BY created_at", (session_id,))]
        result['events'] = [dict(row) for row in conn.execute('SELECT event_id,name,status,created_at FROM events WHERE session_id=? UNION ALL SELECT action_id AS event_id,name,status,updated_at AS created_at FROM actions WHERE session_id=? ORDER BY created_at DESC LIMIT 25', (session_id,session_id))]
        counts = dict(conn.execute('SELECT status,COUNT(*) FROM outbox WHERE session_id=? GROUP BY status', (session_id,)).fetchall())
    result['provenance'] = result['provenance'] or {'data_origin': session['data_origin'], 'baseline_source': None}
    result['cloud_sync'] = {'mode': 'approved' if session['consent'].get('cloud_export') else 'restricted', 'pending': counts.get('pending',0)+counts.get('retry',0), 'delivered':counts.get('delivered',0), 'failed':counts.get('failed',0)}
    result['errors'] = result['errors'] or []
    resource_fields={'source_ref','evidence_ref','title','url','retrieved_at','verification_status','facts','content_hash','version','passages','availability_verified','request_id','task_id'}
    result['resources'] = [{k:v for k,v in resource.items() if k in resource_fields} for resource in (result['resources'] or [])]
    return result


def enqueue_agent(store, conn, session_id, scope, revision):
    store.enqueue_job(conn, session_id, 'agent', f'{session_id}:agent:{scope}:{revision}:0', {'scope': scope, 'revision': revision, 'step': 0})


def request_resources(store, conn, session, category, city):
    revision = session.get('resource_revision', 0) + 1
    patch = {'resource_request': {'category': category, 'city': city.strip(), 'approved': True}, 'resource_revision': revision, 'input_revision':session['input_revision']+1, 'resources': [], 'sources': [], 'evidence_refs': [], 'pending_action': None, 'pending_input': None, 'last_model_call':None, 'last_tool_result':None, 'phase':'researching','errors':[]}
    if session['phase'] == 'paused':
        patch.update(phase='paused', resume_phase='researching')
    store.update_session(conn, session['session_id'], patch)
    enqueue_agent(store, conn, session['session_id'], 'resources', revision)


def make_router(store, settings):
    router = APIRouter(prefix='/api')

    @router.post('/sessions')
    async def create(body: CreateSession, request: Request):
        check_origin(request)
        if not settings.pairing_code:
            raise HTTPException(503, detail={'code':'pairing_unconfigured','message':'Set CLEARLINE_PAIRING_CODE on the paired laptop.'})
        if not hmac.compare_digest(body.pairing_code, settings.pairing_code):
            raise HTTPException(403, detail={'code':'invalid_pairing_code'})
        if not body.consent.recording:
            raise HTTPException(422, detail={'code':'recording_consent_required'})
        token = secrets.token_urlsafe(32)
        session = store.create_session(str(body.profile_id or uuid4()), digest(token), body.consent.model_dump(), body.data_origin, body.recording_task)
        result = public_status(store, session['session_id'])
        result['resume_token'] = token
        return result

    def check_origin(request):
        if request.headers.get('origin', '').rstrip('/') not in settings.allowed_origins:
            raise HTTPException(403, detail={'code':'origin_not_allowed'})

    def mutate(session_id, request):
        check_origin(request)
        return authorize(store, session_id, request)

    @router.get('/sessions/{session_id}')
    def status(session_id: UUID, request: Request):
        authorize(store, session_id, request)
        return public_status(store, str(session_id))

    @router.get('/profiles/{profile_id}/history')
    def history(profile_id: UUID, request: Request):
        token = request.headers.get('authorization', '').removeprefix('Bearer ')
        with read_conn(store) as conn:
            match = conn.execute('SELECT 1 FROM sessions WHERE profile_id=? AND token_hash=?', (str(profile_id), digest(token))).fetchone()
        if not token or not match:
            raise HTTPException(401, detail={'code':'invalid_resume_token'})
        return {'profile_id':str(profile_id), 'sessions':store.history(str(profile_id))}

    @router.post('/sessions/{session_id}/clips', status_code=202)
    async def upload(session_id: UUID, request: Request, clip_id: UUID = Form(...), audio: UploadFile = File(...), supersedes_clip_id: UUID | None = Form(None)):
        session = mutate(session_id, request)
        sid, cid = str(session_id), str(clip_id)
        if not session['consent'].get('recording'):
            raise HTTPException(403, detail={'code':'recording_consent_required'})
        mime = (audio.content_type or '').split(';')[0].strip()
        if mime not in {'audio/webm','video/webm','audio/ogg','application/ogg','audio/wav','audio/x-wav','audio/mp4','video/mp4','audio/mpeg'}:
            raise HTTPException(415, detail={'code':'unsupported_media'})
        fd, path = tempfile.mkstemp(prefix='clip-', suffix='.media', dir=settings.upload_dir)
        accepted = False
        try:
            hasher, size = hashlib.sha256(), 0
            with os.fdopen(fd, 'wb') as output:
                while chunk := await audio.read(65536):
                    size += len(chunk)
                    if size > settings.max_upload_bytes:
                        raise HTTPException(413, detail={'code':'upload_too_large'})
                    hasher.update(chunk)
                    output.write(chunk)
                output.flush()
                os.fsync(output.fileno())
            if size == 0:
                raise HTTPException(422, detail={'code':'empty_recording'})
            with store.transaction() as conn:
                old = conn.execute('SELECT * FROM clips WHERE session_id=? AND clip_id=?', (sid,cid)).fetchone()
                if old:
                    if old['sha256'] != hasher.hexdigest():
                        raise HTTPException(409, detail={'code':'clip_id_content_conflict'})
                    return {'session_id':sid,'clip_id':cid,'status':old['status'],'duplicate':True}
                session = store.get_session(sid, conn=conn)
                if session.get('capture_finished') and not supersedes_clip_id and session['phase'] not in {'awaiting_input','recording'}:
                    raise HTTPException(409, detail={'code':'capture_already_finished'})
                if supersedes_clip_id and not conn.execute('SELECT 1 FROM clips WHERE session_id=? AND clip_id=? AND superseded_by IS NULL', (sid,str(supersedes_clip_id))).fetchone():
                    raise HTTPException(422, detail={'code':'invalid_replacement'})
                if supersedes_clip_id and conn.execute("SELECT 1 FROM clips WHERE session_id=? AND supersedes_clip_id=? AND status IN ('queued','processing')", (sid,str(supersedes_clip_id))).fetchone():
                    raise HTTPException(409, detail={'code':'replacement_already_pending'})
                from backend.storage import utcnow
                now = utcnow()
                conn.execute('INSERT INTO clips(session_id,clip_id,status,media_path,mime_type,sha256,byte_size,input_revision,supersedes_clip_id,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)', (sid,cid,'queued',path,mime,hasher.hexdigest(),size,session['input_revision'],str(supersedes_clip_id) if supersedes_clip_id else None,now,now))
                store.enqueue_job(conn,sid,'audio',f'{sid}:audio:{cid}',{'clip_id':cid})
                phase = 'paused' if session['phase']=='paused' else 'processing'
                store.update_session(conn,sid,{'phase':phase,'resume_phase':'processing','pending_input':None,'errors':[]})
            accepted = True
            return {'session_id':sid,'clip_id':cid,'status':'queued','duplicate':False}
        finally:
            await audio.close()
            if not accepted:
                Path(path).unlink(missing_ok=True)

    @router.post('/sessions/{session_id}/finish')
    def finish(session_id: UUID, request: Request):
        session = mutate(session_id,request)
        sid = str(session_id)
        with store.transaction() as conn:
            session=store.get_session(sid,conn=conn)
            if not session.get('capture_finished'):
                phase='paused' if session['phase']=='paused' else 'processing'
                store.update_session(conn,sid,{'capture_finished':True,'phase':phase,'resume_phase':'processing'})
                store.enqueue_job(conn,sid,'summarize',f'{sid}:summary:{session.get("comparison_revision",0)}',{})
        return public_status(store,sid)

    @router.post('/sessions/{session_id}/resources')
    def resources(session_id: UUID, body: ResourceRequest, request: Request):
        session=mutate(session_id,request)
        with store.transaction() as conn:
            session=store.get_session(str(session_id),conn=conn)
            request_resources(store,conn,session,body.category,body.city)
        return public_status(store,str(session_id))

    @router.post('/sessions/{session_id}/input')
    def user_input(session_id: UUID, body: UserInput, request: Request):
        mutate(session_id,request)
        sid=str(session_id)
        with store.transaction() as conn:
            session=store.get_session(sid,conn=conn)
            if session['input_revision'] != body.input_revision:
                raise HTTPException(409,detail={'code':'stale_input_revision','input_revision':session['input_revision']})
            if body.city is not None:
                resource=session.get('resource_request')
                if not resource or not resource.get('approved'):
                    raise HTTPException(409,detail={'code':'resource_request_required'})
                request_resources(store,conn,session,resource['category'],body.city)
            elif body.transcript is not None:
                clip=conn.execute("SELECT * FROM clips WHERE session_id=? AND clip_id=? AND status='accepted' AND superseded_by IS NULL",(sid,str(body.clip_id))).fetchone()
                if not clip:
                    raise HTTPException(422,detail={'code':'accepted_clip_required'})
                try:
                    metrics=corrected_metrics(json.loads(clip['metrics_json']),body.transcript)
                except (ValueError, AudioError):
                    raise HTTPException(422,detail={'code':'invalid_transcript'})
                conn.execute('UPDATE clips SET transcript=?,metrics_json=? WHERE session_id=? AND clip_id=?',(body.transcript,json.dumps(metrics),sid,str(body.clip_id)))
                revision=session.get('comparison_revision',0)+1
                phase='paused' if session['phase']=='paused' else 'processing'
                store.update_session(conn,sid,{'input_revision':body.input_revision+1,'comparison_revision':revision,'comparison':None,'baseline':None,'completed_at':None,'phase':phase,'resume_phase':'processing','pending_input':None,'errors':[]})
                store.enqueue_job(conn,sid,'summarize',f'{sid}:summary:correction:{revision}',{})
            else:
                if not session.get('pending_input'):
                    raise HTTPException(409,detail={'code':'no_pending_input'})
                scope=session['pending_input'].get('scope','resources' if session.get('resource_request') else 'comparison')
                key='resource_revision' if scope=='resources' else 'comparison_revision'
                revision=session.get(key,0)+1
                next_phase='researching' if scope=='resources' else 'comparing'
                phase='paused' if session['phase']=='paused' else next_phase
                store.update_session(conn,sid,{'input_revision':body.input_revision+1,key:revision,'user_answer':body.answer,'pending_input':None,'phase':phase,'resume_phase':next_phase,'errors':[]})
                enqueue_agent(store,conn,sid,scope,revision)
        return public_status(store,sid)

    @router.post('/sessions/{session_id}/pause')
    def pause(session_id: UUID, request: Request):
        mutate(session_id,request)
        sid=str(session_id)
        with store.transaction() as conn:
            session=store.get_session(sid,conn=conn)
            if session['phase']!='paused' and not session.get('pause_requested'):
                running=conn.execute("SELECT 1 FROM jobs WHERE session_id=? AND status='running'",(sid,)).fetchone()
                patch={'pause_requested':True,'resume_phase':session['phase']}
                if not running:
                    patch['phase']='paused'
                store.update_session(conn,sid,patch)
        return public_status(store,sid)

    @router.post('/sessions/{session_id}/resume')
    def resume(session_id: UUID, request: Request):
        mutate(session_id,request)
        sid=str(session_id)
        with store.transaction() as conn:
            session=store.get_session(sid,conn=conn)
            dependency_pending=session['phase']=='awaiting_input' and (session.get('pending_input') or {}).get('reason_code') in {'whisper_unavailable','ffmpeg_unavailable','audio_dependency_unavailable','transcription_failed'}
            if session['phase'] in {'paused','agent_unavailable','waiting_retry'} or session.get('pause_requested') or dependency_pending:
                phase=session.get('resume_phase') or ('researching' if session.get('resource_request') else 'comparing' if session.get('metrics') else 'processing')
                if phase in {'paused','agent_unavailable','waiting_retry','awaiting_input'}:
                    phase='researching' if session.get('resource_request') else 'comparing'
                store.update_session(conn,sid,{'phase':phase,'pause_requested':False,'errors':[]})
                conn.execute("UPDATE jobs SET status='queued',retry_at=NULL WHERE session_id=? AND status IN ('retry','failed')",(sid,))
        return public_status(store,sid)

    return router
