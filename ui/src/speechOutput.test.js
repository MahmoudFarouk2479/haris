// Run with: npm test (in ui/)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { SPEECH_GAIN, SPEECH_LIMITER, buildSpeechOutput } from './speechOutput.js';

/** A minimal AudioContext: nodes record their parameters and connections. */
function fakeContext() {
  const param = () => ({ value: NaN });
  const node = (kind, params) => {
    const n = { kind, out: [], connect(to) { n.out.push(to); return to; } };
    for (const p of params) n[p] = param();
    return n;
  };
  const destination = { kind: 'destination', out: [] };
  return {
    destination,
    createGain: () => node('gain', ['gain']),
    createDynamicsCompressor: () => node('limiter', ['threshold', 'knee', 'ratio', 'attack', 'release']),
  };
}

test('speech goes gain -> limiter -> speakers, and the voice connects to the gain', () => {
  const ctx = fakeContext();
  const input = buildSpeechOutput(ctx);
  assert.equal(input.kind, 'gain');
  assert.equal(input.gain.value, SPEECH_GAIN);
  assert.equal(input.out.length, 1);
  const limiter = input.out[0];
  assert.equal(limiter.kind, 'limiter');
  assert.deepEqual(limiter.out, [ctx.destination]);
});

test('the limiter is a limiter: hard knee near -3 dB, high ratio, fast attack, short release', () => {
  const limiter = buildSpeechOutput(fakeContext()).out[0];
  for (const [p, v] of Object.entries(SPEECH_LIMITER)) assert.equal(limiter[p].value, v, p);
  assert.ok(limiter.threshold.value <= -2 && limiter.threshold.value >= -6);
  assert.equal(limiter.knee.value, 0);
  assert.ok(limiter.ratio.value >= 12);
  assert.ok(limiter.attack.value <= 0.005);
  assert.ok(limiter.release.value >= 0.05 && limiter.release.value <= 0.3);
});

test('a full-scale peak leaves the chain below 0 dBFS, make-up gain included', () => {
  // Web Audio spec: make-up gain = (1 / gain of the curve at 0 dBFS) ^ 0.6.
  const { threshold, ratio } = SPEECH_LIMITER;
  const curve = (db) => (db <= threshold ? db : threshold + (db - threshold) / ratio);
  const makeupDb = -curve(0) * 0.6;
  const out = (raw) => curve(20 * Math.log10(raw * SPEECH_GAIN)) + makeupDb;
  assert.ok(out(1) < -1.3, `full-scale peak ${out(1)} dBFS`);
  assert.ok(out(1.2) < -1.1, `overshooting peak ${out(1.2)} dBFS`);   // resampling overshoot
  assert.equal(SPEECH_GAIN, 0.7);
});
