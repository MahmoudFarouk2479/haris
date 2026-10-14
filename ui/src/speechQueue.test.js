// Run with: npm test (in ui/)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { SpeechQueue } from './speechQueue.js';

/** A fake engine whose requests finish only when the test says so. */
function fakeEngine({ fail = [] } = {}) {
  const sent = [];
  const pending = [];
  let inFlight = 0;
  let maxInFlight = 0;
  const request = (text) => {
    sent.push(text);
    inFlight += 1;
    maxInFlight = Math.max(maxInFlight, inFlight);
    return new Promise((resolve, reject) => pending.push(() => {
      inFlight -= 1;
      fail.includes(text) ? reject(new Error('429')) : resolve(`audio:${text}`);
    }));
  };
  const finish = async () => { pending.shift()(); await new Promise((r) => setTimeout(r, 0)); };
  return { request, sent, finish, get maxInFlight() { return maxInFlight; } };
}

test('the first sentence is sent at once, on its own', () => {
  const engine = fakeEngine();
  const ready = [];
  const q = new SpeechQueue(engine.request, (audio) => ready.push(audio));
  q.push('Hello there.');
  assert.deepEqual(engine.sent, ['Hello there.']);
  assert.equal(ready.length, 1);
});

test('sentences that arrive while a request runs are merged into one request', async () => {
  const engine = fakeEngine();
  const q = new SpeechQueue(engine.request, () => {});
  q.push('One.');
  q.push('Two.');
  q.push('Three.');
  assert.deepEqual(engine.sent, ['One.']);
  await engine.finish();
  assert.deepEqual(engine.sent, ['One.', 'Two. Three.']);
});

test('never more than one request at a time', async () => {
  const engine = fakeEngine();
  const q = new SpeechQueue(engine.request, () => {});
  for (const s of ['A.', 'B.', 'C.', 'D.']) q.push(s);
  await engine.finish();
  q.push('E.');
  await engine.finish();
  await engine.finish();
  assert.equal(engine.maxInFlight, 1);
  assert.deepEqual(engine.sent, ['A.', 'B. C. D.', 'E.']);
});

test('a batch never exceeds the size limit', async () => {
  const engine = fakeEngine();
  const q = new SpeechQueue(engine.request, () => {}, 20);
  q.push('First.');
  q.push('Twelve chars');
  q.push('Twelve again');
  await engine.finish();
  await engine.finish();
  assert.deepEqual(engine.sent, ['First.', 'Twelve chars', 'Twelve again']);
  assert.ok(engine.sent.every((t) => t.length <= 20));
});

test('a failed request yields null and the queue keeps going', async () => {
  const engine = fakeEngine({ fail: ['Bad.'] });
  const ready = [];
  const q = new SpeechQueue(engine.request, (audio) => ready.push(audio));
  q.push('Bad.');
  q.push('Good.');
  await engine.finish();
  assert.equal(await ready[0], null);
  await engine.finish();
  assert.equal(await ready[1], 'audio:Good.');
});

test('clear drops what has not been sent yet', async () => {
  const engine = fakeEngine();
  const q = new SpeechQueue(engine.request, () => {});
  q.push('Keep.');
  q.push('Drop me.');
  q.clear();
  await engine.finish();
  assert.deepEqual(engine.sent, ['Keep.']);
});
