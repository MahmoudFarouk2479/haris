// Haris's emblem: an eight-point star from Islamic geometry, two layers turning in opposite
// directions around a gold core. Each moving part is its own HTML box holding a small SVG that is
// drawn once; styles.css turns, fades and scales those boxes from data-emblem and --level. Boxes
// are animated by the compositor, so the star costs almost no CPU at any size (an animation
// INSIDE an SVG would repaint the whole star on every frame) and it stays sharp on any screen.

const NS = 'http://www.w3.org/2000/svg';
const HTML_NS = 'http://www.w3.org/1999/xhtml';

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

/** One stacked layer: an absolutely placed box with its own full-size SVG (same viewBox for all). */
function layer(doc, parent, className) {
  const div = doc.createElementNS(HTML_NS, 'div');
  div.setAttribute('class', `emblem-layer ${className}`);
  parent.appendChild(div);
  return div;
}

function canvas(doc, parent) {
  return node(doc, 'svg', { viewBox: '-150 -150 300 300', focusable: 'false' }, parent);
}

export function createEmblem(container, doc = globalThis.document) {
  const root = doc.createElementNS(HTML_NS, 'div');
  root.setAttribute('class', 'emblem');
  root.setAttribute('aria-hidden', 'true');
  container.appendChild(root);

  const halo = canvas(doc, layer(doc, root, 'halo'));
  const defs = node(doc, 'defs', {}, halo);
  const glow = node(doc, 'radialGradient', { id: 'emblem-glow' }, defs);
  node(doc, 'stop', { offset: 0, class: 'glow-in' }, glow);
  node(doc, 'stop', { offset: 1, class: 'glow-out' }, glow);
  node(doc, 'circle', { r: 140, fill: 'url(#emblem-glow)' }, halo);

  const outer = canvas(doc, layer(doc, root, 'layer outer'));
  node(doc, 'circle', { r: 128, class: 'ring dashed' }, outer);
  star(doc, outer, 172, 'star');

  // The wrapper scales with --level (the mic level); the layer inside runs the pulse animation.
  const pulseWrap = layer(doc, root, 'pulse-wrap');
  node(doc, 'circle', { r: 100 }, canvas(doc, layer(doc, pulseWrap, 'pulse')));

  const inner = canvas(doc, layer(doc, root, 'layer inner'));
  node(doc, 'circle', { r: 70, class: 'ring' }, inner);
  star(doc, inner, 88, 'star gold');

  node(doc, 'circle', { r: 16 }, canvas(doc, layer(doc, root, 'core')));

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
