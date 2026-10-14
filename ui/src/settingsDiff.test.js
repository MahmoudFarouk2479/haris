import { test } from 'node:test';
import assert from 'node:assert/strict';
import { sameRules } from './settingsDiff.js';

const saved = { assistantName: 'Haris', userName: '', allowedFolders: ['C:\\Docs'], voiceWake: false, uiLanguage: 'en' };

test('a switch of the interface language alone keeps the rules', () => {
  assert.equal(sameRules(saved, { ...saved, uiLanguage: 'ar' }), true);
  assert.equal(sameRules(saved, { ...saved }), true);
});

test('key order does not matter', () => {
  const { uiLanguage, ...rest } = saved;
  assert.equal(sameRules(saved, { uiLanguage: 'ar', ...rest }), true);
});

test('any other change is a new rule set', () => {
  assert.equal(sameRules(saved, { ...saved, userName: 'Mahmoud' }), false);
  assert.equal(sameRules(saved, { ...saved, allowedFolders: ['C:\\Docs', 'D:\\Work'] }), false);
  assert.equal(sameRules(saved, { ...saved, voiceWake: true, uiLanguage: 'ar' }), false);
  assert.equal(sameRules(saved, { ...saved, extra: 1 }), false);
});

test('without a known previous state, assume the rules changed', () => {
  assert.equal(sameRules(null, saved), false);
  assert.equal(sameRules(saved, null), false);
});
