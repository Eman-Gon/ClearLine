# ClearLine — Session 1 handoff

Phone-first HTML/CSS/JavaScript interface for the corrected build specification, revision 2.1. All Session 1 changes are contained in `frontend/`. Backend and sponsor implementations belong to Sessions 2 and 3.

## Open the synthetic preview

From the repository root:

```sh
python3 frontend/preview.py --port 3001
```

Open **http://localhost:3001**. This is a loopback-only static server, with no API implementation. The default `DEMO_FIXTURE_MODE = true` in `frontend/config.js` renders labeled synthetic data. Start a check-in, choose recording consent, then choose **Use a synthetic example** to explore the entire flow without a microphone. You can also exercise browser recording; its audio is not analyzed or sent anywhere in fixture mode.

## Connect to the backend

1. Set `DEMO_FIXTURE_MODE = false` in `frontend/config.js` and reload the page. API errors never trigger a fixture fallback.
2. Follow the backend's dependency/model setup. Configure `CLEARLINE_PAIRING_CODE` on the laptop and enter that code in the frontend before a new session. The page does not save the pairing code.
3. Start the existing same-origin application from the repository root:

   ```sh
   python -m uvicorn backend.main:app --host 127.0.0.1 --port 3000 --workers 1
   ```

4. Open **http://localhost:3000**. Session 2 serves `frontend/index.html` at `/` and this directory at `/static`; `/api` routes remain on the same origin. The static preview server cannot stand in for FastAPI in live mode.

For the S24, configure Chrome USB port forwarding from the phone's localhost port 3000 to the laptop's localhost port 3000, then open **http://localhost:3000** on the phone. Trusted HTTPS is an alternative. Plain LAN HTTP and USB tethering alone are not the secure microphone setup. The actual phone, permission prompt, screen lock, complete upload, and laptop decoding still need a device test.

See `API_CONTRACT.md` for exact requests, pairing, response fields, and the coordinated Session 2 interface. Public-resource categories used by the UI are `caregiver_support`, `respite_care`, and `caregiver_education`.

## What is implemented

- Home, Check-in, Summary, and Follow-up, responsive mobile navigation, labeled history, runtime readiness, unfinished task, cloud-export status, source cards and event timeline.
- Separate recording and optional cloud-export consent. Public search requires category/city approval again after either changes.
- Complete `MediaRecorder` containers, MIME detection, real live level meter where Web Audio is available, 30-second auto-stop, stable clip IDs, timeout/permission/device/background handling, and microphone release.
- Upload retry using the original clip ID. `/finish` follows an acknowledged upload; a failed finish retries only `/finish`. Accepted audio is released from page memory. Unacknowledged audio stays only in memory, so reload loses it.
- Read-only status polling, pause/resume, dependency-repair resume, replacement input, revision-checked city/answer/transcript correction, and saved-session reconnection without an automatic mutation.
- No browser sponsor calls, keys, scoring engine, clinical conclusions, or persistence of audio/transcripts. Browser storage contains only session/profile/clip identifiers and a resume token.
- Escaped server text and HTTP(S)-only source links. Source facts display unknowns, retrieval status, and supporting passages when supplied.

## What is synthetic or unverified

Fixture measurements, history, timestamps marked illustrative, comparisons, resources, and task events are synthetic. Fixture mutations do not demonstrate a durable worker, local inference, RawTree ingestion, or Nimble retrieval. Fixtures reset on reload. The example.org resource is explicitly a layout example.

Real browser capture and live API adapter code are implemented. Browser regression testing uses Chrome-generated synthetic microphone audio and intercepted API responses; it does not validate a person's recording, backend audio measurements, real sponsor services, or the S24. The backend must supply accepted-clip transcripts for the optional correction editor; unavailable fields stay unavailable.

## Verification

Unit and adapter contracts (Node 22):

```sh
node --experimental-default-type=module --test frontend/tests/*.test.mjs
```

17 tests passed: recording capability/permission/cancellation/timeouts, complete-container assembly, empty audio, background/device interruption, 30-second stop, disposal, shared API routes/auth, duplicate clip retries, read-only fixtures, pause/resume, and revision conflicts.

Browser checks (requires Playwright and a Chrome/Chromium executable, plus the running preview):

```sh
CHROME_PATH='/path/to/chrome' node frontend/tests/browser.mjs
```

If Playwright is provided by a bundled runtime, set `NODE_PATH` to that runtime's `node_modules` directory. `PREVIEW_URL` optionally overrides `http://localhost:3001`.

Passed at **393 × 852** and **1440 × 1000**: navigation and overflow, consent, unavailable metrics, synthetic source labels, pause/resume, category/city confirmation, city correction, fixture reset, actual browser `MediaRecorder` with synthetic input, no upload before Stop, stable-ID retry after network failure, finish-only retry, no mutations during polling, component readiness, storage minimization, and missing-audio-dependency resume. Screenshots were rendered and inspected locally; tests save them under `/tmp/clearline-*.png`.

The pending acceptance gate is an actual S24 recording through the chosen origin, processed by the real backend with the configured local models. Sponsor smoke tests and backend recovery remain owned by Sessions 2 and 3.

## Files

| File | Purpose |
| --- | --- |
| `index.html`, `styles.css` | Four screens and responsive presentation |
| `app.js` | View state, consent/actions, polling, retry and resume UI |
| `recorder.js` | Complete browser recording and cleanup |
| `api.js` | Same-origin shared API adapter |
| `config.js`, `fixtures.js` | Explicit fixture switch and labeled presentation data |
| `preview.py` | Static-only local preview |
| `API_CONTRACT.md` | Coordinated integration handoff |
| `tests/` | Recorder, adapter, and browser verification |

No frontend build step, npm install, external fonts, or third-party asset requests are needed to run the interface.
