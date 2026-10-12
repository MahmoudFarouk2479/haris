// Runs on the audio thread. Turns microphone samples into 80 ms frames of 16 kHz, 16-bit
// mono PCM (the format the local speech engines expect) plus a loudness value for the HUD.
// The AudioContext is normally created at 16 kHz, in which case no resampling happens here.
const TARGET = 16000;
const FRAME = 1280; // 80 ms

class MicProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.ratio = sampleRate / TARGET;
    this.pos = 0;
    this.prev = 0;
    this.frame = new Int16Array(FRAME);
    this.n = 0;
    this.energy = 0;
  }

  process(inputs) {
    const ch = inputs[0] && inputs[0][0];
    if (!ch) return true;
    const at = (i) => (i < 0 ? this.prev : ch[i]);
    while (this.pos < ch.length - 1) {
      const i = Math.floor(this.pos);
      const f = this.pos - i;
      const s = Math.max(-1, Math.min(1, at(i) * (1 - f) + at(i + 1) * f));
      this.frame[this.n++] = s < 0 ? s * 0x8000 : s * 0x7fff;
      this.energy += s * s;
      if (this.n === FRAME) {
        this.port.postMessage({ pcm: this.frame.buffer, rms: Math.sqrt(this.energy / FRAME) }, [this.frame.buffer]);
        this.frame = new Int16Array(FRAME);
        this.n = 0;
        this.energy = 0;
      }
      this.pos += this.ratio;
    }
    this.pos -= ch.length;
    this.prev = ch[ch.length - 1];
    return true;
  }
}

registerProcessor('mic-processor', MicProcessor);
