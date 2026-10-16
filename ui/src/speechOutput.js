// The speech output chain: voice -> gain -> limiter -> speakers.
//
// The synthesised voice peaks at 0 dBFS, and the browser's resampling to the output rate then
// overshoots some peaks; a loopback recording still had samples at full scale with the gain alone.
// A DynamicsCompressorNode set up as a limiter catches what gets through. The Web Audio spec's
// compressor adds a little make-up gain (about +1.7 dB with these settings), so the loudest peaks
// leave it near -1.2 dBFS (input 0 dBFS, after the gain -1.9 dB: -3 + 1.1 / 20 + 1.7).
//
// Volume only: nothing here resamples or stretches the audio, so speed and pitch do not change.
// The microphone path does not go through this chain.

/** Speech is played a little below full scale (0.8 = about -2 dB) before the limiter. */
export const SPEECH_GAIN = 0.8;

/**
 * Limiter settings: a hard knee at -3 dB and a 20:1 ratio hold the peaks; the fast attack (with the
 * compressor's built-in look-ahead) catches them, and the short release lets go between syllables
 * without audible pumping.
 */
export const SPEECH_LIMITER = Object.freeze({ threshold: -3, knee: 0, ratio: 20, attack: 0.003, release: 0.15 });

/**
 * Builds gain -> limiter -> ctx.destination on an AudioContext (or a fake with the same factory
 * methods) and returns the node the voice should connect to.
 */
export function buildSpeechOutput(ctx, { gain = SPEECH_GAIN, limiter = SPEECH_LIMITER } = {}) {
  const g = ctx.createGain();
  g.gain.value = gain;
  const lim = ctx.createDynamicsCompressor();
  for (const [param, value] of Object.entries(limiter)) lim[param].value = value;
  g.connect(lim);
  lim.connect(ctx.destination);
  return g;
}
