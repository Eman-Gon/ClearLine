'use strict';
const el = id => document.getElementById(id);
el('profile').value = localStorage.getItem('clearline-call-profile') || crypto.randomUUID();
let busy = false;
const pendingKey = 'clearline-pending-call';
const status = text => { el('status').textContent = text; };
async function api(path, body) {
  const response = await fetch(path, {method: body ? 'POST' : 'GET', headers: {'Authorization':'Bearer '+el('pair').value, ...(body ? {'Content-Type':'application/json'} : {})}, ...(body ? {body: JSON.stringify(body)} : {})});
  const value = await response.json();
  if (!response.ok) throw new Error(value.detail?.code || 'Request failed ('+response.status+')');
  return value;
}
async function history() {
  const data = await api('/api/call-profiles/'+encodeURIComponent(el('profile').value)+'/history');
  el('history').replaceChildren();
  for (const call of data.calls) {
    const card = document.createElement('article');
    const title = document.createElement('h3'); title.textContent = new Date(call.created_at).toLocaleString()+' · '+call.state;
    const words = document.createElement('pre'); words.textContent = call.transcript || 'No consent-confirmed transcript available.';
    const summary = document.createElement('p'); summary.textContent = 'Vapi-generated summary: '+(call.analysis?.provider_summary || 'Unavailable');
    card.append(title, words, summary); el('history').append(card);
  }
  if (!data.calls.length) el('history').textContent = 'No phone-call history for this profile.';
}
el('refresh').onclick = async () => { try { await history(); } catch(e) { status(e.message); } };
el('call').onclick = async () => {
  if (busy) return;
  if (sessionStorage.getItem(pendingKey)) { status('A prior call request needs review. Refresh history and check Vapi Logs before starting another.'); return; }
  if (!el('permission').checked || !el('cloud').checked) { status('Confirm participant permissions first.'); return; }
  busy = true; el('call').disabled = true;
  try {
    const config = await api('/api/calls/config');
    if (!config.configured) throw new Error('Vapi configuration missing: '+config.missing.join(', '));
    if (config.consent_mode === 'preconsented_demo' && !el('prior').checked) throw new Error('Participant agreement before recording is required for demo mode.');
    const request = {request_id:crypto.randomUUID(),profile_id:el('profile').value,number:el('number').value.trim(),permission_to_call:true,cloud_processing_approved:true,participant_preconsented_demo:el('prior').checked};
    localStorage.setItem('clearline-call-profile',request.profile_id);
    sessionStorage.setItem(pendingKey,request.request_id);
    const call = await api('/api/calls',request);
    status('Call status: '+call.state+'. Refresh history after the call ends.');
    if (call.state === 'failed') sessionStorage.removeItem(pendingKey);
    await history();
  } catch(e) { status(e.message+'. If dispatch may have started, check history and Vapi Logs; do not redial blindly.'); }
  finally { busy = false; el('call').disabled = false; }
};
setInterval(async () => {
  const id = sessionStorage.getItem(pendingKey);
  if (!id || !el('pair').value || busy) return;
  try {
    const call = await api('/api/calls/'+encodeURIComponent(id)); status('Call status: '+call.state);
    if (['completed','failed','consent_unconfirmed','transcript_unavailable'].includes(call.state)) { sessionStorage.removeItem(pendingKey); await history(); }
  } catch(_) { /* Keep the pending ID: a network failure must never redial. */ }
}, 5000);
