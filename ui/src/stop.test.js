import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { STRINGS } from './i18n.js';

const html = readFileSync(new URL('../index.html', import.meta.url), 'utf8');
const main = readFileSync(new URL('./main.js', import.meta.url), 'utf8');
const apiJs = readFileSync(new URL('./api.js', import.meta.url), 'utf8');

test('the Stop request goes to the core with the session token', () => {
  assert.match(apiJs, /export (async )?function stopChat\(\)[\s\S]*?'\/api\/chat\/stop'[\s\S]*?method: 'POST'/);
});

test('while a reply runs, clicking the Send button stops it instead of sending', () => {
  assert.match(main, /\$\('send'\)\.addEventListener\('click', \(e\) => \{\s*if \(busy\) \{\s*e\.preventDefault\(\);\s*stopReply\(\);/);
  // Enter while a reply runs must not stop it: only the button does
  assert.match(main, /\$\('composer'\)\.addEventListener\('submit', \(e\) => \{\s*e\.preventDefault\(\);\s*if \(busy\) return;/);
  assert.doesNotMatch(main, /\$\('send'\)\.disabled = true/);
});

test('a stopped reply says so in the HUD language and ends the voice conversation', () => {
  assert.match(main, /stopped: \(\) => \{[\s\S]*?addMessage\('notice', t\('msg\.stopped'\)\)/);
  assert.match(main, /function stopReply\(\) \{[\s\S]*?lastInputWasVoice = false;[\s\S]*?voice\.stopSpeaking\(\);[\s\S]*?stopChat\(\)/);
});

test('the button reads Stop while busy and Send otherwise, in both languages', () => {
  assert.match(main, /setLabel\(\$\('send'\), busy \? 'composer\.stop' : 'composer\.send'\)/);
  for (const lang of Object.keys(STRINGS)) {
    assert.ok(STRINGS[lang]['composer.stop'], `${lang} composer.stop`);
    assert.ok(STRINGS[lang]['msg.stopped'], `${lang} msg.stopped`);
  }
});

test('the approval tiers are explained in the Security drawer and in Settings, not on the approval card', () => {
  const drawer = html.slice(html.indexOf('id="drawer-security"'), html.indexOf('</aside>', html.indexOf('id="drawer-security"')));
  assert.match(drawer, /data-i18n="sec\.tiers"/);
  assert.match(html, /<legend data-i18n="settings\.access">[\s\S]*?data-i18n="settings\.tiersHint"[\s\S]*?<\/fieldset>/);
  assert.equal(STRINGS.en['sec.tiers'], STRINGS.en['settings.tiersHint']);
  assert.match(STRINGS.en['sec.tiers'], /run and are logged/);
  assert.match(STRINGS.en['sec.tiers'], /waits for your click/);
  assert.doesNotMatch(STRINGS.en['sec.tiers'], /every action/i);
});
