# Host sponsor probes

These commands compile the real Android `core` and portable `sponsors` Kotlin
sources, then run the production HTTPS transport against its fixed provider
hosts. They do not configure the phone, exercise Android Keystore, replace app
consent, or establish device readiness. No `.env` file is loaded.

The default is a **local configuration check**, with no network traffic:

```sh
android/sponsors/tools/live-smoke.sh
android/sponsors/tools/live-smoke.sh self-test
```

Use the installed toolchain from `android/docs/TOOLCHAIN.md`. Compilation is
offline, requires its previously downloaded Gradle dependencies, and uses a
temporary directory under the toolchain on the T7. The directory is removed on
exit. `CLEARLINE_TOOLCHAIN_ROOT` and `CLEARLINE_SPONSOR_SMOKE_BUILD_ROOT` can
select another prepared toolchain or build destination.

Supply keys through your process environment: `NIMBLE_API_KEY`,
`RAWTREE_API_KEY`, and `RAWTREE_DATABASE` (RawTree only). Do not put keys in
command-line arguments or commit them. The launcher removes these variables
from the Gradle process environment. Only the short-lived probe JVM sees them;
its mutable credential copies are wiped after use. Environment strings and
temporary HTTP authorization strings cannot be reliably wiped by the JVM.
Errors are bounded and never include keys or provider response bodies.
If Nimble rejects a response shape, the harness prints only known field names,
types and string lengths to help locate an adapter mismatch; values and unknown
provider fields are omitted.

Explicit live commands:

```sh
android/sponsors/tools/live-smoke.sh nimble-search --allow-live-search
android/sponsors/tools/live-smoke.sh nimble-roundtrip --allow-live-search --allow-live-extract
android/sponsors/tools/live-smoke.sh rawtree-roundtrip --allow-synthetic-write
android/sponsors/tools/live-smoke.sh rawtree-memory --allow-synthetic-write
```

Nimble may consume credits. The synthetic sentence is “I keep forgetting where
I put things.” The actual app query builder produces exactly:

> everyday forgetfulness memory support caregiver support groups near San Francisco CA

The search sends that bounded topic, not the sentence. The roundtrip command
additionally selects the first search result and makes one extraction request;
the production source identity, public DNS, redirect and content checks apply.
No source body is logged. A search with zero usable results is not reported as
a pass. The one-source extraction can fail even when search passes.

RawTree writes one event to `clearline_events` in the explicitly configured
database, using fresh random profile/session/export/event UUIDs and
`data_origin=synthetic`. Use a designated test database. The adapter may create
the table if it does not exist. The probe issues at most three queries scoped
to that exact session and export ID, with `LIMIT 1`, and verifies the complete
event projection. It does not inspect pre-existing phone sessions. An accepted
insert is not a pass until the readback matches. No insert is retried, and no
cleanup is automatic: the synthetic row remains, with its session/export IDs
printed so it can be identified. Re-running creates a new fixture.

The separate `rawtree-memory` command writes three synthetic measurement
summaries to `clearline_session_summaries` under a fresh isolated profile. It
replays the current summary once with the same export ID, so there are four
physical writes but exactly three logical sessions. No snippet, keyword or
transcript is included. Two prior calls have 140/120 recording wpm and 0.6/0.4
energy RMS; the current call has 100 recording wpm and 0.2 energy RMS. The probe
uses the real `.memory()` adapter and requires the exact two prior calls,
current call, counts of three, and baseline means 130/0.5. It allows at most
three memory attempts (each uses three fixed queries), all scoped to the fresh
profile and synthetic provenance. There are no automatic write retries or
deletions. Fixture IDs are printed before dispatch; the four writes remain.

`PASS` describes a real completed provider response. Local configuration only
reports `PRESENT`, `MISSING` or `INVALID`, which does not authenticate a key.
Host authorization grants are limited to these fixture values and live only
under `tools/`; production app authorization is unchanged.
