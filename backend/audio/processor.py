"""Bounded local decoding and transcription for complete recordings.

Call ``process`` from the serial worker, off the HTTP event loop. This module
owns only its decoded scratch file. The worker owns retention of the original
upload and must remove it after committing an accepted/rejected job outcome.
"""

from __future__ import annotations

import importlib.util
import math
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import threading
from typing import Any
import wave


METHOD_VERSION = "clearline-v1"
SAMPLE_RATE = 16_000
ALLOWED_ORIGINS = {"synthetic", "consented_demo"}
_MODEL_CACHE: dict[str, Any] = {}
_MODEL_LOCK = threading.Lock()


class AudioError(Exception):
    def __init__(self, code: str, message: str, retryable: bool = False):
        super().__init__(message)
        self.code = code
        self.message = message
        self.retryable = retryable


def _word_count(transcript: str) -> int:
    # A reproducible lexical-token count, including contractions. It is not a
    # vocabulary/readability measurement or a language-specific word segmenter.
    return len(re.findall(r"[^\W_]+(?:['’][^\W_]+)*", transcript, flags=re.UNICODE))


class AudioProcessor:
    def __init__(
        self,
        ffmpeg_binary: str = "ffmpeg",
        whisper_model_path: str | None = None,
        min_duration_s: float = 2,
        max_duration_s: float = 60,
        *,
        max_upload_bytes: int = 20 * 1024 * 1024,
        decode_timeout_s: float = 30,
    ):
        if not 0 < min_duration_s <= max_duration_s <= 600:
            raise ValueError("Duration limits must satisfy 0 < min <= max <= 600.")
        if max_upload_bytes <= 0 or decode_timeout_s <= 0:
            raise ValueError("Upload and timeout limits must be positive.")
        self.ffmpeg_binary = ffmpeg_binary
        self.whisper_model_path = whisper_model_path
        self.min_duration_s = min_duration_s
        self.max_duration_s = max_duration_s
        self.max_upload_bytes = max_upload_bytes
        self.decode_timeout_s = decode_timeout_s

    def readiness(self) -> dict:
        """Read local configuration only; do not load models or make requests."""
        model_path = Path(self.whisper_model_path).expanduser() if self.whisper_model_path else None
        local_files = bool(model_path and all(
            (model_path / name).is_file()
            for name in ("model.bin", "config.json", "tokenizer.json")
        ))
        return {
            "ffmpeg_available": shutil.which(self.ffmpeg_binary) is not None,
            "whisper_package_available": importlib.util.find_spec("faster_whisper") is not None,
            "whisper_model_configured": local_files,
            "method_version": METHOD_VERSION,
        }

    def process(self, path: str, data_origin: str = "consented_demo") -> dict:
        if data_origin not in ALLOWED_ORIGINS:
            raise AudioError("invalid_provenance", "Recording provenance is required.")
        source = Path(path).resolve()
        try:
            if not source.is_file():
                raise AudioError("audio_missing", "Recording is missing; record a replacement.")
            size = source.stat().st_size
        except OSError as exc:
            raise AudioError("audio_missing", "Recording cannot be read; record a replacement.") from exc
        if size == 0:
            raise AudioError("empty_audio", "The recording is empty; record a replacement.")
        if size > self.max_upload_bytes:
            raise AudioError("audio_too_large", "Recording exceeds the upload size limit.")
        executable = shutil.which(self.ffmpeg_binary)
        if not executable:
            raise AudioError("ffmpeg_unavailable", "Install ffmpeg on the paired laptop before processing audio.")

        with tempfile.TemporaryDirectory(prefix="clearline-decoded-") as directory:
            decoded = Path(directory) / "audio.wav"
            self._decode(executable, source, decoded)
            duration, energy_rms, peak = self._measure_pcm(decoded)
            if duration < self.min_duration_s:
                raise AudioError("audio_too_short", f"Record at least {self.min_duration_s:g} seconds.")
            if duration > self.max_duration_s:
                raise AudioError("audio_too_long", f"Record no more than {self.max_duration_s:g} seconds.")
            # Engineering quality filters on normalized amplitude, not health
            # thresholds. A VAD-based speech check follows in local Whisper.
            if energy_rms < 0.0005 or peak < 0.003:
                raise AudioError("silent_audio", "Recording is silent or too quiet; record a replacement.")
            transcript = self._transcribe(decoded)
            words = _word_count(transcript)
            if words == 0:
                raise AudioError("no_speech", "No usable speech was transcribed; record a replacement.")
            return {
                "metrics": {
                    "duration_s": round(duration, 6),
                    "word_count": words,
                    "recording_wpm": round(words * 60 / duration, 2),
                    "pause_count": None,
                    "energy_rms": round(energy_rms, 8),
                    "pitch_mean_hz": None,
                    "quality": "accepted",
                    "quality_reasons": [],
                    "data_origin": data_origin,
                },
                "transcript": transcript,
                "method_version": METHOD_VERSION,
            }

    def _decode(self, executable: str, source: Path, decoded: Path) -> None:
        command = [
            executable, "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
            "-protocol_whitelist", "file,pipe", "-i", str(source),
            "-map", "0:a:0", "-vn", "-sn", "-dn", "-ac", "1", "-ar", str(SAMPLE_RATE),
            "-acodec", "pcm_s16le", "-t", str(self.max_duration_s + 1),
            "-f", "wav", str(decoded),
        ]
        try:
            result = subprocess.run(
                command, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                stderr=subprocess.PIPE, timeout=self.decode_timeout_s, check=False,
            )
        except subprocess.TimeoutExpired as exc:
            raise AudioError("decode_timeout", "Audio decoding timed out; record a replacement.") from exc
        except OSError as exc:
            raise AudioError("ffmpeg_unavailable", "ffmpeg could not start on the paired laptop.") from exc
        if result.returncode != 0 or not decoded.is_file():
            raise AudioError("invalid_audio", "The complete recording could not be decoded; record a replacement.")

    def _measure_pcm(self, path: Path) -> tuple[float, float, float]:
        try:
            import numpy as np
        except ImportError as exc:
            raise AudioError("audio_dependency_unavailable", "Install the backend audio dependencies on the paired laptop.") from exc
        try:
            with wave.open(str(path), "rb") as recording:
                if (recording.getnchannels(), recording.getsampwidth(), recording.getframerate()) != (1, 2, SAMPLE_RATE):
                    raise AudioError("invalid_audio", "Decoded audio does not match the required PCM format.")
                frames = recording.getnframes()
                if frames > int((self.max_duration_s + 1.1) * SAMPLE_RATE):
                    raise AudioError("audio_too_long", "Recording exceeds the duration limit.")
                pcm = recording.readframes(frames)
                if len(pcm) != frames * 2 or frames == 0:
                    raise AudioError("invalid_audio", "The decoded recording is empty or incomplete.")
        except (wave.Error, EOFError, OSError) as exc:
            raise AudioError("invalid_audio", "The decoded recording cannot be read.") from exc
        samples = np.frombuffer(pcm, dtype="<i2").astype(np.float64) / 32768.0
        return frames / SAMPLE_RATE, float(np.sqrt(np.mean(samples * samples))), float(np.max(np.abs(samples)))

    def _load_model(self) -> Any:
        if not self.whisper_model_path:
            raise AudioError("whisper_unavailable", "Configure a complete local faster-whisper model directory on the paired laptop.")
        directory = Path(self.whisper_model_path).expanduser().resolve()
        # faster-whisper can fetch a tokenizer when this file is absent even
        # with local_files_only=True. Reject incomplete local installations.
        if not all((directory / name).is_file() for name in ("model.bin", "config.json", "tokenizer.json")):
            raise AudioError("whisper_unavailable", "Local Whisper model files are missing; install model.bin, config.json and tokenizer.json.")
        cache_key = str(directory)
        with _MODEL_LOCK:
            if cache_key in _MODEL_CACHE:
                return _MODEL_CACHE[cache_key]
            try:
                from faster_whisper import WhisperModel
                model = WhisperModel(
                    cache_key, device="cpu", compute_type="int8",
                    local_files_only=True, num_workers=1,
                )
            except ImportError as exc:
                raise AudioError("whisper_unavailable", "Install faster-whisper on the paired laptop.") from exc
            except Exception as exc:
                raise AudioError("whisper_unavailable", "The local Whisper model could not be loaded; check its installation.") from exc
            _MODEL_CACHE[cache_key] = model
            return model

    def _transcribe(self, path: Path) -> str:
        model = self._load_model()
        try:
            segments, info = model.transcribe(
                str(path), vad_filter=True, word_timestamps=False, beam_size=1,
                temperature=0, condition_on_previous_text=False,
            )
            if not math.isfinite(info.duration_after_vad) or info.duration_after_vad < 0.2:
                raise AudioError("no_speech", "No detectable speech was found; record a replacement.")
            parts = []
            for segment in segments:
                # Do not accept a no-speech or unreliable transcription as
                # reliable text, even if a model returned words for noise.
                if segment.no_speech_prob > 0.6 or segment.avg_logprob < -1.0:
                    continue
                if segment.text.strip():
                    parts.append(segment.text.strip())
            transcript = " ".join(parts)
        except AudioError:
            raise
        except Exception as exc:
            raise AudioError("transcription_failed", "Local transcription failed; check the laptop audio dependencies.") from exc
        if not transcript or _word_count(transcript) == 0:
            raise AudioError("no_speech", "No usable speech was transcribed; record a replacement.")
        return transcript


def corrected_metrics(metrics: dict, transcript: str) -> dict:
    """Return revised text-derived measurements without mutating old versions."""
    words = _word_count(transcript)
    if words == 0:
        raise AudioError("empty_transcript", "A transcript correction must contain words.")
    duration = metrics.get("duration_s")
    if not isinstance(duration, (float, int)) or not math.isfinite(duration) or duration <= 0:
        raise AudioError("invalid_metrics", "A positive recording duration is required.")
    result = dict(metrics)
    result.update(word_count=words, recording_wpm=round(words * 60 / duration, 2))
    return result


def aggregate_metrics(clips: list[dict]) -> dict | None:
    """Aggregate accepted clips supplied by storage, excluding superseded ones.

    The caller selects one measurement method and only current clip versions.
    Optional quantities remain unavailable if any participating clip lacks them.
    """
    accepted = [m for m in clips if m.get("quality") == "accepted" and not m.get("superseded", False)]
    if not accepted:
        return None
    origins = {m.get("data_origin") for m in accepted}
    if len(origins) != 1 or not origins.issubset(ALLOWED_ORIGINS):
        raise AudioError("invalid_provenance", "Do not combine synthetic and consented recording measurements.")
    for metric in accepted:
        duration = metric.get("duration_s")
        words = metric.get("word_count")
        if (not isinstance(duration, (int, float)) or isinstance(duration, bool)
                or not math.isfinite(duration) or duration <= 0
                or not isinstance(words, int) or isinstance(words, bool) or words < 0):
            raise AudioError("invalid_metrics", "Accepted clips require a positive duration and nonnegative word count.")
    duration = sum(m["duration_s"] for m in accepted)
    words = sum(m["word_count"] for m in accepted)

    def weighted(name: str, squared: bool = False) -> float | None:
        values = [m.get(name) for m in accepted]
        if any(v is None for v in values):
            return None
        if any(not isinstance(v, (int, float)) or isinstance(v, bool) or not math.isfinite(v) or v < 0 for v in values):
            raise AudioError("invalid_metrics", "Measurements must be finite nonnegative numbers or null.")
        mean = sum((v * v if squared else v) * m["duration_s"] for v, m in zip(values, accepted)) / duration
        return round(math.sqrt(mean) if squared else mean, 8)

    pauses = [m.get("pause_count") for m in accepted]
    if any(v is not None and (not isinstance(v, int) or isinstance(v, bool) or v < 0) for v in pauses):
        raise AudioError("invalid_metrics", "Pause counts must be nonnegative integers or null.")
    return {
        "duration_s": round(duration, 6),
        "word_count": words,
        "recording_wpm": round(words * 60 / duration, 2),
        "pause_count": None if any(v is None for v in pauses) else sum(pauses),
        "energy_rms": weighted("energy_rms", squared=True),
        "pitch_mean_hz": weighted("pitch_mean_hz"),
        "quality": "accepted",
        "quality_reasons": [],
        "data_origin": next(iter(origins)),
    }
