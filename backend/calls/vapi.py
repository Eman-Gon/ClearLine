"""Bounded Vapi control-plane client. No call retries or credential logging."""
from dataclasses import dataclass, field
import os
import hmac
from urllib.parse import urlsplit
from uuid import UUID

import httpx


class VapiError(Exception):
    def __init__(self, code, uncertain=False):
        self.code, self.uncertain = code, uncertain
        super().__init__(code)


@dataclass
class VapiSettings:
    api_key: str = field(default_factory=lambda: os.getenv('VAPI_API_KEY', ''))
    assistant_id: str = field(default_factory=lambda: os.getenv('VAPI_ASSISTANT_ID', ''))
    phone_number_id: str = field(default_factory=lambda: os.getenv('VAPI_PHONE_NUMBER_ID', ''))
    webhook_secret: str = field(default_factory=lambda: os.getenv('VAPI_WEBHOOK_SECRET', ''))
    consent_mode: str = field(default_factory=lambda: os.getenv('VAPI_CONSENT_MODE', 'verbal'))
    webhook_url: str = field(default_factory=lambda: os.getenv('VAPI_WEBHOOK_URL', ''))

    def missing(self):
        return [name for name in ('api_key', 'assistant_id', 'phone_number_id', 'webhook_secret', 'webhook_url') if not getattr(self, name)]

    def validate(self):
        if self.consent_mode not in ('verbal', 'preconsented_demo'):
            raise VapiError('vapi_consent_mode_invalid')
        if self.missing():
            raise VapiError('vapi_configuration_missing')
        try:
            UUID(self.assistant_id); UUID(self.phone_number_id)
            url = urlsplit(self.webhook_url)
            assert url.scheme == 'https' and url.hostname and not url.username and not url.password
            assert url.path == '/api/vapi/webhook' and not url.query and not url.fragment
            assert len(self.webhook_secret) >= 32
        except (ValueError, AssertionError):
            raise VapiError('vapi_configuration_invalid') from None


class VapiClient:
    def __init__(self, settings, *, transport=None):
        self.settings, self.transport = settings, transport

    async def _request(self, method, path, body=None, *, dispatch=False):
        try:
            async with httpx.AsyncClient(transport=self.transport, timeout=20, follow_redirects=False) as client:
                async with client.stream(method, 'https://api.vapi.ai' + path,
                                         headers={'Authorization': 'Bearer ' + self.settings.api_key}, json=body) as response:
                    if response.status_code >= 400 or response.is_redirect:
                        raise VapiError('vapi_auth_failed' if response.status_code in (401, 403) else 'vapi_request_failed',
                                        uncertain=dispatch and response.status_code >= 500)
                    data = bytearray()
                    async for chunk in response.aiter_bytes():
                        data.extend(chunk)
                        if len(data) > 2_000_000:
                            raise VapiError('vapi_response_too_large', uncertain=dispatch)
                    import json
                    result = json.loads(data)
                    if not isinstance(result, dict):
                        raise ValueError()
                    return result
        except VapiError:
            raise
        except (httpx.HTTPError, ValueError):
            raise VapiError('vapi_response_unavailable', uncertain=dispatch) from None

    async def preflight(self):
        self.settings.validate()
        assistant = await self._request('GET', '/assistant/' + self.settings.assistant_id)
        server = assistant.get('server') or {}
        consent = (assistant.get('compliancePlan') or {}).get('recordingConsentPlan') or {}
        headers = {k.lower(): v for k, v in (server.get('headers') or {}).items()}
        if server.get('url') != self.settings.webhook_url or not hmac.compare_digest(str(headers.get('x-clearline-vapi-secret', '')), self.settings.webhook_secret):
            raise VapiError('vapi_saved_assistant_webhook_required')
        if 'end-of-call-report' not in assistant.get('serverMessages', []):
            raise VapiError('vapi_end_report_required')
        if self.settings.consent_mode == 'verbal' and (consent.get('type') != 'verbal' or not consent.get('message')):
            raise VapiError('vapi_verbal_consent_required')
        maximum = assistant.get('maxDurationSeconds')
        if not isinstance(maximum, (int, float)) or not 10 <= maximum <= 180:
            raise VapiError('vapi_three_minute_limit_required')

    async def start(self, number, request_id):
        # Saved assistant retains its configured webhook authentication. No inline
        # assistant/server override, whose credentials Vapi may withhold.
        result = await self._request('POST', '/call', {
            'assistantId': self.settings.assistant_id,
            'phoneNumberId': self.settings.phone_number_id,
            'customer': {'number': number},
            'metadata': {'clearline_request_id': request_id},
        }, dispatch=True)
        try:
            return str(UUID(result['id']))
        except (KeyError, TypeError, ValueError):
            raise VapiError('vapi_call_identity_missing', uncertain=True) from None
