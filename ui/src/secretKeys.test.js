import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { KEY_SLOTS, removeKey } from './secretKeys.js';
import { STRINGS, setLanguage, t } from './i18n.js';

const html = readFileSync(new URL('../index.html', import.meta.url), 'utf8');

function fakeApi() {
  const calls = [];
  const api = async (path, options) => {
    calls.push({ path, ...options });
    return { secrets: { anthropic: false }, settings: {}, engineReady: false };
  };
  return { api, calls };
}

test('removing a key asks first and sends one DELETE for that key, with no body', async () => {
  setLanguage('en');
  const { api, calls } = fakeApi();
  const asked = [];
  const view = await removeKey('openai-compatible', { confirm: (q) => (asked.push(q), true), api, t });
  assert.deepEqual(calls, [{ path: '/api/secrets/openai-compatible', method: 'DELETE' }]);
  assert.equal(asked.length, 1);
  assert.match(asked[0], /OpenAI-compatible API key/);
  assert.equal(view.secrets.anthropic, false);
});

test('saying no to the prompt removes nothing', async () => {
  const { api, calls } = fakeApi();
  assert.equal(await removeKey('anthropic', { confirm: () => false, api, t }), null);
  assert.equal(calls.length, 0);
});

test('only the four user keys can be removed from the window', async () => {
  const { api, calls } = fakeApi();
  for (const name of ['memory-key', 'searxng-secret', '../settings']) {
    await assert.rejects(removeKey(name, { confirm: () => true, api, t }));
  }
  assert.equal(calls.length, 0);
  assert.deepEqual(KEY_SLOTS.map((s) => s.name).sort(), ['anthropic', 'colab', 'gemini', 'openai-compatible']);
});

test('each key has its state hint, a Remove button and names in both languages', () => {
  for (const slot of KEY_SLOTS) {
    assert.match(html, new RegExp(`id="${slot.state}"`), slot.state);
    assert.match(html, new RegExp(`<button type="button"[^>]*id="${slot.remove}"[^>]*data-i18n="settings.removeKey"`), slot.remove);
    for (const lang of ['en', 'ar']) {
      assert.ok(STRINGS[lang][`settings.keyName.${slot.name}`], `${lang} name for ${slot.name}`);
      assert.ok(STRINGS[lang][slot.saved] && STRINGS[lang][slot.missing], `${lang} hints for ${slot.name}`);
    }
  }
  for (const lang of ['en', 'ar']) assert.match(STRINGS[lang]['settings.removeKeyConfirm'], /\{key\}/);
});
