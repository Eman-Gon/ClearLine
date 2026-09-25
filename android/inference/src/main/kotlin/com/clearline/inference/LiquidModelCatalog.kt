package com.clearline.inference

import com.clearline.core.ApprovedModelArtifact
import com.clearline.core.ModelId
import com.clearline.core.ModelIdentity
import com.clearline.core.ModelKind

/** Reviewed artifacts only. This is the ordinary Q4_0 release, not the distinct QAD release. */
object LiquidModelCatalog {
    val DEFAULT = ModelIdentity(
        modelId = ModelId("lfm2.5-1.2b-instruct-q4_0"),
        repository = "LiquidAI/LFM2.5-1.2B-Instruct-GGUF",
        revision = "8ed288026e23958ad9dfa92d53ed773a8eee7125",
        filename = "LFM2.5-1.2B-Instruct-Q4_0.gguf",
        sha256 = "2ea801949d760cdf1a2cc04a54262c22c3c0c54f0769d57760c9adeb0e59233f",
        sizeBytes = 695_751_488L,
        modelKind = ModelKind.LIQUID,
        quantization = "Q4_0",
        templateIdentity = "sha256:f05bf4b967dc993bdc7a2fe6e43759ee218eb0eb340d68b063e1c4f8ad148176",
        language = "English demo; other languages not evaluated",
        licenseReference = "https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct-GGUF/blob/8ed288026e23958ad9dfa92d53ed773a8eee7125/LICENSE",
    )

    /** Construct only after the setup UI's explicit download approval. */
    fun downloadArtifact(approvedAtMs: Long) = ApprovedModelArtifact(DEFAULT, downloadUrl(DEFAULT), approvedAtMs)

    fun downloadUrl(identity: ModelIdentity): String =
        "https://huggingface.co/${identity.repository}/resolve/${identity.revision}/${identity.filename}"
}
