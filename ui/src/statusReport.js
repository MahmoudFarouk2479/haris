// The first-launch-of-the-day greeting: a full status report composed from the core's own
// readings (system, voice engines, home-city weather). Pure: no model call, no DOM, no fetch.

/** Condition groups for Open-Meteo's WMO weather codes; each has a wx.<group> string in i18n.js. */
export const WEATHER_GROUPS = ['clear', 'partly', 'overcast', 'fog', 'drizzle', 'rain', 'snow', 'showers', 'thunderstorm'];

const CODE_GROUP = new Map([
  [0, 'clear'], [1, 'partly'], [2, 'partly'], [3, 'overcast'], [45, 'fog'], [48, 'fog'],
  [51, 'drizzle'], [53, 'drizzle'], [55, 'drizzle'], [56, 'drizzle'], [57, 'drizzle'],
  [61, 'rain'], [63, 'rain'], [65, 'rain'], [66, 'rain'], [67, 'rain'],
  [71, 'snow'], [73, 'snow'], [75, 'snow'], [77, 'snow'], [85, 'snow'], [86, 'snow'],
  [80, 'showers'], [81, 'showers'], [82, 'showers'],
  [95, 'thunderstorm'], [96, 'thunderstorm'], [99, 'thunderstorm'],
]);

/** The i18n key for a weather code's condition, or null for a code outside the groups. */
export function weatherKey(code) {
  const group = CODE_GROUP.get(code);
  return group ? `wx.${group}` : null;
}

// Names that are two words, written apart: «عبد الرحمن», «أبو بكر», "Abdel Rahman", "El Sayed".
// Calling someone «يا عبد» or "Abdel" alone is wrong (and in Egyptian Arabic, rude). The prefixes
// are عبد / أبو and Abd / Abdel / Abdul / Abu / El (with their common spellings). "Al" is not one:
// "Al Smith" is Al.
const COMPOUND_FIRST = new Set(['عبد', 'أبو', 'ابو', 'abd', 'abdel', 'abdal', 'abdul', 'abdol', 'abu', 'abou', 'el']);
// The article written as a word of its own after one of those: «عبد ال رحمن», "Abd El Rahman", "Abd Al Rahman".
const ARTICLE = new Set(['ال', 'el', 'al']);
// Titles some people put before their name; the greeting uses the name itself. Latin titles match with
// or without their dot ("Dr" and "Dr."); the Arabic abbreviations are written with theirs.
const LATIN_TITLES = new Set(['mr', 'mrs', 'ms', 'dr', 'prof', 'eng']);
const ARABIC_TITLES = new Set(['د.', 'م.', 'أ.', 'أ.د.']);

function isTitle(word) {
  const w = word.toLowerCase();
  return ARABIC_TITLES.has(w) || LATIN_TITLES.has(w.endsWith('.') ? w.slice(0, -1) : w);
}

/** The name to address the user by: the first name, kept whole when it is a compound one ('' when there is none). */
export function firstNameOf(userName) {
  let words = String(userName ?? '').trim().split(/\s+/u).filter(Boolean);
  if (words.length > 1 && isTitle(words[0])) words = words.slice(1);
  if (words.length < 2 || !COMPOUND_FIRST.has(words[0].toLowerCase())) return words[0] ?? '';
  const take = ARTICLE.has(words[1].toLowerCase()) && words.length > 2 ? 3 : 2;
  return words.slice(0, take).join(' ');
}

const isNumber = (v) => typeof v === 'number' && Number.isFinite(v);

/** "battery 80% and charging" / "battery 80%" / "on AC power" (no battery reported). */
function power(system, t) {
  if (!isNumber(system.battery) || system.battery < 0) return t('report.ac');
  return t(system.charging ? 'report.batteryCharging' : 'report.battery', { pct: Math.round(system.battery) });
}

function systemSentence(system, t) {
  if (!system || !isNumber(system.cpu) || !isNumber(system.memUsedGb) || !isNumber(system.memTotalGb)) return null;
  return t('report.system', {
    cpu: Math.round(system.cpu),
    used: system.memUsedGb.toFixed(1),
    total: Math.round(system.memTotalGb),
    power: power(system, t),
  });
}

/** Up when the local engine answers, or when the work is routed to Colab (listening) / Gemini (speaking). */
function engines(voice) {
  if (!voice) return null;
  return {
    stt: Boolean(voice.stt) || voice.sttRoute === 'colab',
    tts: Boolean(voice.tts) || voice.ttsRoute === 'gemini',
  };
}

/**
 * Haris starts its voice engines at launch and the speech model takes a few seconds to load, so a
 * report composed at once would call it offline. Asks again (up to `tries` times) until both are up;
 * returns the last answer either way (null if the core never answered).
 */
export async function waitForEngines(fetchStatus, { tries = 6, delayMs = 2000, sleep } = {}) {
  let last = null;
  for (let i = 0; i < tries; i++) {
    const status = await fetchStatus().catch(() => null);
    if (status) last = status;
    const up = engines(status);
    if (up?.stt && up?.tts) return status;
    if (i < tries - 1) await sleep(delayMs);
  }
  return last;
}

function engineSentence(up, t) {
  if (up.stt && up.tts) return t('report.voiceReady');
  if (!up.stt && !up.tts) return t('report.voiceDown');
  return t(up.stt ? 'report.ttsDown' : 'report.sttDown');
}

function weatherSentence(weather, t) {
  if (!weather || !weather.city || !isNumber(weather.tempC) || !isNumber(weather.highC)) return null;
  const vars = { city: weather.city, temp: Math.round(weather.tempC), high: Math.round(weather.highC) };
  const key = weatherKey(weather.code);
  return key ? t('report.weather', { ...vars, condition: t(key) }) : t('report.weatherBare', vars);
}

/**
 * The full status report, in the current interface language:
 * greeting, overall state, system, voice engines, weather. Missing readings are skipped.
 */
export function composeStatusReport({ partOfDay, firstName, system, voice, weather }, t) {
  const who = firstName ? t('greet.whoName', { name: firstName }) : t('greet.whoDefault');
  const up = engines(voice);
  return [
    t(`report.${partOfDay}`, { who }),
    up ? t(up.stt && up.tts ? 'report.allOnline' : 'report.attention') : null,
    systemSentence(system, t),
    up ? engineSentence(up, t) : null,
    weatherSentence(weather, t),
  ].filter(Boolean).join(' ');
}
