# Liquid artifact setup

The approved initial artifact is `LiquidAI/LFM2.5-1.2B-Instruct-GGUF` at revision `8ed288026e23958ad9dfa92d53ed773a8eee7125`, file `LFM2.5-1.2B-Instruct-Q4_0.gguf` (ordinary post-training Q4_0, not QAD). Its published LFS size is **695,751,488 bytes** and SHA-256 is **2ea801949d760cdf1a2cc04a54262c22c3c0c54f0769d57760c9adeb0e59233f**. [Pinned official repository](https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct-GGUF/tree/8ed288026e23958ad9dfa92d53ed773a8eee7125)

The metadata was retrieved from Hugging Face's official model API on 2026-09-25. Only a bounded GGUF header was inspected, not a complete model download or execution. The checked metadata and upstream license are in `model-metadata/`. Setup requires user approval after showing the model identity, size, and license reference.

## Private installer

Construct `PrivateModelInstaller(File(context.noBackupFilesDir, "liquid-models").canonicalFile, contentStream = { context.contentResolver.openInputStream(Uri.parse(it)) })`. The application must retain the spec's backup/device-transfer exclusions. The installer accepts exactly an identity in its compiled catalog and either:

- A `content://` document selected for explicit import. The stream is copied to private storage; it is never treated as a filesystem path or used in place.
- The exact HTTPS resolve URL containing the pinned revision and filename, after an explicit download choice. Redirects stay on the declared HTTPS hosts (`huggingface.co`, `us.aws.cdn.hf.co`, and `cas-bridge.xethub.hf.co`); other hosts fail closed. The CDN allowlist may need a reviewed update if Hugging Face changes delivery hosts.

No credentials, transcript, prompt, session IDs, or other private app data enter the model-download request. Normal inference does not call this transport. The network must be available for download; import and verified local loading work offline.

Space checks require the complete artifact size plus a 64 MiB reserve on the destination filesystem. The check cannot reserve against other applications consuming space later, so write failures remain visible. Downloads are not resumed: interrupted `.part` files are removed at startup/setup verification and retried from the beginning.

Every copy computes SHA-256 and counts bytes with an enforced upper bound. A short, oversized, or corrupt stream fails. The temporary file is synced, then promoted with an atomic same-directory rename; no fallback copy publishes a partial artifact. A separate synced/atomically promoted manifest records all approved identity fields. The installer requires both the manifest and final file and re-hashes the complete file before each load. A final file without its completion manifest is unavailable and requires a fresh installation. This favors predictable integrity over saving setup time after interruption.

Installer `INSTALLED` means verified bytes only. The runtime must report `READY` only after a successful native load. Setup emits installing/verifying/installed/failed updates; no installation state claims on-device inference. Imports, hashing, and downloads run on `Dispatchers.IO`. Cancellation is checked for each bounded chunk, and network reads have 30-second timeouts; a blocked platform read may delay cancellation until it returns or times out.

## Exact template and tokenizer identity

The selected GGUF embeds a template whose SHA-256 is `f05bf4b967dc993bdc7a2fe6e43759ee218eb0eb340d68b063e1c4f8ad148176`. The verbatim template is `model-metadata/lfm2.5-q4_0-chat-template.jinja`. Its render uses BOS, role-delimited messages, system `List of tools: [...]`, preserved raw assistant call content, and a trailing assistant-generation prefix. A tool result is a `tool` role message. It does not use the later original repository template's structured `tool_calls` rendering branch.

GGUF metadata identifies BOS `<|startoftext|>` as token 1, `<|im_start|>` as 6, and EOS `<|im_end|>` as 7; `add_bos_token=true`, `add_eos_token=false`. Tool-call markers are tokens 10 and 11 with GGUF token type 3 (control), so native output decoding must preserve them. A fully rendered prompt already has BOS: tokenization must not add it a second time. Count the actual loaded tokenizer's tokens after rendering schemas and tool results.

The original tokenizer repository was also inspected at revision `0f604ada3f766f9f257460c4c9f0b5d6f69d431b`; its newer template hash differs. Do not silently replace the pinned GGUF template with that newer file. Liquid's documented JSON instruction is “Output function calls as JSON”; app-owned strict validation still decides whether a call can run. [Official tool-use documentation](https://docs.liquid.ai/lfm/key-concepts/tool-use)

## Verification status

`PrivateModelInstallerTest` uses short, visibly synthetic byte streams to exercise integrity, short/oversized input, atomic completion markers, storage failure, cancellation, source allowlists, tampering, and restart cleanup. These are installer tests, not model inference tests. No complete weight download, S24 model import/load, offline tool round trip, storage-exhaustion device check, or performance/thermal result is claimed by this document. Device acceptance remains pending.
