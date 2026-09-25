import { PROFILE_ID } from './config.js';

// Frontend presentation fixtures only. Nothing here processes audio, executes a
// sponsor tool, persists durable state, or calls a remote service.
const FIXTURE_DATE = '2026-09-21T17:00:00.000Z';
const METRICS = Object.freeze({
  duration_s: 24.1,
  word_count: 45,
  recording_wpm: 112.03,
  pause_count: null,
  energy_rms: 0.08,
  pitch_mean_hz: null,
  quality: 'accepted',
  quality_reasons: [],
  data_origin: 'synthetic',
});
const copy = value => JSON.parse(JSON.stringify(value));
const uuid = () => globalThis.crypto?.randomUUID?.() ?? 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, character => {
  const random = Math.floor(Math.random() * 16);
  return (character === 'x' ? random : (random & 3) | 8).toString(16);
});

function fixtureError(message, code = 'fixture_error', status = 400) {
  const error = new Error(message);
  Object.assign(error, { name: 'FixtureError', code, status, retryable: false });
  return error;
}

function fixtureComparison(metrics) {
  return {
    status: 'illustrative_only',
    baseline_source: 'synthetic_fixture',
    baseline_label: 'Synthetic demo reference — not your personal baseline',
    baseline_session_count: 3,
    session_count: 3,
    source: 'synthetic_demo_reference',
    data_origin: 'synthetic',
    baseline_ref: 'synthetic-demo-reference-v1',
    metrics: {
      recording_wpm: { current: metrics.recording_wpm, mean: 110.5, delta: 1.53 },
      energy_rms: { current: metrics.energy_rms, mean: 0.075, delta: 0.005 },
    },
    unavailable_metrics: ['pause_count', 'pitch_mean_hz'],
    missing_metrics: ['pause_count', 'pitch_mean_hz'],
    note: 'Every value is synthetic. No health interpretation is provided.',
  };
}

function fixtureResource(category, city) {
  return {
    id: 'synthetic-source-1',
    title: 'Example caregiver support listing',
    description: `Synthetic preview for ${category} in ${city}. This is a layout example, not a real service or a search result.`,
    category,
    city,
    source_url: 'https://example.org/clearline-synthetic-resource',
    retrieved_at: FIXTURE_DATE,
    retrieval_label: 'Synthetic retrieval date — no page was retrieved',
    verification_status: 'synthetic_fixture',
    data_origin: 'synthetic',
    fields: { phone: null, address: null, hours: null, availability: null, eligibility: null },
    unknown_fields: ['phone', 'address', 'hours', 'availability', 'eligibility'],
    supporting_passage: null,
    content_version: 'synthetic-layout-v1',
  };
}

const HISTORY = [
  { session_id: '52c2d8df-6c52-43c4-9edf-07d113cf1b11', completed_at: '2026-09-21T17:00:00.000Z', metrics: { ...METRICS, recording_wpm: 110.5 } },
  { session_id: '52c2d8df-6c52-43c4-9edf-07d113cf1b12', completed_at: '2026-09-18T17:00:00.000Z', metrics: { ...METRICS, recording_wpm: 109.8 } },
  { session_id: '52c2d8df-6c52-43c4-9edf-07d113cf1b13', completed_at: '2026-09-15T17:00:00.000Z', metrics: { ...METRICS, recording_wpm: 111.2 } },
].map(item => ({
  ...item,
  created_at: item.completed_at,
  profile_id: PROFILE_ID,
  phase: 'ready',
  summary_version: 1,
  data_origin: 'synthetic',
  label: 'Synthetic check-in',
  date_label: 'Illustrative date',
  recording_task: 'check_in',
  measurement_method_version: 'synthetic-fixture-v1',
}));

export function createFixtureApi() {
  const sessions = new Map();
  const pauseStates = new Map();
  const resourceRequests = new Map();
  const get = id => {
    const session = sessions.get(id);
    if (!session) throw fixtureError('This synthetic session is no longer in memory. Start a new fixture check-in. Real recovery requires the backend.', 'fixture_session_missing', 404);
    return session;
  };
  const event = (session, name, label) => {
    session.state_version += 1;
    const eventId = uuid();
    const timestamp = new Date().toISOString();
    session.events.push({ id: eventId, event_id: eventId, name, label, status: 'simulated', timestamp, created_at: timestamp, data_origin: 'synthetic' });
  };
  const requireActive = session => {
    if (session.phase === 'paused') throw fixtureError('Resume the synthetic session before continuing.', 'session_paused', 409);
  };
  return {
    setResumeToken() {},
    async health() {
      return {
        execution_mode: 'fixture',
        components: {
          backend: { status: 'fixture', label: 'Frontend fixture — backend not checked' },
          liquid: { status: 'not_connected', label: 'Liquid — not connected in fixture mode' },
          rawtree: { status: 'not_connected', label: 'RawTree — no export in fixture mode' },
          nimble: { status: 'not_connected', label: 'Nimble — no search in fixture mode' },
        },
      };
    },
    async history(profileId) {
      const created = [...sessions.values()].filter(session => session.profile_id === profileId && session.completed_at).map(session => ({
        session_id: session.session_id, profile_id: session.profile_id, completed_at: session.completed_at, created_at: session.completed_at,
        phase: session.phase, summary_version: 1, metrics: session.metrics, data_origin: 'synthetic',
        label: 'Synthetic preview check-in', date_label: 'Preview interaction time',
      }));
      return copy({ sessions: [...created, ...(profileId === PROFILE_ID ? HISTORY : [])], data_origin: 'synthetic' });
    },
    async createSession({ profile_id = PROFILE_ID, consent } = {}) {
      if (!consent?.recording) throw fixtureError('Recording consent is required before starting.', 'recording_consent_required', 403);
      const session = {
        session_id: uuid(),
        profile_id,
        state_version: 0,
        input_revision: 0,
        phase: 'recording',
        capture_finished: false,
        execution_mode: 'fixture',
        consent: { recording: true, cloud_export: Boolean(consent.cloud_export) },
        metrics: null,
        comparison: null,
        resources: [],
        resource_request: null,
        clips: [],
        events: [],
        pending_action: { name: 'record_clip', label: 'Record a complete clip; the preview will show synthetic measurements.' },
        pending_input: null,
        errors: [],
        provenance: {
          data_origin: 'synthetic',
          label: 'Synthetic fixture — not a real observation',
          baseline_source: 'synthetic_fixture',
          note: 'The microphone can record a real local preview, but fixture measurements never describe it. Audio is not analyzed or sent anywhere in this mode.',
        },
        cloud_sync: { status: 'not_sent', label: 'Fixture mode — nothing sent to RawTree', export_approved: Boolean(consent.cloud_export), mode: consent.cloud_export ? 'approved' : 'restricted', pending: 0, delivered: 0, failed: 0 },
      };
      event(session, 'fixture.session_created', 'Synthetic session created in this browser tab');
      sessions.set(session.session_id, session);
      return copy({ ...session, resume_token: 'synthetic-fixture-token-not-an-auth-credential' });
    },
    async getSession(id) { return copy(get(id)); },
    async uploadClip(id, { clipId, blob, supersedesClipId } = {}) {
      const session = get(id);
      requireActive(session);
      if (!clipId) throw fixtureError('The recording has no clip ID.', 'missing_clip_id');
      // Retrying an accepted identifier is an unchanged read of the receipt.
      if (session.clips.some(clip => clip.clip_id === clipId)) return copy(session);
      if (!blob || !blob.size) throw fixtureError('The recording is empty. Record a new complete clip.', 'empty_audio');
      if (supersedesClipId) {
        const previous = session.clips.find(clip => clip.clip_id === supersedesClipId);
        if (!previous) throw fixtureError('The clip to replace is unavailable.', 'unknown_clip', 404);
        previous.status = 'superseded';
      }
      // Do not retain, inspect or transcribe the supplied audio blob.
      session.clips.push({ clip_id: clipId, status: 'accepted', data_origin: 'synthetic', label: 'Simulated receipt; audio not analyzed' });
      session.input_revision += 1;
      session.phase = 'processing';
      session.metrics = null;
      session.comparison = null;
      session.resources = [];
      delete session.completed_at;
      session.pending_action = { name: 'finish_capture', label: 'Finish capture to display synthetic summary values' };
      event(session, 'fixture.clip_accepted', 'Simulated complete clip receipt — no audio processing');
      return copy(session);
    },
    async finish(id) {
      const session = get(id);
      requireActive(session);
      if (!session.clips.some(clip => clip.status === 'accepted')) throw fixtureError('Accept a complete clip before finishing capture.', 'no_accepted_clips', 409);
      if (session.completed_at && session.phase === 'awaiting_user_choice') return copy(session);
      session.metrics = copy(METRICS);
      session.comparison = fixtureComparison(session.metrics);
      session.capture_finished = true;
      session.phase = 'awaiting_user_choice';
      session.completed_at = new Date().toISOString();
      session.pending_action = { name: 'await_user_choice', label: 'Optional: choose a public resource category and city' };
      event(session, 'fixture.summary_displayed', 'Synthetic descriptive summary displayed — no model or comparison tool ran');
      return copy(session);
    },
    async resources(id, { category, city } = {}) {
      const session = get(id);
      requireActive(session);
      if (!category?.trim() || !city?.trim()) throw fixtureError('Choose a resource category and city.', 'missing_resource_input');
      if (!session.metrics) throw fixtureError('Finish the check-in before requesting follow-up resources.', 'check_in_unfinished', 409);
      resourceRequests.set(id, { category: category.trim(), city: city.trim() });
      session.resource_request = { category: category.trim(), city: city.trim(), approved: true };
      session.input_revision += 1;
      session.resources = [fixtureResource(category.trim(), city.trim())];
      session.phase = 'ready';
      session.pending_action = null;
      event(session, 'fixture.resources_displayed', 'Synthetic resource card displayed — no Liquid or Nimble call');
      return copy(session);
    },
    async input(id, { input_revision, answer, transcript, city, clip_id } = {}) {
      const session = get(id);
      requireActive(session);
      if (input_revision !== session.input_revision) throw fixtureError('This input is based on an older revision. Refresh and try again.', 'revision_conflict', 409);
      if (transcript !== undefined && !session.clips.some(clip => clip.clip_id === clip_id)) throw fixtureError('A transcript correction needs its accepted clip ID.', 'unknown_clip', 404);
      if ([answer, transcript, city].filter(value => value !== undefined).length !== 1) throw fixtureError('Provide exactly one answer or correction.', 'invalid_input');
      if (city !== undefined && !city.trim()) throw fixtureError('Enter a city to continue.', 'missing_city');
      session.input_revision += 1;
      session.pending_input = null;
      if (city !== undefined) {
        const previous = resourceRequests.get(id);
        session.resources = [];
        if (previous) {
          resourceRequests.set(id, { ...previous, city: city.trim() });
          session.resource_request = { ...previous, city: city.trim(), approved: true };
          session.resources = [fixtureResource(previous.category, city.trim())];
          session.phase = 'ready';
          session.pending_action = null;
        }
      }
      // Corrections never turn fixed fixture numbers into measurements of audio.
      event(session, 'fixture.input_updated', 'Synthetic input revision updated; fixture values remain illustrative');
      return copy(session);
    },
    async pause(id) {
      const session = get(id);
      if (session.phase !== 'paused') {
        pauseStates.set(id, session.phase);
        session.phase = 'paused';
        event(session, 'fixture.paused', 'Synthetic workflow paused in browser memory');
      }
      return copy(session);
    },
    async resume(id) {
      const session = get(id);
      if (session.phase === 'paused') {
        session.phase = pauseStates.get(id) ?? 'awaiting_user_choice';
        pauseStates.delete(id);
        event(session, 'fixture.resumed', 'Synthetic workflow resumed — no backend recovery was tested');
      }
      return copy(session);
    },
  };
}
