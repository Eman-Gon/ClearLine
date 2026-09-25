# Phone-local agent and recovery

This is Session 2's native Kotlin workflow implementation for [specification 3.1](../../ClearLine_Fixed_Build_Spec.md). It uses the phone's embedded Liquid runtime and transactional local storage. The Python backend and web prototype are not dependencies. Native implementation is in progress; no S24 acceptance or successful Android APK build is established by this document.

## Components

- `OnDeviceCoordinator` implements `CheckInCommands`. One command mutex protects state changes and one execution mutex serializes foreground work. `WorkflowStore` owns durable claims, plans, atomic commits, consent records, deletion, and the outbox. Session 1 supplies Room and local audio processing; Session 3 supplies the typed sponsor clients.
- `OnDeviceLocalAgent` in `inference/` proposes one tool through `ToolCallingLocalAgent`. `WorkflowPolicy` independently validates the current identity, scope, revision, consent, candidate references, and completion requirements before dispatch and commit. Generated text cannot grant approval, select credentials, issue SQL, or run code.
- `EmbeddedLiquidRuntime` implements `ModelRuntime` and `LocalGenerationEngine`. It loads the approved GGUF through the in-process `clearline_liquid` JNI library and CPU llama.cpp. The shared model arbiter serializes Liquid and Whisper use. Cancellation waits for the native operation to reach a safe boundary before releasing ownership or unloading.
- `PrivateModelInstaller` verifies an approved private import/download. Installed bytes and a loaded runtime have distinct states. See [artifact identity and setup](../inference/MODEL_SETUP.md) and [native source/build identity](../inference/native/README.md).

The local tool set is baseline retrieval, descriptive comparison, approved public Search, extraction of a saved candidate, a bounded missing-input question, and validated completion. Baselines come from eligible Room summaries. RawTree exports and explicit/post-delivery memory refreshes use separate typed ports; cloud history never replaces the phone checkpoint or local baseline. Recording, reviewed Nimble query approval, measurement export, and optional reviewed snippet/keyword export remain separate choices.

## Execution and recovery boundaries

The coordinator initializes recovery without launching tools. The app supplies foreground/background lifecycle events; interrupted work needs explicit Resume. State observation and Flow collection do not create jobs, run inference, or refresh sponsor data.

An eligible job is claimed transactionally. Inference runs outside the transaction; the validated plan, action ID, and assistant/tool-call identity are persisted before execution. Tool work also runs outside the transaction. Its current revision is checked again before Room atomically commits the result, completed action, new checkpoint/state, and eligible export projections. Full results remain local; prompt construction uses a bounded projection and the saved matching exchange.

Recovery reuses committed successes and a valid unfinished plan. An interrupted, uncommitted read may run again with its saved identity. An export delivered before its acknowledgement may also replay. These boundaries do not promise exactly-once external execution. The initial action budget is twelve per workflow revision. Pause exposes its pending state while cancellation drains. Deletion cancels execution and relies on store tombstones to reject late writes; durable file cleanup can finish after restart. Recovery requires surviving app data.

The initial model budget is 4096 context tokens: at most 3072 fully rendered input tokens and 768 output tokens. Counts use the loaded tokenizer, including schemas, role/control tokens, and tool results. Optional candidate descriptions shrink first; required state that still cannot fit fails visibly. The parser rejects malformed/truncated output, duplicate/unknown fields, multiple calls, and invalid arguments. Three invalid generation attempts end with `AGENT_UNAVAILABLE`; there is no remote inference fallback or scripted planner presented as Liquid.

## Device gate and verification

After installing the verified models, load `LiquidModelCatalog.DEFAULT` and run `LiquidNonceProbe(embeddedRuntime).run()` on the actual S24 with networking disabled. The first generated, validated `echo_nonce` call has empty arguments. Only then does the probe create an unpredictable nonce, return a tool-role result, run a second inference, and require `confirm_nonce` to contain that exact value. A fake-engine protocol test or ordinary chat reply does not pass this gate.

Record the S24 variant/SoC/RAM/OS, exact model/native identities, APK ABI, cold-load time, both turns' token counts and latency, memory, loaded-model cancellation/unload, and repeated-run thermal behavior. Also test real audio/ASR coexistence and Search-commit → process kill → reopen → explicit Resume → unfinished Extract, preserving the accepted clip and committed Search IDs. Hardware timings remain unmeasured until those runs occur.

After provisioning the [Android toolchain](../docs/TOOLCHAIN.md), the module checks are:

```sh
cd android
./gradlew :agent:test :inference:testDebugUnitTest :storage:testDebugUnitTest
./gradlew :app:assembleDebug
```

Observed host evidence on September 25, 2026: a temporary JVM-only harness using Kotlin 2.2.10, coroutines 1.10.1, serialization 1.8.0, and JDK 17 passed the initial ten `CoordinatorTest` cases. They use explicit in-memory store/model/audio/sponsor doubles and cover observation, offline local comparison, duplicate commands, pause, saved-plan reconstruction, stale results, network failure, and export revocation. They do not exercise Room, Whisper, loaded Liquid, actual process death, or an S24. Installer tests use tiny synthetic bytes; inference protocol tests use fake engines. Separate host native checks compile the real pinned llama.cpp library without weights; their results and limits are recorded in the native README.

The full Android build/device gates remain separate from these host checks. The toolchain record documents the current SDK/license/build prerequisites; no desktop or mocked result substitutes for an installed APK, offline nonce round trip, phone recording, or phone process-death recovery.
