import asyncio
import hashlib
import io
import json
from pathlib import Path
from uuid import uuid4
import wave

import pytest
from fastapi.testclient import TestClient
from backend.main import create_app
from backend.api.config import Settings
from backend.audio import AudioError
from backend.storage import Store
from backend.worker.interfaces import UnavailableRunner


class TestAudio:
    __test__ = False
    calls=0
    def process(self,path,data_origin):
        self.calls+=1
        assert Path(path).read_bytes()
        return {'transcript':'a test recording','method_version':'clearline-v1','metrics':{'duration_s':3.,'word_count':3,'recording_wpm':60.,'pause_count':None,'energy_rms':0.1,'pitch_mean_hz':None,'quality':'accepted','quality_reasons':[],'data_origin':data_origin}}


class DeterministicTestAgent:
    """Explicit test double; never installed in production."""
    def __init__(self):
        self.executions=[]
    async def advance(self,checkpoint):
        names=checkpoint['permitted_tools']
        name=next(n for n in names if n!='request_user_input')
        args={'profile_id':checkpoint['profile_id'],'session_id':checkpoint['session_id']} if name=='get_baseline_summary' else {'session_id':checkpoint['session_id'],'baseline_ref':checkpoint['baseline_ref']} if name=='compare_recording_metrics' else {'summary':'Completed descriptive comparison','evidence_refs':[]}
        return {'name':name,'arguments':args,'model_call':{'role':'assistant','tool_calls':[]}}
    async def execute(self,action,checkpoint):
        self.executions.append(action['action_id'])
        return {'status':'insufficient_history','session_count':0,'metrics':{},'baseline_ref':None,'baseline_source':'consented_demo_history'}


@pytest.fixture
def setup(tmp_path):
    settings=Settings(database_path=str(tmp_path/'test.sqlite3'),upload_dir=str(tmp_path/'uploads'),pairing_code='test-only-pair',allowed_origins=('http://testserver',),worker_enabled=False)
    audio=TestAudio()
    app=create_app(settings,audio_processor=audio,agent_runner=UnavailableRunner())
    with TestClient(app) as client:
        yield client,app,audio,settings


def create(client,cloud=False):
    response=client.post('/api/sessions',headers={'Origin':'http://testserver'},json={'pairing_code':'test-only-pair','consent':{'recording':True,'cloud_export':cloud}})
    assert response.status_code==200,response.text
    data=response.json()
    headers={'Origin':'http://testserver','Authorization':'Bearer '+data['resume_token']}
    return data,headers


def upload(client,data,headers,content=b'complete synthetic test file',clip_id=None,**extra):
    cid=clip_id or str(uuid4())
    response=client.post(f'/api/sessions/{data["session_id"]}/clips',headers=headers,data={'clip_id':cid,**extra},files={'audio':('recording.webm',content,'audio/webm')})
    return response,cid


def tick(app,count=1):
    for _ in range(count):
        asyncio.run(app.state.worker.run_once())


def test_duplicate_upload_and_polling_are_idempotent(setup):
    client,app,audio,_=setup
    data,h=create(client)
    response,cid=upload(client,data,h)
    assert response.status_code==202
    duplicate,_=upload(client,data,h,clip_id=cid)
    assert duplicate.json()['duplicate'] is True
    conflict,_=upload(client,data,h,clip_id=cid,content=b'different')
    assert conflict.status_code==409
    with app.state.store.connection() as conn:
        before=[conn.execute(f'SELECT COUNT(*) FROM {t}').fetchone()[0] for t in ('clips','jobs','events','checkpoints','actions')]
    status=client.get('/api/sessions/'+data['session_id'],headers=h).json()
    for _ in range(5):
        assert client.get('/api/sessions/'+data['session_id'],headers=h).json()==status
    with app.state.store.connection() as conn:
        after=[conn.execute(f'SELECT COUNT(*) FROM {t}').fetchone()[0] for t in ('clips','jobs','events','checkpoints','actions')]
    assert before==after
    assert before[:2]==[1,1]
    assert audio.calls==0


def test_processing_finish_and_missing_agent_visible(setup):
    client,app,audio,_=setup
    data,h=create(client)
    upload(client,data,h)
    client.post('/api/sessions/'+data['session_id']+'/finish',headers=h)
    tick(app,5)
    status=client.get('/api/sessions/'+data['session_id'],headers=h).json()
    assert status['phase']=='agent_unavailable'
    assert status['metrics']['recording_wpm']==60
    assert audio.calls==1
    assert list(Path(app.state.settings.upload_dir).iterdir())==[]
    assert not app.state.store.pending_outbox()


def test_pause_resume_is_idempotent(setup):
    client,app,audio,_=setup
    data,h=create(client)
    upload(client,data,h)
    url='/api/sessions/'+data['session_id']
    paused=client.post(url+'/pause',headers=h).json()
    assert paused['phase']=='paused'
    tick(app)
    assert audio.calls==0
    assert client.post(url+'/pause',headers=h).json()['state_version']==paused['state_version']
    resumed=client.post(url+'/resume',headers=h).json()
    assert client.post(url+'/resume',headers=h).json()['state_version']==resumed['state_version']
    tick(app)
    assert audio.calls==1


def test_recovery_retains_recording_and_requeues_claimed_job(setup):
    client,app,audio,settings=setup
    data,h=create(client)
    upload(client,data,h)
    job=app.state.store.claim_job()
    assert job['kind']=='audio'
    reloaded=Store(settings.database_path)
    recovery=reloaded.recover()
    assert recovery['jobs_requeued']==1
    app.state.worker.cleanup_media()
    assert len(list(Path(settings.upload_dir).iterdir()))==1
    tick(app)
    assert audio.calls==1
    assert reloaded.get_session(data['session_id'])['phase']=='recording'


def test_auth_origin_and_revision_checks(setup):
    client,app,_,_=setup
    data,h=create(client)
    url='/api/sessions/'+data['session_id']
    assert client.get(url).status_code==401
    assert client.post(url+'/finish',headers={'Authorization':h['Authorization']}).status_code==403
    response=client.post(url+'/input',headers=h,json={'input_revision':99,'answer':'x'})
    assert response.status_code==409
    assert 'token_hash' not in client.get(url,headers=h).json()


def test_real_ffmpeg_rejects_invalid_audio(setup):
    from backend.audio import AudioProcessor
    client,app,_,_=setup
    app.state.worker.audio=AudioProcessor()
    data,h=create(client)
    upload(client,data,h,content=b'this is not an audio container')
    tick(app)
    status=client.get('/api/sessions/'+data['session_id'],headers=h).json()
    assert status['phase']=='awaiting_input'
    assert status['errors'][0]['code']=='invalid_audio'
    assert status['metrics'] is None
    resumed=client.post('/api/sessions/'+data['session_id']+'/resume',headers=h).json()
    assert resumed['phase']=='awaiting_input'
    assert resumed['state_version']==status['state_version']


def test_dependency_can_resume_existing_audio(setup):
    client,app,audio,_=setup
    class MissingAudio:
        def process(self,*a,**kw):
            raise AudioError('whisper_unavailable','Model missing')
    app.state.worker.audio=MissingAudio()
    data,h=create(client)
    upload(client,data,h)
    tick(app)
    assert list(Path(app.state.settings.upload_dir).iterdir())
    app.state.worker.audio=audio
    client.post('/api/sessions/'+data['session_id']+'/resume',headers=h)
    tick(app)
    assert audio.calls==1


def test_same_word_count_correction_creates_new_summary(setup):
    client,app,audio,_=setup
    data,h=create(client)
    _,cid=upload(client,data,h)
    url='/api/sessions/'+data['session_id']
    client.post(url+'/finish',headers=h)
    tick(app,4)
    state=client.get(url,headers=h).json()
    invalid=client.post(url+'/input',headers=h,json={'input_revision':state['input_revision'],'clip_id':cid,'transcript':'!!!'})
    assert invalid.status_code==422
    corrected=client.post(url+'/input',headers=h,json={'input_revision':state['input_revision'],'clip_id':cid,'transcript':'three changed words'})
    assert corrected.status_code==200,corrected.text
    tick(app,2)
    with app.state.store.connection() as conn:
        assert conn.execute('SELECT COUNT(*) FROM session_summaries').fetchone()[0]==2
    assert audio.calls==1


def test_completed_agent_action_not_repeated_after_restart(setup):
    client,app,_,settings=setup
    agent=DeterministicTestAgent()
    app.state.worker.agent=agent
    data,h=create(client)
    upload(client,data,h)
    url='/api/sessions/'+data['session_id']
    client.post(url+'/finish',headers=h)
    tick(app,4)
    assert len(agent.executions)==1
    Store(settings.database_path).recover()
    tick(app,6)
    assert len(agent.executions)==1
    assert client.get(url,headers=h).json()['phase']=='awaiting_user_choice'


def test_outbox_retries_same_event_and_no_private_fields(setup):
    client,app,_,_=setup
    data,h=create(client,cloud=True)
    class Exporter:
        def __init__(self): self.calls=[]
        async def append_event(self,table,payload):
            self.calls.append(payload)
            if len(self.calls)==1: raise TimeoutError()
    exporter=Exporter()
    app.state.worker.exporter=exporter
    asyncio.run(app.state.worker.deliver_outbox_once())
    with app.state.store.transaction() as conn:
        conn.execute('UPDATE outbox SET retry_at=NULL')
    asyncio.run(app.state.worker.deliver_outbox_once())
    assert exporter.calls[0]['event_id']==exporter.calls[1]['event_id']
    assert not {'transcript','audio','token_hash','consent','pairing_code','name'} & exporter.calls[0].keys()


def test_retryable_tool_failure_goes_back_to_model_with_error(setup):
    client,app,_,_=setup
    class FailingAgent(DeterministicTestAgent):
        seen_error=None
        async def advance(self,checkpoint):
            if (checkpoint.get('last_tool_result') or {}).get('code')=='source_timeout':
                self.seen_error=checkpoint['last_tool_result']
                return {'name':'request_user_input','arguments':{'reason_code':'source_timeout','question':'Try another city?'}}
            return await super().advance(checkpoint)
        async def execute(self,action,checkpoint):
            class Failure(Exception):
                code='source_timeout'
                retryable=True
            raise Failure()
    # Start directly at comparison with known local measurements; no audio fiction.
    agent=FailingAgent()
    app.state.worker.agent=agent
    data,h=create(client)
    sid=data['session_id']
    with app.state.store.transaction() as conn:
        app.state.store.update_session(conn,sid,{'phase':'comparing','comparison_revision':1,'metrics':{'recording_wpm':60}})
        app.state.store.enqueue_job(conn,sid,'agent','failure-test',{'scope':'comparison','revision':1,'step':0})
    tick(app)
    # Model advance initially has last_tool_result None; failure handling remains visible.
    state=app.state.store.get_session(sid)
    if state['errors'][0]['code']=='worker_error':
        pytest.fail('Test model unexpectedly failed before source request')
    assert state['phase']=='waiting_retry'
    with app.state.store.transaction() as conn:
        conn.execute('UPDATE jobs SET retry_at=NULL')
    tick(app)
    assert agent.seen_error['retryable'] is True
    assert app.state.store.get_session(sid)['phase']=='awaiting_input'


def test_replacement_reservation_and_city_change_preserve_audio(setup):
    client,app,audio,_=setup
    data,h=create(client)
    _,cid=upload(client,data,h)
    tick(app)
    response,_=upload(client,data,h,supersedes_clip_id=cid)
    assert response.status_code==202
    duplicate,_=upload(client,data,h,supersedes_clip_id=cid)
    assert duplicate.status_code==409
    tick(app)
    url='/api/sessions/'+data['session_id']
    result=client.post(url+'/resources',headers=h,json={'category':'caregiver_support','city':'Oakland','approved':True})
    assert result.status_code==200,result.text
    state=result.json()
    changed=client.post(url+'/input',headers=h,json={'input_revision':state['input_revision'],'city':'Berkeley'})
    assert changed.status_code==200
    assert changed.json()['resource_request']['city']=='Berkeley'
    with app.state.store.connection() as conn:
        assert conn.execute("SELECT COUNT(*) FROM clips WHERE status='accepted' AND superseded_by IS NULL").fetchone()[0]==1
    assert audio.calls==2
