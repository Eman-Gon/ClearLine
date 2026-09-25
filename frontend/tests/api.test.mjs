import test from 'node:test';
import assert from 'node:assert/strict';
import { createApi, ApiError } from '../api.js';
import { PROFILE_ID } from '../config.js';

test('live adapter uses only shared routes and carries pairing, consent, and resume authorization', async () => {
  const calls = [];
  const api = createApi({ fixtureMode: false, fetchImpl: async (path, init) => {
    calls.push({ path, ...init });
    return new Response(JSON.stringify(path === '/api/sessions' ? { session_id: 'session-1', profile_id: 'profile-1', resume_token: 'test-token' } : {}), { status: 200 });
  } });
  await api.health();
  await api.createSession({ consent: { recording: true, cloud_export: false }, pairing_code: 'local-pair' });
  await api.getSession('session-1');
  await api.history('profile-1');
  const blob = new Blob(['complete media container'], { type: 'audio/webm' });
  await api.uploadClip('session-1', { clipId: 'stable-clip', blob, mimeType: 'audio/webm' });
  await api.uploadClip('session-1', { clipId: 'stable-clip', blob, mimeType: 'audio/webm' });
  await api.finish('session-1');
  await api.resources('session-1', { category: 'respite care', city: 'Oakland' });
  await api.input('session-1', { input_revision: 2, transcript: 'Corrected words', clip_id: 'stable-clip' });
  await api.pause('session-1');
  await api.resume('session-1');
  assert.deepEqual(calls.map(call => `${call.method} ${call.path}`), [
    'GET /api/health', 'POST /api/sessions', 'GET /api/sessions/session-1', 'GET /api/profiles/profile-1/history',
    'POST /api/sessions/session-1/clips', 'POST /api/sessions/session-1/clips', 'POST /api/sessions/session-1/finish',
    'POST /api/sessions/session-1/resources', 'POST /api/sessions/session-1/input', 'POST /api/sessions/session-1/pause', 'POST /api/sessions/session-1/resume',
  ]);
  assert.deepEqual(JSON.parse(calls[1].body), { consent: { recording: true, cloud_export: false }, data_origin: 'consented_demo', recording_task: 'check_in', pairing_code: 'local-pair' });
  assert(calls.every(call => call.credentials === 'same-origin' && call.cache === 'no-store'));
  assert(calls.slice(2).every(call => call.headers.Authorization === 'Bearer test-token'));
  assert.equal(calls[4].headers['Content-Type'], undefined);
  assert.equal(calls[4].body.get('clip_id'), 'stable-clip');
  assert.equal(calls[5].body.get('clip_id'), 'stable-clip');
  assert.equal(await calls[4].body.get('audio').text(), await blob.text());
  assert.deepEqual(JSON.parse(calls[7].body), { category: 'respite care', city: 'Oakland', approved: true });
  assert.deepEqual(JSON.parse(calls[8].body), { input_revision: 2, transcript: 'Corrected words', clip_id: 'stable-clip' });
  api.setResumeToken(null);
  await api.health();
  assert.equal(calls.at(-1).headers.Authorization, undefined);
});

test('live failures stay failures and do not enable synthetic responses', async () => {
  const statuses = [401, 409, 429, 503];
  for (const status of statuses) {
    const api = createApi({ fixtureMode: false, fetchImpl: async () => new Response(JSON.stringify({ detail: { message: 'Expected server failure', code: 'expected_failure' } }), { status }) });
    await assert.rejects(api.getSession('session'), error => error instanceof ApiError && error.status === status && error.code === 'expected_failure' && error.retryable === [429, 503].includes(status));
  }
  const malformed = createApi({ fixtureMode: false, fetchImpl: async () => new Response('<html>no API</html>') });
  await assert.rejects(malformed.health(), error => error.code === 'invalid_response');
  const offline = createApi({ fixtureMode: false, fetchImpl: async () => { throw new TypeError('offline'); } });
  await assert.rejects(offline.health(), error => error.code === 'network_error' && error.retryable);
});

test('timeout aborts request and zero-byte audio is rejected before upload', async () => {
  let aborted = false;
  const api = createApi({ fixtureMode: false, timeoutMs: 5, fetchImpl: (_path, { signal }) => new Promise((_resolve, reject) => {
    signal.addEventListener('abort', () => { aborted = true; reject(new DOMException('Aborted', 'AbortError')); });
  }) });
  await assert.rejects(api.health(), error => error.code === 'timeout' && error.retryable);
  assert.equal(aborted, true);
  assert.throws(() => api.uploadClip('session', { clipId: 'clip', blob: new Blob([]) }), error => error.code === 'empty_audio');
});

test('fixture reads are immutable, duplicate uploads and pause/resume are idempotent', async () => {
  const api = createApi({ fixtureMode: true });
  const initial = await api.createSession({ consent: { recording: true, cloud_export: true } });
  const id = initial.session_id;
  const before = await api.getSession(id);
  for (let index = 0; index < 10; index++) {
    const status = await api.getSession(id);
    assert.deepEqual(status, before);
    status.events.push({ name: 'external-mutation' });
    await api.history(PROFILE_ID);
  }
  assert.deepEqual(await api.getSession(id), before);
  const accepted = await api.uploadClip(id, { clipId: 'stable-clip', blob: new Blob(['audio']) });
  assert.deepEqual(await api.uploadClip(id, { clipId: 'stable-clip', blob: new Blob(['same retry']) }), accepted);
  const paused = await api.pause(id);
  assert.deepEqual(await api.pause(id), paused);
  const resumed = await api.resume(id);
  assert.equal(resumed.phase, accepted.phase);
  assert.deepEqual(await api.resume(id), resumed);
  const finished = await api.finish(id);
  assert.equal(finished.capture_finished, true);
  assert.equal(finished.metrics.data_origin, 'synthetic');
  assert.equal(finished.cloud_sync.status, 'not_sent');
  const resources = await api.resources(id, { category: 'caregiver support groups', city: 'Oakland' });
  assert.deepEqual(resources.resource_request, { category: 'caregiver support groups', city: 'Oakland', approved: true });
  assert.equal(resources.resources[0].verification_status, 'synthetic_fixture');
  assert.equal(new URL(resources.resources[0].source_url).hostname, 'example.org');
  assert(resources.resources[0].unknown_fields.every(field => resources.resources[0].fields[field] === null));
  await assert.rejects(api.input(id, { input_revision: 0, city: 'Berkeley' }), error => error.code === 'revision_conflict');
  const corrected = await api.input(id, { input_revision: resources.input_revision, city: 'Berkeley' });
  assert.equal(corrected.resources[0].city, 'Berkeley');
  assert.equal(corrected.resource_request.city, 'Berkeley');
  assert.deepEqual(corrected.clips, resources.clips);
  assert.deepEqual(corrected.metrics, resources.metrics);
  const freshAdapter = createApi({ fixtureMode: true });
  await assert.rejects(freshAdapter.getSession(id), error => error.code === 'fixture_session_missing');
});
