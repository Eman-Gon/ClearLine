import hmac,json,os
from datetime import datetime,timezone
from uuid import UUID
from fastapi import APIRouter,HTTPException,Request
from pydantic import BaseModel,Field,ConfigDict
from .models import ScheduleInput
from .storage import initialize
from .worker import stamp

class Device(BaseModel):
    model_config=ConfigDict(extra='forbid')
    device_id: UUID
    profile_id: UUID
    token: str=Field(min_length=10,max_length=4096)

class Toggle(BaseModel):
    enabled: bool


def make_family_router(store,settings):
    initialize(store); router=APIRouter(prefix='/api/family')
    def auth(request,mutation=False):
        token=request.headers.get('authorization','').removeprefix('Bearer ')
        if not settings.pairing_code or not hmac.compare_digest(token,settings.pairing_code): raise HTTPException(401,detail={'code':'invalid_pairing_code'})
        origin=request.headers.get('origin')
        # Native clients have no browser Origin. Browser mutations need allowlisting.
        if mutation and origin is not None and origin.rstrip('/') not in settings.allowed_origins: raise HTTPException(403,detail={'code':'origin_not_allowed'})
    @router.get('/status')
    async def status(request:Request):
        auth(request)
        return {'execution':'server','phone_background_required':False,'liquid_runtime':'backend_loopback_llama_cpp','push_configured':bool(os.getenv('FIREBASE_SERVICE_ACCOUNT_FILE')),'consent_mode':os.getenv('VAPI_CONSENT_MODE','verbal'),'scheduler_running':settings.worker_enabled}
    @router.put('/schedules/{schedule_id}')
    async def save(schedule_id:UUID,body:ScheduleInput,request:Request):
        auth(request,True)
        if schedule_id!=body.schedule_id: raise HTTPException(422,detail={'code':'schedule_id_mismatch'})
        due=body.first_call_at.astimezone(timezone.utc)
        if due<=datetime.now(timezone.utc): raise HTTPException(422,detail={'code':'choose_future_call_time'})
        with store.transaction() as c:
            prior=c.execute('SELECT * FROM phone_schedules WHERE schedule_id=?',(str(schedule_id),)).fetchone()
            value=body.model_dump_json()
            if prior and prior['config_json']==value: return {'schedule_id':str(schedule_id),'next_due':prior['next_due'],'enabled':bool(prior['enabled'])}
            c.execute('INSERT INTO phone_schedules VALUES(?,?,?,?,?,?) ON CONFLICT(schedule_id) DO UPDATE SET profile_id=excluded.profile_id,config_json=excluded.config_json,next_due=excluded.next_due,enabled=excluded.enabled,updated_at=excluded.updated_at',(str(schedule_id),str(body.profile_id),value,stamp(due),int(body.enabled),stamp()))
        return {'schedule_id':str(schedule_id),'next_due':stamp(due),'enabled':body.enabled}
    @router.get('/profiles/{profile_id}/schedules')
    async def schedules(profile_id:UUID,request:Request):
        auth(request)
        with store.connection() as c: rows=c.execute('SELECT * FROM phone_schedules WHERE profile_id=?',(str(profile_id),)).fetchall()
        return {'schedules':[{**json.loads(r['config_json']),'next_due':r['next_due'],'enabled':bool(r['enabled'])} for r in rows]}
    @router.post('/schedules/{schedule_id}/pause')
    async def pause(schedule_id:UUID,request:Request):
        auth(request,True)
        with store.transaction() as c:
            if not c.execute('SELECT 1 FROM phone_schedules WHERE schedule_id=?',(str(schedule_id),)).fetchone(): raise HTTPException(404)
            c.execute('UPDATE phone_schedules SET enabled=0,updated_at=? WHERE schedule_id=?',(stamp(),str(schedule_id)))
        return {'enabled':False,'note':'Pauses future dispatch; an already dispatched phone call cannot be recalled.'}
    @router.post('/devices')
    async def register(body:Device,request:Request):
        auth(request,True)
        with store.transaction() as c:
            c.execute('INSERT INTO phone_devices VALUES(?,?,?,?) ON CONFLICT(device_id) DO UPDATE SET profile_id=excluded.profile_id,token=excluded.token,updated_at=excluded.updated_at',(str(body.device_id),str(body.profile_id),body.token,stamp()))
        return {'registered':True}
    @router.get('/profiles/{profile_id}/reports')
    async def reports(profile_id:UUID,request:Request):
        auth(request)
        with store.connection() as c:
            rows=c.execute('SELECT * FROM phone_reports WHERE profile_id=? ORDER BY updated_at DESC LIMIT 30',(str(profile_id),)).fetchall()
            result=[]
            for r in rows:
                notifications=[dict(n) for n in c.execute('SELECT state,error_code,attempts FROM phone_notifications WHERE session_id=?',(r['session_id'],))]
                result.append({**dict(r),'report':json.loads(r['report_json']),'notifications':notifications,'notification_status':'no_registered_device' if not notifications else 'see_delivery_states'})
                result[-1].pop('report_json')
            occurrences=[dict(r) for r in c.execute('SELECT o.occurrence_id,o.due_at,o.state,o.error_code FROM phone_occurrences o JOIN phone_schedules s USING(schedule_id) WHERE s.profile_id=? ORDER BY o.due_at DESC LIMIT 30',(str(profile_id),))]
        return {'reports':result,'occurrences':occurrences,'source':'server_pipeline'}
    @router.post('/reports/{session_id}/retry')
    async def retry(session_id:UUID,request:Request):
        auth(request,True)
        with store.transaction() as c:
            r=c.execute('SELECT * FROM phone_reports WHERE session_id=?',(str(session_id),)).fetchone()
            if not r: raise HTTPException(404)
            report=json.loads(r['report_json']); stage=report.get('resume_stage')
            if r['stage']!='failed' or stage not in ('history','analysis','search','store'): raise HTTPException(409,detail={'code':'report_not_retryable'})
            c.execute('UPDATE phone_reports SET stage=?,attempts=0,next_attempt=NULL,error_code=NULL WHERE session_id=?',(stage,str(session_id)))
        return {'stage':stage,'note':'Retries analysis only; never places a call.'}
    return router
