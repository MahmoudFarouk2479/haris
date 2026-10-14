import { test } from 'node:test';
import assert from 'node:assert/strict';
import { EMBLEM_STATES, createEmblem, emblemStateFor } from './emblem.js';

test('every HUD state maps to an emblem state', () => {
  const expected = {
    idle: 'idle', listening: 'listening', thinking: 'thinking', transcribing: 'thinking', acting: 'thinking',
    speaking: 'speaking', waiting: 'waiting', error: 'error',
  };
  for (const [hud, emblem] of Object.entries(expected)) assert.equal(emblemStateFor(hud), emblem, hud);
  for (const s of Object.values(expected)) assert.ok(EMBLEM_STATES.includes(s));
});

test('an unknown state shows the idle emblem', () => {
  assert.equal(emblemStateFor('dancing'), 'idle');
  assert.equal(emblemStateFor(undefined), 'idle');
});

function fakeNode(name) {
  return {
    name, attrs: {}, children: [], dataset: {}, styleVars: {},
    setAttribute(k, v) { this.attrs[k] = v; },
    appendChild(c) { this.children.push(c); return c; },
    style: { setProperty(k, v) { this.owner.styleVars[k] = v; } },
  };
}
const fakeDoc = {
  createElementNS(ns, name) {
    const n = fakeNode(name);
    n.style.owner = n;
    return n;
  },
};

test('createEmblem draws two four-square stars and sets state and level on the container', () => {
  const container = fakeNode('div');
  container.style.owner = container;
  const emblem = createEmblem(container, fakeDoc);

  const all = [];
  const walk = (n) => { all.push(n); n.children.forEach(walk); };
  walk(container);
  assert.equal(all.filter((n) => n.name === 'rect').length, 4);
  assert.equal(all[1].name, 'svg');
  assert.equal(all[1].attrs['aria-hidden'], 'true');
  assert.equal(container.dataset.emblem, 'idle');

  emblem.setState('waiting');
  assert.equal(container.dataset.emblem, 'waiting');
  emblem.setState('acting');
  assert.equal(container.dataset.emblem, 'thinking');

  emblem.setLevel(0.5);
  assert.equal(container.styleVars['--level'], '0.500');
  emblem.setLevel(7);
  assert.equal(container.styleVars['--level'], '1.000');
  emblem.setLevel('junk');
  assert.equal(container.styleVars['--level'], '0.000');
});
