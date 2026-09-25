# Frontend API handoff

The route contract is Section 8 of `ClearLine_Fixed_Build_Spec.md` (revision 2.1). Request bodies and browser pairing were coordinated with Session 2 during implementation. `api.js` uses only these same-origin routes, with `credentials: 'same-origin'`, no automatic retries, and no automatic fallback to fixtures.

## Mode and pairing

`config.js` exports `DEMO_FIXTURE_MODE = true`. Change it to `false` to use the FastAPI application. `PROFILE_ID` is a synthetic fixture profile, never a default real identity. `POLL_INTERVAL_MS` is 2500.

`createApi()` returns the methods below. `createSession()` automatically keeps its returned `resume_token` in memory. `setResumeToken(token)` restores a previously saved token; subsequent requests send `Authorization: Bearer <token>`. The application owns minimal browser storage (session/profile/clip identifiers and resume token); the adapter stores no audio, transcript, pairing code, or session payload on disk. Pairing codes come from the user and must match the laptop configuration. There are no sponsor credentials in the frontend.

`GET` requests use `cache: 'no-store'`. Timeout is 20 seconds for regular requests and 45 seconds for uploads. A timeout has an unknown server outcome: retry an upload with its original `clip_id`. The adapter never invents success or switches mode after an error.

## Requests

| Adapter method | Route | Payload |
| --- | --- | --- |
| `health()` | `GET /api/health` | None |
| `history(profileId)` | `GET /api/profiles/{id}/history` | None; authorized profile only |
| `createSession(options)` | `POST /api/sessions` | JSON shown below |
| `getSession(id)` | `GET /api/sessions/{id}` | None; strictly read-only |
| `uploadClip(id, options)` | `POST /api/sessions/{id}/clips` | Multipart `clip_id`, `audio`, optional `supersedes_clip_id` |
| `finish(id)` | `POST /api/sessions/{id}/finish` | `{}` |
| `resources(id, {category, city})` | `POST /api/sessions/{id}/resources` | `{category, city, approved: true}` |
| `input(id, options)` | `POST /api/sessions/{id}/input` | `{input_revision, ...oneCorrection}` |
| `pause(id)` | `POST /api/sessions/{id}/pause` | `{}` |
| `resume(id)` | `POST /api/sessions/{id}/resume` | `{}` |

Create body:

```json
{
  "consent": {"recording": true, "cloud_export": false},
  "data_origin": "consented_demo",
  "recording_task": "check_in",
  "pairing_code": "<entered by the user>"
}
```

An authorized `profile_id` UUID may be supplied to reuse a real profile. Omit it for a new real profile. The backend returns `session_id`, `profile_id`, and `resume_token`; it may also return a complete status. The UI reads status after creating a session.

Upload adapter options are `{clipId, blob, mimeType, supersedesClipId?}`. The caller supplies the complete combined recording only after the recorder's final `stop` event. The browser sets the multipart boundary; the adapter does not set multipart `Content-Type`. `mimeType` determines the filename extension; the Blob carries its content type.

Resource categories supported by Session 2: `caregiver support groups`, `respite care`, `caregiver education` (and the matching `caregiver_support`, `respite_care`, `caregiver_education` aliases). A user-approved category/city request is necessary; a measurement difference must never create one automatically.

Exactly one correction is allowed per input request: `{answer}`, `{city}`, or `{transcript, clip_id}`. Include the latest `input_revision` and refresh after a 409 conflict. The UI never calculates a corrected measurement itself.

## Responses the UI renders

Required stored status fields from the specification: `session_id`, `state_version`, `phase`, `execution_mode`, `metrics`, `comparison`, `resources`, `pending_action`, `errors`, `provenance`, `cloud_sync`. Missing metric values stay `null`. Polling must not change any version, event count, job, or external-call count.

Optional presentation fields used by fixtures and requested for real status: `profile_id`, `input_revision`, `consent`, `clips`, `events`, `pending_input`, `completed_at`, `capture_finished`, `resource_request`. Their nested shapes are presentation conveniences, not extra routes. The UI must tolerate absent fields while Session 2/3 fill them in:

- `clips`: `[{clip_id, status, data_origin, label?}]`.
- `events`: `[{id, name, label, status, timestamp, data_origin?}]`; real backend may supply equivalent event text/times.
- `pending_input`: `null` or `{question, reason_code}`.
- `pending_action`: `null` or `{name, label?, ...boundedArguments}`.
- `capture_finished`: whether capture was explicitly finished after acknowledged uploads; `resource_request`: `null` or the approved `{category, city, approved: true}` request, used to distinguish city corrections from a new category request.
- `comparison`: status plus baseline source, label, count, per-metric descriptive values, and unavailable metric names. Fixture form uses `baseline_source`, `baseline_label`, `baseline_session_count`, `metrics: {metricName: {current, mean, delta}}`, `unavailable_metrics`.
- `resources`: cards with `title`, `description`, `source_url`, `retrieved_at`, `verification_status`, `fields`, and `unknown_fields`. Fields with no evidence remain null. Fixture URLs use `example.org` and `verification_status: 'synthetic_fixture'`.
- `provenance`: data origin and display explanation. Fixture form is `{data_origin: 'synthetic', label, baseline_source, note}`.
- `cloud_sync`: state and explanation. Fixture form is `{status: 'not_sent', label, export_approved}`; approving export in preview does not send it.

Health returns `{components: {backend, liquid, rawtree, nimble}}`, each component containing `status` and `label`; extra components such as `audio` are allowed. A loaded/configured adapter is not proof that a real sponsor smoke test passed.

Session 2's implemented nested names are also represented in fixtures: events use `event_id` and `created_at`; comparison uses `session_count`, `source`, `data_origin`, and `missing_metrics`; cloud sync uses `mode` (`approved` or `restricted`) and `pending`, `delivered`, `failed` counts; history summaries use `created_at`. Fixture-only labels/aliases make synthetic provenance explicit. The UI must handle the real field names without requiring those optional fixture labels.

History returns `{sessions: [...]}`; each item identifies one completed session's latest eligible summary and its provenance. Fixture items include `session_id`, `completed_at`, `phase`, `summary_version`, `metrics`, `data_origin`, `label`, and `date_label`. Fixtures are never a real person's baseline.

Mutation responses may be complete status or acknowledgments. The UI should read `getSession()` afterward to render server-owned state. Errors expose `message`, `code`, `status`, and `retryable`; the live adapter accepts either an `error` or `detail` envelope, including FastAPI validation arrays. HTTP 408/429/5xx and network/timeouts default to retryable; credential, authorization, missing-session, conflict, and validation errors require the appropriate user action.

## What fixture mode actually does

Fixtures are explicit, in-memory frontend presentations. GET status and history return detached snapshots and never advance state. Mutations display synthetic state changes. Duplicate clip identifiers reuse the receipt; pause/resume are idempotent. The fixed summary values are never calculated from microphone audio; the supplied Blob is not retained or uploaded. Example resources are not retrieved, verified, or real recommendations. No sponsor API is contacted, including when cloud consent is selected.

Synthetic history dates and the synthetic resource retrieval date are illustrative. Event timestamps identify browser interactions, not real tool execution. Reloading the page discards synthetic session state; a saved fixture ID then returns an explicit missing-fixture error. This preview does **not** demonstrate durable backend recovery.

Run the adapter contract checks with:

```sh
node --experimental-default-type=module --test frontend/tests/api.test.mjs
```

These checks use an injected Fetch implementation and synthetic in-memory data. They do not test a live backend, sponsor services, audio decoding, or an S24 microphone.
