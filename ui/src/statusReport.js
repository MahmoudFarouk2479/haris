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

/** The first whitespace-separated word of the user's name ('' when there is none). */
export function firstNameOf(userName) {
  return String(userName ?? '').trim().split(/\s+/u)[0] ?? '';
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
