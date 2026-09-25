"""Synthetic PCM and explicit transcription doubles; no claimed speech smoke test."""

from pathlib import Path
import shutil
import struct
import subprocess
import sys
from types import SimpleNamespace
import wave

import pytest

from backend.audio import AudioError, AudioProcessor, aggregate_metrics, corrected_metrics
from backend.audio import processor as audio_module


def recording(tmp_path, *, duration=3, amplitude=8192, name="synthetic.wav"):
    path = tmp_path / name
    # Alternating amplitude has exactly known RMS; it is deliberately synthetic,
    # not an attempt to fake voice or a real transcription integration test.
    samples = struct.pack("<hh", amplitude, -amplitude) * int(16_000 * duration / 2)
    with wave.open(str(path), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(16_000)
        output.writeframes(samples)
    return path


@pytest.fixture
def fake_transcription(monkeypatch):
    monkeypatch.setattr(AudioProcessor, "_transcribe", lambda self, path: "One two three four five six.")


@pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="Real ffmpeg decoder is not installed")
def test_real_ffmpeg_decoding_and_normalized_rms(tmp_path, fake_transcription):
    path = recording(tmp_path)
    result = AudioProcessor().process(str(path), data_origin="synthetic")
    assert result["method_version"] == "clearline-v1"
    assert result["transcript"] == "One two three four five six."
    assert result["metrics"] == {
        "duration_s": 3.0, "word_count": 6, "recording_wpm": 120.0,
        "energy_rms": 0.25, "pause_count": None, "pitch_mean_hz": None,
        "quality": "accepted", "quality_reasons": [], "data_origin": "synthetic",
    }
    assert path.exists(), "worker owns the original upload until its transaction commits"


@pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="Real ffmpeg decoder is not installed")
@pytest.mark.parametrize("duration,amplitude,error", [
    (1, 8192, "audio_too_short"), (3, 0, "silent_audio"),
    (3, 2, "silent_audio"), (4, 8192, "audio_too_long"),
])
def test_unusable_audio(tmp_path, duration, amplitude, error, fake_transcription):
    path = recording(tmp_path, duration=duration, amplitude=amplitude)
    with pytest.raises(AudioError) as caught:
        AudioProcessor(max_duration_s=3).process(str(path))
    assert caught.value.code == error
    assert caught.value.retryable is False


@pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="Real ffmpeg decoder is not installed")
def test_corrupt_container_is_rejected(tmp_path):
    path = tmp_path / "fragment.webm"
    path.write_bytes(b"This is not a complete audio container")
    with pytest.raises(AudioError, match="complete recording") as caught:
        AudioProcessor().process(str(path))
    assert caught.value.code == "invalid_audio"


@pytest.mark.parametrize("kind,code", [("missing", "audio_missing"), ("empty", "empty_audio"), ("large", "audio_too_large")])
def test_file_validation_before_decoder(tmp_path, kind, code):
    path = tmp_path / "upload.bin"
    if kind != "missing":
        path.write_bytes(b"12345" if kind == "large" else b"")
    with pytest.raises(AudioError) as caught:
        AudioProcessor(max_upload_bytes=4).process(str(path))
    assert caught.value.code == code


def test_missing_decoder_fails_explicitly(tmp_path):
    path = recording(tmp_path)
    with pytest.raises(AudioError) as caught:
        AudioProcessor(ffmpeg_binary=str(tmp_path / "no-ffmpeg")).process(str(path))
    assert caught.value.code == "ffmpeg_unavailable"


def test_missing_whisper_configuration_never_downloads():
    with pytest.raises(AudioError) as caught:
        AudioProcessor()._load_model()
    assert caught.value.code == "whisper_unavailable"


def test_incomplete_local_model_never_imports_or_downloads(tmp_path, monkeypatch):
    (tmp_path / "model.bin").write_bytes(b"test placeholder")
    (tmp_path / "config.json").write_text("{}")
    monkeypatch.setitem(sys.modules, "faster_whisper", SimpleNamespace(WhisperModel=lambda *a, **k: pytest.fail("must not load without tokenizer")))
    with pytest.raises(AudioError, match="tokenizer.json"):
        AudioProcessor(whisper_model_path=str(tmp_path))._load_model()


def test_missing_whisper_package_fails_explicitly(tmp_path, monkeypatch):
    for name in ("model.bin", "config.json", "tokenizer.json"):
        (tmp_path / name).write_text("test placeholder")
    monkeypatch.setitem(sys.modules, "faster_whisper", None)
    with pytest.raises(AudioError, match="Install faster-whisper") as caught:
        AudioProcessor(whisper_model_path=str(tmp_path))._load_model()
    assert caught.value.code == "whisper_unavailable"


def test_local_model_is_loaded_once_with_downloads_disabled(tmp_path, monkeypatch):
    for name in ("model.bin", "config.json", "tokenizer.json"):
        (tmp_path / name).write_text("test placeholder")
    calls = []
    model = object()
    def constructor(*args, **kwargs):
        calls.append((args, kwargs))
        return model
    monkeypatch.setitem(sys.modules, "faster_whisper", SimpleNamespace(WhisperModel=constructor))
    monkeypatch.setattr(audio_module, "_MODEL_CACHE", {})
    assert AudioProcessor(whisper_model_path=str(tmp_path))._load_model() is model
    assert AudioProcessor(whisper_model_path=str(tmp_path))._load_model() is model
    assert len(calls) == 1
    assert calls[0][0] == (str(tmp_path.resolve()),)
    assert calls[0][1]["local_files_only"] is True


def test_decoder_uses_bounded_argument_list_and_unique_cleanup(tmp_path, monkeypatch):
    source = recording(tmp_path, name="upload ; $(not_a_command).wav")
    calls = []
    monkeypatch.setattr(audio_module.shutil, "which", lambda name: "/fake/ffmpeg")
    def run(command, **kwargs):
        calls.append((command, kwargs))
        shutil.copyfile(source, command[-1])
        return SimpleNamespace(returncode=0)
    monkeypatch.setattr(audio_module.subprocess, "run", run)
    monkeypatch.setattr(AudioProcessor, "_transcribe", lambda self, path: "synthetic words")
    AudioProcessor().process(str(source))
    AudioProcessor().process(str(source))
    assert calls[0][0][-1] != calls[1][0][-1]
    assert calls[0][0][calls[0][0].index("-i") + 1] == str(source)
    assert calls[0][1]["timeout"] == 30
    assert "shell" not in calls[0][1]
    for command, _ in calls:
        assert not Path(command[-1]).exists()


def test_decode_timeout_is_visible_and_cleans_scratch(tmp_path, monkeypatch):
    source = recording(tmp_path)
    scratch = []
    monkeypatch.setattr(audio_module.shutil, "which", lambda name: "/fake/ffmpeg")
    def run(command, **kwargs):
        scratch.append(command[-1])
        raise subprocess.TimeoutExpired(command, kwargs["timeout"])
    monkeypatch.setattr(audio_module.subprocess, "run", run)
    with pytest.raises(AudioError) as caught:
        AudioProcessor().process(str(source))
    assert caught.value.code == "decode_timeout"
    assert not Path(scratch[0]).parent.exists()


@pytest.mark.parametrize("speech_duration,text,probability,logprob", [
    (0, "invented words", 0.01, -0.1),
    (2, "", 0.01, -0.1),
    (2, "invented words", 0.99, -0.1),
    (2, "invented words", 0.01, -2),
])
def test_no_speech_or_unreliable_transcript_is_rejected(tmp_path, monkeypatch, speech_duration, text, probability, logprob):
    options = []
    def transcribe(path, **kwargs):
        options.append(kwargs)
        segment = SimpleNamespace(text=text, no_speech_prob=probability, avg_logprob=logprob)
        return iter([segment]), SimpleNamespace(duration_after_vad=speech_duration)
    monkeypatch.setattr(AudioProcessor, "_load_model", lambda self: SimpleNamespace(transcribe=transcribe))
    with pytest.raises(AudioError) as caught:
        AudioProcessor()._transcribe(tmp_path / "synthetic.wav")
    assert caught.value.code == "no_speech"
    assert options[0]["vad_filter"] is True


def metric(**changes):
    value = {
        "duration_s": 10, "word_count": 20, "recording_wpm": 120,
        "energy_rms": 0.1, "pause_count": None, "pitch_mean_hz": None,
        "quality": "accepted", "quality_reasons": [], "data_origin": "synthetic",
    }
    value.update(changes)
    return value


def test_aggregation_pools_durations_words_and_rms():
    result = aggregate_metrics([
        metric(), metric(duration_s=30, word_count=30, energy_rms=0.3),
        metric(quality="rejected"), metric(superseded=True),
    ])
    assert result["duration_s"] == 40
    assert result["word_count"] == 50
    assert result["recording_wpm"] == 75
    assert result["energy_rms"] == pytest.approx((0.01 * 10 / 40 + 0.09 * 30 / 40) ** 0.5)
    assert result["pause_count"] is None
    assert result["pitch_mean_hz"] is None
    assert aggregate_metrics([]) is None


def test_aggregation_preserves_missing_fields_and_separates_provenance():
    assert aggregate_metrics([metric(), metric(energy_rms=None)])["energy_rms"] is None
    with pytest.raises(AudioError) as caught:
        aggregate_metrics([metric(), metric(data_origin="consented_demo")])
    assert caught.value.code == "invalid_provenance"


def test_correction_changes_only_word_derived_metrics():
    original = metric()
    revised = corrected_metrics(original, "I'm recording four words.")
    assert revised["word_count"] == 4
    assert revised["recording_wpm"] == 24
    assert original["word_count"] == 20
    assert {k: v for k, v in revised.items() if k not in {"word_count", "recording_wpm"}} == {
        k: v for k, v in original.items() if k not in {"word_count", "recording_wpm"}
    }
    with pytest.raises(AudioError) as caught:
        corrected_metrics(original, "... !!!")
    assert caught.value.code == "empty_transcript"
