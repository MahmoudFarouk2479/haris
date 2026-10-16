import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { DRAWERS } from './drawers.js';

const html = readFileSync(new URL('../index.html', import.meta.url), 'utf8');
const ids = new Set([...html.matchAll(/\bid="([^"]+)"/g)].map((m) => m[1]));

test('the page has the rail, a drawer per rail button, and the emblem', () => {
  for (const id of DRAWERS) {
    assert.ok(ids.has(`drawer-${id}`), `missing #drawer-${id}`);
    assert.match(html, new RegExp(`data-drawer="${id}"`), `missing rail button for ${id}`);
  }
  assert.ok(ids.has('emblem'));
  assert.ok(!ids.has('reactor'), 'the old reactor canvas is gone');
  assert.match(html, /<link rel="icon"/);
});

test('no inline styles or scripts (the CSP forbids them)', () => {
  assert.doesNotMatch(html, /\sstyle="/);
  assert.doesNotMatch(html, /<script(?![^>]*\bsrc=)/);
});

test('ids are unique', () => {
  const all = [...html.matchAll(/\bid="([^"]+)"/g)].map((m) => m[1]);
  assert.equal(all.length, new Set(all).size);
});

const main = readFileSync(new URL('./main.js', import.meta.url), 'utf8');

test("every id main.js reads with $('…') exists in index.html", () => {
  for (const [, id] of main.matchAll(/\$\('([A-Za-z0-9-]+)'\)/g)) {
    assert.ok(ids.has(id), `main.js reads #${id}, which index.html does not have`);
  }
});

test('Three.js and the reactor are gone', () => {
  assert.doesNotMatch(main, /reactor/);
  const pkg = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8'));
  assert.equal(pkg.dependencies.three, undefined);
  assert.equal(pkg.dependencies['@fontsource/ibm-plex-sans-arabic'], '5.3.0');
});

test('messages keep their own direction', () => {
  assert.match(main, /body\.dir = 'auto'/);
});

test('voice mode hides the transcript from sight only, so its aria-live replies are still announced', () => {
  const css = readFileSync(new URL('./styles.css', import.meta.url), 'utf8');
  assert.match(html, /id="transcript"[^>]*aria-live="polite"/);
  assert.doesNotMatch(css, /body\.chat-hidden \.transcript[^{]*\{[^}]*display:\s*none/);
  assert.match(css, /body\.chat-hidden \.transcript \{[^}]*clip-path: inset\(50%\)/);
});

test('speech plays with a little headroom (volume only, never faster or slower)', () => {
  const voiceJs = readFileSync(new URL('./voice.js', import.meta.url), 'utf8');
  const outputJs = readFileSync(new URL('./speechOutput.js', import.meta.url), 'utf8');
  const gain = Number(outputJs.match(/export const SPEECH_GAIN = ([\d.]+);/)?.[1]);
  assert.ok(gain >= 0.7 && gain <= 0.9, `SPEECH_GAIN ${gain}`);
  assert.match(voiceJs, /this\.gain \?\?= buildSpeechOutput\(this\.out\)/);
  assert.match(voiceJs, /analyser\.connect\(this\.gain\)/);
  assert.doesNotMatch(outputJs, /playbackRate|detune/);
  assert.doesNotMatch(voiceJs, /analyser\.connect\(this\.out\.destination\)/);
  assert.doesNotMatch(voiceJs, /playbackRate|detune/);
});

test('long voice statuses wrap to two lines with the full text in a tooltip, not an ellipsis', () => {
  const css = readFileSync(new URL('./styles.css', import.meta.url), 'utf8');
  const rule = css.match(/\.voice-rows dd \{([^}]*)\}/)?.[1] ?? '';
  assert.match(rule, /white-space: normal/);
  assert.match(rule, /-webkit-line-clamp: 2/);
  assert.match(main, /\$\(id\)\.title = \$\(id\)\.textContent/);
});
