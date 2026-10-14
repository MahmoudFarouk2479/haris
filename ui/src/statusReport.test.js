import { test } from 'node:test';
import assert from 'node:assert/strict';
import { STRINGS, setLanguage, t } from './i18n.js';
import { WEATHER_GROUPS, composeStatusReport, firstNameOf, waitForEngines, weatherKey } from './statusReport.js';

const system = { cpu: 12.4, memUsedGb: 9.1, memTotalGb: 15.8, battery: 80, charging: true };
const voiceUp = { stt: true, tts: true, sttRoute: 'local', ttsRoute: 'local' };
const weather = { city: 'Cairo', country: 'Egypt', tempC: 24, code: 0, highC: 29, lowC: 18 };

function inLanguage(lang, fn) {
  setLanguage(lang);
  try {
    return fn();
  } finally {
    setLanguage('en');
  }
}

test('first name: first word, extra spaces, Arabic names, empty', () => {
  assert.equal(firstNameOf('Mahmoud Farouk'), 'Mahmoud');
  assert.equal(firstNameOf('   Mahmoud    Farouk  '), 'Mahmoud');
  assert.equal(firstNameOf('محمود فاروق'), 'محمود');
  assert.equal(firstNameOf('\tمحمود  '), 'محمود');
  assert.equal(firstNameOf('Mahmoud'), 'Mahmoud');
  assert.equal(firstNameOf(''), '');
  assert.equal(firstNameOf('   '), '');
  assert.equal(firstNameOf(undefined), '');
});

test('weather codes map to the nine condition groups', () => {
  const cases = {
    0: 'clear', 1: 'partly', 2: 'partly', 3: 'overcast', 45: 'fog', 48: 'fog',
    51: 'drizzle', 55: 'drizzle', 57: 'drizzle', 61: 'rain', 65: 'rain', 67: 'rain',
    71: 'snow', 77: 'snow', 85: 'snow', 86: 'snow', 80: 'showers', 82: 'showers',
    95: 'thunderstorm', 96: 'thunderstorm', 99: 'thunderstorm',
  };
  for (const [code, group] of Object.entries(cases)) assert.equal(weatherKey(Number(code)), `wx.${group}`, code);
  assert.equal(weatherKey(42), null);
  assert.equal(weatherKey(undefined), null);
  for (const g of WEATHER_GROUPS) {
    for (const lang of ['en', 'ar']) assert.ok(STRINGS[lang][`wx.${g}`], `${lang} wx.${g}`);
  }
});

test('English, everything up', () => {
  const text = composeStatusReport({ partOfDay: 'evening', firstName: 'Mahmoud', system, voice: voiceUp, weather }, t);
  assert.equal(text,
    'Good evening, Mahmoud. All systems are online. CPU at 12%, memory 9.1 of 16 GB, battery 80% and charging. '
    + "Voice engines are ready. In Cairo it's 24°C with clear skies, high of 29.");
});

test('Arabic, everything up', () => {
  const text = inLanguage('ar', () =>
    composeStatusReport({ partOfDay: 'morning', firstName: 'محمود', system, voice: voiceUp, weather: { ...weather, city: 'القاهرة' } }, t));
  assert.equal(text,
    'صباح الخير يا محمود. كل الأنظمة تعمل. المعالج عند 12%، والذاكرة 9.1 من 16 جيجابايت، والبطارية 80% وتُشحن. '
    + 'محركات الصوت جاهزة. في القاهرة الآن 24°C، سماء صافية، والعظمى اليوم 29°C.');
  assert.doesNotMatch(text, /clear|skies|Good/);
});

test('something down: attention first, then what is offline (en + ar)', () => {
  const sttDown = { ...voiceUp, stt: false };
  const en = composeStatusReport({ partOfDay: 'afternoon', firstName: '', system, voice: sttDown, weather: null }, t);
  assert.equal(en,
    'Good afternoon, sir. Some systems need attention. CPU at 12%, memory 9.1 of 16 GB, battery 80% and charging. '
    + 'Speech recognition is offline — is Docker Desktop running?');
  const ttsDown = composeStatusReport({ partOfDay: 'afternoon', firstName: 'Mahmoud', system, voice: { ...voiceUp, tts: false }, weather: null }, t);
  assert.match(ttsDown, /Some systems need attention\..*The speaking voice is offline — is Docker Desktop running\?$/);
  const bothDown = composeStatusReport({ partOfDay: 'afternoon', firstName: 'Mahmoud', system, voice: { stt: false, tts: false }, weather: null }, t);
  assert.match(bothDown, /Both voice engines are offline — is Docker Desktop running\?$/);
  const ar = inLanguage('ar', () =>
    composeStatusReport({ partOfDay: 'evening', firstName: '', system, voice: sttDown, weather: null }, t));
  assert.equal(ar,
    'مساء الخير يا فندم. بعض الأنظمة تحتاج إلى انتباه. المعالج عند 12%، والذاكرة 9.1 من 16 جيجابايت، والبطارية 80% وتُشحن. '
    + 'التعرّف على الكلام متوقف — هل Docker Desktop يعمل؟');
});

test('Colab or Gemini routes count as up even when the local engine is not', () => {
  const routed = { stt: false, tts: false, sttRoute: 'colab', ttsRoute: 'gemini' };
  const text = composeStatusReport({ partOfDay: 'morning', firstName: 'Mahmoud', system, voice: routed, weather: null }, t);
  assert.match(text, /All systems are online\..*Voice engines are ready\.$/);
});

test('AC power, no battery, plain battery (en + ar)', () => {
  const ac = { ...system, battery: -1, charging: false };
  assert.match(composeStatusReport({ partOfDay: 'morning', firstName: 'Mahmoud', system: ac, voice: voiceUp, weather: null }, t),
    /CPU at 12%, memory 9\.1 of 16 GB, on AC power\./);
  assert.match(composeStatusReport({ partOfDay: 'morning', firstName: 'Mahmoud', system: { ...system, charging: false }, voice: voiceUp, weather: null }, t),
    /, battery 80%\./);
  const ar = inLanguage('ar', () =>
    composeStatusReport({ partOfDay: 'morning', firstName: 'محمود', system: ac, voice: voiceUp, weather: null }, t));
  assert.match(ar, /والذاكرة 9\.1 من 16 جيجابايت، والجهاز على الكهرباء مباشرة\./);
});

test('no weather, no system, unknown condition: those sentences are skipped', () => {
  const noWeather = composeStatusReport({ partOfDay: 'morning', firstName: 'Mahmoud', system, voice: voiceUp, weather: null }, t);
  assert.doesNotMatch(noWeather, /In |°C/);
  assert.match(noWeather, /Voice engines are ready\.$/);
  const bare = composeStatusReport({ partOfDay: 'morning', firstName: 'Mahmoud', system: null, voice: voiceUp, weather: undefined }, t);
  assert.equal(bare, 'Good morning, Mahmoud. All systems are online. Voice engines are ready.');
  const oddCode = composeStatusReport({ partOfDay: 'morning', firstName: 'Mahmoud', system: null, voice: voiceUp, weather: { ...weather, code: 42 } }, t);
  assert.match(oddCode, /In Cairo it's 24°C, high of 29\.$/);
  const arOdd = inLanguage('ar', () =>
    composeStatusReport({ partOfDay: 'morning', firstName: 'محمود', system: null, voice: voiceUp, weather: { ...weather, city: 'القاهرة', code: 42 } }, t));
  assert.match(arOdd, /في القاهرة الآن 24°C، والعظمى اليوم 29°C\.$/);
});

test('unknown engine state: no claim either way, no engine sentence', () => {
  const text = composeStatusReport({ partOfDay: 'morning', firstName: 'Mahmoud', system, voice: null, weather }, t);
  assert.equal(text,
    "Good morning, Mahmoud. CPU at 12%, memory 9.1 of 16 GB, battery 80% and charging. In Cairo it's 24°C with clear skies, high of 29.");
});

test('every weather group reads naturally in English and has no English in Arabic', () => {
  for (const g of WEATHER_GROUPS) {
    assert.doesNotMatch(STRINGS.ar[`wx.${g}`], /[A-Za-z]/, g);
  }
});

test('waitForEngines: at once when up, retries while starting, gives up with the last answer', async () => {
  const noSleep = async () => {};
  let calls = 0;
  assert.deepEqual(await waitForEngines(async () => { calls++; return voiceUp; }, { tries: 5, sleep: noSleep }), voiceUp);
  assert.equal(calls, 1);

  const answers = [{ stt: false, tts: true }, null, { stt: false, tts: true }, voiceUp];
  calls = 0;
  assert.deepEqual(await waitForEngines(async () => answers[calls++], { tries: 6, sleep: noSleep }), voiceUp);
  assert.equal(calls, 4);

  calls = 0;
  const down = { stt: false, tts: false };
  assert.deepEqual(await waitForEngines(async () => { calls++; return down; }, { tries: 3, sleep: noSleep }), down);
  assert.equal(calls, 3);

  const colab = { stt: false, tts: true, sttRoute: 'colab' };
  calls = 0;
  assert.deepEqual(await waitForEngines(async () => { calls++; return colab; }, { tries: 3, sleep: noSleep }), colab);
  assert.equal(calls, 1);

  assert.equal(await waitForEngines(async () => null, { tries: 2, sleep: noSleep }), null);
});
