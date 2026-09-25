# Native RawTree adapter

`RawTreeHistoryClient` implements optional exported history and analytics. Room remains the authority for phone history, workflow claims, checkpoints, and recovery. The adapter never mutates Room and never grants consent.

## Contract checked September 25, 2026

The [API reference](https://rawtree.com/docs/reference/api), [authentication documentation](https://rawtree.com/docs/reference/authentication), and [query guide](https://rawtree.com/docs/guides/query-data) establish:

- Fixed origin `https://api.rawtree.com`, bearer credentials, and `x-rawtree-database` selection.
- Inserts at `POST /v1/tables/<allowlisted table>` and read-only SQL at `POST /v1/query` with a `sql` field.
- The default JSON query envelope contains `data` rows.
- Credentials apply across their organization/cluster's databases. A database selector is not a per-person security boundary.

A desktop metadata-only check using existing owner configuration returned HTTP 200 for database listing and a constant `SELECT 1 AS clearline_contract_probe`. The configured database was present; the query returned `data`, `meta`, `rows`, and `statistics` with the expected constant. No event was written and no stored voice/history data was read. Credentials were not displayed or copied into Android. These checks do **not** satisfy the phone write/readback gate.

## Approval and projection

Both synthetic and consented demonstration exports require current authorization. The adapter copies caller-owned collections, builds a fresh selected projection, then supplies that same immutable approval value to `SponsorAuthorization.requireExport`. The shared transport runs the callback after credential lookup and immediately before sending. The destination/enabled setting is also rechecked. Revocation cannot recall a request already in flight.

The wire contains schema version 3, a stable export ID, random pseudonymous session UUID, hashed profile reference, input revision, provenance, export timestamp, and only the selected projection. It excludes local profile IDs, approval IDs, full checkpoints, prompts, audio, and unrestricted transcripts.

The separately authorized call-insights extension permits a transcript snippet of at most 200 characters and a keyword of at most 40 characters in the measurement projection. The concrete authorization guard must require **both** `MEASUREMENTS` and `TRANSCRIPT_SNIPPET` selections whenever either field is present. Ordinary measurement-history reads redact these two fields; the separate snippet-history request requires its own current authorization. No full transcript is supported.

`MemoryQuery` has no text-selection field. The app/coordinator must preserve measurement-only memory access and apply its current text-read policy after the request returns: when the current text grant is absent, null snippet/keyword fields in prior/current rows before persisting or exposing the snapshot. Keep counts, dates, metrics and diagnostics. This uses existing snapshot copies; hiding text only in a screen is insufficient for other consumers. It does not retroactively delete previously approved cloud exports or grant permission to reuse their text in a new outgoing query/export.

Public-resource exports retain public titles/descriptions, source/version identity, timestamps, supported facts, and exact supporting public passages. A missing fact stays null. Public source text is evidence, never a new instruction. Measurement metadata and quality reasons are validated against the implemented method/engineering codes.

## Bounded history and memory

Callers choose an enum projection type, canonical identifiers, and a bounded limit. They cannot submit SQL. Queries use fixed templates, deduplicate retries, and select latest versions before eligibility filters. Exact-ID reads support an explicitly approved synthetic write/readback probe without creating a new write operation.

Memory presents **up to eight latest eligible prior sessions in the preceding 56 days**, not every session in that period. It matches profile, task, provenance, measurement method, and lexical method, and excludes the current session from the prior comparison. Session creation/completion timestamps are required for memory eligibility; export timestamps never substitute for missing chronology. Current exported measurements are queried separately.

Provider-wide counts are a separate exact aggregate over latest logical summaries for the requested profile/provenance. One current session summary is one logical data point, so data-point and session counts currently coincide. Correction versions and replayed delivery IDs do not inflate either count. Counts are not derived from the eight-row page and are not a statement that all stored records are eligible for the current baseline. The adapter does not claim known truncation or complete eight-week coverage.

Counts, previous rows, and the current row are separate reads, not a server transaction. Inconsistent counts/rows, malformed responses, missing configuration, denied consent, and provider failures produce unavailable results with null counts and typed diagnostics. Diagnostics contain template ID, bounded returned-row count, elapsed milliseconds, and an error enum; they contain no tokens, source text, snippets, or request bodies.

## Verification

Subsequent [credentialed host verification](../docs/SPONSOR_LIVE_CHECKS.md) passed
one synthetic event's exact readback, three logical summaries after one replay,
two prior calls and the correct current call, and the expected cloud baseline.
Live JSON storage omitted explicitly null pitch/pause fields; the decoder now
treats missing and null as unmeasured while still rejecting numeric values.
All data used fresh isolated synthetic identities. These tests used the real
portable Android adapter and transport, not a physical phone.

`RawTreeHistoryClientTest` exercises actual Kotlin adapter code with a mocked HTTP port: selected export fields, both provenance consent gates, dispatch-time revocation/destination changes, immutable snapshots, supported source facts, retry identity, bounded history, logical count templates, provider counts versus the small page, and missing chronology. The host JVM test harness does not test Android Keystore, APK installation, or S24 networking.

The S24 still needs an explicitly approved synthetic event write followed by exact-ID readback from the intended database. No automatic smoke write runs at startup or in these tests.
