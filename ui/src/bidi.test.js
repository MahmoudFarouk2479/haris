// Run with: npm test (in ui/)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { bidiParts, setBidiText } from './bidi.js';
import { STRINGS } from './i18n.js';

const STATUS_KEYS = ['voice.online', 'voice.onlineGpu', 'voice.offline', 'voice.colab', 'voice.colabBackup',
  'voice.gemini', 'voice.geminiBackup', 'voice.geminiPaused', 'voice.noWakeModel'];

test('the Arabic "Gemini (Piper backup)" isolates both engine names, so RTL keeps the written order', () => {
  assert.deepEqual(bidiParts(STRINGS.ar['voice.geminiBackup']), [
    { text: '● ', latin: false },
    { text: 'Gemini', latin: true },
    { text: ' (', latin: false },
    { text: 'Piper', latin: true },
    { text: ' احتياطي)', latin: false },
  ]);
});

test('every voice status, in both languages, has each Latin run isolated and loses no text', () => {
  for (const lang of ['en', 'ar']) {
    for (const key of STATUS_KEYS) {
      const text = STRINGS[lang][key].replace('{time}', '02:59');
      const parts = bidiParts(text);
      assert.equal(parts.map((p) => p.text).join(''), text, `${lang}.${key}`);
      for (const p of parts.filter((x) => !x.latin)) assert.doesNotMatch(p.text, /[A-Za-z]/, `${lang}.${key}: "${p.text}"`);
    }
  }
  const latin = (s) => bidiParts(s).filter((p) => p.latin).map((p) => p.text);
  assert.deepEqual(latin(STRINGS.ar['voice.onlineGpu']), ['GPU']);
  assert.deepEqual(latin(STRINGS.ar['voice.colabBackup']), ['Colab']);
  assert.deepEqual(latin(STRINGS.ar['voice.geminiPaused'].replace('{time}', '02:59')), ['Piper', 'Gemini']);
  assert.deepEqual(latin(STRINGS.ar['voice.noWakeModel']), ['hey haris']);
  assert.deepEqual(latin(STRINGS.ar['voice.online']), []);
});

test('setBidiText builds text nodes and <bdi> elements, replacing what was there', () => {
  const made = [];
  const doc = {
    createTextNode: (text) => ({ kind: 'text', text }),
    createElement: (tag) => {
      const n = { kind: tag, textContent: '' };
      made.push(n);
      return n;
    },
  };
  let children = ['old'];
  const el = { ownerDocument: doc, replaceChildren: (...c) => { children = c; } };
  setBidiText(el, '● Gemini (Piper احتياطي)');
  assert.deepEqual(children.map((c) => c.kind), ['text', 'bdi', 'text', 'bdi', 'text']);
  assert.deepEqual(made.map((n) => n.textContent), ['Gemini', 'Piper']);
});

test('voice statuses are set through setBidiText, never innerHTML', () => {
  const main = readFileSync(new URL('./main.js', import.meta.url), 'utf8');
  const fn = main.slice(main.indexOf('async function refreshVoiceStatus'), main.indexOf('async function applyVoiceSettings'));
  assert.match(fn, /setBidiText\(\$\(id\), text\)/);
  assert.doesNotMatch(fn, /innerHTML|\.textContent =/);
  assert.doesNotMatch(readFileSync(new URL('./bidi.js', import.meta.url), 'utf8'), /innerHTML\s*=/);
});
