# Call-specific search and visible RawTree history

This feature implements revision 3.1 of the build specification in the native Android path. It does not add a Python service or a phone-to-laptop dependency.

## Transcript-driven search

`CallInsights` reads accepted, non-superseded Whisper transcripts in stable clip order. Its versioned, deterministic English phrase rules recognize stated memory, word-finding, worry, loneliness, sleep, and hearing concerns. The generated public query contains a topic selected from that text, the chosen resource category, and the selected city. A local excerpt explains the selection. Names, arbitrary instructions, and literal transcript details are not copied into generated search text.

For the same city and category:

- “I keep forgetting where I put things” proposes “everyday forgetfulness memory support caregiver support groups near San Francisco CA”.
- “I can't sleep these days” proposes “sleep difficulties support caregiver support groups near San Francisco CA”.

Unsupported text uses an explicitly labeled category fallback. This is a bounded phrase recognizer, not general language understanding or a diagnostic model. An actual descriptive comparison may supply the largest available relative WPM/RMS change; a zero baseline does not yield a percentage. Pitch, pauses, emotion, vocabulary change, and clinical drift scores are not measured by this implementation.

The approved draft includes its exact query, city, category, builder version, basis, and transcript SHA-256. Only the approved query goes to Nimble's Search v2 endpoint. The local basis and transcript fingerprint do not become request context. Transcript corrections invalidate dependent research. The UI must distinguish a proposal from a committed successful search, and shows at most three real result candidates.

## RawTree memory

RawTree history is a separately sourced view of approved cloud exports. The phone remains authoritative for workflow recovery and its offline baseline. `RawTreeMemoryMath` selects latest summary revisions before filtering eligibility, excludes the current session from prior history, matches profile/provenance/task/measurement methods, and uses at most eight completed sessions in the preceding 56 days. Two eligible sessions are required for baseline means.

Provider counts are separate aggregates, never the number of rows in a bounded history page. Corrected summaries and replayed deliveries must not create new logical memory points. With one summary per session, data point and session counts may be equal. Counts cannot imply ten-second chunks that the recorder did not produce. The current recording path admits complete clips; it does not claim ten-second live cloud ticks.

Optional snippets are limited to 200 characters and keywords to 40. They need separate transcript-snippet export consent in addition to measurement consent. Full audio, full transcripts, credentials, and private agent checkpoints are excluded. Memory diagnostics contain a fixed template identifier, row count, measured latency, and typed error rather than raw provider responses or credentials.

## Verification

Core tests cover distinct queries from distinct transcripts, common negations, explicit fallback, bounded local excerpts, zero-baseline handling, correction fingerprints, history eligibility, deduplication, origin separation, missing counts, and serialization. Sponsor tests use intercepted requests and controlled responses; these are not live service acceptance.

On September 25, 2026, the consolidated JVM core suite passed 24 tests: 7 query-builder, 12 RawTree-memory, and 5 existing contract tests. The memory suite includes conflicting equal-version payloads and stable averaging of extreme finite inputs. Reproduce from `android/` with the JDK/Gradle toolchain documented in `TOOLCHAIN.md`:

```sh
gradle --offline --no-daemon --configure-on-demand \
  -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process :core:test
```

Configuration on demand permits this JVM-only verification without configuring the Android native modules. Initial sandboxed Gradle attempts could not open its daemon socket; the successful run used the approved host execution path. The Gradle wrapper download also needs network or its cached distribution.

Integration verification is recorded in [`sponsors/README.md`](../sponsors/README.md) and [`agent/README.md`](../agent/README.md). The sponsor harness passed 68 core/sponsor tests, including exact-query transmission, logical history counts, authentic session chronology, and revoked dispatch authorization. Native screen source compatibility was also checked with the Compose compiler and Android API dependencies on the host; this is not an APK installation or visual/device test.

The S24 capture, inference, sponsor write/readback, UI, and process-recovery gates still require a connected device, configured credentials, and explicitly approved test data. A successful JVM test or APK build does not satisfy those gates.
