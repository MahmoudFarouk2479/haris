// Run with: npm test (in ui/)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { BATCH_GROWTH, SpeechQueue } from './speechQueue.js';

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
  // 'A.' alone; then at most 3 x 2 chars ('B. C.'); 'E.' arrived meanwhile and joins 'D.'.
  assert.deepEqual(engine.sent, ['A.', 'B. C.', 'D. E.']);
});

test('batches grow step by step instead of the whole rest of the reply after the first sentence', async () => {
  const engine = fakeEngine();
  const ready = [];
  const q = new SpeechQueue(engine.request, (audio) => ready.push(audio));
  const sentences = Array.from({ length: 20 }, (_, i) => `Sentence number ${String(i).padStart(2, '0')} is here.`);   // 27 chars
  for (const s of sentences) q.push(s);
  while (engine.sent.join(' ').length < sentences.join(' ').length) await engine.finish();
  await engine.finish();
  // Every sentence is spoken once, in order.
  assert.equal(engine.sent.join(' '), sentences.join(' '));
  assert.equal(engine.maxInFlight, 1);
  assert.deepEqual(engine.sent.map((t) => t.split(' Sentence').length), [1, 2, 5, 12]);
  for (let i = 1; i < engine.sent.length; i++) {
    assert.ok(engine.sent[i].length <= engine.sent[i - 1].length * BATCH_GROWTH, `batch ${i}`);
  }
  // Each request's audio reaches the player in the order the requests were sent.
  assert.deepEqual(await Promise.all(ready), engine.sent.map((t) => `audio:${t}`));
});

test('the next request is sent as soon as the one before returns, while that audio still plays', async () => {
  const engine = fakeEngine();
  const q = new SpeechQueue(engine.request, () => {});
  q.push('First part, ');
  q.push('then the second sentence.');
  q.push('And a third one.');
  assert.deepEqual(engine.sent, ['First part,']);
  await engine.finish();   // the first audio is ready (and starts playing): the next batch is already on its way
  assert.equal(engine.sent.length, 2);
  assert.equal(engine.maxInFlight, 1);
});

test('a new reply starts small again once the queue has gone idle', async () => {
  const engine = fakeEngine();
  const q = new SpeechQueue(engine.request, () => {});
  q.push('A long first sentence that sets a high limit.');
  await engine.finish();
  q.push('Next.');
  q.push('Reply.');
  assert.deepEqual(engine.sent.slice(1), ['Next.']);
  await engine.finish();
  assert.deepEqual(engine.sent.slice(2), ['Reply.']);
});

test('after a barge-in the next reply starts with its first sentence alone', async () => {
  const engine = fakeEngine();
  const q = new SpeechQueue(engine.request, () => {});
  q.push('Old reply sentence one.');
  q.push('Old two.');
  q.clear();
  q.push('New.');
  q.push('New two.');
  await engine.finish();
  assert.deepEqual(engine.sent, ['Old reply sentence one.', 'New.']);
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
