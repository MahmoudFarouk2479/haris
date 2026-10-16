import '@fontsource/orbitron/500.css';
import '@fontsource/orbitron/700.css';
import '@fontsource/rajdhani/500.css';
import '@fontsource/rajdhani/600.css';
import '@fontsource/ibm-plex-sans-arabic/400.css';
import '@fontsource/ibm-plex-sans-arabic/600.css';
import './styles.css';

import { api, hasToken, streamChat, subscribeEvents } from './api.js';
import { createEmblem } from './emblem.js';
import { mountDrawers } from './drawers.js';
import { mountChatPanel } from './chatPanel.js';
import { applyLanguage, brandFor, getLanguage, locale, messageHeaderFor, relabelMessages, t } from './i18n.js';
import { composeStatusReport, firstNameOf, waitForEngines } from './statusReport.js';
import { briefingQuietMs, deliverBriefing } from './briefing.js';
import { sameRules } from './settingsDiff.js';
import { SentenceSplitter, Voice } from './voice.js';
import { takePhoto } from './camera.js';

const $ = (id) => document.getElementById(id);

/** Writes a translated label and keeps its key, so applyLanguage re-renders it after a switch. */
function setLabel(el, key) {
  el.dataset.i18n = key;
  el.textContent = t(key);
}

const emblem = createEmblem($('emblem'));
const drawers = mountDrawers();

function setState(state) {
  document.body.dataset.state = state;
  $('state-label').textContent = t(`state.${state}`);
  emblem.setState(state);
}

/** A short burst of emblem activity (voice level, streamed text) that settles back to rest. */
let pulseTimer = null;
function pulse(level) {
  emblem.setLevel(level);
  clearTimeout(pulseTimer);
  pulseTimer = setTimeout(() => emblem.setLevel(0), 180);
}

// ── Voice ──────────────────────────────────────────────────────────────────
let settingsCache = null;
const voice = new Voice({
  onLevel: (rms) => pulse(Math.min(1, rms * 6)),
  onWake: () => {
    if (busy) return;
    voice.chime();
    talk({ auto: true });
  },
  onMicState: ({ open, recording, wake }) => {
    $('mic-badge').hidden = !open;
    $('mic-badge').classList.toggle('rec', recording);
    setLabel($('mic-label'), recording ? 'mic.recording' : wake ? 'mic.wake' : 'mic.on');
    $('mic').classList.toggle('active', recording);
    if (!recording && document.body.dataset.state === 'listening') setState('transcribing');
  },
  onSpeaking: (on) => {
    if (on) {
      setState('speaking');
      return;
    }
    if (!busy && document.body.dataset.state === 'speaking') setState('idle');
    // Conversation mode: after answering a spoken request, listen a few seconds for a follow-up
    // (no wake word needed). Silence ends it; the mic badge shows when it is listening.
    if (!busy && lastInputWasVoice && settingsCache?.voiceFollowUp && !voice.recording) {
      setTimeout(() => {
        if (!busy && !voice.recording && !voice.speaking) talk({ auto: true, followUp: true });
      }, 250);
    }
  },
  onNeedsGesture: once(() => addMessage('notice', t('msg.needsGesture'))),
  onSpeechError: once(() => addMessage('error', t('msg.speechError'))),
});

function once(fn) {
  let done = false;
  return (...args) => {
    if (!done) {
      done = true;
      fn(...args);
    }
  };
}

let lastInputWasVoice = false;

/** One spoken request: listen, transcribe locally, then treat it like a typed message. */
async function talk({ auto, followUp = false }) {
  if (busy) return;
  if (followUp) voice.chime();
  setState('listening');
  try {
    const recorded = voice.record({ auto });
    const text = await recorded;
    if (!text) {
      lastInputWasVoice = false;   // silence ends the conversation
      setState('idle');
      return;
    }
    lastInputWasVoice = true;
    send(text);
  } catch (e) {
    addMessage('error', e.message || t('msg.voiceFailed'));
    setState('error');
    setTimeout(() => setState('idle'), 2500);
  }
}

$('mic').addEventListener('click', () => {
  if (voice.recording) {
    setState('transcribing');
    voice.stopRecording();
  } else {
    talk({ auto: true });
  }
});

// Push-to-talk: hold Ctrl+Space anywhere in the window.
window.addEventListener('keydown', (e) => {
  if (e.ctrlKey && e.code === 'Space' && !e.repeat) {
    e.preventDefault();
    talk({ auto: false });
  }
});
window.addEventListener('keyup', (e) => {
  if (e.code === 'Space' && voice.recording && !voice.recording.auto) {
    e.preventDefault();
    setState('transcribing');
    voice.stopRecording();
  }
});

async function refreshVoiceStatus() {
  try {
    const s = await api('/api/voice/status');
    const mark = (id, ok) => {
      $(id).textContent = ok ? t('voice.online') : t('voice.offline');
      $(id).classList.toggle('off', !ok);
    };
    if (s.sttRoute === 'colab') {
      $('voice-stt').textContent = s.stt ? t('voice.colabBackup') : t('voice.colab');
      $('voice-stt').classList.remove('off');
    } else {
      mark('voice-stt', s.stt);
      if (s.stt && s.sttDevice === 'gpu') $('voice-stt').textContent = t('voice.onlineGpu');
    }
    if (s.ttsRoute === 'gemini') {
      $('voice-tts').textContent = s.tts ? t('voice.geminiBackup') : t('voice.gemini');
      $('voice-tts').classList.remove('off');
    } else if (s.ttsRoute === 'gemini-paused') {
      // Google's limit was reached (often the free daily quota): Piper speaks until it resets.
      const time = new Date(s.ttsResumeAt).toLocaleTimeString(locale(), { hour: '2-digit', minute: '2-digit' });
      $('voice-tts').textContent = t('voice.geminiPaused', { time });
      $('voice-tts').classList.toggle('off', !s.tts);
    } else {
      mark('voice-tts', s.tts);
    }
    mark('voice-wake', s.wake && s.wakeWords.includes('hey_haris'));
    if (s.wake && !s.wakeWords.includes('hey_haris')) $('voice-wake').textContent = t('voice.noWakeModel');
    // A long status wraps to two lines; the tooltip always has all of it.
    for (const id of ['voice-stt', 'voice-tts', 'voice-wake']) $(id).title = $(id).textContent;
  } catch {
    /* ignore */
  }
}

async function applyVoiceSettings(s) {
  if (s.voiceWake && !voice.wakeEnabled) {
    try {
      await voice.enableWake();
    } catch {
      addMessage('error', t('msg.micRefused'));
    }
  } else if (!s.voiceWake && voice.wakeEnabled) {
    voice.disableWake();
  }
}

// ── Boot sequence ──────────────────────────────────────────────────────────
async function boot() {
  const lines = [
    'H.A.R.I.S. CORE v0.6',
    'Binding to loopback 127.0.0.1 ............ OK',
    'Inbound network interfaces ............... NONE',
    'Session token ............................ VERIFIED',
    'Credential vault ......................... SEALED',
    'Permission gate .......................... ARMED',
    'Guardian emblem .......................... ONLINE',
  ];
  const log = $('boot-log');
  const fast = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  for (const line of lines) {
    log.textContent += line + '\n';
    await sleep(fast ? 20 : 150);
  }
  await sleep(fast ? 50 : 350);
  $('boot').classList.add('done');
  setTimeout(() => $('boot').remove(), 900);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ── Clock ──────────────────────────────────────────────────────────────────
function tickClock() {
  const now = new Date();
  $('clock-time').textContent = now.toLocaleTimeString(locale(), { hour12: false });
  $('clock-date').textContent = now
    .toLocaleDateString(locale(), { weekday: 'short', day: '2-digit', month: 'short', year: 'numeric' })
    .toUpperCase();
}

// ── Telemetry ──────────────────────────────────────────────────────────────
const cpuHistory = [];
async function refreshSystem() {
  try {
    const s = await api('/api/system');
    $('cpu-value').textContent = `${s.cpu.toFixed(1)}%`;
    $('cpu-bar').style.width = `${Math.min(100, s.cpu)}%`;
    const memPct = (s.memUsedGb / s.memTotalGb) * 100;
    $('mem-value').textContent = `${s.memUsedGb} / ${s.memTotalGb} GB`;
    $('mem-bar').style.width = `${memPct}%`;
    $('cpu-name').textContent = shortCpu(s.cpuName);
    $('cpu-name').title = s.cpuName;
    $('cores').textContent = s.cores;
    $('processes').textContent = s.processes;
    $('uptime').textContent = formatUptime(s.uptimeSeconds);
    $('power').textContent = s.battery < 0 ? t('sys.ac') : `${s.battery}%${s.charging ? ' ⚡' : ''}`;
    cpuHistory.push(s.cpu);
    if (cpuHistory.length > 60) cpuHistory.shift();
    drawSpark($('cpu-spark'), cpuHistory);
  } catch {
    /* core busy or restarting — try again on the next tick */
  }
}

function shortCpu(name) {
  return name.replace(/\(R\)|\(TM\)|CPU|Processor|@.*$/gi, '').replace(/\s+/g, ' ').trim();
}

function formatUptime(sec) {
  const d = Math.floor(sec / 86400);
  const h = Math.floor((sec % 86400) / 3600);
  const m = Math.floor((sec % 3600) / 60);
  return d ? `${d}d ${h}h ${m}m` : `${h}h ${m}m`;
}

function drawSpark(canvas, values) {
  const g = canvas.getContext('2d');
  const { width: w, height: h } = canvas;
  const accent = getComputedStyle(document.body).getPropertyValue('--line').trim();
  g.clearRect(0, 0, w, h);
  g.strokeStyle = accent;
  g.globalAlpha = 0.25;
  for (let y = 0; y <= h; y += h / 4) {
    g.beginPath(); g.moveTo(0, y); g.lineTo(w, y); g.stroke();
  }
  g.globalAlpha = 1;
  g.lineWidth = 1.5;
  g.beginPath();
  values.forEach((v, i) => {
    const x = (i / 59) * w;
    const y = h - (v / 100) * (h - 4) - 2;
    i ? g.lineTo(x, y) : g.moveTo(x, y);
  });
  g.stroke();
  g.lineTo(((values.length - 1) / 59) * w, h);
  g.lineTo(0, h);
  g.closePath();
  g.globalAlpha = 0.12;
  g.fillStyle = accent;
  g.fill();
  g.globalAlpha = 1;
}

// ── Activity & security ────────────────────────────────────────────────────
function toolLabel(tool) {
  const key = `tool.${tool}`;
  const label = t(key);
  return label === key ? tool : label;
}
const DECISION_ICON = { auto: '◇', session: '◆', approved: '✓', 'approved (session)': '✓', denied: '✕', user: '↶' };

let lastActivity = null;
async function refreshActivity() {
  try {
    const a = await api('/api/activity');
    $('tools-count').textContent = t('sec.toolsCount', { n: a.tools });
    setContext(a.externalContent);
    const newest = a.entries[0]?.time ?? '';
    if (lastActivity !== null && newest && newest !== lastActivity) drawers.markNew('activity');
    lastActivity = newest;
    const list = $('activity');
    list.replaceChildren();
    for (const e of a.entries.slice(0, 6)) {
      const li = document.createElement('li');
      li.className = e.outcome === 'ok' ? 'ok' : e.decision === 'denied' ? 'denied' : 'err';
      const icon = document.createElement('b');
      icon.textContent = DECISION_ICON[e.decision] ?? '·';
      const text = document.createElement('span');
      text.textContent = e.summary;
      text.title = `${e.tool} · ${e.risk} · ${e.decision} · ${e.outcome}\n${new Date(e.time).toLocaleString(locale())}`;
      li.append(icon, text);
      list.append(li);
    }
    if (!a.entries.length) {
      const li = document.createElement('li');
      li.className = 'empty';
      li.textContent = t('activity.empty');
      list.append(li);
    }
    $('undo').disabled = !a.undo;
    $('undo-label').textContent = a.undo ? t('activity.nextUndo', { what: a.undo }) : '';
  } catch {
    /* ignore */
  }
}

let externalNow = false;
function setContext(external) {
  const el = $('context-state');
  el.textContent = external ? t('sec.external') : t('sec.clean');
  el.classList.toggle('warn', external);
  if (external && !externalNow) drawers.markNew('security');
  externalNow = external;
}

$('undo').addEventListener('click', async () => {
  const { message } = await api('/api/undo', { method: 'POST' });
  addMessage('notice', message);
  refreshActivity();
});

// ── Conversation ───────────────────────────────────────────────────────────
const transcript = $('transcript');
const input = $('input');
let assistantName = 'Haris';
let userName = '';
let engineReady = false;
let busy = false;
/** Set once the user has typed or spoken a request this session (the daily briefing then waits for a lull). */
let userHasSpoken = false;

function addMessage(role, text = '') {
  const el = document.createElement('article');
  el.className = `msg ${role}`;
  const who = document.createElement('header');
  who.dataset.role = role;   // so a language switch can re-label it (relabelMessages)
  who.textContent = messageHeaderFor(role, assistantName);
  const body = document.createElement('div');
  body.className = 'body';
  body.dir = 'auto';
  body.textContent = text;   // never innerHTML: model output and tool results are untrusted text
  el.append(who, body);
  transcript.append(el);
  scrollDown();
  if (role !== 'user') chat.markNew();
  return body;
}

const toolLines = new Map();
function showTool({ id, tool, summary, status, detail }) {
  let line = toolLines.get(id);
  if (!line) {
    line = document.createElement('div');
    line.className = 'tool-line';
    line.dir = 'auto';
    transcript.append(line);
    toolLines.set(id, line);
  }
  const icon = { running: '⟳', done: '✓', denied: '✕', error: '!' }[status] ?? '·';
  line.dataset.status = status;
  line.textContent = `${icon} ${toolLabel(tool).toUpperCase()} · ${summary}${detail ? ' — ' + detail : ''}`;
  scrollDown();
}

function scrollDown() {
  transcript.scrollTop = transcript.scrollHeight;
}

// Wide windows: the conversation is a side column that can be hidden. It lands at the bottom
// whenever it reappears or the layout switches.
const chat = mountChatPanel({ onReveal: scrollDown });

/** Every picture shared with the model appears in the transcript, so there are no hidden looks. */
function showPicture(src) {
  if (!src.startsWith('data:image/jpeg;base64,')) return;
  const fig = document.createElement('figure');
  fig.className = 'shot';
  const img = document.createElement('img');
  img.src = src;
  img.alt = t('shot.alt');
  const cap = document.createElement('figcaption');
  cap.textContent = t('shot.caption');
  fig.append(img, cap);
  transcript.append(fig);
  img.addEventListener('load', scrollDown);
}

function flash() {
  const f = $('flash');
  f.classList.remove('on');
  void f.offsetWidth;
  f.classList.add('on');
}

/** Characters per second of the last reply; null until there is one. */
let lastThroughput = null;
function renderThroughput() {
  $('throughput').textContent = lastThroughput === null ? '—' : t('sys.charsPerSec', { n: lastThroughput });
}

async function send(text, { label } = {}) {
  if (busy || !text.trim()) return;
  busy = true;
  if (!label) userHasSpoken = true;
  $('send').disabled = true;
  addMessage('user', label ?? text);
  setState('thinking');

  let body = null;
  const started = performance.now();
  let firstAt = 0;
  let chars = 0;
  voice.stopSpeaking();
  const speaker = settingsCache?.voiceReplies ? new SentenceSplitter((s) => voice.speak(s)) : null;
  const bubble = () => {
    if (!body) {
      body = addMessage('assistant');
      body.classList.add('streaming');
    }
    return body;
  };
  const closeBubble = () => {
    if (body) {
      body.classList.remove('streaming');
      if (!body.textContent) body.closest('.msg').remove();
    }
    body = null;
  };

  await streamChat(text, {
    meta: (m) => { $('engine').textContent = m.provider; },
    delta: ({ text: delta }) => {
      if (!firstAt) {
        firstAt = performance.now();
        $('latency').textContent = `${Math.round(firstAt - started)} ms`;
      }
      setState('speaking');
      chars += delta.length;
      bubble().textContent += delta;
      speaker?.push(delta);
      scrollDown();
      pulse(Math.min(1, 0.25 + delta.length / 40));
    },
    tool: (tl) => {
      speaker?.flush();
      closeBubble();
      showTool(tl);
      setState(tl.status === 'running' ? 'acting' : 'thinking');
      if (tl.status !== 'running') refreshActivity();
    },
    confirm: (request) => {
      closeBubble();
      setState('waiting');
      askConfirmation(request);
    },
    notice: ({ message }) => {
      closeBubble();
      addMessage('notice', message);
    },
    security: ({ externalContent }) => setContext(externalContent),
    image: ({ src }) => showPicture(src),
    done: () => {
      speaker?.flush();
      const secs = (performance.now() - (firstAt || started)) / 1000;
      lastThroughput = chars && secs > 0 ? Math.round(chars / secs) : null;
      renderThroughput();
      setState(voice.speaking ? 'speaking' : 'idle');
    },
    error: ({ message }) => {
      closeBubble();
      addMessage('error', message);
      setState('error');
      setTimeout(() => setState('idle'), 2500);
    },
  });

  closeBubble();
  closeConfirmation();
  busy = false;
  $('send').disabled = false;
  refreshActivity();
  input.focus();
}

$('composer').addEventListener('submit', (e) => {
  e.preventDefault();
  lastInputWasVoice = false;
  const text = input.value;
  input.value = '';
  autosize();
  send(text);
});
input.addEventListener('keydown', (e) => {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    $('composer').requestSubmit();
  }
});
function autosize() {
  input.style.height = 'auto';
  input.style.height = `${Math.min(input.scrollHeight, 140)}px`;
}
input.addEventListener('input', autosize);

$('reset').addEventListener('click', async () => {
  await api('/api/chat/reset', { method: 'POST' });
  transcript.replaceChildren();
  toolLines.clear();
  setContext(false);
  addMessage('assistant', t('msg.cleared'));
});

// ── Authorisation card ─────────────────────────────────────────────────────
// The only place an action can be approved. Buttons arm after a short delay so a stray
// click or Enter press cannot approve something the user has not read; Deny has focus.
const confirmDialog = $('confirm');
let pending = null;
let timer = null;
const ARM_DELAY_MS = 700;
const TIMEOUT_MS = 120_000;

function askConfirmation(request) {
  pending = request;
  const risk = request.risk;
  $('confirm-risk').textContent = risk === 'CRITICAL' ? t('confirm.critical') : t('confirm.risk');
  confirmDialog.dataset.risk = risk;
  $('confirm-tool').textContent = `${toolLabel(request.tool).toUpperCase()} · ${request.tool}`;
  $('confirm-summary').textContent = request.summary;
  $('confirm-args').textContent = request.arguments;
  $('confirm-warning').hidden = !request.externalContent;
  const preview = request.preview?.startsWith('data:image/jpeg;base64,') ? request.preview : null;
  $('confirm-preview-box').hidden = !preview;
  $('confirm-preview').src = preview ?? '';
  $('confirm-destination').textContent = request.destination ? t('confirm.destination', { where: request.destination }) : '';
  $('confirm-destination').classList.toggle('cloud', /internet/.test(request.destination ?? ''));
  $('confirm-session').hidden = !request.sessionOption;

  const armed = [$('confirm-once'), $('confirm-session')];
  armed.forEach((b) => { b.disabled = true; });
  setTimeout(() => armed.forEach((b) => { b.disabled = false; }), ARM_DELAY_MS);

  const bar = $('confirm-timer-bar');
  bar.style.transition = 'none';
  bar.style.width = '100%';
  requestAnimationFrame(() => requestAnimationFrame(() => {
    bar.style.transition = `width ${TIMEOUT_MS}ms linear`;
    bar.style.width = '0%';
  }));
  clearTimeout(timer);
  timer = setTimeout(() => decide('DENY'), TIMEOUT_MS);

  confirmDialog.showModal();
  $('confirm-deny').focus();
}

async function decide(decision) {
  if (!pending) return;
  const id = pending.id;
  closeConfirmation();
  setState('thinking');
  try {
    await api(`/api/confirm/${encodeURIComponent(id)}`, { method: 'POST', body: { decision } });
  } catch {
    /* already timed out on the core: it is treated as denied */
  }
}

function closeConfirmation() {
  pending = null;
  clearTimeout(timer);
  $('confirm-preview').src = '';   // drop the preview picture from the page
  if (confirmDialog.open) confirmDialog.close();
}

$('confirm-deny').addEventListener('click', () => decide('DENY'));
$('confirm-once').addEventListener('click', () => decide('ALLOW_ONCE'));
$('confirm-session').addEventListener('click', () => decide('ALLOW_SESSION'));
confirmDialog.addEventListener('cancel', (e) => {   // Esc = deny
  e.preventDefault();
  decide('DENY');
});

// ── Settings ───────────────────────────────────────────────────────────────
const dialog = $('settings');
const form = $('settings-form');
const TEXT_FIELDS = [
  'assistantName', 'userName', 'homeCity', 'provider', 'anthropicModel', 'anthropicEffort',
  'ollamaBaseUrl', 'ollamaModel', 'lmStudioBaseUrl', 'lmStudioModel', 'openAiBaseUrl', 'openAiModel',
  'sttLanguage', 'voiceEnglish', 'voiceArabic', 'colabBaseUrl', 'colabModel', 'replyLanguage', 'ttsEngine', 'geminiVoice',
  'uiLanguage',
];
const CHECK_FIELDS = ['webSearch', 'voiceReplies', 'voiceWake', 'voiceFollowUp', 'morningBriefing', 'startWithWindows', 'colabStt'];
let secretsCache = {};

function fillSettings(view) {
  const s = view.settings;
  settingsCache = s;
  secretsCache = view.secrets;
  engineReady = view.engineReady;
  for (const f of TEXT_FIELDS) form.elements[f].value = s[f] ?? '';
  for (const f of CHECK_FIELDS) form.elements[f].checked = Boolean(s[f]);
  form.elements.allowedFolders.value = (s.allowedFolders ?? []).join('\n');
  applyUi(s);
  showProviderFields();
}

/** Texts that depend on saved keys; rewritten whenever the language changes. */
function renderKeyStates() {
  const v = secretsCache;
  $('anthropic-key-state').textContent = v.anthropic ? t('settings.keyStored') : t('settings.keyMissing');
  $('openai-key-state').textContent = v['openai-compatible'] ? t('settings.keyStored') : t('settings.keyMissing');
  $('gemini-key-state').textContent = v.gemini ? t('settings.geminiKeySaved') : t('settings.geminiKeyMissing');
  $('colab-key-state').textContent = v.colab ? t('settings.colabTokenSaved') : t('settings.colabTokenMissing');
  $('colab-key-state').classList.toggle('warn-text', !v.colab);
}

function applyIdentity(s) {
  assistantName = s.assistantName || 'Haris';
  userName = s.userName || '';
  $('assistant-name').textContent = brandFor(assistantName);
  document.title = brandFor(assistantName);
}

/** Interface language: re-render every translated text, then the texts this file writes itself. */
function applyUi(s) {
  applyLanguage(s.uiLanguage === 'ar' ? 'ar' : 'en');
  applyIdentity(s);
  relabelMessages(transcript, assistantName);   // headers only; what was said stays as it was
  setState(document.body.dataset.state || 'idle');
  tickClock();
  setContext(externalNow);
  renderKeyStates();
  renderThroughput();
  refreshVoiceStatus();
  refreshActivity();
}

function showProviderFields() {
  const provider = form.elements.provider.value;
  for (const block of form.querySelectorAll('.provider-fields')) {
    block.hidden = block.dataset.provider !== provider;
  }
}
form.elements.provider.addEventListener('change', showProviderFields);

async function saveSettings() {
  $('settings-error').textContent = '';
  const before = settingsCache;   // as the core last returned it, like the PUT's answer below
  const body = Object.fromEntries(TEXT_FIELDS.map((f) => [f, form.elements[f].value.trim()]));
  for (const f of CHECK_FIELDS) body[f] = form.elements[f].checked;
  body.allowedFolders = form.elements.allowedFolders.value.split('\n').map((l) => l.trim()).filter(Boolean);
  try {
    let view = await api('/api/settings', { method: 'PUT', body });
    let keysEntered = false;
    const keys = [['anthropicKey', 'anthropic'], ['openAiKey', 'openai-compatible'], ['colabKey', 'colab'], ['geminiKey', 'gemini']];
    for (const [field, name] of keys) {
      const value = form.elements[field].value.trim();
      if (value) {
        view = await api(`/api/secrets/${name}`, { method: 'PUT', body: { value } });
        form.elements[field].value = '';
        keysEntered = true;
      }
    }
    // Only the interface language changed (or nothing): the core kept its conversation, so the
    // transcript and the outside-content warning stay too. A new key still starts afresh, as before.
    const rulesKept = sameRules(before, view.settings);
    if (rulesKept && keysEntered) await api('/api/chat/reset', { method: 'POST' });
    fillSettings(view);
    applyVoiceSettings(view.settings);
    if (rulesKept && !keysEntered) {
      addMessage('notice', t('msg.uiSaved'));
    } else {
      transcript.replaceChildren();
      toolLines.clear();
      setContext(false);
      addMessage('notice', t('msg.settingsSaved'));
    }
    return true;
  } catch (e) {
    $('settings-error').textContent = e.message;
    return false;
  }
}

async function loadModels() {
  const list = $('model-list');
  list.replaceChildren();
  try {
    const res = await api('/api/models');
    $('engine').textContent = res.provider || '—';
    for (const id of res.models) {
      const opt = document.createElement('option');
      opt.value = id;
      list.append(opt);
    }
    if (res.error) $('settings-error').textContent = res.error;
    else if (!res.models.length) $('settings-error').textContent = t('msg.noModels');
  } catch (e) {
    $('settings-error').textContent = e.message;
  }
}

$('open-settings').addEventListener('click', async () => {
  fillSettings(await api('/api/settings'));
  $('settings-error').textContent = '';
  const auto = await api('/api/system/autostart').catch(() => ({ available: false }));
  form.elements.startWithWindows.disabled = !auto.available;
  setLabel($('autostart-hint'), auto.available ? 'settings.autostartOn' : 'settings.autostartOff');
  dialog.showModal();
  loadModels();
});
$('close-settings').addEventListener('click', () => dialog.close());
$('test-speaker').addEventListener('click', () => {
  voice.speak('Voice check. All systems are online, sir.');
  voice.speak('اختبار الصوت. كل الأنظمة شغالة.');
});
$('refresh-models').addEventListener('click', async () => {
  if (await saveSettings()) loadModels();
});
form.addEventListener('submit', async (e) => {
  e.preventDefault();
  if (await saveSettings()) {
    dialog.close();
    loadModels();
  }
});

// ── Memory ─────────────────────────────────────────────────────────────────
const memoryDialog = $('memory');

async function showMemory() {
  $('memory-error').textContent = '';
  const list = $('memory-list');
  list.replaceChildren();
  let data;
  try {
    data = await api('/api/memory');
  } catch (e) {
    $('memory-error').textContent = e.message;
    return;
  }
  if (data.error) $('memory-error').textContent = data.error;
  if (!data.facts.length) {
    const li = document.createElement('li');
    li.className = 'empty';
    li.textContent = t('memory.empty');
    list.append(li);
  }
  for (const f of [...data.facts].reverse()) {
    const li = document.createElement('li');
    const tag = document.createElement('span');
    tag.className = 'tag';
    tag.textContent = f.category;
    const text = document.createElement('span');
    text.className = 'fact';
    text.dir = 'auto';
    text.textContent = f.text;
    text.title = t('memory.remembered', { when: new Date(f.created).toLocaleString(locale()) });
    const del = document.createElement('button');
    del.type = 'button';
    del.className = 'btn ghost danger';
    del.textContent = '✕';
    del.title = t('memory.forgetOne');
    del.addEventListener('click', async () => {
      await api(`/api/memory/${encodeURIComponent(f.id)}`, { method: 'DELETE' });
      showMemory();
    });
    li.append(tag, text, del);
    list.append(li);
  }
}

$('open-memory').addEventListener('click', () => {
  memoryDialog.showModal();
  showMemory();
});
$('close-memory').addEventListener('click', () => memoryDialog.close());
$('memory-wipe').addEventListener('click', async () => {
  if (!window.confirm(t('memory.wipeConfirm'))) return;
  await api('/api/memory', { method: 'DELETE' });
  showMemory();
});

// ── Start ──────────────────────────────────────────────────────────────────
async function start() {
  boot();
  tickClock();
  setInterval(tickClock, 1000);
  $('endpoint').textContent = `127.0.0.1:${location.port}`;

  if (!hasToken()) {
    setLabel($('session'), 'sec.notAuthorised');
    addMessage('error', t('msg.noToken'));
    setState('error');
    return;
  }
  setLabel($('session'), 'sec.verified');

  try {
    const view = await api('/api/settings');
    settingsCache = view.settings;
    secretsCache = view.secrets;
    engineReady = view.engineReady;
    applyUi(view.settings);
    loadModels();
    applyVoiceSettings(view.settings);
  } catch (e) {
    addMessage('error', e.message);
  }

  refreshSystem();
  refreshActivity();
  refreshVoiceStatus();
  setInterval(refreshSystem, 2000);
  setInterval(refreshVoiceStatus, 15000);
  subscribeEvents({
    notification: ({ title, message }) => {
      addMessage('reminder', `${title}: ${message}`);
      pulse(0.8);
    },
    // The core asks for a photo only after the user approved look_through_camera.
    camera: ({ id }) => takePhoto(id, {
      onCameraState: (on) => { $('cam-badge').hidden = !on; },
      onFlash: flash,
    }).catch(() => addMessage('error', t('msg.cameraFailed'))),
  });
  input.focus();

  // Haris greets out loud as soon as it opens, in the interface language; replies follow the
  // language the user speaks (Settings → "replies in"). On a fresh install (no engine yet) it says
  // how to start and opens Settings. Otherwise the first launch of the day gets a full status report
  // (system, voice engines, weather; no model call) and later launches a short greeting. The daily
  // briefing (once a day, through the model) follows on its day.
  const shown = sleep(1600);
  if (!engineReady) {
    await shown;
    greet(t('greet.setup', { name: /^haris$/i.test(assistantName) ? t('brand.short') : assistantName }));
    $('open-settings').click();
    return;
  }
  const claim = await api('/api/greeting/claim', { method: 'POST' }).catch(() => null);
  const firstName = firstNameOf(userName);
  const [report] = await Promise.all([claim?.full ? statusReport(firstName) : null, shown]);
  if (report) {
    greet(report);
  } else {
    const who = firstName ? t('greet.whoName', { name: firstName }) : t('greet.whoDefault');
    greet(t(`greet.${partOfDay()}`, { who }));
  }
  // The daily briefing (on its day) goes out at the first quiet moment: after the greeting has been
  // spoken and never into a turn of the user's. It is claimed only then, so waiting never loses it.
  deliverBriefing({
    claim: () => api('/api/briefing/claim', { method: 'POST' }),
    send: (prompt) => send(prompt, { label: t('label.dailyBriefing') }),
    isIdle: pageIsIdle,
    sleep,
    quietMs: () => briefingQuietMs(userHasSpoken),
  });
}

/** Nothing going on: no turn, no speech, no microphone, nothing typed, no dialog open. */
function pageIsIdle() {
  return !busy && !voice.speaking && !voice.recording && document.body.dataset.state === 'idle'
    && !input.value.trim() && !document.querySelector('dialog[open]');
}

/**
 * Reads voice engines (given ~12 s to finish starting) and weather (~8 s, then left out: a cold
 * first lookup can take ~5 s) in parallel, then the system, so the CPU figure is a recent one
 * rather than the launch spike.
 */
async function statusReport(firstName) {
  const [voiceStatus, weather] = await Promise.all([
    waitForEngines(() => api('/api/voice/status'), { tries: 6, delayMs: 2500, sleep }),
    Promise.race([api(`/api/weather/now?lang=${getLanguage()}`).catch(() => null), sleep(8000).then(() => null)]),
  ]);
  const system = await api('/api/system').catch(() => null);
  return composeStatusReport({ partOfDay: partOfDay(), firstName, system, voice: voiceStatus, weather }, t);
}

/** Shows a fixed line and speaks it — no model needed, so it works before anything is set up. */
function greet(text) {
  addMessage('assistant', text);
  if (settingsCache?.voiceReplies !== false) voice.speak(text);
}

function partOfDay() {
  const h = new Date().getHours();
  return h < 12 ? 'morning' : h < 18 ? 'afternoon' : 'evening';
}

start();
