"""FCM delivery occurs on the server; payload contains no health/transcript data."""
import asyncio
import os
from backend.integrations.common import IntegrationError

class PushSender:
    async def send(self,token,session_id):
        return await asyncio.to_thread(self._send,token,session_id)
    def _send(self,token,session_id):
        credential=os.getenv('FIREBASE_SERVICE_ACCOUNT_FILE','')
        if not credential: raise IntegrationError('push_not_configured','Firebase server credential missing')
        try:
            import firebase_admin
            from firebase_admin import credentials,messaging
            try: app=firebase_admin.get_app('clearline-family')
            except ValueError: app=firebase_admin.initialize_app(credentials.Certificate(credential),name='clearline-family')
            message=messaging.Message(token=token,
                notification=messaging.Notification(title='ClearLine check-in update',body='Open ClearLine to view the report or any follow-up needed.'),
                data={'session_id':session_id},
                android=messaging.AndroidConfig(priority='high',notification=messaging.AndroidNotification(channel_id='clearline_reports',tag=session_id,click_action='CLEARLINE_REPORT')))
            return {'provider':'FCM','message_id':messaging.send(message,app=app),'status':'accepted_by_provider'}
        except ImportError: raise IntegrationError('push_sdk_missing','Firebase admin SDK missing') from None
        except Exception: raise IntegrationError('push_send_failed','Firebase delivery request failed',True) from None
