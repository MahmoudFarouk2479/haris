import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  CHAT_SIDE_MIN_WIDTH, chatLayoutFor, chatViewFor, mountChatPanel, restoreChatOpen, serializeChatOpen,
} from './chatPanel.js';

test('the conversation becomes a side column from 1200 px wide', () => {
  assert.equal(CHAT_SIDE_MIN_WIDTH, 1200);
  assert.equal(chatLayoutFor(1199), 'stacked');
  assert.equal(chatLayoutFor(1200), 'side');
  assert.equal(chatLayoutFor(3840), 'side');
  assert.equal(chatLayoutFor(900), 'stacked');
});

test('open/closed is stored as "open" or "closed"', () => {
  assert.equal(serializeChatOpen(true), 'open');
  assert.equal(serializeChatOpen(false), 'closed');
});

test('only a stored "closed" starts hidden; anything else starts open', () => {
  assert.equal(restoreChatOpen('closed'), false);
  assert.equal(restoreChatOpen('open'), true);
  for (const raw of [null, undefined, '', 'garbage', 'CLOSED', '{"open":false}', 'false']) {
    assert.equal(restoreChatOpen(raw), true, String(raw));
  }
});

test('the column hides only in the side layout; stacked always shows the conversation', () => {
  assert.deepEqual(chatViewFor(1920, true), { layout: 'side', hidden: false, hideButton: true, tab: false });
  assert.deepEqual(chatViewFor(1920, false), { layout: 'side', hidden: true, hideButton: false, tab: true });
  assert.deepEqual(chatViewFor(1199, false), { layout: 'stacked', hidden: false, hideButton: false, tab: false });
  assert.deepEqual(chatViewFor(1199, true), { layout: 'stacked', hidden: false, hideButton: false, tab: false });
});

// ── A page just big enough for mountChatPanel ─────────────────────────────
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

function fakePage({ width = 1920, stored = null, storage = 'ok' } = {}) {
  const listeners = {};
  const doc = { activeElement: null };
  const el = (id) => {
    const handlers = {};
    const node = {
      id,
      hidden: false,
      attrs: {},
      classList: fakeClassList(),
      setAttribute(k, v) { this.attrs[k] = String(v); },
      getAttribute(k) { return this.attrs[k] ?? null; },
      addEventListener(type, fn) { (handlers[type] ??= []).push(fn); },
      click() { for (const fn of handlers.click ?? []) fn({ target: node }); },
      focus() { doc.activeElement = node; },
    };
    return node;
  };
  const nodes = Object.fromEntries(['chat', 'chat-hide', 'chat-show', 'input', 'transcript'].map((id) => [id, el(id)]));
  Object.assign(doc, {
    body: { classList: fakeClassList() },
    getElementById: (id) => nodes[id] ?? null,
  });
  const saved = { value: stored };
  const localStorage = storage === 'blocked'
    ? { getItem() { throw new Error('blocked'); }, setItem() { throw new Error('blocked'); } }
    : { getItem: () => saved.value, setItem: (k, v) => { saved.key = k; saved.value = v; } };
  const win = {
    innerWidth: width,
    localStorage,
    addEventListener(type, fn) { (listeners[type] ??= []).push(fn); },
    resize(w) {
      this.innerWidth = w;
      for (const fn of listeners.resize ?? []) fn();
    },
  };
  return { doc, win, nodes, saved };
}

test('mount: open by default on a wide window, with the hide button showing', () => {
  const { doc, win, nodes } = fakePage();
  const chat = mountChatPanel({ doc, win });
  assert.equal(chat.open, true);
  assert.ok(doc.body.classList.contains('chat-side'));
  assert.ok(!doc.body.classList.contains('chat-hidden'));
  assert.equal(nodes['chat-hide'].hidden, false);
  assert.equal(nodes['chat-show'].hidden, true);
  assert.equal(nodes['chat-hide'].getAttribute('aria-expanded'), 'true');
  assert.equal(nodes['chat-show'].getAttribute('aria-expanded'), 'true');
});

test('mount: hiding moves focus to the tab, remembers it, and opening focuses the input', () => {
  const { doc, win, nodes, saved } = fakePage();
  const chat = mountChatPanel({ doc, win });
  nodes['chat-hide'].click();
  assert.equal(chat.open, false);
  assert.ok(doc.body.classList.contains('chat-hidden'));
  assert.equal(nodes['chat-show'].hidden, false);
  assert.equal(nodes['chat-hide'].hidden, true);
  assert.equal(nodes['chat-show'].getAttribute('aria-expanded'), 'false');
  assert.equal(doc.activeElement, nodes['chat-show']);
  assert.deepEqual([saved.key, saved.value], ['haris.chat', 'closed']);

  nodes['chat-show'].click();
  assert.equal(chat.open, true);
  assert.ok(!doc.body.classList.contains('chat-hidden'));
  assert.equal(doc.activeElement, nodes.input);
  assert.equal(saved.value, 'open');
});

test('mount: a stored "closed" starts hidden; garbage or blocked storage starts open', () => {
  assert.equal(mountChatPanel(fakePage({ stored: 'closed' })).open, false);
  assert.equal(mountChatPanel(fakePage({ stored: 'nonsense' })).open, true);
  const blocked = fakePage({ storage: 'blocked' });
  const chat = mountChatPanel(blocked);
  assert.equal(chat.open, true);
  blocked.nodes['chat-hide'].click();   // writing fails quietly; the toggle still works
  assert.equal(chat.open, false);
});

test('mount: a narrow window shows the conversation and keeps the remembered choice', () => {
  const page = fakePage({ width: 1100, stored: 'closed' });
  const chat = mountChatPanel(page);
  const { doc, nodes, win, saved } = page;
  assert.ok(!doc.body.classList.contains('chat-side'));
  assert.ok(!doc.body.classList.contains('chat-hidden'));
  assert.equal(nodes['chat-hide'].hidden, true);
  assert.equal(nodes['chat-show'].hidden, true);
  assert.equal(chat.open, false, 'still closed for when the window widens');
  assert.equal(saved.value, 'closed', 'a narrow window does not overwrite the choice');
  win.resize(1400);
  assert.ok(doc.body.classList.contains('chat-side'));
  assert.ok(doc.body.classList.contains('chat-hidden'));
  assert.equal(nodes['chat-show'].hidden, false);
});

test('mount: new messages put a dot on the tab only while hidden; opening clears it', () => {
  const page = fakePage();
  const { nodes } = page;
  const chat = mountChatPanel(page);
  chat.markNew();
  assert.ok(!nodes['chat-show'].classList.contains('news'), 'no dot while the column is open');
  nodes['chat-hide'].click();
  chat.markNew();
  assert.ok(nodes['chat-show'].classList.contains('news'));
  nodes['chat-show'].click();
  assert.ok(!nodes['chat-show'].classList.contains('news'));
});

test('mount: the transcript is revealed (for scrolling down) on open and on a layout switch', () => {
  const page = fakePage({ width: 1920 });
  let reveals = 0;
  mountChatPanel({ ...page, onReveal: () => { reveals += 1; } });
  assert.equal(reveals, 1, 'initial render');
  page.win.resize(1910);
  assert.equal(reveals, 1, 'same layout, already visible: nothing to do');
  page.nodes['chat-hide'].click();
  assert.equal(reveals, 1);
  page.nodes['chat-show'].click();
  assert.equal(reveals, 2, 'opened');
  page.win.resize(1000);
  assert.equal(reveals, 3, 'side -> stacked');
});

test('mount: a focused tab that disappears on narrowing hands focus to the input', () => {
  const page = fakePage({ stored: 'closed' });
  mountChatPanel(page);
  page.nodes['chat-show'].focus();
  page.win.resize(1000);
  assert.equal(page.nodes['chat-show'].hidden, true);
  assert.equal(page.doc.activeElement, page.nodes.input);
});

test('mount: Esc is not wired to the chat', () => {
  const page = fakePage();
  const chat = mountChatPanel(page);
  assert.equal(page.doc.addEventListener, undefined, 'mountChatPanel adds no document key handler');
  assert.equal(chat.open, true);
});

test('index.html has the column header, hide button and tab, both controlling the column', () => {
  const html = readFileSync(new URL('../index.html', import.meta.url), 'utf8');
  assert.match(html, /<section class="console" id="chat"/);
  for (const id of ['chat-hide', 'chat-show']) {
    const tag = html.match(new RegExp(`<button[^>]*id="${id}"[^>]*>`))?.[0];
    assert.ok(tag, `missing #${id}`);
    assert.match(tag, /type="button"/);
    assert.match(tag, /aria-controls="chat"/);
    assert.match(tag, /aria-expanded=/);
    assert.match(tag, /data-i18n-title="chat\.(hide|show)"/);
    assert.match(tag, /data-i18n-aria="chat\.(hide|show)"/);
  }
  assert.match(html, /data-i18n="chat\.title"/);
  assert.match(html, /data-i18n="chat\.tab"/);
});
