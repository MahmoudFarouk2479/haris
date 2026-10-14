// Haris's emblem: an eight-point star from Islamic geometry, two layers turning in opposite
// directions around a gold core. It is plain SVG; styles.css animates it from data-emblem and
// --level, so the CPU stays idle and the star is sharp on any screen.

const NS = 'http://www.w3.org/2000/svg';

export const EMBLEM_STATES = ['idle', 'listening', 'thinking', 'speaking', 'waiting', 'error'];

const FOR_HUD_STATE = {
  idle: 'idle',
  listening: 'listening',
  thinking: 'thinking',
  transcribing: 'thinking',
  acting: 'thinking',
  speaking: 'speaking',
  waiting: 'waiting',
  error: 'error',
};

export function emblemStateFor(hudState) {
  return FOR_HUD_STATE[hudState] ?? 'idle';
}

function node(doc, name, attrs, parent) {
  const n = doc.createElementNS(NS, name);
  for (const [k, v] of Object.entries(attrs)) n.setAttribute(k, String(v));
  parent.appendChild(n);
  return n;
}

/** Two squares, straight and turned 45°: the eight-point star. */
function star(doc, parent, size, className) {
  const g = node(doc, 'g', { class: className }, parent);
  const half = -size / 2;
  node(doc, 'rect', { x: half, y: half, width: size, height: size }, g);
  node(doc, 'rect', { x: half, y: half, width: size, height: size, transform: 'rotate(45)' }, g);
}

export function createEmblem(container, doc = globalThis.document) {
  const svg = node(doc, 'svg', { viewBox: '-150 -150 300 300', class: 'emblem', 'aria-hidden': 'true', focusable: 'false' }, container);
  const defs = node(doc, 'defs', {}, svg);
  const glow = node(doc, 'radialGradient', { id: 'emblem-glow' }, defs);
  node(doc, 'stop', { offset: 0, class: 'glow-in' }, glow);
  node(doc, 'stop', { offset: 1, class: 'glow-out' }, glow);
  node(doc, 'circle', { r: 140, class: 'halo', fill: 'url(#emblem-glow)' }, svg);

  const outer = node(doc, 'g', { class: 'layer outer' }, svg);
  node(doc, 'circle', { r: 128, class: 'ring dashed' }, outer);
  star(doc, outer, 172, 'star');

  node(doc, 'circle', { r: 100, class: 'pulse' }, svg);

  const inner = node(doc, 'g', { class: 'layer inner' }, svg);
  node(doc, 'circle', { r: 70, class: 'ring' }, inner);
  star(doc, inner, 88, 'star gold');

  node(doc, 'circle', { r: 16, class: 'core' }, svg);

  container.dataset.emblem = 'idle';
  return {
    setState(hudState) {
      container.dataset.emblem = emblemStateFor(hudState);
    },
    /** 0–1: microphone or speech loudness; drives the pulse and the core. */
    setLevel(level) {
      const value = Math.max(0, Math.min(1, Number(level) || 0));
      container.style.setProperty('--level', value.toFixed(3));
    },
  };
}
