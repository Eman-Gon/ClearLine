# Live sponsor verification and APK 0.1.1 — September 25, 2026

The actual portable Android sponsor adapters and production HTTPS transport now
pass live Nimble Search/Extract, RawTree event write/readback, and RawTree memory
checks from this Mac. This is host-to-provider verification. The physical phone's
networking, Keystore, consent UI and full workflow remain unverified.

## Updated phone build

- APK: `/Volumes/T7/ClearLine-build/share/ClearLine-0.1.1-native-debug.apk`.
- Size: 25,347,856 bytes.
- SHA-256: `fb53f42ff1613aff1416e92dd7b7596d7c106ee831da9d48b9a0936f3324dc01`.
- Package `com.clearline.app`, version `0.1.1-native`, version code `2`.
- Same debug signing certificate as 0.1, so install as an update to retain downloaded
  models and app data. Device upgrade behavior has not been exercised here.
- Minimum Android 8.0 / API 26, target API 35, ARM64 only.
- Full Android command from [the initial build record](APK_BUILD.md) passed in 42s:
  193 actionable tasks, 13 executed and 180 up-to-date.
- Current reports contain **174 unit tests**, zero failures/errors/skips: core 24,
  agent 25, storage 16, audio 9, inference 44, sponsors 48, app 8. Four new regression
  cases cover the live compatibility fixes below; unchanged tasks reused their results.
- The host probe's 10 offline tests also passed separately. These are harness tests,
  not additional Android/device tests.
- Signature verification and 16 KiB ZIP alignment passed. All three packaged native
  libraries are byte-identical to the ELF-verified initial APK. Certificate SHA-256:
  `e0decf68c8bb491ec456a3d5cd57fe70a70e2006bb7f059e30a83e6574ed718b`.

Use [the phone guide](PHONE_TESTING.md) or the T7 share folder's `START-HERE.txt`.
The original 0.1 APK remains available under its original name as historical evidence.

## Nimble

The production query builder was given an invented sentence, “I keep forgetting
where I put things.” Its exact outgoing topic query was:

> everyday forgetfulness memory support caregiver support groups near San Francisco CA

Live Search returned three usable candidates. Extraction of the first returned
candidate completed and produced one bounded supporting passage. Source bodies
were not logged. The fixed [Search](https://docs.nimbleway.com/api-reference/search/search)
and [Extract](https://docs.nimbleway.com/api-reference/extract/extract) endpoints,
credential transport, public URL/DNS checks and response parsers are the same ones
the phone uses. The full synthetic sentence was not sent as the query.

The first live search exposed descriptions of 10,184, 12,853 and 8,328 characters
even with `full_content=false`. The previous 4,000-character strict parser rejected
the entire search. Search descriptions now become bounded 4,000-character display
previews without splitting a surrogate pair. Non-string descriptions still fail;
URL/identity limits, the 2 MiB response limit and extracted-evidence limits remain.
Regression tests cover those observed lengths, Unicode boundaries and invalid types.

## RawTree

A constant query and table metadata returned HTTP 200 using the existing configured
database. Initially it contained none of the four ClearLine tables. Per the
[official API contract](https://rawtree.com/docs/reference/api), inserts can create
their target tables; no manual schema or database replacement was performed.

- One clearly labeled synthetic event was accepted and read back by exact session
  and export ID on the second bounded query attempt. The complete typed projection
  matched. An acknowledgement alone was not treated as successful readback.
- A separate synthetic profile held two prior summaries and one current summary.
  Replaying the same current export yielded **three logical sessions**, not four.
- The actual memory adapter returned exactly the two prior calls and the matching
  current call. Baseline means were **130 recording WPM** and **0.5 RMS**, calculated
  from the two synthetic prior values (140/120 WPM and 0.6/0.4 RMS).
- The corrected memory probe passed on its first query attempt. No transcript,
  snippet, keyword, personal profile, audio or real call history was used.

The first memory attempt exposed a second adapter mismatch: RawTree's `__raw_data`
readback omitted fields inserted as JSON null. The app required explicit null for
unmeasured pitch/pauses and rejected otherwise valid summaries. It now accepts
missing or null as unknown, while rejecting numeric values for these unsupported
measurements. Regression tests cover both cases. Exact scoped SQL independently
returned counts 3, previous rows 2 and current row 1 before the decoder fix.

Test data remains in the configured database: one event and two isolated synthetic
memory fixtures (the diagnostic run and corrected run), each involving three
summaries and a deliberate replay. They are not a family's history or eight weeks
of actual calls. Fresh random profile IDs and `data_origin=synthetic` separate them
from phone profiles and live provenance. No test data was deleted or substituted
into the app's local baseline.

## Reproduce and inspect

The [opt-in host probe](../sponsors/tools/live-smoke/README.md) compiles actual source
and uses exact synthetic fixture authorizations. Default mode is local configuration
inspection only. Sponsor keys are supplied through the process environment, removed
from Gradle's environment, and never written into APKs, reports or source files.

```sh
android/sponsors/tools/live-smoke.sh self-test
android/sponsors/tools/live-smoke.sh nimble-roundtrip --allow-live-search --allow-live-extract
android/sponsors/tools/live-smoke.sh rawtree-roundtrip --allow-synthetic-write
android/sponsors/tools/live-smoke.sh rawtree-memory --allow-synthetic-write
```

Live commands may use provider credits and persist their explicitly described
synthetic fixtures. Re-running creates new random fixture identities. They do not
authorize the phone app or upload anyone's real recording/transcript.

Sanitized evidence is on the T7 under `/Volumes/ClearLineBuild/logs/`:
`sponsor-nimble-roundtrip.log`, `sponsor-rawtree-roundtrip.log`,
`sponsor-rawtree-memory.log`, `sponsor-self-test.log`, and `android-build-0.1.1.log`.
Actual phone acceptance still follows [the handoff gates](SESSION_1_HANDOFF.md).
