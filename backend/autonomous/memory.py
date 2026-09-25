"""Consented phone-session memory, separate from the legacy metrics-only export."""
import hashlib
import json
from uuid import UUID
from backend.integrations.rawtree import RawTreeClient
from backend.integrations.common import IntegrationError

TABLE='clearline_phone_sessions'

def profile_ref(profile_id): return hashlib.sha256(str(UUID(profile_id)).encode()).hexdigest()

class PhoneMemory:
    def __init__(self, client=None): self.client=client or RawTreeClient.from_env()
    async def aclose(self): await self.client.aclose()
    async def history(self,profile_id,session_id,data_origin):
        ref=profile_ref(profile_id); sid=str(UUID(session_id))
        if data_origin not in ('real','consented_demo','synthetic'): raise ValueError('Invalid provenance')
        predicate=f"profile_ref = '{ref}' AND data_origin = '{data_origin}' AND session_id != '{sid}'"
        rows=await self.client._query(f'SELECT count(DISTINCT session_id) AS session_count FROM {TABLE} WHERE {predicate}')
        try:
            count=int(rows[0]['session_count'])
            if count<0: raise ValueError()
        except (ValueError,KeyError,IndexError,TypeError): raise IntegrationError('rawtree_invalid_count','RawTree session count unavailable') from None
        history=await self.client._query(f'''SELECT payload FROM (
SELECT __raw_data AS payload, created_at, session_id,
row_number() OVER (PARTITION BY session_id ORDER BY version DESC, event_id DESC) AS latest_rank
FROM {TABLE} WHERE {predicate}) WHERE latest_rank=1 ORDER BY created_at DESC, session_id DESC LIMIT 10''')
        sessions=[]
        for value in history:
            row=self.client._payload(value)
            if row.get('profile_ref')!=ref or row.get('data_origin')!=data_origin or row.get('session_id')==sid:
                raise IntegrationError('rawtree_history_mismatch','History provenance mismatch')
            UUID(row['session_id'])
            if not isinstance(row.get('transcript'),str) or len(row['transcript'])>50000:
                raise IntegrationError('rawtree_invalid_transcript','Invalid stored transcript')
            sessions.append({k:row.get(k) for k in ('session_id','created_at','transcript','data_origin')})
        if count<len(sessions): raise IntegrationError('rawtree_history_mismatch','History count mismatch')
        return {'source':'RawTree','table':TABLE,'session_count':count,'returned_sessions':len(sessions),'history_limit':10,'sessions':sessions,'data_origin':data_origin}
    async def save(self,call,report):
        event={'event_id':str(UUID(call['request_id'])),'session_id':str(UUID(call['request_id'])),
               'profile_ref':profile_ref(call['profile_id']),'version':1,'created_at':call['created_at'],
               'data_origin':call['data_origin'],'transcript':call['transcript'],
               'analysis_json':json.dumps(report,ensure_ascii=False,allow_nan=False)}
        if not call.get('rawtree_storage_approved'): raise IntegrationError('rawtree_consent_required','RawTree transcript storage permission missing')
        result=await self.client._post('/v1/tables/'+TABLE,event)
        if result.get('error') or result.get('success') is False: raise IntegrationError('rawtree_write_failed','RawTree write failed',True)
        rows=await self.client._query(f"SELECT event_id FROM {TABLE} WHERE event_id = '{event['event_id']}' LIMIT 1")
        if not rows: raise IntegrationError('rawtree_readback_pending','Session write not visible yet',True)
        return {'source':'RawTree','event_id':event['event_id'],'readback_verified':True}
