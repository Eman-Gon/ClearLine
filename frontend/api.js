import { DEMO_FIXTURE_MODE } from './config.js';
import { createFixtureApi } from './fixtures.js';

export class ApiError extends Error {
  constructor(message, { code = 'request_failed', status = 0, retryable = false, details = null } = {}) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    this.status = status;
    this.retryable = retryable;
    this.details = details;
  }
}

function segment(value) {
  if (typeof value !== 'string' || !value.trim()) {
    throw new ApiError('A session or profile identifier is required.', { code: 'missing_identifier' });
  }
  return encodeURIComponent(value);
}

function serverError(payload, status) {
  const detail = payload?.error ?? payload?.detail ?? payload;
  let message = typeof detail === 'string' ? detail : detail?.message;
  if (!message && Array.isArray(detail)) {
    message = detail.map(item => `${(item.loc ?? []).join('.')}: ${item.msg ?? 'invalid value'}`).join('; ');
  }
  const defaults = {
    401: 'Pair this browser with the laptop again before continuing.',
    403: 'The laptop did not authorize this action. Check pairing and consent.',
    404: 'This session is not available on the paired laptop.',
    409: 'The session changed. Refresh its status before trying again.',
    413: 'This recording is too large. Try a shorter complete clip.',
    415: 'The laptop does not accept this recording format.',
    429: 'The service is busy. Your work can be retried shortly.',
  };
  return new ApiError(message || defaults[status] || `The laptop returned an error (${status}).`, {
    status,
    code: typeof detail?.code === 'string' ? detail.code : `http_${status}`,
    retryable: typeof detail?.retryable === 'boolean' ? detail.retryable : status === 408 || status === 429 || status >= 500,
    details: detail,
  });
}

/** Plain same-origin adapter. Fetch/timeout overrides exist for contract tests. */
export function createApi({
  fixtureMode = DEMO_FIXTURE_MODE,
  fetchImpl = globalThis.fetch?.bind(globalThis),
  timeoutMs = 20000,
  uploadTimeoutMs = 45000,
} = {}) {
  if (fixtureMode) return createFixtureApi();
  let resumeToken = null;

  async function request(path, { method = 'GET', body, multipart = false, timeout = timeoutMs } = {}) {
    if (typeof fetchImpl !== 'function') throw new ApiError('This browser cannot make API requests.', { code: 'fetch_unavailable' });
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeout);
    const headers = { Accept: 'application/json' };
    if (resumeToken) headers.Authorization = `Bearer ${resumeToken}`;
    if (body !== undefined && !multipart) headers['Content-Type'] = 'application/json';
    try {
      const response = await fetchImpl(path, {
        method,
        headers,
        credentials: 'same-origin',
        signal: controller.signal,
        cache: 'no-store',
        ...(body === undefined ? {} : { body: multipart ? body : JSON.stringify(body) }),
      });
      const raw = await response.text();
      let payload = {};
      if (raw) {
        try { payload = JSON.parse(raw); }
        catch {
          if (!response.ok) throw serverError(null, response.status);
          throw new ApiError('The laptop returned an unreadable response. Check that the API is running.', { code: 'invalid_response', status: response.status });
        }
      }
      if (!response.ok) throw serverError(payload, response.status);
      return payload;
    } catch (error) {
      if (error instanceof ApiError) throw error;
      if (controller.signal.aborted) {
        throw new ApiError('The request timed out. Check the laptop connection and retry. Recording retries keep the same clip ID.', { code: 'timeout', retryable: true });
      }
      throw new ApiError('The paired laptop could not be reached. Reconnect and retry your unfinished action.', { code: 'network_error', retryable: true });
    } finally {
      clearTimeout(timer);
    }
  }

  const sessionPath = id => `/api/sessions/${segment(id)}`;
  const post = (id, action, body = {}) => request(`${sessionPath(id)}/${action}`, { method: 'POST', body });
  return {
    setResumeToken(token) { resumeToken = typeof token === 'string' && token ? token : null; },
    health: () => request('/api/health'),
    history: profileId => request(`/api/profiles/${segment(profileId)}/history`),
    async createSession({ profile_id, consent, pairing_code, data_origin = 'consented_demo', recording_task = 'check_in' } = {}) {
      const result = await request('/api/sessions', {
        method: 'POST',
        body: { ...(profile_id ? { profile_id } : {}), consent, data_origin, recording_task, ...(pairing_code ? { pairing_code } : {}) },
      });
      if (result.resume_token) resumeToken = result.resume_token;
      return result;
    },
    getSession: id => request(sessionPath(id)),
    uploadClip(id, { clipId, blob, mimeType, supersedesClipId } = {}) {
      if (!clipId) throw new ApiError('This recording has no clip ID.', { code: 'missing_clip_id' });
      if (!blob || typeof blob.size !== 'number' || blob.size === 0) {
        throw new ApiError('The recording is empty. Record a new complete clip.', { code: 'empty_audio' });
      }
      const form = new FormData();
      form.append('clip_id', clipId);
      if (supersedesClipId) form.append('supersedes_clip_id', supersedesClipId);
      const mediaType = (mimeType || blob.type || 'application/octet-stream').toLowerCase();
      const extension = mediaType.includes('mp4') ? 'm4a' : mediaType.includes('ogg') ? 'ogg' : mediaType.includes('webm') ? 'webm' : 'bin';
      form.append('audio', blob, `recording.${extension}`);
      return request(`${sessionPath(id)}/clips`, { method: 'POST', body: form, multipart: true, timeout: uploadTimeoutMs });
    },
    finish: id => post(id, 'finish'),
    resources: (id, { category, city } = {}) => post(id, 'resources', { category, city, approved: true }),
    input: (id, { input_revision, answer, transcript, city, clip_id } = {}) => post(id, 'input', {
      input_revision,
      ...(answer !== undefined ? { answer } : {}),
      ...(transcript !== undefined ? { transcript } : {}),
      ...(city !== undefined ? { city } : {}),
      ...(clip_id !== undefined ? { clip_id } : {}),
    }),
    pause: id => post(id, 'pause'),
    resume: id => post(id, 'resume'),
  };
}
