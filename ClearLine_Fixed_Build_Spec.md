# ClearLine — Autonomous Calling Build Specification

Revision 4.0 · September 25, 2026. Supersedes the phone-only execution restrictions in [revision 3.1](docs/ARCHIVED_ON_DEVICE_SPEC_3_1.md). The native recorder remains an optional separate mode.

## Intended experience

A family schedules a check-in. A server triggers Vapi to call the parent's ordinary phone. After explicit consent to recording and cloud processing, the assistant conducts a short conversation. A server processes the completed participant transcript, reads that parent's real RawTree history, runs Liquid, optionally searches Nimble, stores the session in RawTree, and notifies the family app. The parent needs no app. The family app may be closed or on the same phone that receives the test call.

## Responsibilities and deployment

- Vapi: telephone connection, consent conversation, recording/transcription after consent, authenticated completed-call webhook. Its summaries are not ClearLine analysis.
- Server scheduler: timezone-aware once/daily schedules; explicit recipient/time/permissions; pause controls; stable occurrence IDs; no catch-up calls more than 15 minutes late; no automatic redial after uncertain dispatch.
- RawTree: consented, provenance-separated participant transcripts and Liquid reports. Query actual distinct prior session count and latest ten sessions, excluding current session and duplicate deliveries. A missing service/table is an error, never an invented empty history.
- Liquid: runs as a pinned LFM model in llama.cpp on the backend host, on loopback only. It summarizes participant statements, compares cited prior/current quotes, states history/context limits, and decides whether resources help. Invalid or unsupported output fails explicitly; no Vapi-summary fallback.
- Nimble: searches a non-identifying concern-specific public query derived from Liquid's exact current-transcript concern; report exposes query, reason, historical evidence and up to three candidate results. Search authorization is part of enrollment. Search results are unverified public candidates, not medical recommendations.
- Server persistence: SQLite schedule/occurrence/action/report/notification state and bounded retries. RawTree writes use stable event IDs and readback; queries deduplicate. Replaying a webhook cannot redial or create another report.
- Android family app: scheduling controls, consent settings, report history and FCM notifications. It fetches reports after resume/notification. It does not need to run Liquid or a background scheduler. Push payload contains no private report text.

The demo backend can run on the awake Mac with model/build files on the T7. Autonomous operation while the family is away requires an always-on host with persistent disk, process supervision, HTTPS, and network access. A sleeping laptop or disconnected temporary tunnel is not an always-on deployment. FCM provider acceptance is not proof of handset delivery. Android force-stop and disabled notifications must be reported as delivery limits.

## Consent and provenance

Default real-parent mode requires consent before artifacts are retained. Vapi's built-in recording consent plan currently requires Enterprise. Investigate a supported two-assistant flow with recording/logging/transcript storage disabled during consent and a server-gated handoff into the recording assistant; do not claim it verified until live acceptance tests pass. Until a verified consent route exists, block real parent calls.

A separately labeled, pre-consented scripted demo may record from the beginning only after the participant agrees beforehand; it cannot authorize real-parent enrollment. Declined, absent or unverifiable consent produces a visible failure and no report-content export. Enrollment must approve Vapi cloud processing, RawTree transcript/report storage, family sharing, and optional de-identified Nimble searches. No actual test call without the user's chosen recipient and time.

Partition history by profile and data origin (`synthetic`, `consented_demo`, `real`). Label demo sessions visibly. Do not invent emotion, pitch, pause counts, speaking rate, drift scores, diagnoses, or eight weeks of history. Transcript word count is descriptive only. No clinical risk scoring or emergency-monitoring promise.

## Family report

Show call/pipeline status and errors, consent source, data origin, actual RawTree prior-session count, returned-history window and previous calls with dates and excerpts. Show Liquid summary, supported changes with current/prior quotes and session references, model/source identity and context limits. Show Nimble exact query, concern quote, rationale, results and search status. Show RawTree storage/readback status and push-delivery status separately.

## Acceptance gates

1. Synthetic end-to-end scheduler → one Vapi request → authenticated consent-confirmed report → RawTree read → Liquid → optional Nimble → RawTree write/readback → notification, with injected service doubles clearly identified.
2. Real sponsor test using only labeled synthetic transcript fixtures: verify RawTree counts, Liquid model execution and evidence validation, actual Nimble request/results and RawTree persistence.
3. Crash/replay/duplicate tests, declined consent, missing history service, invalid model output, unavailable search, failed writes, expired schedule, DST, pause and notification failures.
4. Build Android APK; same-device test answers the telephone call, then opens report/receives push while app was closed. This requires Firebase setup, device notification permission and a real scheduled call explicitly approved by recipient/time.
5. Record each gate as verified, failed or blocked; never equate mocks, API configuration, or push acceptance with a real handset test.

## Session implementation prompts

Session 1: implement the Android family UI and server APIs for profiles, schedules, consent, report history and FCM registration. Retain the optional native recorder. Display true tool provenance and explicit incomplete states.

Session 2: implement durable server scheduling, authenticated Vapi ingress, consent gates, occurrence/webhook idempotency, timeout/failure reporting, stage checkpoints and notification outbox. Keep the scheduler and Liquid independent of Android lifetime.

Session 3: connect phone transcripts to actual RawTree history, server-side Liquid analysis with quote validation, optional transcript-specific Nimble research and stable RawTree writes. Run synthetic integration and live sponsor checks before recipient-approved phone acceptance. Update verified/setup evidence, commit and push.
