import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  CLOSED, PIN_MIN_WIDTH, drawerReducer, escapeClosesDrawer, isPinned, restoreDrawerState, serializeDrawerState,
} from './drawers.js';

const WIDE = { width: 1920 };
const SMALL = { width: 1366 };

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

test('an outside click closes a floating drawer but not a pinned one', () => {
  const open = drawerReducer(CLOSED, { type: 'toggle', id: 'activity' }, WIDE);
  assert.deepEqual(drawerReducer(open, { type: 'outside' }, WIDE), CLOSED);
  const pinned = drawerReducer(open, { type: 'pin' }, WIDE);
  assert.equal(drawerReducer(pinned, { type: 'outside' }, WIDE), pinned);
});

test('a pinned drawer floats below 1600 px and is pinned again above', () => {
  const pinned = drawerReducer(drawerReducer(CLOSED, { type: 'toggle', id: 'system' }, WIDE), { type: 'pin' }, WIDE);
  assert.equal(isPinned(pinned, 1366), false);
  assert.deepEqual(drawerReducer(pinned, { type: 'outside' }, SMALL), CLOSED);
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
