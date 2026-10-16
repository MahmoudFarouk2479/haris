// Microphone, wake word and speech playback for the HUD.
//
// Privacy model:
//   * the microphone opens only when the user enables voice (push-to-talk or wake word);
//   * while idle with the wake word on, 80 ms frames go to the LOCAL wake-word engine only,
//     and only while Haris is not speaking (so it never wakes itself up);
//   * a recording starts only after the wake word or a push-to-talk press, ends on silence,
//     and goes to the LOCAL speech-to-text engine (or, when the user chose the Colab engine and enabled
//     it, to their own Colab notebook behind its access token); nothing is stored;
//   * the HUD shows a live indicator whenever the microphone is open.
import { postBinary, postForAudio, sessionToken } from './api.js';
import { SpeechQueue } from './speechQueue.js';
import { buildSpeechOutput } from './speechOutput.js';

const SPEECH_RMS = 0.025;        // above this a frame counts as speech
const END_SILENCE_MS = 600;      // this much quiet after speech ends a recording
const NO_SPEECH_MS = 5000;       // give up if nobody speaks
const MAX_RECORD_MS = 15000;
const FRAME_MS = 80;

export class Voice {
  constructor({ onLevel, onWake, onMicState, onSpeaking, onNeedsGesture, onSpeechError }) {
    this.onLevel = onLevel;
    this.onWake = onWake;
    this.onMicState = onMicState;
    this.onSpeaking = onSpeaking;
    this.onNeedsGesture = onNeedsGesture;
    this.onSpeechError = onSpeechError;
    this.ctx = null;
    this.stream = null;
    this.recording = null;
    this.wakeEnabled = false;
    this.ws = null;
    this.speaking = false;
    this.queue = [];
    this.out = null;
    this.gain = null;
    this.current = null;
    // One speech request at a time; sentences that arrive meanwhile are sent together.
    this.speech = new SpeechQueue(
      (text) => postForAudio('/api/voice/speak', { text }),
      (audio) => {
        this.queue.push(audio);
        if (!this.speaking) this.playNext();
      },
    );
  }

  get micOpen() {
    return Boolean(this.stream);
  }

  async openMic() {
    if (this.stream) return;
    this.stream = await navigator.mediaDevices.getUserMedia({
      audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true },
    });
    try {
      this.ctx = new AudioContext({ sampleRate: 16000 });
    } catch {
      this.ctx = new AudioContext();   // the worklet resamples
    }
    await this.ctx.audioWorklet.addModule('/mic-worklet.js');
    const source = this.ctx.createMediaStreamSource(this.stream);
    const node = new AudioWorkletNode(this.ctx, 'mic-processor');
    node.port.onmessage = (e) => this.frame(e.data);
    source.connect(node);
    this.state();
  }

  closeMic() {
    this.cancelRecording();
    this.stream?.getTracks().forEach((t) => t.stop());
    this.stream = null;
    this.ctx?.close();
    this.ctx = null;
    this.state();
  }

  state() {
    this.onMicState?.({ open: this.micOpen, recording: Boolean(this.recording), wake: this.wakeEnabled && this.ws?.readyState === 1 });
  }

  frame({ pcm, rms }) {
    if (this.recording) {
      const r = this.recording;
      r.chunks.push(new Uint8Array(pcm));
      r.elapsed += FRAME_MS;
      this.onLevel?.(rms);
      if (rms > SPEECH_RMS) {
        r.heard = true;
        r.quiet = 0;
      } else {
        r.quiet += FRAME_MS;
      }
      if (r.auto && ((r.heard && r.quiet >= END_SILENCE_MS) || (!r.heard && r.elapsed >= NO_SPEECH_MS))) {
        this.finishRecording();
      } else if (r.elapsed >= MAX_RECORD_MS) {
        this.finishRecording();
      }
      return;
    }
    if (this.ws?.readyState === 1 && !this.speaking) {
      this.ws.send(pcm);
    }
  }

  /**
   * Records one utterance. auto=true stops on silence (wake word / click);
   * auto=false waits for stopRecording() (push-to-talk key released).
   * Resolves with the transcript, or '' when nothing was said.
   */
  async record({ auto }) {
    await this.openMic();
    this.stopSpeaking();
    if (this.recording) return '';
    return new Promise((resolve, reject) => {
      this.recording = { chunks: [], elapsed: 0, quiet: 0, heard: false, auto, resolve, reject };
      this.state();
    });
  }

  stopRecording() {
    if (this.recording) this.finishRecording();
  }

  cancelRecording() {
    if (this.recording) {
      const r = this.recording;
      this.recording = null;
      r.resolve('');
      this.state();
    }
  }

  async finishRecording() {
    const r = this.recording;
    this.recording = null;
    this.state();
    this.onLevel?.(0);
    if (!r.heard) return r.resolve('');
    const total = r.chunks.reduce((n, c) => n + c.length, 0);
    const pcm = new Uint8Array(total);
    let off = 0;
    for (const c of r.chunks) {
      pcm.set(c, off);
      off += c.length;
    }
    r.chunks.length = 0;
    try {
      const { text } = await postBinary('/api/voice/transcribe', pcm);
      r.resolve(text ?? '');
    } catch (e) {
      r.reject(e);
    } finally {
      pcm.fill(0);   // do not leave the recording lying around in memory
    }
  }

  // ── Wake word ──────────────────────────────────────────────────────────

  async enableWake() {
    this.wakeEnabled = true;
    await this.openMic();
    this.connectWake();
  }

  disableWake() {
    this.wakeEnabled = false;
    this.ws?.close();
    this.ws = null;
    if (!this.recording) this.closeMic();
    this.state();
  }

  connectWake() {
    if (!this.wakeEnabled || this.ws) return;
    const ws = new WebSocket(`ws://${location.host}/ws/wake`, [sessionToken()]);
    ws.binaryType = 'arraybuffer';
    ws.onopen = () => this.state();
    ws.onmessage = (e) => {
      try {
        const msg = JSON.parse(e.data);
        if (msg.type === 'wake' && !this.recording) this.onWake?.(msg.name);
      } catch {
        /* ignore */
      }
    };
    ws.onclose = () => {
      this.ws = null;
      this.state();
      if (this.wakeEnabled) setTimeout(() => this.connectWake(), 5000);
    };
    this.ws = ws;
  }

  // ── Speech output ──────────────────────────────────────────────────────

  /** Queues one sentence; it is fetched (alone or with the ones after it) and played in order. */
  speak(text) {
    this.speech.push(text);
  }

  async playNext() {
    const next = this.queue.shift();
    if (!next) {
      this.speaking = false;
      this.onSpeaking?.(false);
      return;
    }
    this.speaking = true;
    this.onSpeaking?.(true);
    const bytes = await next;
    if (!bytes) {
      this.onSpeechError?.('The speech engine did not answer.');
      return this.playNext();
    }
    if (!this.speaking) return this.playNext();
    if (!(await this.ensureOutput())) {
      this.queue.length = 0;
      this.speaking = false;
      this.onSpeaking?.(false);
      return;
    }
    let buffer;
    try {
      buffer = await this.out.decodeAudioData(bytes);
    } catch {
      return this.playNext();
    }
    const src = this.out.createBufferSource();
    const analyser = this.out.createAnalyser();
    analyser.fftSize = 512;
    src.buffer = buffer;
    // The meter reads the voice before the gain and the limiter, so the emblem reacts exactly as before.
    this.gain ??= buildSpeechOutput(this.out);
    src.connect(analyser);
    analyser.connect(this.gain);
    const data = new Uint8Array(analyser.fftSize);
    const meter = () => {
      if (this.current !== src) return;
      analyser.getByteTimeDomainData(data);
      let sum = 0;
      for (const v of data) sum += ((v - 128) / 128) ** 2;
      this.onLevel?.(Math.sqrt(sum / data.length));
      requestAnimationFrame(meter);
    };
    this.current = src;
    src.onended = () => {
      if (this.current === src) {
        this.current = null;
        this.playNext();
      }
    };
    src.start();
    meter();
  }

  /**
   * Browsers keep audio "suspended" until the page has been clicked or typed into. Resume it, and
   * if the browser still refuses, ask for one click (onNeedsGesture) and resume on that click.
   */
  async ensureOutput() {
    this.out ??= new AudioContext();
    if (this.out.state !== 'running') {
      try {
        await Promise.race([this.out.resume(), new Promise((r) => setTimeout(r, 400))]);
      } catch {
        /* still suspended */
      }
    }
    if (this.out.state === 'running') return true;
    this.onNeedsGesture?.();
    const unlock = () => {
      this.out?.resume();
      window.removeEventListener('pointerdown', unlock, true);
      window.removeEventListener('keydown', unlock, true);
    };
    window.addEventListener('pointerdown', unlock, true);
    window.addEventListener('keydown', unlock, true);
    return false;
  }

  /** Barge-in: the user started talking, so Haris stops. */
  stopSpeaking() {
    this.speech.clear();
    this.queue.length = 0;
    const src = this.current;
    this.current = null;
    try {
      src?.stop();
    } catch {
      /* already stopped */
    }
    if (this.speaking) {
      this.speaking = false;
      this.onSpeaking?.(false);
    }
  }

  /** A short two-tone chime: "I'm listening". */
  chime() {
    this.out ??= new AudioContext();
    this.out.resume().catch(() => {});
    const t = this.out.currentTime;
    [880, 1320].forEach((f, i) => {
      const o = this.out.createOscillator();
      const g = this.out.createGain();
      o.frequency.value = f;
      g.gain.setValueAtTime(0.0001, t + i * 0.09);
      g.gain.exponentialRampToValueAtTime(0.15, t + i * 0.09 + 0.02);
      g.gain.exponentialRampToValueAtTime(0.0001, t + i * 0.09 + 0.12);
      o.connect(g).connect(this.out.destination);
      o.start(t + i * 0.09);
      o.stop(t + i * 0.09 + 0.13);
    });
  }
}

/** Splits streamed text into speakable sentences (English and Arabic punctuation). */
export class SentenceSplitter {
  constructor(onSentence) {
    this.buf = '';
    this.first = true;
    this.onSentence = onSentence;
  }

  push(text) {
    this.buf += text;
    // The first chunk is cut early — at a comma once it has a few words, or at a space once it is
    // long — so Haris starts talking while the rest of the reply is still being written.
    if (this.first) {
      const comma = this.buf.slice(12).search(/[,،;؛:]\s/);
      const cut = comma >= 0 ? 12 + comma + 1 : this.buf.length > 40 ? this.buf.lastIndexOf(' ', 40) : -1;
      if (cut > 0) {
        this.first = false;
        this.onSentence(this.buf.slice(0, cut));
        this.buf = this.buf.slice(cut);
      }
    }
    let m;
    while ((m = this.buf.match(/^([\s\S]*?[.!?؟؛…](?=\s)|[\s\S]*?\n)/))) {
      const sentence = m[0];
      this.buf = this.buf.slice(sentence.length);
      if (sentence.trim().length > 1) {
        this.first = false;
        this.onSentence(sentence);
      }
    }
  }

  flush() {
    if (this.buf.trim()) this.onSentence(this.buf);
    this.buf = '';
  }
}
