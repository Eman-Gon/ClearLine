# Native sponsor tools (Session 3)

This module implements the optional Android Nimble and RawTree ports in the revision 3.1 build specification. It does not run Liquid inference, record audio, mutate Room, schedule jobs, or grant consent. Offline transcription, measurements, local history and recovery remain owned by the phone's local modules.

## Composition and credentials

Create `SponsorServices.create(applicationContext, authorization)` once per application process. It exposes `credentials: SponsorCredentialSettings`, `resources: PublicResourceClient`, and `history: ApprovedHistoryClient`. The default authorization denies all operations. Close the services when disposing the application graph in tests.

The app's `SponsorAuthorization` reads current persisted state immediately before dispatch. It must reject deleted or stale sessions, changed input revisions, revoked approval, and a source outside the current committed Search. An `ApprovedExport` object is not itself proof of current consent. Check approved exact snippet/keyword values as well as both measurement and text-export grants. No observer, credential entry, or settings toggle performs a sponsor request.

Only Nimble and RawTree have credentials. Enter owner-provided values through native settings and close each `SecretValue` after use. There is no Liquid API key. The UI can observe configured/missing/error status but cannot read tokens. Never copy desktop `.env` into Android assets, BuildConfig, fixtures, or logs.

Each sponsor token is encrypted with AES-256-GCM using a distinct Android Keystore key and authenticated service identity. Ciphertext and nonsecret enablement/database settings live under `Context.noBackupFilesDir`. Clear removes both key and ciphertext. Missing keys or corrupt ciphertext produce errors rather than a replacement key that silently loses the saved credential. Temporary token byte arrays are wiped on completion/cancellation; OkHttp's authorization header necessarily uses a transient JVM string. Keystore does not protect against every compromised-device threat.

An in-memory credential version check blocks a pending request if its key is cleared/replaced or its sponsor settings change while authorization is suspended. Session consent is also rechecked by the app. A request already sent cannot be recalled.

## Nimble and source evidence

Search posts the exact approved query to `https://sdk.nimbleway.com/v2/search`. Only query, country and bounded result/content options leave the phone. Transcript rationale and full transcript remain local. Legacy category requests use a bounded fallback phrase; an approved transcript-shaped query is never replaced with that phrase.

Extract uses `/v2/extract` only for a selected result of that approved search. Titles, descriptions, vendor request/task IDs, retrieval times, normalized source URL, content hash and exact supporting passages are preserved. Missing phone/address/hours remain null. A task ID without completed content is not evidence. Returned pages are untrusted text and cannot grant consent or trigger tools.

Source checks reject unsupported schemes, userinfo, local/private/reserved addresses and disallowed redirects. Phone DNS validation cannot pin the remote Nimble resolver or prove its redirect policy; returned target URLs are checked again. The HTTP transport itself uses only fixed sponsor HTTPS origins, disables redirects and automatic connection retries, bounds request/response sizes, and never logs raw response bodies or credentials. Errors are typed and redacted.

See [RAWTREE.md](RAWTREE.md) for projections, logical counts, exact-ID history and the bounded cloud baseline.

## Repeatable checks

The host harness compiles actual `core` and sponsor Kotlin sources using the isolated toolchain. Android binding files are explicitly excluded, so this is not an APK or Keystore test:

On September 25, 2026, the complete host run passed 68 tests with zero failures, errors or skips: core 24, cipher 4, vault 4, HTTP 5, Nimble 17 and RawTree 14. It used Kotlin 2.2.10, JDK 17 and Gradle 8.11.1. The covered dispatch cases include revoked consent and credential clear/replace/disable during authorization. Only indentation and documentation changed after that run.

```sh
cd /Users/emanschool/ClearLine
android/sponsors/tools/test-host-jvm.sh
```

After installing the SDK/NDK and accepting the SDK agreement through the setup flow in [TOOLCHAIN.md](../docs/TOOLCHAIN.md), run:

```sh
cd /Users/emanschool/ClearLine/android
source /private/tmp/clearline-android-toolchain/env.sh
./gradlew --no-daemon :sponsors:testDebugUnitTest :sponsors:assembleDebugAndroidTest
adb devices -l
./gradlew --no-daemon :sponsors:connectedDebugAndroidTest
```

`CredentialVaultDeviceTest` uses unique test-only aliases and synthetic ciphertext in its isolated instrumentation context. It exercises the actual Android Keystore/AtomicFile path, reconstruction and deletion without accessing owner credentials or sending network requests. Test cleanup is restricted to its own generated namespaces.

## S24 live acceptance (not yet run)

These checks require the installed app, an attached authorized S24, owner-entered credentials, and the indicated approvals. Creating synthetic test content does not authorize its export. Use the production UI/coordinator and ports; do not add an authorization bypass or automatic startup probe.

1. Verify offline local recording, summary/history and resume while both sponsor toggles are off. Opening summaries and diagnostics must not make network requests.
2. Review a synthetic transcript-shaped query and city, approve the exact text, run Search, and record only sanitized request metadata. Verify the displayed dispatched query and returned descriptions/URLs. Change the transcript or revoke approval before another dispatch and verify it is blocked.
3. Commit Search, stop the application process, manually reopen and resume. Verify only unfinished Extract runs; committed Search and recording are not repeated. Confirm source hash/time/citations and unknown fields.
4. Review and explicitly approve one synthetic event export to the intended database. Record its stable export ID. After delivery acknowledgement, query that exact ID using `BoundedHistoryQuery(sessionId, exportId, limit = 1, field = EVENTS)`. A delivery acknowledgement alone does not confirm query visibility. If the provider has not exposed the row yet, retain pending confirmation and use an explicit bounded refresh.
5. Separately approve an exact synthetic snippet (at most 200 characters) and keyword (at most 40) with measurements. Verify ordinary measurement reads redact text and the approved text read returns those exact values. Verify revoked text approval prevents a pending export.
6. With individually approved synthetic summaries, verify known distinct sessions, replayed export IDs and a corrected summary revision. Counts must remain stable for corrections/retries. The cloud baseline uses up to eight latest eligible prior sessions in the preceding 56 days, with real session chronology, at least two eligible rows, and current session excluded. Show provider-wide counts separately from the bounded baseline sample, plus actual dates, latency and retrieval/cache state.
7. Clear or replace a key and disable/change the sponsor destination while a request is waiting for authorization; verify the pending dispatch fails. Interrupt an in-flight operation and verify cancellation, persisted local recovery and redacted errors. Do not claim a sent request was recalled.

No live sponsor write, phone Search/Extract, Android framework compilation or S24 execution has been verified by the host test suite. RawTree's desktop metadata-only API check is documented separately and does not satisfy these phone gates.
