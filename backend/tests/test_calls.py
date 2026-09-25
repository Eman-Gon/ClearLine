from uuid import uuid4
from fastapi import FastAPI
from fastapi.testclient import TestClient
from backend.api.config import Settings
from backend.storage import Store
from backend.calls.routes import make_call_router
from backend.calls.vapi import VapiSettings, VapiError

class Provider:
    def __init__(self, uncertain=False):
        self.count=0; self.id=str(uuid4()); self.uncertain=uncertain
    async def preflight(self): pass
    async def start(self,number,request_id):
        self.count+=1
        if self.uncertain: raise VapiError('vapi_response_unavailable', uncertain=True)
        return self.id

def make_client(tmp_path,uncertain=False):
    store=Store(str(tmp_path/'calls.db')); app=FastAPI(); provider=Provider(uncertain)
    settings=Settings(pairing_code='test-pair',allowed_origins=('http://testserver',))
    app.include_router(make_call_router(store,settings,VapiSettings(webhook_secret='s'*40,consent_mode='verbal'),provider))
    return TestClient(app),provider

def body():
    return dict(request_id=str(uuid4()),profile_id=str(uuid4()),number='+15555550123',permission_to_call=True,cloud_processing_approved=True)

HEADERS={'Authorization':'Bearer test-pair','Origin':'http://testserver'}

def report(provider,consent=True):
    message={'type':'end-of-call-report','call':{'id':provider.id},'artifact':{'messages':[{'role':'assistant','message':'Do not count these words.'},{'role':'user','message':'I enjoyed walking today.'}]}}
    if consent: message['compliance']={'recordingConsent':{'type':'verbal','grantedAt':'2026-09-25T10:00:00Z'}}
    return {'message':message}

def test_dispatch_is_idempotent_and_uncertainty_prevents_redial(tmp_path):
    client,p=make_client(tmp_path,True); b=body()
    assert client.post('/api/calls',json=b,headers=HEADERS).json()['state']=='dispatch_unknown'
    assert client.post('/api/calls',json=b,headers=HEADERS).status_code==202
    b['request_id']=str(uuid4())
    assert client.post('/api/calls',json=b,headers=HEADERS).status_code==409
    assert p.count==1

def test_authenticated_parent_only_memory_and_duplicate_report(tmp_path):
    client,p=make_client(tmp_path); b=body()
    assert client.post('/api/calls',json=b).status_code==401
    assert client.post('/api/calls',json=b,headers={**HEADERS,'Origin':'https://untrusted.example'}).status_code==403
    assert client.post('/api/calls',json=b,headers=HEADERS).status_code==202
    payload=report(p)
    assert client.post('/api/vapi/webhook',json=payload).status_code==401
    h={'X-ClearLine-Vapi-Secret':'s'*40}
    assert client.post('/api/vapi/webhook',json=payload,headers=h).json()['state']=='completed'
    assert client.post('/api/vapi/webhook',json=payload,headers=h).json()['duplicate']
    result=client.get('/api/calls/'+b['request_id'],headers=HEADERS).json()
    assert result['transcript']=='I enjoyed walking today.'
    assert result['analysis']['word_count']==4
    assert result['analysis']['drift_score'] is None
    assert client.get('/api/call-profiles/'+b['profile_id']+'/history',headers=HEADERS).json()['calls'][0]['request_id']==b['request_id']

def test_no_consent_discards_report_content(tmp_path):
    client,p=make_client(tmp_path); b=body(); client.post('/api/calls',json=b,headers=HEADERS)
    client.post('/api/vapi/webhook',json=report(p,False),headers={'X-ClearLine-Vapi-Secret':'s'*40})
    result=client.get('/api/calls/'+b['request_id'],headers=HEADERS).json()
    assert result['state']=='consent_unconfirmed'
    assert result['transcript'] is None and result['analysis'] is None

def test_demo_attestation_is_per_call_and_persists(tmp_path):
    store=Store(str(tmp_path/'demo.db')); p=Provider(); app=FastAPI()
    config=VapiSettings(webhook_secret='s'*40,consent_mode='preconsented_demo')
    app.include_router(make_call_router(store,Settings(pairing_code='test-pair',allowed_origins=('http://testserver',)),config,p))
    client=TestClient(app); b=body()
    assert client.post('/api/calls',json=b,headers=HEADERS).status_code==422
    b['participant_preconsented_demo']=True
    assert client.post('/api/calls',json=b,headers=HEADERS).status_code==202
    assert client.post('/api/vapi/webhook',json=report(p,False),headers={'X-ClearLine-Vapi-Secret':'s'*40}).json()['state']=='completed'
    result=client.get('/api/calls/'+b['request_id'],headers=HEADERS).json()
    assert result['analysis']['consent_source']=='prior_demo_attestation'

def test_provider_preflight_and_dispatch_contract():
    import asyncio, httpx, json
    from backend.calls.vapi import VapiClient
    config=VapiSettings(api_key='test',assistant_id=str(uuid4()),phone_number_id=str(uuid4()),webhook_secret='s'*40,webhook_url='https://example.test/api/vapi/webhook',consent_mode='preconsented_demo')
    calls=[]; call_id=str(uuid4())
    def serve(req):
        calls.append(req)
        if req.method=='GET':
            return httpx.Response(200,json={'server':{'url':config.webhook_url,'headers':{'X-ClearLine-Vapi-Secret':config.webhook_secret}},'serverMessages':['end-of-call-report'],'maxDurationSeconds':180})
        data=json.loads(req.content)
        assert data['assistantId']==config.assistant_id and 'assistantOverrides' not in data
        return httpx.Response(200,json={'id':call_id})
    client=VapiClient(config,transport=httpx.MockTransport(serve))
    asyncio.run(client.preflight())
    assert asyncio.run(client.start('+15555550123',str(uuid4())))==call_id
    assert len(calls)==2
