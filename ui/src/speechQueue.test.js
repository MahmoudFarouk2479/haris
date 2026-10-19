// Run with: npm test (in ui/)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { BATCH_GROWTH, SpeechQueue } from './speechQueue.js';

/** The older tests check batching on its own, with no wait for a longer first batch. */
const NO_WAIT = { firstChars: 0 };

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

/** A timer the test fires by hand. */
function fakeTimer() {
  const timers = new Map();
  let next = 1;
  return {
    set: (fn, ms) => { timers.set(next, { fn, ms }); return next++; },
    cancel: (id) => timers.delete(id),
    fire: () => { const all = [...timers.values()]; timers.clear(); all.forEach((t) => t.fn()); },
    get pending() { return [...timers.values()].map((t) => t.ms); },
  };
}

test('with no wait for a longer start, the first sentence is sent at once, on its own', () => {
  const engine = fakeEngine();
  const ready = [];
  const q = new SpeechQueue(engine.request, (audio) => ready.push(audio), 1200, { firstChars: 0 });
  q.push('Hello there.');
  assert.deepEqual(engine.sent, ['Hello there.']);
  assert.equal(ready.length, 1);
});

test('the first request waits until it has enough text, so the next batch is ready before it ends', () => {
  const engine = fakeEngine();
  const timer = fakeTimer();
  const q = new SpeechQueue(engine.request, () => {}, 1200, { firstChars: 40, timer });
  q.push('Okay,');
  q.push('I will not open it.');
  assert.deepEqual(engine.sent, []);
  q.push('Do you want something else?');
  assert.deepEqual(engine.sent, ['Okay, I will not open it. Do you want something else?']);
  assert.deepEqual(timer.pending, []);
});

test('the end of the reply sends a short start at once', () => {
  const engine = fakeEngine();
  const timer = fakeTimer();
  const q = new SpeechQueue(engine.request, () => {}, 1200, { firstChars: 150, timer });
  q.push('Done.');
  assert.deepEqual(engine.sent, []);
  q.flush();
  assert.deepEqual(engine.sent, ['Done.']);
  assert.deepEqual(timer.pending, []);
});

test('a start that waits too long is sent anyway, so speech never stalls', () => {
  const engine = fakeEngine();
  const timer = fakeTimer();
  const q = new SpeechQueue(engine.request, () => {}, 1200, { firstChars: 150, holdMs: 1500, timer });
  q.push('Let me check that.');
  q.push('One moment.');
  assert.deepEqual(timer.pending, [1500]);
  timer.fire();
  assert.deepEqual(engine.sent, ['Let me check that. One moment.']);
});

test('the first batch stops at about the start size and the rest follows in growing batches', async () => {
  const engine = fakeEngine();
  const q = new SpeechQueue(engine.request, () => {}, 1200, { firstChars: 40, timer: fakeTimer() });
  const sentences = Array.from({ length: 6 }, (_, i) => `Sentence number ${i} is here.`);   // 26 chars
  for (const s of sentences) q.push(s);
  assert.deepEqual(engine.sent, [sentences.slice(0, 2).join(' ')]);
  await engine.finish();
  assert.deepEqual(engine.sent.slice(1), [sentences.slice(2).join(' ')]);
});

test('a streamed reply queues its sentences and releases them when it ends or pauses for a tool', () => {
  const main = readFileSync(new URL('./main.js', import.meta.url), 'utf8');
  assert.match(main, /new SentenceSplitter\(\(s\) => voice\.speakPart\(s\)\)/);
  assert.equal(main.match(/speaker\?\.flush\(\);\s*voice\.endSpeech\(\);/g)?.length, 2);
  const voice = readFileSync(new URL('./voice.js', import.meta.url), 'utf8');
  assert.match(voice, /speak\(text\) \{\s*this\.speech\.push\(text\);\s*this\.speech\.flush\(\);/);
});

test('after a barge-in the next reply waits for a full start again', async () => {
  const engine = fakeEngine();
  const timer = fakeTimer();
  const q = new SpeechQueue(engine.request, () => {}, 1200, { firstChars: 40, timer });
  q.push('Short.');
  q.clear();
  assert.deepEqual(timer.pending, []);
  q.push('New.');
  assert.deepEqual(engine.sent, []);
});

test('sentences that arrive while a request runs are merged into one request', async () => {
  const engine = fakeEngine();
  const q = new SpeechQueue(engine.request, () => {}, 1200, NO_WAIT);
  q.push('One.');
  q.push('Two.');
  q.push('Three.');
  assert.deepEqual(engine.sent, ['One.']);
  await engine.finish();
  assert.deepEqual(engine.sent, ['One.', 'Two. Three.']);
});

test('never more than one request at a time', async () => {
  const engine = fakeEngine();
  const q = new SpeechQueue(engine.request, () => {}, 1200, NO_WAIT);
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
  const q = new SpeechQueue(engine.request, (audio) => ready.push(audio), 1200, NO_WAIT);
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
  const q = new SpeechQueue(engine.request, () => {}, 1200, NO_WAIT);
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
  const q = new SpeechQueue(engine.request, () => {}, 1200, NO_WAIT);
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
  const q = new SpeechQueue(engine.request, () => {}, 1200, NO_WAIT);
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
  const q = new SpeechQueue(engine.request, () => {}, 20, NO_WAIT);
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
  const q = new SpeechQueue(engine.request, (audio) => ready.push(audio), 1200, NO_WAIT);
  q.push('Bad.');
  q.push('Good.');
  await engine.finish();
  assert.equal(await ready[0], null);
  await engine.finish();
  assert.equal(await ready[1], 'audio:Good.');
});

test('clear drops what has not been sent yet', async () => {
  const engine = fakeEngine();
  const q = new SpeechQueue(engine.request, () => {}, 1200, NO_WAIT);
  q.push('Keep.');
  q.push('Drop me.');
  q.clear();
  await engine.finish();
  assert.deepEqual(engine.sent, ['Keep.']);
});
