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

function fakeNode(name, ns) {
  return {
    name, ns, attrs: {}, children: [], dataset: {}, styleVars: {},
    setAttribute(k, v) { this.attrs[k] = v; },
    appendChild(c) { this.children.push(c); return c; },
    style: { setProperty(k, v) { this.owner.styleVars[k] = v; } },
  };
}
const fakeDoc = {
  createElementNS(ns, name) {
    const n = fakeNode(name, ns);
    n.style.owner = n;
    return n;
  },
};
const HTML = 'http://www.w3.org/1999/xhtml';
const SVG = 'http://www.w3.org/2000/svg';

function drawn() {
  const container = fakeNode('div', HTML);
  container.style.owner = container;
  const emblem = createEmblem(container, fakeDoc);
  const all = [];
  const walk = (n) => { all.push(n); n.children.forEach(walk); };
  walk(container);
  return { container, emblem, all };
}
const classOf = (n) => n.attrs.class ?? '';
const layer = (all, name) => all.find((n) => n.name === 'div' && classOf(n).split(' ').includes(name));

test('createEmblem draws two four-square stars inside one hidden wrapper', () => {
  const { container, all } = drawn();
  assert.equal(all.filter((n) => n.name === 'rect').length, 4);
  assert.equal(container.children.length, 1);
  const root = container.children[0];
  assert.equal(root.name, 'div');
  assert.equal(root.ns, HTML);
  assert.equal(classOf(root), 'emblem');
  assert.equal(root.attrs['aria-hidden'], 'true');
  for (const svg of all.filter((n) => n.name === 'svg')) {
    assert.equal(svg.ns, SVG);
    assert.equal(svg.attrs.viewBox, '-150 -150 300 300');
    assert.equal(svg.attrs.focusable, 'false');
  }
});

test('every moving part is its own HTML layer, stacked like the old single SVG', () => {
  const { container } = drawn();
  const root = container.children[0];
  // Animations on HTML boxes run on the compositor; inside an SVG they repaint the whole star every frame.
  assert.deepEqual(root.children.map(classOf),
    ['emblem-layer halo', 'emblem-layer layer outer', 'emblem-layer pulse-wrap', 'emblem-layer layer inner', 'emblem-layer core']);
  for (const div of root.children) assert.equal(div.ns, HTML);

  const { all } = drawn();
  const inside = (n) => { const found = []; const w = (x) => { found.push(x); x.children.forEach(w); }; w(n); return found; };
  const rectsIn = (n) => inside(n).filter((x) => x.name === 'rect').length;
  const outer = layer(all, 'outer');
  const inner = layer(all, 'inner');
  assert.equal(rectsIn(outer), 2);
  assert.equal(rectsIn(inner), 2);
  assert.ok(inside(inner).some((x) => classOf(x) === 'star gold'));
  assert.equal(classOf(layer(all, 'pulse-wrap').children[0]), 'emblem-layer pulse');
  assert.ok(inside(layer(all, 'halo')).some((x) => x.attrs.fill === 'url(#emblem-glow)'));
});

test('createEmblem sets state and level on the container', () => {
  const { container, emblem } = drawn();
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
