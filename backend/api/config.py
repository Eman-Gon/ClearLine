from dataclasses import dataclass, field
import os
from pathlib import Path


@dataclass
class Settings:
    database_path: str = field(default_factory=lambda: os.getenv('CLEARLINE_DB', 'backend/data/clearline.sqlite3'))
    upload_dir: str = field(default_factory=lambda: os.getenv('CLEARLINE_UPLOAD_DIR', 'backend/data/uploads'))
    pairing_code: str = field(default_factory=lambda: os.getenv('CLEARLINE_PAIRING_CODE', ''))
    allowed_origins: tuple[str, ...] = field(default_factory=lambda: tuple(v.strip().rstrip('/') for v in os.getenv('CLEARLINE_ALLOWED_ORIGINS', 'http://localhost:3000,http://127.0.0.1:3000').split(',') if v.strip()))
    whisper_model_path: str | None = field(default_factory=lambda: os.getenv('WHISPER_MODEL_PATH'))
    ffmpeg_binary: str = field(default_factory=lambda: os.getenv('FFMPEG_BINARY', 'ffmpeg'))
    max_upload_bytes: int = 16 * 1024 * 1024
    max_action_count: int = 12
    worker_enabled: bool = True
    frontend_dir: str = str(Path(__file__).resolve().parents[2] / 'frontend')
