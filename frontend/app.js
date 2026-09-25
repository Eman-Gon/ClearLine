import { DEMO_FIXTURE_MODE, PROFILE_ID, POLL_INTERVAL_MS } from './config.js';
import { createApi } from './api.js';
import { ClipRecorder } from './recorder.js';

const $ = id => document.getElementById(id);
const escapeHTML = value => String(value ?? '').replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]));
const human = value => typeof value === 'string' ? value.replace(/[_.]/g, ' ') : '';
const number = (value, digits = 1) => typeof value === 'number' && Number.isFinite(value) ? value.toLocaleString(undefined, { maximumFractionDigits: digits }) : '—';
const date = value => value && !Number.isNaN(Date.parse(value)) ? new Date(value).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' }) : 'Time unavailable';
const safeURL = value => { try { const url = new URL(value); return ['https:', 'http:'].includes(url.protocol) && !url.username && !url.password ? url.href : null; } catch { return null; } };
const api = createApi();
const storageKey = `clearline.${DEMO_FIXTURE_MODE ? 'fixture' : 'live'}.resume.v1`;
let saved = {};
try { saved = JSON.parse(localStorage.getItem(storageKey) || '{}') || {}; } catch { /* Storage can be disabled; the tab still works. */ }
let session = null, health = null, history = [], pending = null, busy = false, uploading = false, polling = false, disconnected = false, currentScreen = 'home';
let recordState = 'idle', elapsed = 0, lastTranscriptRevision = null, resumeRequestedVersion = null;
let historyRevision = null;
const dependencyReasons = new Set(['whisper_unavailable', 'ffmpeg_unavailable', 'audio_dependency_unavailable', 'transcription_failed']);
const needsDependency = () => dependencyReasons.has(session?.pending_input?.reason_code);
const needsReplacement = () => session?.pending_input?.reason_code === 'replacement_recording';
api.setResumeToken(saved.resume_token);

const phases = {
  recording: ['Ready for your voice.', 'Record a complete clip when you’re ready.'],
  processing: ['Your recording is processing.', 'The paired laptop is checking the recording and preparing descriptive measurements.'],
  awaiting_input: ['A little more input is needed.', 'Review the requested detail or record a replacement clip.'],
  comparing: ['Putting the recording in context.', 'The task is comparing eligible session summaries.'],
  awaiting_user_choice: ['Your next step is up to you.', 'Review the summary, or choose a category and city for a public-resource search.'],
  researching: ['Following the sources.', 'The requested public resources are being researched.'],
  waiting_retry: ['A step is still unfinished.', 'A request could not finish. Its pending step is saved on the paired laptop.'],
  paused: ['A pause, with room to return.', 'The task is paused. Resume when you’re ready to continue.'],
  agent_unavailable: ['The local agent is unavailable.', 'The model or its tools are not ready. Your accepted input remains on the paired laptop.'],
  ready: ['This step is complete.', 'Your check-in and available source information are here to return to.'],
};
const metricInfo = [
  ['duration_s', 'Recording length', 'sec', 'Full clip duration', 1],
  ['word_count', 'Words recorded', 'words', 'From local transcription', 0],
  ['recording_wpm', 'Recording pace', 'wpm', 'Words ÷ full clip duration', 1],
  ['energy_rms', 'Recording amplitude', 'RMS', 'Depends on device and setup', 3],
  ['pause_count', 'Pauses', '', 'Requires usable timestamps', 0],
  ['pitch_mean_hz', 'Average voiced pitch', 'Hz', 'Only when voiced frames support it', 1],
];

function persist() {
  const minimal = { session_id: saved.session_id, profile_id: saved.profile_id, resume_token: saved.resume_token, clip_id: saved.clip_id };
  try { localStorage.setItem(storageKey, JSON.stringify(minimal)); } catch { notify('Browser storage is unavailable. Keep this tab open to retain the session connection.'); }
}
function notify(message) { $('notice').textContent = message; $('notice').hidden = !message; }
function showError(error) { $('error-notice').textContent = error?.message || String(error); $('error-notice').hidden = false; }
function clearError() { $('error-notice').hidden = true; }
function navigate(screen, focus = true) {
  if (!['home', 'checkin', 'summary', 'followup'].includes(screen)) screen = 'home';
  currentScreen = screen;
  for (const element of document.querySelectorAll('.screen')) element.hidden = element.id !== `screen-${screen}`;
  for (const link of document.querySelectorAll('[data-screen]')) {
    if (link.dataset.screen === screen) link.setAttribute('aria-current', 'page'); else link.removeAttribute('aria-current');
  }
  if (location.hash !== `#${screen}`) window.history.replaceState(null, '', `#${screen}`);
  if (focus) { $('main').focus({ preventScroll: true }); window.scrollTo({ top: 0, behavior: 'instant' }); }
}
function setSession(value) {
  if (!value?.session_id || !value.phase) return;
  if (session?.session_id === value.session_id && value.state_version < session.state_version) return;
  session = value;
  saved.session_id = value.session_id;
  saved.profile_id = value.profile_id || saved.profile_id;
  if (value.resume_token) saved.resume_token = value.resume_token;
  persist();
  render();
  const revision = value.metrics ? `${value.session_id}:${value.input_revision}:${JSON.stringify(value.metrics)}` : null;
  if (revision && revision !== historyRevision) { historyRevision = revision; void refreshHistory(); }
}
async function refreshStatus() {
  const id = saved.session_id;
  if (!id) return;
  const value = await api.getSession(id); // Read-only. Never executes a mutation from polling.
  if (id === saved.session_id) { const reconnected = disconnected; disconnected = false; setSession(value); if (reconnected) notify('Laptop reconnected. Stored status restored; use Resume for an eligible unfinished task.'); }
}
async function refreshHistory() {
  const profileId = DEMO_FIXTURE_MODE ? PROFILE_ID : saved.profile_id;
  if (!profileId) { history = []; renderHistory(); return; }
  try { const result = await api.history(profileId); history = Array.isArray(result.sessions) ? result.sessions : []; renderHistory(); }
  catch (error) { $('history-list').innerHTML = `<div class="empty-state">History unavailable. ${escapeHTML(error.message)}</div>`; }
}
async function refreshHealth() {
  try { health = await api.health(); disconnected = false; }
  catch { health = null; disconnected = true; }
  renderConnection();
}
async function action(task) {
  if (busy || uploading) return;
  busy = true; clearError(); renderControls();
  try { await task(); }
  catch (error) { showError(error); if (error.status === 409) { try { await refreshStatus(); } catch { /* Original error remains visible. */ } } }
  finally { busy = false; render(); }
}
async function ensureSession() {
  if (session) return session;
  if (saved.session_id) { await refreshStatus(); return session; }
  if (!$('consent-form').reportValidity()) throw new Error('Choose recording consent before starting a demonstration session.');
  const pairing = $('pairing-code').value.trim();
  if (!DEMO_FIXTURE_MODE && !pairing) throw new Error('Enter the pairing code configured on your laptop.');
  const value = await api.createSession({
    ...(DEMO_FIXTURE_MODE ? { profile_id: PROFILE_ID } : saved.profile_id ? { profile_id: saved.profile_id } : {}),
    consent: { recording: $('recording-consent').checked, cloud_export: $('export-consent').checked },
    pairing_code: pairing,
  });
  api.setResumeToken(value.resume_token);
  $('pairing-code').value = '';
  // The shared create route may return identifiers/token before a full status.
  saved.session_id = value.session_id;
  saved.profile_id = value.profile_id || saved.profile_id;
  saved.resume_token = value.resume_token;
  persist();
  setSession(value);
  if (!session) await refreshStatus();
  return session;
}

function renderConnection() {
  const text = DEMO_FIXTURE_MODE ? 'Synthetic preview' : disconnected ? 'Laptop disconnected' : health ? 'Laptop connected' : 'Checking connection';
  $('connection').innerHTML = `<span class="status-dot"></span>${escapeHTML(text)}`;
  $('runtime-details').innerHTML = ['backend', 'audio', 'liquid', 'rawtree', 'nimble'].map(name => {
    const component = health?.components?.[name];
    return `<div class="runtime-item"><strong>${escapeHTML(name === 'liquid' ? 'Liquid · local model' : human(name))}</strong><span>${escapeHTML(human(component?.status) || 'Not available')}${component?.label ? ` · ${escapeHTML(component.label)}` : ''}</span></div>`;
  }).join('');
}
function renderHistory() {
  $('history-source').textContent = DEMO_FIXTURE_MODE ? 'Synthetic history' : history.length ? `${history.length} sessions` : 'No history yet';
  $('history-list').innerHTML = history.length ? history.map(item => {
    const synthetic = item.data_origin === 'synthetic' || item.metrics?.data_origin === 'synthetic';
    return `<article class="history-item"><span class="history-symbol" aria-hidden="true">≋</span><div class="history-text"><h3>${escapeHTML(item.label || (synthetic ? 'Synthetic check-in' : 'Demonstration check-in'))}</h3><p>${escapeHTML(date(item.completed_at || item.created_at))} · ${synthetic ? 'Synthetic data' : 'Consented demo'}${item.date_label ? ` · ${escapeHTML(item.date_label)}` : ''}</p></div><div class="history-value">${number(item.metrics?.duration_s)} sec<small>${number(item.metrics?.recording_wpm)} recording wpm</small></div></article>`;
  }).join('') : '<div class="empty-state">Your accepted check-ins will appear here after processing.</div>';
}
function cloudLabel() {
  const sync = session?.cloud_sync;
  if (!sync) return 'Cloud export is off until you choose otherwise.';
  if (sync.label) return sync.label;
  if (sync.mode === 'restricted' || sync.status === 'restricted') return 'Restricted mode · measurements and personal checkpoints stay off RawTree.';
  return `Cloud export approved · ${sync.delivered ?? 0} delivered · ${sync.pending ?? 0} pending${sync.failed ? ` · ${sync.failed} failed` : ''}`;
}
function render() {
  renderConnection(); renderControls(); renderSummary(); renderFollowup();
  const info = phases[session?.phase] || ['A fresh place to begin.', 'Your unfinished task will appear here after your first check-in.'];
  $('unfinished-task').innerHTML = `<h2>${escapeHTML(info[0])}</h2><p>${escapeHTML(info[1])}</p>`;
  $('continue-task').hidden = !session;
  $('continue-task').textContent = session?.phase === 'ready' ? 'Review your follow-up →' : 'Pick up this step →';
  const newAllowed = !session || ['ready', 'awaiting_user_choice'].includes(session.phase);
  $('start-checkin').innerHTML = `${newAllowed ? 'Start a check-in' : 'Continue your check-in'} <span aria-hidden="true">↗</span>`;
  if (session?.consent) {
    $('recording-consent').checked = Boolean(session.consent.recording);
    $('export-consent').checked = Boolean(session.consent.cloud_export);
  }
  $('recording-consent').disabled = Boolean(session) || busy;
  $('export-consent').disabled = Boolean(session) || busy;
  $('consent-lock').hidden = !session;
  $('pairing-field').hidden = DEMO_FIXTURE_MODE || Boolean(session);
  $('export-note').textContent = DEMO_FIXTURE_MODE ? 'Synthetic preview · nothing is sent to RawTree.' : $('export-consent').checked ? 'Export approved · approved measurements and workflow metadata only.' : 'Restricted mode · cloud export is off.';
}
function renderControls() {
  const recording = recordState === 'recording', requesting = recordState === 'requesting', stopping = recordState === 'stopping';
  const canCapture = !session || session.phase === 'recording' || (session.phase === 'awaiting_input' && needsReplacement());
  const supported = ClipRecorder.support();
  $('record-button').disabled = stopping || uploading || (!recording && !requesting && (busy || !!pending || !canCapture || !supported.supported));
  $('record-label').textContent = recording ? 'Stop recording' : requesting ? 'Cancel microphone request' : stopping ? 'Finishing recording…' : busy ? 'Starting session…' : 'Start recording';
  $('record-button').classList.toggle('recording', recording);
  $('recorder-status').textContent = recording ? 'Recording · local microphone' : requesting ? 'Waiting for permission' : stopping ? 'Finishing complete clip' : 'Microphone off';
  $('record-support').textContent = supported.supported ? 'Stops automatically at 30 seconds.' : supported.reason;
  $('fixture-clip').disabled = busy || uploading || !!pending || !canCapture || recordState !== 'idle';
  $('start-checkin').disabled = busy || uploading || recordState !== 'idle' || !!pending;
  $('continue-task').disabled = busy;
  const canSearch = !!session?.metrics && !['paused', 'processing', 'recording'].includes(session.phase);
  $('search-button').disabled = busy || uploading || !canSearch;
  $('resource-help').textContent = DEMO_FIXTURE_MODE ? 'Preview only: displays a synthetic source card. No search is sent.' : !canSearch ? 'Complete a check-in, and resume it if paused, before searching.' : 'Only source-backed fields will be shown. Unknown details remain unknown.';
  for (const id of ['pause-button', 'resume-button']) $(id).disabled = busy || uploading || recordState !== 'idle';
  if (session && resumeRequestedVersion === session.state_version) $('resume-button').disabled = true;
  for (const form of ['transcript-form', 'input-form']) $(form).querySelector('button').disabled = busy || uploading || session?.phase === 'paused';
}
function renderSummary() {
  let title = 'Ready when you are.', description = 'Record a check-in to see its descriptive measurements.', buttons = '';
  const phase = session?.phase;
  const accepted = session?.clips?.some(clip => ['queued', 'processing', 'accepted', 'completed'].includes(clip.status));
  if (pending) {
    title = uploading ? (pending.accepted ? 'Finishing capture…' : 'Sending the complete clip…') : pending.accepted ? 'Clip accepted. Capture still needs to finish.' : pending.interrupted ? 'Recording interrupted.' : 'Your clip is waiting to send.';
    description = uploading ? 'Keep this page open until the laptop acknowledges the recording.' : pending.reason || 'The recording remains in this tab. A retry uses the same clip ID.';
    if (pending.durationS < 20 && !pending.accepted) description += ' This clip is shorter than the recommended 20–30 seconds.';
    if (!uploading) buttons = `<button class="button primary" data-capture="retry" ${busy ? 'disabled' : ''}>${pending.accepted ? 'Finish capture' : pending.interrupted ? 'Send this clip' : 'Retry upload'}</button>${!pending.accepted ? '<button class="button secondary" data-capture="discard">Record again</button>' : ''}`;
  } else if (session) {
    [title, description] = phases[phase] || ['Session status', human(phase)];
    if (session.metrics) {
      title = session.metrics.quality === 'accepted' ? 'Recording measurements available.' : 'Recording needs another look.';
      description = session.metrics.quality_reasons?.length ? session.metrics.quality_reasons.join(' · ') : DEMO_FIXTURE_MODE ? 'These values are synthetic. Your microphone audio was not analyzed.' : 'Quality describes the recording, not a health assessment.';
    }
    if (accepted && session.capture_finished === false) buttons += '<button class="button primary" data-capture="finish">Finish accepted capture</button>';
    if (needsReplacement()) buttons += '<a class="button secondary" href="#checkin">Record a replacement</a>';
    if (needsDependency()) { description = session.pending_input.question; buttons += '<a class="button secondary" href="#followup">View task &amp; resume</a>'; }
  }
  const errors = (session?.errors || []).map(error => typeof error === 'string' ? error : error.message || human(error.code)).filter(Boolean);
  $('capture-progress').innerHTML = `<div><h2>${escapeHTML(title)}</h2><p>${escapeHTML(description)}</p>${errors.length ? `<p>${escapeHTML(errors.join(' · '))}</p>` : ''}</div>${buttons ? `<div class="button-row">${buttons}</div>` : ''}`;
  const metrics = session?.metrics;
  $('measurement-source').textContent = metrics ? metrics.data_origin === 'synthetic' || DEMO_FIXTURE_MODE ? 'Synthetic measurements' : 'Consented demo recording' : 'Not measured yet';
  $('metrics-grid').innerHTML = metricInfo.map(([key, label, unit, note, digits]) => `<article class="metric-card"><p class="metric-label">${label}</p><div class="metric-value">${number(metrics?.[key], digits)}${metrics?.[key] != null ? `<small>${unit}</small>` : ''}</div><p class="metric-note">${metrics?.[key] == null ? 'Unavailable · ' : ''}${note}</p></article>`).join('');
  const comparison = session?.comparison;
  const count = comparison?.baseline_session_count ?? comparison?.session_count ?? 0;
  $('baseline-count').textContent = `${count} ${count === 1 ? 'session' : 'sessions'}`;
  const baselineSource = human(comparison?.baseline_source || comparison?.source) || 'Source not provided';
  $('baseline-description').textContent = !comparison ? 'A comparison will appear when an eligible history is available.' : comparison.status === 'insufficient_history' ? `Insufficient history. ${count} eligible completed sessions; no personal baseline is established. Source: ${baselineSource}.` : comparison.baseline_label || `${baselineSource}. Only eligible completed session versions belong in this reference.`;
  const compareMetrics = comparison?.metrics || {};
  $('comparison-values').innerHTML = Object.entries(compareMetrics).map(([key, item]) => {
    const label = metricInfo.find(info => info[0] === key)?.[1] || human(key);
    return `<div class="comparison-row"><strong>${escapeHTML(label)}</strong><span>Current ${number(item.current, 3)} · Mean ${number(item.mean, 3)} · Δ ${number(item.delta, 3)}</span></div>`;
  }).join('');
  const transcriptClip = session?.clips?.find(clip => typeof clip.transcript === 'string' && clip.status !== 'superseded');
  $('correction-panel').hidden = !transcriptClip;
  if (transcriptClip && lastTranscriptRevision !== session.input_revision && document.activeElement !== $('transcript')) {
    $('transcript').value = transcriptClip.transcript;
    $('transcript').dataset.clipId = transcriptClip.clip_id;
    $('transcript').dataset.inputRevision = session.input_revision;
    lastTranscriptRevision = session.input_revision;
  }
}
function renderFollowup() {
  const info = phases[session?.phase] || ['A next step starts with you.', 'Nothing is searched until you choose a category and city.'];
  $('task-title').textContent = info[0];
  $('task-description').textContent = info[1];
  if (needsDependency() || needsReplacement()) $('task-description').textContent = session.pending_input.question;
  if (session?.pause_requested && session.phase !== 'paused') $('task-description').textContent = 'Pause requested. The current action is finishing at a safe boundary.';
  const pendingAction = session?.pending_action;
  $('task-meta').textContent = session ? `${DEMO_FIXTURE_MODE ? 'Simulated state' : 'Saved state'} · ${human(session.phase)} · version ${session.state_version}${pendingAction ? `\nNext: ${pendingAction.label || human(pendingAction.name) || 'Waiting for an action'}` : ''}\nExecution: ${human(session.execution_mode) || 'Unavailable'}` : '';
  const errors = (session?.errors || []).map(error => typeof error === 'string' ? error : `${error.message || human(error.code)}${error.retryable ? ' · retryable' : ''}`);
  if (errors.length) $('task-meta').textContent += `\n${errors.join('\n')}`;
  $('pause-button').hidden = !session || ['paused', 'ready'].includes(session.phase);
  $('resume-button').hidden = !session || (!['paused', 'waiting_retry', 'agent_unavailable'].includes(session.phase) && !needsDependency());
  $('cloud-status').textContent = cloudLabel();
  const input = session?.pending_input;
  $('input-form').hidden = !input?.question || needsDependency() || needsReplacement();
  if (document.activeElement !== $('input-answer') || !$('input-question').textContent) {
    $('input-question').textContent = input?.question || '';
    $('input-answer').dataset.inputRevision = session?.input_revision ?? '';
  }
  const resources = Array.isArray(session?.resources) ? session.resources : [];
  $('resource-count').textContent = `${resources.length} ${resources.length === 1 ? 'source' : 'sources'}`;
  $('resource-list').innerHTML = resources.length ? resources.map(resource => {
    const url = safeURL(resource.source_url || resource.url);
    const synthetic = resource.data_origin === 'synthetic' || resource.verification_status === 'synthetic_fixture' || DEMO_FIXTURE_MODE;
    const fields = resource.fields || resource.facts || {};
    const known = ['phone', 'email', 'address', 'hours', 'availability', 'eligibility'];
    const valueOf = value => typeof value === 'object' && value !== null ? value.value : value;
    const unknown = resource.unknown_fields || known.filter(key => valueOf(fields[key]) == null);
    const passages = Array.isArray(resource.passages) ? resource.passages.slice(0, 6) : [];
    const citations = passages.length ? `<details class="source-passages"><summary>Supporting source passages</summary>${passages.map(passage => `<blockquote>${escapeHTML(passage.text)}<small>${escapeHTML(passage.passage_ref)}</small></blockquote>`).join('')}</details>` : '';
    return `<article class="card resource-card"><span class="chip">${escapeHTML(synthetic ? 'Synthetic source · not retrieved' : human(resource.verification_status) || 'Unverified lead')}</span><h3>${escapeHTML(resource.title || 'Public source')}</h3><p>${escapeHTML(resource.description || 'Description unavailable.')}</p><div class="source-meta">${synthetic ? 'Illustrative timestamp' : 'Retrieved'}: ${escapeHTML(date(resource.retrieved_at))}${resource.content_version || resource.content_hash ? ` · Version ${escapeHTML(String(resource.content_version || resource.content_hash).slice(0,20))}` : ''}</div>${url ? `<a href="${escapeHTML(url)}" target="_blank" rel="noopener noreferrer">${escapeHTML(url)} ↗</a>` : '<p class="small-copy">Source URL unavailable</p>'}<dl>${known.map(key => `<dt>${escapeHTML(human(key))}</dt><dd>${escapeHTML(valueOf(fields[key]) ?? 'Unknown')}</dd>`).join('')}</dl>${unknown.length ? `<p class="unknown">Unknown: ${escapeHTML(unknown.map(human).join(', '))}</p>` : ''}<p class="small-copy">${synthetic ? 'Layout example only. This is not a service recommendation.' : 'Source statements do not independently confirm current availability.'}</p>${citations}${resource.supporting_passage ? `<p class="small-copy">Source passage: ${escapeHTML(resource.supporting_passage)}</p>` : ''}</article>`;
  }).join('') : '<div class="empty-state">Sources will appear after your requested search.<br>Unknown details will stay clearly marked.</div>';
  const events = [...(session?.events || [])].sort((a, b) => (Date.parse(b.created_at || b.timestamp) || 0) - (Date.parse(a.created_at || a.timestamp) || 0)).slice(0, 8);
  $('timeline').innerHTML = events.length ? events.map(event => `<li>${escapeHTML(event.label || human(event.name) || 'Session event')}<small>${escapeHTML(date(event.created_at || event.timestamp))} · ${escapeHTML(human(event.status) || 'Status unavailable')}${DEMO_FIXTURE_MODE ? ' · synthetic fixture' : ''}</small></li>`).join('') : '<li>No task events yet.<small>Only stored events are shown here.</small></li>';
}

const bars = Array.from({ length: 39 }, () => { const bar = document.createElement('i'); $('waveform').append(bar); return bar; });
const recorder = new ClipRecorder({
  onState({ state }) { recordState = state; renderControls(); },
  onElapsed(seconds) { elapsed = seconds; $('elapsed').textContent = `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(Math.floor(seconds % 60)).padStart(2, '0')}`; $('recording-hint').textContent = seconds < 20 ? 'Take your time. Aim for 20–30 seconds.' : 'You can stop whenever you’re ready.'; },
  onLevel(level) { bars.forEach((bar, index) => { bar.style.height = `${5 + Math.min(1, level * 4) * (68 - Math.abs(index - 19) * 2.5)}px`; }); },
  onError(error) { showError(error); },
  onComplete(clip) {
    pending = { ...clip, accepted: false };
    saved.clip_id = clip.clipId; persist();
    navigate('summary'); render();
    if (clip.interrupted) notify(clip.reason || 'Recording was interrupted. Review it before sending.');
    else void uploadPending();
  },
});

async function uploadPending() {
  if (!pending || uploading || !session) return;
  uploading = true; clearError(); render();
  try {
    if (!pending.accepted) {
      const rejected = session.clips?.find(clip => ['rejected', 'missing'].includes(clip.status));
      await api.uploadClip(session.session_id, { clipId: pending.clipId, blob: pending.blob, mimeType: pending.mimeType, ...(rejected ? { supersedesClipId: rejected.clip_id } : {}) });
      pending.accepted = true;
      pending.blob = null; // Receipt acknowledged: release audio before finish/status calls.
    }
    setSession(await api.finish(session.session_id)); // Never called before upload acknowledgement.
    pending = null;
    notify(DEMO_FIXTURE_MODE ? 'Synthetic preview loaded. No audio was analyzed and no sponsor service was called.' : 'The complete recording was accepted by the laptop. Processing may continue.');
    await refreshStatus(); await refreshHistory();
  } catch (error) { showError(error); }
  finally { uploading = false; render(); }
}
function newCheckin() {
  if (session && ['ready', 'awaiting_user_choice'].includes(session.phase)) {
    session = null;
    saved = { profile_id: saved.profile_id }; persist(); api.setResumeToken(null);
    $('recording-consent').checked = false; $('export-consent').checked = false;
    lastTranscriptRevision = null;
  }
  clearError(); notify(''); render(); navigate('checkin');
}
function updateSearchPreview() {
  const select = $('resource-category');
  $('search-preview').textContent = `${select.selectedOptions[0].textContent} in ${$('resource-city').value.trim() || 'your chosen city'}`;
  $('search-consent').checked = false;
}

$('start-checkin').addEventListener('click', newCheckin);
$('continue-task').addEventListener('click', () => navigate(session?.phase === 'recording' ? 'checkin' : ['processing', 'comparing', 'awaiting_input'].includes(session?.phase) ? 'summary' : 'followup'));
document.querySelectorAll('a[href^="#"]').forEach(link => link.addEventListener('click', event => { if (['home', 'checkin', 'summary', 'followup'].includes(link.hash.slice(1))) { event.preventDefault(); navigate(link.hash.slice(1)); } }));
window.addEventListener('hashchange', () => navigate(location.hash.slice(1)));
$('export-consent').addEventListener('change', render);
$('record-button').addEventListener('click', () => {
  if (['recording', 'requesting'].includes(recordState)) { recorder.stop(); return; }
  void action(async () => { await ensureSession(); elapsed = 0; $('elapsed').textContent = '00:00'; await recorder.start(); });
});
$('fixture-clip').addEventListener('click', () => void action(async () => {
  await ensureSession();
  pending = { blob: new Blob(['CLEARLINE SYNTHETIC FIXTURE — NOT AUDIO'], { type: 'audio/webm' }), mimeType: 'audio/webm', clipId: crypto.randomUUID(), durationS: 24.1, accepted: false };
  saved.clip_id = pending.clipId; persist(); navigate('summary'); await uploadPending();
}));
$('capture-progress').addEventListener('click', event => {
  const actionName = event.target.closest('[data-capture]')?.dataset.capture;
  if (actionName === 'retry') void uploadPending();
  if (actionName === 'discard' && !uploading) { pending = null; delete saved.clip_id; persist(); clearError(); notify('Unsent clip discarded from this page. Record a new complete clip.'); navigate('checkin'); render(); }
  if (actionName === 'finish') void action(async () => { setSession(await api.finish(session.session_id)); await refreshStatus(); });
});
for (const id of ['resource-category', 'resource-city']) $(id).addEventListener('input', updateSearchPreview);
$('resource-form').addEventListener('submit', event => {
  event.preventDefault();
  if (!session || !$('search-consent').checked) return;
  const request = { category: $('resource-category').value, city: $('resource-city').value.trim() };
  void action(async () => {
    const old = session.resource_request;
    if (old?.category === request.category && old.city !== request.city) setSession(await api.input(session.session_id, { input_revision: session.input_revision, city: request.city }));
    else setSession(await api.resources(session.session_id, request));
    $('search-consent').checked = false;
    await refreshStatus();
    notify(DEMO_FIXTURE_MODE ? 'Synthetic resource preview. No public search was performed.' : 'Your category and city were saved for the requested search.');
  });
});
$('input-form').addEventListener('submit', event => { event.preventDefault(); if (session) void action(async () => { setSession(await api.input(session.session_id, { input_revision: Number($('input-answer').dataset.inputRevision), answer: $('input-answer').value.trim() })); $('input-answer').value = ''; await refreshStatus(); }); });
$('transcript-form').addEventListener('submit', event => { event.preventDefault(); if (session) void action(async () => { setSession(await api.input(session.session_id, { input_revision: Number($('transcript').dataset.inputRevision), transcript: $('transcript').value.trim(), clip_id: $('transcript').dataset.clipId })); await refreshStatus(); notify('Correction saved. Dependent measurements and comparisons will be updated.'); }); });
$('pause-button').addEventListener('click', () => void action(async () => { setSession(await api.pause(session.session_id)); await refreshStatus(); }));
$('resume-button').addEventListener('click', () => void action(async () => { const version = session.state_version; resumeRequestedVersion = version; try { setSession(await api.resume(session.session_id)); await refreshStatus(); } catch (error) { resumeRequestedVersion = null; throw error; } }));
$('about-button').addEventListener('click', () => $('privacy-dialog').showModal());
$('close-privacy').addEventListener('click', () => $('privacy-dialog').close());
window.addEventListener('beforeunload', event => { if (pending?.blob || recordState !== 'idle') { event.preventDefault(); event.returnValue = ''; } });
window.addEventListener('pagehide', event => { if (!event.persisted) recorder.dispose(); });
window.addEventListener('offline', () => { if (!DEMO_FIXTURE_MODE) { disconnected = true; renderConnection(); notify('Connection interrupted. Unsent audio is only in this tab; accepted work remains on the laptop.'); } });
window.addEventListener('online', () => void refreshHealth());

async function poll() {
  if (!polling && !busy && !uploading && document.visibilityState !== 'hidden' && saved.session_id) {
    polling = true;
    try { await refreshStatus(); }
    catch (error) { disconnected = true; renderConnection(); if (error.status !== 404) notify(`Connection interrupted. ${error.message}`); }
    finally { polling = false; }
  }
  setTimeout(poll, POLL_INTERVAL_MS);
}

async function initialize() {
  $('mode-description').textContent = DEMO_FIXTURE_MODE ? 'Desktop prototype · synthetic preview · no live analysis or sponsor calls' : 'Desktop prototype · consented demo · processing on your paired laptop';
  $('fixture-controls').hidden = !DEMO_FIXTURE_MODE;
  $('privacy-fixture').hidden = !DEMO_FIXTURE_MODE;
  $('pairing-code').required = !DEMO_FIXTURE_MODE;
  render(); navigate(location.hash.slice(1) || 'home', false);
  await refreshHealth();
  if (saved.session_id) {
    try {
      await refreshStatus();
      if (saved.clip_id && !session?.clips?.some(clip => clip.clip_id === saved.clip_id)) notify('The previous recording was not acknowledged. Audio is not saved across reloads; record a replacement.');
      else notify(DEMO_FIXTURE_MODE ? 'Synthetic session restored in this tab.' : 'Saved session reconnected. Use Resume for an unfinished task; no work was started by this status check.');
    } catch (error) {
      if (error.status === 404 || error.status === 401) { saved = {}; api.setResumeToken(null); persist(); notify(DEMO_FIXTURE_MODE ? 'The synthetic preview resets on reload. Start a new preview check-in.' : 'The saved session could not be authorized. Pair with the laptop to start again.'); }
      else { disconnected = true; showError(error); }
    }
  }
  await refreshHistory(); render(); void poll();
}
void initialize();
