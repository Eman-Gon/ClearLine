"""Webhook-only public ingress. Never expose the family API through the tunnel."""
from dotenv import load_dotenv
from fastapi import FastAPI
from backend.api.config import Settings
from backend.storage import Store
from backend.calls.routes import make_call_router

load_dotenv()
settings = Settings()
router = make_call_router(Store(settings.database_path), settings)
app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
app.router.routes.extend(r for r in router.routes if r.path == '/api/vapi/webhook')
