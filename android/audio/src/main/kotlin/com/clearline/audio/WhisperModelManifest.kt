package com.clearline.audio

import com.clearline.core.*

object WhisperModelManifest {
    const val RUNTIME_COMMIT = "a8d002cfd879315632a579e73f0148d06959de36"
    val identity = ModelIdentity(
        ModelId("whisper-tiny.en-f16-v1"), "ggerganov/whisper.cpp", "5359861c739e955e79d9a303bcbc70fb988958b1",
        "ggml-tiny.en.bin", "921e4cf8686fdd993dcd081a5da5b6c365bfde1162e72b08d75ac75289920b1f", 77704715L,
        ModelKind.WHISPER, "F16", "whisper-english-transcribe-v1", "en", "https://github.com/openai/whisper/blob/main/LICENSE"
    )
    val downloadUrl = "https://huggingface.co/${identity.repository}/resolve/${identity.revision}/${identity.filename}"
    fun approvedDownload() = ApprovedModelArtifact(identity, downloadUrl, System.currentTimeMillis())
}
