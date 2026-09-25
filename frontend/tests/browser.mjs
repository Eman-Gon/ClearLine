// Run with a local preview server and Playwright available via NODE_PATH.
// All API responses below are intercepted test fixtures; no sponsor calls occur.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { readFile } from 'node:fs/promises';
const require = createRequire(import.meta.url);
const { chromium } = require('playwright');
const baseURL = process.env.PREVIEW_URL || 'http://localhost:3001';
const browser = await chromium.launch({
  headless: true,
  ...(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {}),
  args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'],
});
const browserErrors = [];
const makeContext = async () => {
  const context = await browser.newContext({ viewport: { width: 393, height: 852 }, deviceScaleFactor: 1 });
  context.on('page', page => { page.on('pageerror', error => browserErrors.push(error.message)); page.on('dialog', dialog => dialog.accept()); });
  return context;
};
async function noOverflow(page) { assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, 'Mobile page must not scroll sideways'); }
try {
  const fixture = await makeContext();
  const page = await fixture.newPage();
  const fixtureRequests = [];
  page.on('request', request => { if (new URL(request.url()).pathname.startsWith('/api/')) fixtureRequests.push(request.url()); });
  await page.goto(baseURL);
  await page.locator('.history-item').first().waitFor();
  await noOverflow(page);
  await page.screenshot({ path: '/tmp/clearline-home-mobile.png', fullPage: true });
  await page.locator('#start-checkin').click();
  await page.locator('#fixture-clip').click();
  assert.equal(await page.locator('#screen-checkin').isVisible(), true, 'Consent required before synthetic session');
  await page.locator('#recording-consent').check();
  await page.locator('#fixture-clip').click();
  await page.locator('#measurement-source').filter({ hasText: 'Synthetic measurements' }).waitFor();
  assert.match(await page.locator('#baseline-description').innerText(), /not your personal baseline/);
  assert.equal(await page.locator('.metric-value').nth(4).innerText(), '—', 'Null pause count must remain unavailable');
  await noOverflow(page);
  await page.screenshot({ path: '/tmp/clearline-summary-mobile.png', fullPage: true });
  await page.locator('[data-screen=followup]').click();
  await page.locator('#pause-button').click();
  await page.locator('#resume-button').waitFor({ state: 'visible' });
  assert.match(await page.locator('#task-title').innerText(), /pause/);
  await page.locator('#resume-button').click();
  await page.locator('#resource-city').fill('Oakland, CA');
  await page.locator('#search-consent').check();
  await page.locator('#search-button').click();
  await page.locator('.resource-card').waitFor();
  assert.match(await page.locator('.resource-card').innerText(), /Synthetic source · not retrieved/);
  assert.match(await page.locator('.resource-card').innerText(), /Unknown/);
  await page.locator('#resource-city').fill('Berkeley, CA');
  assert.equal(await page.locator('#search-consent').isChecked(), false, 'Changed city must revoke search confirmation');
  await page.locator('#search-consent').check();
  await page.locator('#search-button').click();
  await page.waitForFunction(() => document.querySelector('.resource-card')?.textContent.includes('Berkeley'));
  await noOverflow(page);
  await page.screenshot({ path: '/tmp/clearline-followup-mobile.png', fullPage: true });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.locator('[data-screen=home]').click();
  await page.screenshot({ path: '/tmp/clearline-home-desktop.png', fullPage: true });
  assert.equal(fixtureRequests.length, 0, 'Fixture mode cannot issue live API calls');
  await page.reload();
  await page.waitForFunction(() => document.querySelector('#notice').textContent.includes('resets on reload'));
  await fixture.close();

  const live = await makeContext();
  const livePage = await live.newPage();
  const config = await readFile(new URL('../config.js', import.meta.url), 'utf8');
  await livePage.route('**/static/config.js', route => route.fulfill({ contentType: 'text/javascript', body: config.replace('DEMO_FIXTURE_MODE = true', 'DEMO_FIXTURE_MODE = false') }));
  const requests = [], uploadIDs = [];
  let uploads = 0, finishes = 0;
  const id = '55fc3779-fd0c-49b1-a3eb-83b0f37f1173';
  const profile = 'bb18f4ae-f270-486b-bb77-0cc2d8f16a2a';
  let state = { session_id: id, profile_id: profile, state_version: 1, input_revision: 0, phase: 'recording', execution_mode: 'local_pipeline', consent: { recording: true, cloud_export: false }, metrics: null, comparison: null, resources: [], errors: [], clips: [], events: [], cloud_sync: { mode: 'restricted', pending: 0, delivered: 0, failed: 0 }, capture_finished: false };
  const respond = (route, data) => route.fulfill({ contentType: 'application/json', body: JSON.stringify(data) });
  await livePage.route('**/api/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname, method = request.method();
    requests.push({ path, method });
    assert.match(`${method} ${path}`, /^(GET \/api\/(health|profiles\/[^/]+\/history|sessions\/[^/]+)|POST \/api\/sessions(\/[^/]+\/(clips|finish|resources|input|pause|resume))?)$/, 'Only shared API routes');
    if (path === '/api/health') return respond(route, { components: { backend: { status: 'ready', label: 'Local server' }, liquid: { status: 'unavailable', label: 'Local model' } } });
    if (path.endsWith('/history')) return respond(route, { sessions: state.metrics ? [{ session_id: id, metrics: state.metrics, data_origin: 'consented_demo', created_at: '2026-09-25T20:00:00Z' }] : [] });
    if (path === '/api/sessions') {
      assert.equal(request.postDataJSON().pairing_code, 'test-pairing');
      return respond(route, { session_id: id, profile_id: profile, resume_token: 'test-resume-token' });
    }
    assert.equal(request.headers().authorization, 'Bearer test-resume-token');
    if (method === 'GET') return respond(route, state);
    if (path.endsWith('/clips')) {
      uploads++;
      const body = request.postDataBuffer().toString();
      const clipID = body.match(/name="clip_id"\r\n\r\n([^\r]+)/)?.[1];
      assert.ok(clipID, 'Multipart contains stable clip_id');
      assert.match(body, /name="audio"; filename="recording.webm"/);
      uploadIDs.push(clipID);
      if (uploads === 1) return route.abort('failed'); // Uncertain first upload: explicit retry must reuse UUID.
      state.clips = [{ clip_id: clipID, status: 'queued' }];
      state.phase = 'processing'; state.state_version++;
      return respond(route, { session_id: id, clip_id: clipID, status: 'queued', duplicate: false });
    }
    if (path.endsWith('/finish')) {
      finishes++;
      assert.equal(uploads, 2, 'Finish only after successful receipt');
      if (finishes === 1) return route.abort('failed');
      state = { ...state, state_version: state.state_version + 1, input_revision: 1, capture_finished: true, phase: 'awaiting_user_choice', metrics: { duration_s: 1, word_count: 3, recording_wpm: 180, energy_rms: 0.05, pause_count: null, pitch_mean_hz: null, quality: 'accepted', data_origin: 'consented_demo' }, comparison: { status: 'insufficient_history', session_count: 0, baseline_source: 'local_history', metrics: {} } };
      state.clips = [{ clip_id: uploadIDs[0], status: 'accepted', transcript: 'A simple day.' }];
      return respond(route, state);
    }
    if (path.endsWith('/input')) {
      const payload = request.postDataJSON();
      assert.equal(payload.input_revision, state.input_revision, 'Correction carries the viewed revision');
      assert.equal(payload.clip_id, uploadIDs[0]);
      assert.equal(payload.transcript, 'A pleasant day.');
      state = { ...state, state_version: state.state_version + 1, input_revision: state.input_revision + 1, clips: [{ ...state.clips[0], transcript: payload.transcript }] };
      return respond(route, state);
    }
    if (path.endsWith('/resume')) { state = { ...state, state_version: state.state_version + 1, phase: 'processing', pending_input: null }; return respond(route, state); }
    throw new Error(`Unexpected test action ${method} ${path}`);
  });
  await livePage.goto(baseURL);
  await livePage.locator('#start-checkin').click();
  await livePage.locator('#recording-consent').check();
  await livePage.locator('#pairing-code').fill('test-pairing');
  await livePage.locator('#record-button').click();
  await livePage.locator('#recorder-status').filter({ hasText: 'Recording · local microphone' }).waitFor();
  await livePage.waitForTimeout(1200);
  assert.equal(uploads, 0, 'No timeslice upload before final Stop');
  await livePage.locator('#record-button').click();
  await livePage.locator('[data-capture=retry]').waitFor();
  assert.equal(finishes, 0, 'Failed upload must not finish capture');
  await livePage.locator('[data-capture=retry]').click();
  await livePage.locator('[data-capture=retry]').filter({ hasText: 'Finish capture' }).waitFor();
  assert.equal(uploads, 2);
  assert.equal(uploadIDs[0], uploadIDs[1]);
  await livePage.locator('[data-capture=retry]').click();
  await livePage.locator('#measurement-source').filter({ hasText: 'Consented demo' }).waitFor();
  assert.equal(uploads, 2, 'Retrying finish cannot re-upload accepted clip');
  assert.equal(finishes, 2);
  await livePage.locator('#correction-panel summary').click();
  await livePage.locator('#transcript').fill('A pleasant day.');
  await livePage.locator('#transcript-form button').click();
  await livePage.waitForFunction(() => document.querySelector('#notice').textContent.includes('Correction saved'));
  const mutationCount = requests.filter(r => r.method === 'POST').length;
  await livePage.waitForTimeout(5400);
  assert.equal(requests.filter(r => r.method === 'POST').length, mutationCount, 'Polling cannot trigger mutations');
  assert.ok(requests.filter(r => r.method === 'GET' && r.path === `/api/sessions/${id}`).length >= 3);
  await livePage.locator('[data-screen=home]').click();
  await livePage.locator('.history-item').waitFor();
  await livePage.locator('.runtime-panel summary').click();
  assert.match(await livePage.locator('#runtime-details').innerText(), /unavailable.*Local model/);
  const stored = await livePage.evaluate(() => JSON.parse(localStorage.getItem('clearline.live.resume.v1')));
  assert.deepEqual(Object.keys(stored).sort(), ['clip_id', 'profile_id', 'resume_token', 'session_id']);
  state = { ...state, phase: 'awaiting_input', state_version: state.state_version + 1, pending_input: { reason_code: 'whisper_unavailable', question: 'Configure Whisper on the laptop, then resume.' } };
  await livePage.locator('[data-screen=followup]').click();
  await livePage.locator('#resume-button').waitFor({ state: 'visible' });
  assert.equal(await livePage.locator('#input-form').isVisible(), false, 'Dependency repair is not a generic user answer');
  await livePage.locator('#resume-button').click();
  await livePage.locator('#task-title').filter({ hasText: 'processing' }).waitFor();
  assert.equal(browserErrors.length, 0, browserErrors.join('\n'));
  console.log('PASS: 393px fixture screens, 1440px layout, consent, null metrics, pause/resume, approved search/city correction, reload, synthetic browser audio, same-ID upload retry, finish retry, read-only polling, readiness, storage minimization, dependency resume.');
  console.log('Screenshots: /tmp/clearline-{home-mobile,summary-mobile,followup-mobile,home-desktop}.png');
  await live.close();
} finally { await browser.close(); }
