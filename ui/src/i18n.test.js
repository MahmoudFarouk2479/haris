import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { STRINGS, applyLanguage, brandFor, messageHeaderFor, relabelMessages, setLanguage, speakerFor, t } from './i18n.js';

const HUD_STATES = ['idle', 'thinking', 'speaking', 'acting', 'waiting', 'error', 'listening', 'transcribing'];

test('both languages have exactly the same keys, and none is empty', () => {
  assert.deepEqual(Object.keys(STRINGS.ar).sort(), Object.keys(STRINGS.en).sort());
  for (const lang of ['en', 'ar']) {
    for (const [key, value] of Object.entries(STRINGS[lang])) assert.ok(value.trim(), `${lang}.${key} is empty`);
  }
});

test('every HUD state has a label', () => {
  for (const s of HUD_STATES) assert.ok(STRINGS.en[`state.${s}`], s);
});

test('t interpolates, falls back to English, then to the key', () => {
  setLanguage('en');
  assert.equal(t('sec.toolsCount', { n: 25 }), '25 allow-listed');
  setLanguage('ar');
  const saved = STRINGS.ar['brand.tagline'];
  delete STRINGS.ar['brand.tagline'];
  try {
    assert.equal(t('brand.tagline'), STRINGS.en['brand.tagline']);
  } finally {
    STRINGS.ar['brand.tagline'] = saved;
  }
  assert.equal(t('no.such.key'), 'no.such.key');
  setLanguage('en');
});

test('an unknown language falls back to English', () => {
  assert.equal(setLanguage('fr'), 'en');
});

/** A document just big enough for applyLanguage. */
function fakeDocument(elements) {
  const root = { attrs: {}, setAttribute(k, v) { this.attrs[k] = v; } };
  return {
    documentElement: root,
    querySelectorAll(selector) {
      const attr = selector.slice(1, -1);
      return elements.filter((e) => attr in e.attrs);
    },
  };
}
function fakeElement(attrs) {
  return {
    attrs: { ...attrs },
    textContent: '',
    getAttribute(k) { return this.attrs[k] ?? null; },
    setAttribute(k, v) { this.attrs[k] = v; },
  };
}

test('applyLanguage switches direction and fills text and attributes', () => {
  const label = fakeElement({ 'data-i18n': 'rail.system' });
  const input = fakeElement({ 'data-i18n-placeholder': 'composer.placeholder' });
  const button = fakeElement({ 'data-i18n-title': 'rail.settings', 'data-i18n-aria': 'rail.settings' });
  const picture = fakeElement({ 'data-i18n-alt': 'confirm.previewAlt' });
  const doc = fakeDocument([label, input, button, picture]);

  assert.equal(applyLanguage('ar', doc), 'ar');
  assert.equal(doc.documentElement.attrs.dir, 'rtl');
  assert.equal(doc.documentElement.attrs.lang, 'ar');
  assert.equal(label.textContent, STRINGS.ar['rail.system']);
  assert.equal(input.attrs.placeholder, STRINGS.ar['composer.placeholder']);
  assert.equal(button.attrs.title, STRINGS.ar['rail.settings']);
  assert.equal(button.attrs['aria-label'], STRINGS.ar['rail.settings']);
  assert.equal(picture.attrs.alt, STRINGS.ar['confirm.previewAlt']);

  applyLanguage('en', doc);
  assert.equal(doc.documentElement.attrs.dir, 'ltr');
  assert.equal(label.textContent, 'System');
});

test('brandFor keeps a custom name, Latin or Arabic, in both languages', () => {
  for (const lang of ['en', 'ar']) {
    setLanguage(lang);
    assert.equal(brandFor('Haris'), STRINGS[lang]['brand.name']);
    assert.equal(brandFor(''), STRINGS[lang]['brand.name']);
    assert.equal(brandFor('Friday'), 'F.R.I.D.A.Y.');
    assert.equal(brandFor('نور'), 'نور');
    assert.equal(speakerFor('haris'), STRINGS[lang]['brand.short'].toUpperCase());
    assert.equal(speakerFor('Friday'), 'FRIDAY');
  }
  setLanguage('en');
});

test('every key index.html uses exists', () => {
  const html = readFileSync(new URL('../index.html', import.meta.url), 'utf8');
  for (const [, key] of html.matchAll(/data-i18n(?:-title|-placeholder|-aria|-alt)?="([^"]+)"/g)) {
    assert.ok(STRINGS.en[key], `index.html uses missing key ${key}`);
  }
});

test("every literal t('…') key main.js uses exists", () => {
  const js = readFileSync(new URL('./main.js', import.meta.url), 'utf8');
  for (const [, key] of js.matchAll(/\bt\(\s*'([^']+)'/g)) {
    assert.ok(STRINGS.en[key], `main.js uses missing key ${key}`);
  }
});

test('every key main.js passes to setLabel exists', () => {
  const js = readFileSync(new URL('./main.js', import.meta.url), 'utf8');
  const calls = [...js.matchAll(/\bsetLabel\(\$\('[^']+'\),([^;]+)\);/g)];
  assert.ok(calls.length >= 4, 'the session, mic and autostart labels keep their keys');
  for (const [, args] of calls) {
    for (const [, key] of args.matchAll(/'([^']+)'/g)) assert.ok(STRINGS.en[key], `main.js uses missing key ${key}`);
  }
});

test('message headers follow the interface language; the bodies do not change', () => {
  setLanguage('en');
  const roles = ['user', 'assistant', 'notice', 'error', 'reminder'];
  const messages = roles.map((role) => {
    const header = fakeElement({ 'data-role': role });
    header.textContent = messageHeaderFor(role, 'Haris');
    return { header, body: { textContent: `said by ${role}: hello / مرحبا` } };
  });
  assert.deepEqual(messages.map((m) => m.header.textContent),
    [STRINGS.en['who.you'], STRINGS.en['brand.short'].toUpperCase(), STRINGS.en['who.system'], STRINGS.en['who.system'], STRINGS.en['who.reminder']]);
  const transcript = {
    querySelectorAll(selector) {
      assert.equal(selector, 'header[data-role]');
      return messages.map((m) => m.header);
    },
  };

  setLanguage('ar');
  relabelMessages(transcript, 'Haris');
  assert.deepEqual(messages.map((m) => m.header.textContent),
    [STRINGS.ar['who.you'], STRINGS.ar['brand.short'].toUpperCase(), STRINGS.ar['who.system'], STRINGS.ar['who.system'], STRINGS.ar['who.reminder']]);
  messages.forEach((m, i) => assert.equal(m.body.textContent, `said by ${roles[i]}: hello / مرحبا`));

  setLanguage('en');
  relabelMessages(transcript, 'Friday');
  assert.equal(messages[0].header.textContent, 'YOU');
  assert.equal(messages[1].header.textContent, 'FRIDAY');
});

test('main.js tags each message header with its role and re-labels them on a language switch', () => {
  const js = readFileSync(new URL('./main.js', import.meta.url), 'utf8');
  assert.match(js, /who\.dataset\.role = role;/);
  // The whole body of applyUi (up to its closing brace at the start of a line), so a block with
  // braces inside it does not hide the call.
  const start = js.indexOf('function applyUi(s) {');
  assert.ok(start >= 0);
  const body = js.slice(start, start + js.slice(start).search(/\n\}/));
  assert.match(body, /relabelMessages\(transcript, assistantName\)/);
});
