import { test } from 'node:test';
import assert from 'node:assert/strict';
import { briefingQuietMs, deliverBriefing, whenIdle } from './briefing.js';

/** A virtual clock: sleep(ms) moves time on at once, and the page's state is a function of time. */
function clock() {
  const c = { now: 0, log: [] };
  c.sleep = async (ms) => {
    c.now += ms;
  };
  return c;
}

const PROMPT = { prompt: 'Daily briefing, please.' };

test('waits until the page has been idle for the whole quiet period', async () => {
  const c = clock();
  // Busy (the status report is being spoken) until 5 s, idle after.
  await whenIdle(() => c.now >= 5000, { sleep: c.sleep, quietMs: () => 2000 });
  assert.ok(c.now >= 7000, `returned at ${c.now}`);
  assert.ok(c.now < 7600, `returned at ${c.now}`);
});

test('a moment of activity starts the quiet period again', async () => {
  const c = clock();
  const busyAt = (t) => t < 1000 || (t >= 2500 && t < 3000);
  await whenIdle(() => !busyAt(c.now), { sleep: c.sleep, quietMs: () => 2000 });
  assert.ok(c.now >= 5000, `returned at ${c.now}`);
});

test('the briefing is claimed only when it can start, and sent at once', async () => {
  const c = clock();
  const calls = [];
  const sent = await deliverBriefing({
    claim: async () => { calls.push(`claim@${c.now}`); return PROMPT; },
    send: (p) => calls.push(`send@${c.now}:${p}`),
    isIdle: () => c.now >= 4000,   // the status report finishes speaking at 4 s
    sleep: c.sleep,
    quietMs: () => 2000,
  });
  assert.equal(sent, true);
  assert.equal(calls.length, 2);
  assert.match(calls[0], /^claim@6\d{3}$/);
  assert.equal(calls[1], `send@${calls[0].slice(6)}:Daily briefing, please.`);
});

test('never sent into the user\'s own turn: a turn that starts during the claim is waited out', async () => {
  const c = clock();
  let userTurn = false;
  const calls = [];
  await deliverBriefing({
    claim: async () => {
      userTurn = true;   // the user pressed Enter while the claim was on its way
      calls.push('claim');
      return PROMPT;
    },
    send: () => calls.push(`send@${c.now}`),
    isIdle: () => {
      if (userTurn && c.now >= 10_000) userTurn = false;   // their reply finished at 10 s
      return !userTurn;
    },
    sleep: c.sleep,
    quietMs: () => 2000,
  });
  assert.equal(calls[0], 'claim');
  assert.ok(Number(calls[1].slice(5)) >= 12_000, calls[1]);
});

test('no briefing due (or the claim failed): nothing is sent', async () => {
  const c = clock();
  for (const claim of [async () => null, async () => ({}), async () => { throw new Error('offline'); }]) {
    let sent = false;
    const result = await deliverBriefing({ claim, send: () => { sent = true; }, isIdle: () => true, sleep: c.sleep });
    assert.equal(result, false);
    assert.equal(sent, false);
  }
});

test('once the user is talking to Haris, the briefing waits for a real lull', () => {
  assert.equal(briefingQuietMs(false), 2000);
  assert.ok(briefingQuietMs(true) >= 30_000);
});
