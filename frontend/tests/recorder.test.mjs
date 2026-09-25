import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { webcrypto } from 'node:crypto';

// Load the browser ES module without adding a repository-wide package manifest.
const source = await readFile(new URL('../recorder.js', import.meta.url), 'utf8');
const { ClipRecorder } = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);

function environment(t, overrides = {}) {
  const tracks = [new class extends EventTarget {
    readyState = 'live';
    stops = 0;
    stop() { this.stops += 1; this.readyState = 'ended'; }
  }()];
  const stream = { getTracks: () => tracks, getAudioTracks: () => tracks };
  let requested = 0;
  const recorders = [];
  class FakeMediaRecorder extends EventTarget {
    static isTypeSupported(type) { return type === 'audio/webm;codecs=opus'; }
    state = 'inactive';
    constructor(input, { mimeType }) {
      super();
      this.mimeType = mimeType;
      this.stream = input;
      recorders.push(this);
      if (overrides.constructorError) throw overrides.constructorError;
    }
    start() { this.state = 'recording'; }
    stop() { this.state = 'inactive'; }
    data(text) {
      const event = new Event('dataavailable');
      event.data = new Blob([text], { type: this.mimeType });
      this.dispatchEvent(event);
    }
    finish() { this.state = 'inactive'; this.dispatchEvent(new Event('stop')); }
  }
  const doc = new EventTarget();
  doc.visibilityState = 'visible';
  const timers = new Map();
  let timerID = 0;
  const globals = {
    isSecureContext: true,
    crypto: webcrypto,
    navigator: { mediaDevices: { getUserMedia: options => {
      requested += 1;
      assert.deepEqual(options, { audio: true, video: false });
      return overrides.getUserMedia?.() ?? Promise.resolve(stream);
    } } },
    MediaRecorder: FakeMediaRecorder,
    document: doc,
    window: new EventTarget(),
    AudioContext: undefined,
    webkitAudioContext: undefined,
    requestAnimationFrame: undefined,
    setTimeout: (callback, delay) => { timers.set(++timerID, { callback, delay }); return timerID; },
    clearTimeout: id => timers.delete(id),
    setInterval: (callback, delay) => { timers.set(++timerID, { callback, delay }); return timerID; },
    clearInterval: id => timers.delete(id),
  };
  const previous = Object.fromEntries(Object.keys(globals).map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]));
  for (const [key, value] of Object.entries(globals)) Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });
  const states = [];
  const clips = [];
  const errors = [];
  const recorder = new ClipRecorder({ onState: state => states.push(state), onComplete: clip => clips.push(clip), onError: error => errors.push(error) });
  t.after(() => {
    recorder.dispose();
    for (const [key, descriptor] of Object.entries(previous)) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor);
      else delete globalThis[key];
    }
  });
  return { recorder, recorders, stream, tracks, states, clips, errors, doc, timers, requested: () => requested,
    runTimer(delay) {
      const entry = [...timers.entries()].find(([, timer]) => timer.delay === delay);
      assert.ok(entry, `Expected a timer for ${delay}ms`);
      timers.delete(entry[0]);
      entry[1].callback();
    },
  };
}

test('feature detection does not request permission and rejects insecure origins', t => {
  const e = environment(t);
  assert.equal(ClipRecorder.support().supported, true);
  assert.equal(e.requested(), 0);
  globalThis.isSecureContext = false;
  assert.match(ClipRecorder.support().reason, /HTTPS|localhost/);
  assert.equal(e.requested(), 0);
});

test('unsupported MIME type fails before requesting the microphone', async t => {
  const e = environment(t);
  globalThis.MediaRecorder.isTypeSupported = () => false;
  assert.equal(await e.recorder.start(), false);
  assert.equal(e.requested(), 0);
  assert.match(e.errors[0].message, /format/);
});

test('waits for final stop and combines every nonempty chunk into one clip', async t => {
  const e = environment(t);
  assert.equal(await e.recorder.start(), true);
  const media = e.recorders[0];
  media.data('header:');
  media.data('middle:');
  media.data('');
  e.recorder.stop();
  assert.equal(e.recorder.state, 'stopping');
  assert.equal(e.clips.length, 0);
  assert.equal(e.tracks[0].readyState, 'ended');
  media.data('footer');
  media.finish();
  assert.equal(e.clips.length, 1);
  assert.equal(await e.clips[0].blob.text(), 'header:middle:footer');
  assert.match(e.clips[0].clipId, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  assert.equal(e.clips[0].interrupted, false);
  assert.equal(e.clips[0].mimeType, 'audio/webm;codecs=opus');
  assert.equal(e.timers.size, 0);
  media.finish();
  assert.equal(e.clips.length, 1);
});

test('empty final recording produces an actionable error, never a clip', async t => {
  const e = environment(t);
  await e.recorder.start();
  e.recorder.stop();
  e.recorders[0].finish();
  assert.equal(e.clips.length, 0);
  assert.match(e.errors[0].message, /empty audio file/);
  assert.equal(e.recorder.state, 'idle');
  assert.equal(e.tracks[0].readyState, 'ended');
});

test('permission rejection is reported and the recorder is reusable', async t => {
  let denied = true;
  const e = environment(t, { getUserMedia: () => denied ? Promise.reject(new DOMException('Denied', 'NotAllowedError')) : Promise.resolve(e.stream) });
  assert.equal(await e.recorder.start(), false);
  assert.match(e.errors[0].message, /permission was denied/);
  assert.equal(e.recorder.state, 'idle');
  denied = false;
  assert.equal(await e.recorder.start(), true);
});

test('cancelling slow permission releases any subsequently granted stream', async t => {
  let grant;
  const e = environment(t, { getUserMedia: () => new Promise(resolve => { grant = resolve; }) });
  const started = e.recorder.start();
  e.recorder.stop({ interrupted: true, reason: 'Page closed.' });
  assert.equal(await started, false);
  grant(e.stream);
  await Promise.resolve();
  assert.equal(e.recorders.length, 0);
  assert.equal(e.tracks[0].readyState, 'ended');
  assert.equal(e.states.at(-1).interrupted, true);
});

test('permission timeout releases a late stream without recording', async t => {
  let grant;
  const e = environment(t, { getUserMedia: () => new Promise(resolve => { grant = resolve; }) });
  const started = e.recorder.start();
  e.runTimer(30_000);
  assert.equal(await started, false);
  assert.match(e.errors[0].message, /timed out/);
  grant(e.stream);
  await Promise.resolve();
  assert.equal(e.tracks[0].readyState, 'ended');
  assert.equal(e.recorders.length, 0);
});

test('MediaRecorder construction failure releases acquired tracks', async t => {
  const e = environment(t, { constructorError: new DOMException('Format not supported', 'NotSupportedError') });
  assert.equal(await e.recorder.start(), false);
  assert.equal(e.tracks[0].readyState, 'ended');
  assert.match(e.errors[0].message, /format/);
  assert.equal(e.timers.size, 0);
});

test('backgrounding stops capture and marks the completed clip as interrupted', async t => {
  const e = environment(t);
  await e.recorder.start();
  e.recorders[0].data('audio');
  e.doc.visibilityState = 'hidden';
  e.doc.dispatchEvent(new Event('visibilitychange'));
  assert.equal(e.recorder.state, 'stopping');
  assert.equal(e.tracks[0].readyState, 'ended');
  e.recorders[0].finish();
  assert.equal(e.clips[0].interrupted, true);
  assert.match(e.clips[0].reason, /backgrounded|locked/);
  e.doc.visibilityState = 'visible';
  e.doc.dispatchEvent(new Event('visibilitychange'));
  assert.equal(e.requested(), 1, 'returning to the page must never restart capture');
});

test('microphone disconnect preserves interruption provenance', async t => {
  const e = environment(t);
  await e.recorder.start();
  e.recorders[0].data('audio');
  e.tracks[0].dispatchEvent(new Event('ended'));
  e.recorders[0].finish();
  assert.equal(e.clips[0].interrupted, true);
  assert.match(e.clips[0].reason, /disconnected/);
});

test('30 second deadline stops capture but still waits for the complete container', async t => {
  const e = environment(t);
  await e.recorder.start();
  e.recorders[0].data('audio');
  e.runTimer(30_000);
  assert.equal(e.recorder.state, 'stopping');
  assert.equal(e.clips.length, 0);
  e.recorders[0].finish();
  assert.equal(e.clips[0].interrupted, false);
});

test('missing final stop event times out without exposing partial audio', async t => {
  const e = environment(t);
  await e.recorder.start();
  e.recorders[0].data('partial');
  e.recorder.stop();
  e.runTimer(5_000);
  assert.equal(e.clips.length, 0);
  assert.equal(e.recorder.state, 'idle');
  assert.match(e.errors[0].message, /did not finish/);
  e.recorders[0].finish();
  assert.equal(e.clips.length, 0);
});

test('disposal releases capture and suppresses final audio callbacks', async t => {
  const e = environment(t);
  await e.recorder.start();
  e.recorders[0].data('audio');
  e.recorder.dispose();
  e.recorders[0].finish();
  assert.equal(e.tracks[0].readyState, 'ended');
  assert.equal(e.clips.length, 0);
  assert.equal(e.timers.size, 0);
  assert.equal(await e.recorder.start(), false);
});
