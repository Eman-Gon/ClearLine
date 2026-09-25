"""Authenticated telephone demo API with durable, idempotent completed-call memory."""
import hashlib
import hmac
import json
import re
from datetime import datetime, timezone
from uuid import UUID

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, ConfigDict, Field

from .vapi import VapiClient, VapiError, VapiSettings


class CallRequest(BaseModel):
    model_config = ConfigDict(extra='forbid')
    request_id: UUID
    profile_id: UUID
    number: str = Field(pattern=r'^\+[1-9][0-9]{7,14}$')
    permission_to_call: bool
    cloud_processing_approved: bool
    participant_preconsented_demo: bool = False
    rawtree_storage_approved: bool = False
    resource_search_approved: bool = False
    data_origin: str = Field(default="consented_demo", pattern="^(real|consented_demo|synthetic)$")
    city: str = Field(default="", max_length=100, pattern=r"^[\w .,()'’-]*$")


def now():
    return datetime.now(timezone.utc).isoformat()


def object_value(value):
    return value if isinstance(value, dict) else {}


def analyse(message, preconsented_demo=False):
    """Transcript-only observations; never infer acoustic metrics or clinical drift."""
    call = object_value(message.get('call'))
    consent = object_value(object_value(message.get('compliance') or call.get('compliance')).get('recordingConsent'))
    granted = consent.get('grantedAt')
    try:
        assert consent.get('type') == 'verbal' and isinstance(granted, str)
        assert datetime.fromisoformat(granted.replace('Z', '+00:00')).tzinfo is not None
    except (ValueError, AssertionError):
        if not preconsented_demo:
            return 'consent_unconfirmed', None, None
    artifact = object_value(message.get('artifact'))
    messages = artifact.get('messages')
    # Structured speaker roles prevent counting the assistant's words as the parent.
    if not isinstance(messages, list) or len(messages) > 2000:
        return 'transcript_unavailable', None, None
    parts = [row['message'] for row in messages if isinstance(row, dict) and row.get('role') == 'user'
             and isinstance(row.get('message'), str)]
    transcript = '\n'.join(parts).strip()
    if not transcript or len(transcript) > 50_000:
        return 'transcript_unavailable', None, None
    words = re.findall(r"\b[\w]+(?:['’][\w]+)*\b", transcript)
    summary = object_value(message.get('analysis')).get('summary')
    summary = summary[:4000] if isinstance(summary, str) and summary.strip() else None
    signals = []
    for topic, pattern in [('word finding', r'\b(?:trouble|difficulty) (?:finding|remembering) words\b'),
                           ('memory', r'\b(?:forgetting|forgot|forgetful|memory)\b'),
                           ('sleep', r'\b(?:sleep|sleeping)\b'), ('worry', r'\b(?:worried|anxious|afraid)\b')]:
        if re.search(pattern, transcript, re.I):
            signals.append(topic)
    return 'completed', transcript, {'source': 'vapi_transcript_v1', 'consent_source': 'prior_demo_attestation' if preconsented_demo else 'vapi_verbal', 'word_count': len(words),
        'mentioned_topics': signals, 'provider_summary': summary,
        'summary_source': 'vapi' if summary else None,
        'speaking_rate': None, 'pitch': None, 'pauses': None, 'emotion': None, 'drift_score': None}


def make_call_router(store, settings, vapi_settings=None, vapi_client=None):
    config = vapi_settings or VapiSettings()
    client = vapi_client or VapiClient(config)
    router = APIRouter(prefix='/api')
    with store.transaction() as conn:
        conn.execute('''CREATE TABLE IF NOT EXISTS telephone_calls (
            request_id TEXT PRIMARY KEY, profile_id TEXT NOT NULL, request_hash TEXT NOT NULL,
            provider_id TEXT UNIQUE, state TEXT NOT NULL, created_at TEXT NOT NULL,
            completed_at TEXT, transcript TEXT, analysis_json TEXT, error_code TEXT,
            webhook_hash TEXT, preconsented_demo INTEGER NOT NULL DEFAULT 0)''')
        if 'preconsented_demo' not in {r[1] for r in conn.execute('PRAGMA table_info(telephone_calls)')}:
            conn.execute('ALTER TABLE telephone_calls ADD COLUMN preconsented_demo INTEGER NOT NULL DEFAULT 0')
        if 'call_options_json' not in {r[1] for r in conn.execute('PRAGMA table_info(telephone_calls)')}:
            conn.execute("ALTER TABLE telephone_calls ADD COLUMN call_options_json TEXT NOT NULL DEFAULT '{}'")
        conn.execute('CREATE INDEX IF NOT EXISTS telephone_profile ON telephone_calls(profile_id,created_at)')

    def authorize(request, mutation=False):
        supplied = request.headers.get('authorization', '').removeprefix('Bearer ')
        if not settings.pairing_code or not hmac.compare_digest(supplied, settings.pairing_code):
            raise HTTPException(401, detail={'code': 'invalid_pairing_code'})
        if mutation and request.headers.get('origin', '').rstrip('/') not in settings.allowed_origins:
            raise HTTPException(403, detail={'code': 'origin_not_allowed'})

    def public(row):
        return {**{k: row[k] for k in ('request_id','profile_id','provider_id','state','created_at','completed_at','transcript','error_code')},
                'analysis': json.loads(row['analysis_json']) if row['analysis_json'] else None}

    @router.get('/calls/config')
    async def readiness(request: Request):
        authorize(request)
        return {'configured': not config.missing(), 'missing': config.missing(), 'live_verified': False, 'consent_mode': config.consent_mode}

    @router.post('/calls', status_code=202)
    async def start(body: CallRequest, request: Request):
        authorize(request, True)
        return await dispatch(body)

    async def dispatch(body: CallRequest):
        if not body.permission_to_call or not body.cloud_processing_approved:
            raise HTTPException(422, detail={'code': 'call_and_cloud_permission_required'})
        if config.consent_mode == 'preconsented_demo' and not body.participant_preconsented_demo:
            raise HTTPException(422, detail={'code': 'participant_prior_demo_consent_required'})
        if body.participant_preconsented_demo and body.data_origin != 'consented_demo':
            raise HTTPException(422, detail={'code':'demo_consent_cannot_authorize_real_calls'})
        identifier = str(body.request_id)
        fingerprint = hashlib.sha256(body.model_dump_json().encode()).hexdigest()
        with store.connection() as conn:
            prior = conn.execute('SELECT * FROM telephone_calls WHERE request_id=?', (identifier,)).fetchone()
        if prior:
            if prior['request_hash'] != fingerprint:
                raise HTTPException(409, detail={'code': 'call_request_conflict'})
            return public(prior)
        try:
            await client.preflight()
        except VapiError as error:
            raise HTTPException(503, detail={'code': error.code}) from None
        # Claim before contacting Vapi. Repeats cannot place a second call, even
        # after timeout/process death. Dispatch uncertainty needs manual review.
        with store.transaction() as conn:
            prior = conn.execute('SELECT * FROM telephone_calls WHERE request_id=?', (identifier,)).fetchone()
            if prior:
                if prior['request_hash'] != fingerprint:
                    raise HTTPException(409, detail={'code': 'call_request_conflict'})
                return public(prior)
            active = conn.execute("SELECT 1 FROM telephone_calls WHERE profile_id=? AND state IN ('dispatching','queued','ringing','in-progress','dispatch_unknown')", (str(body.profile_id),)).fetchone()
            if active:
                raise HTTPException(409, detail={'code': 'call_already_active_or_uncertain'})
            conn.execute('INSERT INTO telephone_calls(request_id,profile_id,request_hash,state,created_at,preconsented_demo) VALUES(?,?,?,?,?,?)',
                         (identifier,str(body.profile_id),fingerprint,'dispatching',now(),int(config.consent_mode == 'preconsented_demo' and body.participant_preconsented_demo)))
            options=body.model_dump(mode='json',exclude={'number','request_id','profile_id'})
            conn.execute('UPDATE telephone_calls SET call_options_json=? WHERE request_id=?',(json.dumps(options),identifier))
        try:
            provider_id = await client.start(body.number, identifier)
            with store.transaction() as conn:
                conn.execute("UPDATE telephone_calls SET provider_id=?,state=CASE WHEN webhook_hash IS NULL THEN 'queued' ELSE state END WHERE request_id=?", (provider_id,identifier))
        except VapiError as error:
            with store.transaction() as conn:
                conn.execute('UPDATE telephone_calls SET state=?,error_code=? WHERE request_id=? AND webhook_hash IS NULL',
                             ('dispatch_unknown' if error.uncertain else 'failed', error.code, identifier))
        with store.connection() as conn:
            return public(conn.execute('SELECT * FROM telephone_calls WHERE request_id=?', (identifier,)).fetchone())

    @router.get('/calls/{request_id}')
    async def status(request_id: UUID, request: Request):
        authorize(request)
        with store.connection() as conn:
            row = conn.execute('SELECT * FROM telephone_calls WHERE request_id=?', (str(request_id),)).fetchone()
        if not row:
            raise HTTPException(404, detail={'code': 'call_not_found'})
        return public(row)

    @router.get('/call-profiles/{profile_id}/history')
    async def history(profile_id: UUID, request: Request):
        authorize(request)
        with store.connection() as conn:
            rows = conn.execute('SELECT * FROM telephone_calls WHERE profile_id=? ORDER BY created_at DESC LIMIT 20', (str(profile_id),)).fetchall()
        return {'calls': [public(row) for row in rows], 'source': 'backend_sqlite_telephone_calls'}

    @router.post('/vapi/webhook')
    async def webhook(request: Request):
        secret = request.headers.get('x-clearline-vapi-secret', '')
        if len(config.webhook_secret) < 32 or not hmac.compare_digest(secret, config.webhook_secret):
            raise HTTPException(401, detail={'code': 'invalid_webhook_secret'})
        body = bytearray()
        async for chunk in request.stream():
            body.extend(chunk)
            if len(body) > 1_000_000:
                raise HTTPException(413, detail={'code': 'webhook_too_large'})
        try:
            message = object_value(json.loads(body).get('message'))
            call = object_value(message.get('call'))
            provider_id = str(UUID(call.get('id', '')))
        except (ValueError, AttributeError, TypeError):
            raise HTTPException(422, detail={'code': 'invalid_vapi_report'}) from None
        if message.get('type') != 'end-of-call-report':
            return {'ignored': True}
        # Only completed, consent-confirmed reports for calls we requested enter memory.
        request_id = object_value(call.get('metadata')).get('clearline_request_id')
        with store.transaction() as conn:
            row = conn.execute('SELECT * FROM telephone_calls WHERE provider_id=?', (provider_id,)).fetchone()
            if not row and isinstance(request_id, str):
                row = conn.execute('SELECT * FROM telephone_calls WHERE request_id=? AND provider_id IS NULL', (request_id,)).fetchone()
            if not row:
                raise HTTPException(404, detail={'code': 'unknown_call'})
            if row['webhook_hash']:
                return {'duplicate': True, 'state': row['state']}
            state, transcript, analysis = analyse(message, bool(row['preconsented_demo']))
            conn.execute('UPDATE telephone_calls SET provider_id=?,state=?,completed_at=?,transcript=?,analysis_json=?,webhook_hash=? WHERE request_id=?',
                         (provider_id,state,now(),transcript,json.dumps(analysis) if analysis else None,hashlib.sha256(body).hexdigest(),row['request_id']))
        return {'received': True, 'state': state}

    router.dispatch_call = dispatch
    return router
