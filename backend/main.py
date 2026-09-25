"""Run: python -m uvicorn backend.main:app --host 127.0.0.1 --port 3000 --workers 1."""
import asyncio
from contextlib import asynccontextmanager
import importlib.util
import os
from pathlib import Path
import shutil

from dotenv import load_dotenv
from fastapi import FastAPI
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles

from backend.api.config import Settings
from backend.api.routes import make_router, UploadLimitMiddleware
from backend.audio import AudioProcessor
from backend.storage import Store
from backend.worker.runner import Worker
from backend.worker.interfaces import UnavailableRunner


def create_app(settings: Settings | None = None, *, audio_processor=None, agent_runner=None, exporter=None):
    settings = settings or Settings()
    store = Store(settings.database_path)
    Path(settings.upload_dir).mkdir(parents=True, exist_ok=True, mode=0o700)
    audio_processor = audio_processor or AudioProcessor(ffmpeg_binary=settings.ffmpeg_binary, whisper_model_path=settings.whisper_model_path)
    integration_error = None
    if agent_runner is None:
        try:
            from backend.agent import create_agent_runner, create_exporter
            agent_runner = create_agent_runner()
            exporter = exporter or create_exporter()
        except (ImportError, RuntimeError, ValueError) as exc:
            integration_error = type(exc).__name__
            agent_runner = UnavailableRunner()
    worker = Worker(store, audio_processor, agent_runner, exporter, settings)

    @asynccontextmanager
    async def lifespan(app):
        # A process-scoped advisory lock prevents a second app worker from recovering
        # jobs already executing in the first. The OS releases it on process death.
        import fcntl
        lock = open(str(settings.database_path) + '.worker.lock', 'a')
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            lock.close()
            raise RuntimeError('ClearLine requires one application process per database.')
        task = None
        try:
            store.recover()
            worker.cleanup_media()
            if settings.worker_enabled:
                task = asyncio.create_task(worker.run())
            yield
        finally:
            worker.stop_requested = True
            if task:
                await task
            for service in (agent_runner,exporter):
                close=getattr(service,'aclose',None)
                if close:
                    await close()
            fcntl.flock(lock, fcntl.LOCK_UN)
            lock.close()

    app = FastAPI(title='ClearLine demo API', lifespan=lifespan)
    app.state.store = store
    app.state.worker = worker
    app.state.settings = settings
    app.add_middleware(UploadLimitMiddleware, max_bytes=settings.max_upload_bytes + 65536)
    app.include_router(make_router(store, settings))

    @app.get('/api/health')
    def health():
        readiness=audio_processor.readiness() if hasattr(audio_processor,'readiness') else {}
        whisper_ready=readiness.get('whisper_package_available',False) and readiness.get('whisper_model_configured',False)
        available = not isinstance(agent_runner, UnavailableRunner)
        return {
            'status': 'ready' if settings.pairing_code else 'pairing_unconfigured',
            'demo_only': True,
            'components': {
                'backend': {'status': 'ready', 'label': 'Local SQLite backend'},
                'audio': {'status': 'configured' if whisper_ready and shutil.which(settings.ffmpeg_binary) else 'unavailable', 'label': 'Local Whisper and ffmpeg; readiness is configuration only'},
                'liquid': {'status': 'configured_unverified' if available else 'agent_unavailable', 'label': 'Local Liquid agent; live tool round trip required'},
                'rawtree': {'status': 'configured_unverified' if exporter and os.getenv('RAWTREE_API_KEY') and os.getenv('RAWTREE_DATABASE') else 'missing_credentials', 'label': 'Approved export outbox'},
                'nimble': {'status': 'configured_unverified' if available and os.getenv('NIMBLE_API_KEY') else 'missing_credentials', 'label': 'Public resource adapter; live test required'},
            },
            'pairing_configured': bool(settings.pairing_code),
            'integration_status': 'adapter_loaded' if available else 'agent_unavailable',
        }

    @app.get('/')
    def index():
        path = Path(settings.frontend_dir) / 'index.html'
        if path.is_file():
            return FileResponse(path)
        return JSONResponse({'app': 'ClearLine', 'frontend': 'Session 1 frontend not yet available', 'api_docs': '/docs'})

    if Path(settings.frontend_dir).is_dir():
        app.mount('/static', StaticFiles(directory=settings.frontend_dir), name='static')
    return app


load_dotenv()
app = create_app()
