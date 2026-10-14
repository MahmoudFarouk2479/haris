import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

// The idle HUD costs one compositor frame per visible change, so the always-running animations step at a
// fixed rate (the eased curves are baked into keyframes) and Chromium skips the frames in between.
const css = readFileSync(new URL('./styles.css', import.meta.url), 'utf8');

function rule(selector) {
  const at = css.indexOf(`${selector} {`);
  assert.ok(at >= 0, `missing rule ${selector}`);
  return css.slice(at, css.indexOf('}', at));
}
function keyframeStops(name) {
  const at = css.indexOf(`@keyframes ${name} {`);
  assert.ok(at >= 0, `missing @keyframes ${name}`);
  let depth = 0;
  let end = at;
  for (let i = css.indexOf('{', at); i < css.length; i += 1) {
    if (css[i] === '{') depth += 1;
    if (css[i] === '}' && --depth === 0) { end = i; break; }
  }
  return [...css.slice(at, end).matchAll(/([\d.]+)%/g)].map((m) => Number(m[1]));
}
const seconds = (body) => Number(body.match(/([\d.]+)s\b/)[1]);
const stepsOf = (body) => Number(body.match(/steps\((\d+)\)/)?.[1] ?? NaN);

test('the idle star turns about 30 times a second, in whole steps', () => {
  for (const sel of ['.emblem .outer', '.emblem .inner']) {
    const body = rule(sel);
    const rate = stepsOf(body) / seconds(body);
    assert.ok(rate >= 29 && rate <= 31, `${sel}: ${rate} updates/s`);
  }
});

test('the core breathes in about 15 steps a second along its eased curve', () => {
  const body = rule('.emblem .core');
  assert.match(body, /animation: breath 4s steps\(6\) infinite/);
  const stops = keyframeStops('breath');
  assert.equal(stops.length, 11); // every 10 % = 0.4 s, 6 steps each = 15 updates/s
});

test('the status dots blink about 15 times a second along the old ease curve', () => {
  for (const [sel, name, duration] of [['.state-dot', 'state-blink', 1.4], ['.mic-dot', 'mic-blink', 1.2]]) {
    const body = rule(sel);
    assert.match(body, new RegExp(`animation: ${name} ${duration}s step-end infinite`), sel);
    const stops = keyframeStops(name);
    const rate = (stops.length - 1) / duration;
    assert.ok(rate >= 14.5 && rate <= 15.5, `${name}: ${rate} updates/s`);
  }
});

test('responsive parts stay smooth: the listening pulse and the level-driven scales are not stepped', () => {
  assert.doesNotMatch(rule("[data-emblem='listening'] .pulse"), /steps|step-/);
  assert.match(rule('.emblem .pulse-wrap'), /transition: transform 0\.12s ease-out/);
  assert.match(rule('.emblem .core'), /transition: transform 0\.12s ease-out/);
});

test('the top bar keeps its colour but has no backdrop blur to redo on every frame', () => {
  const body = rule('.topbar');
  assert.doesNotMatch(body, /backdrop-filter/);
  assert.match(body, /background: rgba\(6, 17, 15, 0\.82\)/);
});

test('reduced motion still stops every animation', () => {
  assert.match(css, /@media \(prefers-reduced-motion: reduce\) \{\s*\*, \*::before, \*::after \{ animation: none !important; transition: none !important; \}/);
});
