// Audio stays in memory. The caller owns consent, upload, and retry decisions.
const MIME_TYPES = [
  'audio/webm;codecs=opus',
  'audio/mp4',
  'audio/ogg;codecs=opus',
  'audio/webm',
  'audio/ogg',
];
const MAX_DURATION_MS = 30_000;
const PERMISSION_TIMEOUT_MS = 30_000;
const FINAL_STOP_TIMEOUT_MS = 5_000;
const noop = () => {};
const now = () => globalThis.performance?.now() ?? Date.now();

function stopTracks(stream) {
  for (const track of stream?.getTracks() ?? []) track.stop();
}

function clipUUID() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID();
  if (!globalThis.crypto?.getRandomValues) {
    throw new Error('This browser cannot create a secure clip ID. Use a current Chrome browser.');
  }
  const bytes = globalThis.crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function microphoneError(error) {
  const messages = {
    NotAllowedError: 'Microphone permission was denied. Allow microphone access in browser settings, then choose Record again.',
    SecurityError: 'Microphone access is blocked. Open the app using trusted HTTPS or USB-forwarded localhost, then allow the microphone.',
    NotFoundError: 'No microphone was found. Connect a microphone, then choose Record again.',
    NotReadableError: 'The microphone could not be opened. Close other apps using it, check the connection, then try again.',
    AbortError: 'Microphone setup was interrupted. Check the microphone and choose Record again.',
    OverconstrainedError: 'This microphone cannot use the requested recording settings. Try another microphone.',
    NotSupportedError: 'This browser could not record a supported audio format. Try a current Chrome browser.',
  };
  return new Error(messages[error?.name] || error?.message || 'Recording could not start. Check microphone access and try again.');
}

export class ClipRecorder {
  static support() {
    if (globalThis.isSecureContext !== true) {
      return { supported: false, reason: 'Microphone access needs trusted HTTPS or USB-forwarded localhost. Ordinary HTTP on a local network is not supported.', mimeType: null };
    }
    if (!globalThis.navigator?.mediaDevices?.getUserMedia) {
      return { supported: false, reason: 'Microphone access is unavailable in this browser. Use a current Chrome browser.', mimeType: null };
    }
    if (typeof globalThis.MediaRecorder !== 'function' || typeof globalThis.MediaRecorder.isTypeSupported !== 'function') {
      return { supported: false, reason: 'Audio recording is unavailable in this browser. Use a current Chrome browser.', mimeType: null };
    }
    const mimeType = MIME_TYPES.find(type => {
      try { return globalThis.MediaRecorder.isTypeSupported(type); } catch { return false; }
    });
    return mimeType
      ? { supported: true, reason: null, mimeType }
      : { supported: false, reason: 'No supported audio recording format is available. Try a current Chrome browser.', mimeType: null };
  }

  constructor({ onState = noop, onLevel = noop, onElapsed = noop, onComplete = noop, onError = noop } = {}) {
    Object.assign(this, { onState, onLevel, onElapsed, onComplete, onError });
    this.state = 'idle';
    this._attempt = null;
    this._disposed = false;
    this._visibilityChanged = () => {
      if (globalThis.document?.visibilityState === 'hidden') {
        this.stop({ interrupted: true, reason: 'The page was backgrounded or the screen locked. Recording was interrupted; review this clip before sending it.' });
      }
    };
    this._pageHidden = () => this.stop({ interrupted: true, reason: 'The page was left. Recording was interrupted; unsent audio is not saved after the page closes.' });
  }

  // Call directly from a user gesture, only after the caller has checked consent.
  // No permission request occurs in the constructor or support().
  async start() {
    if (this._disposed || this._attempt) return false;
    const support = ClipRecorder.support();
    if (!support.supported) {
      this.onError(new Error(support.reason));
      return false;
    }
    if (globalThis.document?.visibilityState === 'hidden') {
      this.onError(new Error('Return to this page and choose Record to start the microphone.'));
      return false;
    }
    const attempt = { chunks: [], interrupted: false, reason: null, mimeType: support.mimeType, trackListeners: [], recorderListeners: [] };
    this._attempt = attempt;
    this._watchPage();
    this._setState('requesting', attempt);
    try {
      attempt.clipId = clipUUID();
      const cancelled = new Promise(resolve => { attempt.cancelRequest = () => resolve(null); });
      const timedOut = new Promise((resolve, reject) => {
        attempt.permissionTimer = setTimeout(() => reject(new Error('Microphone permission timed out. Dismiss the pending browser prompt and choose Record again when ready.')), PERMISSION_TIMEOUT_MS);
      });
      const requested = globalThis.navigator.mediaDevices.getUserMedia({ audio: true, video: false }).then(stream => {
        // getUserMedia cannot be aborted. Release permission granted after cancel,
        // timeout, backgrounding, or disposal without ever starting a recording.
        if (this._attempt !== attempt || this._disposed) {
          stopTracks(stream);
          return null;
        }
        attempt.stream = stream;
        return stream;
      });
      const stream = await Promise.race([requested, cancelled, timedOut]);
      clearTimeout(attempt.permissionTimer);
      if (this._attempt !== attempt || !stream || this._disposed) return false;
      attempt.stream = stream;
      const tracks = stream.getAudioTracks();
      if (!tracks.length || tracks.every(track => track.readyState === 'ended')) {
        throw new Error('The microphone disconnected before recording began. Reconnect it and choose Record again.');
      }
      attempt.recorder = new globalThis.MediaRecorder(stream, { mimeType: support.mimeType });
      this._listenRecorder(attempt, 'dataavailable', event => {
        if (this._attempt === attempt && event.data?.size > 0) attempt.chunks.push(event.data);
      });
      this._listenRecorder(attempt, 'stop', () => this._complete(attempt));
      this._listenRecorder(attempt, 'error', () => {
        if (this._attempt !== attempt) return;
        const reason = 'The browser interrupted audio recording. Review the partial clip or record a replacement.';
        this.stop({ interrupted: true, reason });
        this.onError(new Error(reason));
      });
      for (const track of tracks) {
        const ended = () => this.stop({ interrupted: true, reason: 'The microphone disconnected. Review the partial clip or record a replacement.' });
        track.addEventListener('ended', ended);
        attempt.trackListeners.push([track, ended]);
      }
      // Timeslices only limit buffering: ALL chunks are assembled at final stop.
      attempt.recorder.start(1_000);
      attempt.startedAt = now();
      this._startMeter(attempt);
      attempt.elapsedTimer = setInterval(() => {
        if (this._attempt === attempt && this.state === 'recording') this.onElapsed((now() - attempt.startedAt) / 1_000);
      }, 200);
      attempt.limitTimer = setTimeout(() => this.stop(), MAX_DURATION_MS);
      this._setState('recording', attempt);
      this.onElapsed(0);
      return true;
    } catch (error) {
      if (this._attempt === attempt) this._fail(attempt, microphoneError(error));
      return false;
    }
  }

  stop({ interrupted = false, reason = null } = {}) {
    const attempt = this._attempt;
    if (!attempt) return;
    if (interrupted) {
      attempt.interrupted = true;
      attempt.reason = reason || 'Recording was interrupted. Review the clip before sending it.';
    }
    if (this.state === 'requesting') {
      this._cleanup(attempt);
      this._attempt = null;
      this._setState('idle', attempt);
      return;
    }
    if (this.state === 'stopping') {
      if (interrupted) this._setState('stopping', attempt);
      return;
    }
    attempt.stoppedAt = now();
    this._setState('stopping', attempt);
    // Never expose a partial container when the final stop event is missing.
    attempt.stopTimer = setTimeout(() => {
      if (this._attempt === attempt) this._fail(attempt, new Error('The browser did not finish the audio file. No clip was sent. Keep this page open and record a replacement.'));
    }, FINAL_STOP_TIMEOUT_MS);
    try {
      if (attempt.recorder.state !== 'inactive') attempt.recorder.stop();
      this._releaseCapture(attempt);
    } catch (error) {
      this._fail(attempt, microphoneError(error));
    }
  }

  // Disposal deliberately discards unsent audio and ignores delayed events.
  dispose() {
    this._disposed = true;
    const attempt = this._attempt;
    this._attempt = null;
    if (attempt) {
      this._cleanup(attempt);
      if (attempt.recorder?.state !== 'inactive') {
        try { attempt.recorder?.stop(); } catch { /* Tracks are already released. */ }
      }
    }
    this._unwatchPage();
    this.state = 'idle';
  }

  _complete(attempt) {
    if (this._attempt !== attempt || this._disposed) return;
    if (this.state !== 'stopping') {
      attempt.interrupted = true;
      attempt.reason ||= 'The browser ended recording unexpectedly. Review this clip before sending it.';
    }
    attempt.stoppedAt ??= now();
    const mimeType = attempt.recorder.mimeType || attempt.mimeType;
    const blob = new Blob(attempt.chunks, { type: mimeType });
    const durationS = Math.max(0, (attempt.stoppedAt - attempt.startedAt) / 1_000);
    this._cleanup(attempt);
    this._attempt = null;
    this._setState('idle', attempt);
    this.onElapsed(durationS);
    if (!blob.size) {
      this.onError(new Error('The browser returned an empty audio file. Check the microphone and record a new 20–30 second clip.'));
      return;
    }
    this.onComplete({ blob, mimeType, clipId: attempt.clipId, durationS, interrupted: attempt.interrupted, reason: attempt.reason });
  }

  _fail(attempt, error) {
    this._cleanup(attempt);
    this._attempt = null;
    if (attempt.recorder?.state !== 'inactive') {
      try { attempt.recorder?.stop(); } catch { /* Failure still releases tracks. */ }
    }
    this._setState('idle', attempt);
    this.onError(error);
  }

  _setState(state, attempt) {
    this.state = state;
    this.onState({ state, interrupted: Boolean(attempt?.interrupted), reason: attempt?.reason || null });
  }

  _listenRecorder(attempt, name, callback) {
    attempt.recorder.addEventListener(name, callback);
    attempt.recorderListeners.push([name, callback]);
  }

  _watchPage() {
    globalThis.document?.addEventListener('visibilitychange', this._visibilityChanged);
    globalThis.window?.addEventListener('pagehide', this._pageHidden);
  }

  _unwatchPage() {
    globalThis.document?.removeEventListener('visibilitychange', this._visibilityChanged);
    globalThis.window?.removeEventListener('pagehide', this._pageHidden);
  }

  _startMeter(attempt) {
    const AudioContext = globalThis.AudioContext || globalThis.webkitAudioContext;
    if (!AudioContext || !globalThis.requestAnimationFrame) return;
    try {
      const context = new AudioContext();
      attempt.audioContext = context;
      const analyser = context.createAnalyser();
      analyser.fftSize = 256;
      const source = context.createMediaStreamSource(attempt.stream);
      source.connect(analyser);
      attempt.meterSource = source;
      const samples = new Uint8Array(analyser.fftSize);
      const draw = () => {
        if (this._attempt !== attempt || this.state !== 'recording') return;
        if (context.state === 'running') {
          analyser.getByteTimeDomainData(samples);
          const power = samples.reduce((sum, value) => sum + ((value - 128) / 128) ** 2, 0) / samples.length;
          this.onLevel(Math.min(1, Math.sqrt(power)));
        }
        attempt.animationFrame = globalThis.requestAnimationFrame(draw);
      };
      // A blocked meter must not prevent the recording itself.
      if (context.state === 'suspended') context.resume().catch(noop);
      attempt.animationFrame = globalThis.requestAnimationFrame(draw);
    } catch {
      // The level is optional; never substitute a synthetic waveform.
    }
  }

  _releaseCapture(attempt) {
    clearTimeout(attempt.limitTimer);
    clearInterval(attempt.elapsedTimer);
    if (attempt.animationFrame !== undefined) globalThis.cancelAnimationFrame?.(attempt.animationFrame);
    try { attempt.meterSource?.disconnect(); } catch { /* Already disconnected. */ }
    attempt.meterSource = null;
    if (attempt.audioContext) {
      try { Promise.resolve(attempt.audioContext.close()).catch(noop); } catch { /* Already closed. */ }
      attempt.audioContext = null;
    }
    for (const [track, callback] of attempt.trackListeners) track.removeEventListener('ended', callback);
    attempt.trackListeners = [];
    stopTracks(attempt.stream);
    attempt.stream = null;
    this.onLevel(0);
  }

  _cleanup(attempt) {
    clearTimeout(attempt.permissionTimer);
    clearTimeout(attempt.stopTimer);
    attempt.cancelRequest?.();
    for (const [name, callback] of attempt.recorderListeners) attempt.recorder.removeEventListener(name, callback);
    attempt.recorderListeners = [];
    this._releaseCapture(attempt);
    this._unwatchPage();
    attempt.chunks = [];
  }
}
