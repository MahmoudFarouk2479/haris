import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  CLOSED, DOCKED_MIN_WIDTH, PIN_MIN_WIDTH, drawerReducer, escapeClosesDrawer, isPinned, restoreDrawerState, serializeDrawerState,
} from './drawers.js';
import { CHAT_SIDE_MIN_WIDTH } from './chatPanel.js';

const WIDE = { width: 1920 };
const SMALL = { width: 1366 };
const NARROW = { width: 1100 };

test('only one drawer is open at a time, and toggling the open one closes it', () => {
  let s = drawerReducer(CLOSED, { type: 'toggle', id: 'system' }, WIDE);
  assert.equal(s.open, 'system');
  s = drawerReducer(s, { type: 'toggle', id: 'voice' }, WIDE);
  assert.equal(s.open, 'voice');
  s = drawerReducer(s, { type: 'toggle', id: 'voice' }, WIDE);
  assert.deepEqual(s, CLOSED);
});

test('unknown drawers are ignored', () => {
  assert.equal(drawerReducer(CLOSED, { type: 'toggle', id: 'nope' }, WIDE), CLOSED);
});

test('pinning works only on wide windows', () => {
  const open = drawerReducer(CLOSED, { type: 'toggle', id: 'system' }, SMALL);
  assert.equal(drawerReducer(open, { type: 'pin' }, SMALL), open);
  const pinned = drawerReducer(open, { type: 'pin' }, { width: PIN_MIN_WIDTH });
  assert.equal(pinned.pinned, true);
  assert.equal(drawerReducer(pinned, { type: 'pin' }, WIDE).pinned, false);
});

test('below 1200 px a drawer floats over the stage, so an outside click closes it', () => {
  const open = drawerReducer(CLOSED, { type: 'toggle', id: 'activity' }, NARROW);
  assert.deepEqual(drawerReducer(open, { type: 'outside' }, NARROW), CLOSED);
  assert.deepEqual(drawerReducer(open, { type: 'outside' }, { width: DOCKED_MIN_WIDTH - 1 }), CLOSED);
});

test('from 1200 px an open drawer has its own place, so an outside click leaves it open (no jump under the pointer)', () => {
  for (const at of [{ width: DOCKED_MIN_WIDTH }, SMALL, { width: 1536 }, WIDE]) {
    const open = drawerReducer(CLOSED, { type: 'toggle', id: 'activity' }, at);
    assert.equal(drawerReducer(open, { type: 'outside' }, at), open, String(at.width));
    // It still closes with its rail icon and with Esc (its close button sends 'escape').
    assert.deepEqual(drawerReducer(open, { type: 'toggle', id: 'activity' }, at), CLOSED);
    assert.deepEqual(drawerReducer(open, { type: 'escape' }, at), CLOSED);
  }
});

test('drawers dock at the width where the conversation becomes a side column', () => {
  assert.equal(DOCKED_MIN_WIDTH, CHAT_SIDE_MIN_WIDTH);
});

test('a pinned drawer is pinned only from 1600 px, and an outside click never closes it', () => {
  const pinned = drawerReducer(drawerReducer(CLOSED, { type: 'toggle', id: 'system' }, WIDE), { type: 'pin' }, WIDE);
  assert.equal(drawerReducer(pinned, { type: 'outside' }, WIDE), pinned);
  assert.equal(isPinned(pinned, 1366), false);
  assert.equal(drawerReducer(pinned, { type: 'outside' }, SMALL), pinned);   // docked, not pinned
  assert.deepEqual(drawerReducer(pinned, { type: 'outside' }, NARROW), CLOSED);   // floating
  assert.equal(isPinned(pinned, 1920), true);
});

test('switching drawers on a wide window keeps the pin', () => {
  const pinned = drawerReducer(drawerReducer(CLOSED, { type: 'toggle', id: 'system' }, WIDE), { type: 'pin' }, WIDE);
  const next = drawerReducer(pinned, { type: 'toggle', id: 'security' }, WIDE);
  assert.deepEqual(next, { open: 'security', pinned: true });
});

test('Esc closes whatever is open', () => {
  const pinned = drawerReducer(drawerReducer(CLOSED, { type: 'toggle', id: 'voice' }, WIDE), { type: 'pin' }, WIDE);
  assert.deepEqual(drawerReducer(pinned, { type: 'escape' }, WIDE), CLOSED);
  assert.equal(drawerReducer(CLOSED, { type: 'escape' }, WIDE), CLOSED);
});

test('Esc does not close a drawer while a dialog is open', () => {
  assert.equal(escapeClosesDrawer({ querySelector: () => ({}) }), false);
  assert.equal(escapeClosesDrawer({ querySelector: () => null }), true);
});

test('only a pinned drawer is remembered, and restored', () => {
  assert.equal(serializeDrawerState({ open: 'voice', pinned: false }), '');
  const saved = serializeDrawerState({ open: 'voice', pinned: true });
  assert.deepEqual(restoreDrawerState(saved), { open: 'voice', pinned: true });
});

test('a drawer pinned on a wide screen starts closed in a narrow window', () => {
  const saved = serializeDrawerState({ open: 'voice', pinned: true });
  assert.deepEqual(restoreDrawerState(saved, PIN_MIN_WIDTH - 1), CLOSED);
  assert.deepEqual(restoreDrawerState(saved, PIN_MIN_WIDTH), { open: 'voice', pinned: true });
});

test('a broken stored value falls back to closed', () => {
  for (const raw of [null, '', 'not json', '{"pinned":"nope"}', '42', '{"pinned":null}']) {
    assert.deepEqual(restoreDrawerState(raw), CLOSED, String(raw));
  }
});

// ── A page just big enough for mountDrawers ───────────────────────────────
function fakeClassList() {
  const set = new Set();
  return {
    set,
    add: (c) => set.add(c),
    remove: (c) => set.delete(c),
    contains: (c) => set.has(c),
    toggle(c, on) {
      const want = on === undefined ? !set.has(c) : Boolean(on);
      if (want) set.add(c);
      else set.delete(c);
      return want;
    },
  };
}

function fakePage(width) {
  const docListeners = {};
  const node = (props = {}) => {
    const handlers = {};
    return {
      hidden: false,
      attrs: {},
      dataset: {},
      classList: fakeClassList(),
      setAttribute(k, v) { this.attrs[k] = String(v); },
      addEventListener(type, fn) { (handlers[type] ??= []).push(fn); },
      click() { for (const fn of handlers.click ?? []) fn({ stopPropagation() {} }); },
      contains: () => false,
      focus() {},
      closest: () => null,
      ...props,
    };
  };
  const drawers = Object.fromEntries(DRAWERS_UNDER_TEST.map((id) => [id, node()]));
  const buttons = Object.fromEntries(DRAWERS_UNDER_TEST.map((id) => [id, node({ dataset: { drawer: id } })]));
  const doc = {
    activeElement: null,
    body: { classList: fakeClassList() },
    getElementById: (id) => drawers[id.replace('drawer-', '')] ?? null,
    querySelector(sel) {
      const m = sel.match(/^\[data-drawer="(\w+)"\]$/);
      return m ? buttons[m[1]] : null;
    },
    querySelectorAll: (sel) => (sel === '[data-drawer]' ? Object.values(buttons) : []),
    addEventListener(type, fn) { (docListeners[type] ??= []).push(fn); },
  };
  const win = {
    innerWidth: width,
    localStorage: { getItem: () => null, setItem() {} },
    addEventListener() {},
  };
  const outside = () => {
    for (const fn of docListeners.pointerdown ?? []) fn({ target: node() });
  };
  return { doc, win, buttons, drawers, outside };
}

const DRAWERS_UNDER_TEST = ['system', 'activity', 'voice', 'security'];

test('the page: an open drawer sets body.drawer-open, and an outside click keeps it from 1200 px', async () => {
  const { mountDrawers } = await import('./drawers.js');
  const page = fakePage(1536);
  mountDrawers({ doc: page.doc, win: page.win });
  assert.equal(page.doc.body.classList.contains('drawer-open'), false);
  page.buttons.voice.click();
  assert.equal(page.drawers.voice.hidden, false);
  assert.equal(page.doc.body.classList.contains('drawer-open'), true);
  page.outside();
  assert.equal(page.drawers.voice.hidden, false);
  assert.equal(page.doc.body.classList.contains('drawer-open'), true);
  page.buttons.voice.click();
  assert.equal(page.drawers.voice.hidden, true);
  assert.equal(page.doc.body.classList.contains('drawer-open'), false);
});

test('the page: below 1200 px an outside click closes the floating drawer', async () => {
  const { mountDrawers } = await import('./drawers.js');
  const page = fakePage(1000);
  mountDrawers({ doc: page.doc, win: page.win });
  page.buttons.system.click();
  assert.equal(page.doc.body.classList.contains('drawer-open'), true);
  page.outside();
  assert.equal(page.drawers.system.hidden, true);
  assert.equal(page.doc.body.classList.contains('drawer-open'), false);
});
