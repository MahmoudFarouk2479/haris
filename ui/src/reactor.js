// The holographic core in the middle of the HUD: an arc-reactor stack of rings, a
// wireframe heart, a gyroscope of tilted orbits and a particle halo, all additive and
// bloomed. It reacts to the assistant's state (colour + speed) and to its output (pulse).
import * as THREE from 'three';
import { EffectComposer } from 'three/addons/postprocessing/EffectComposer.js';
import { RenderPass } from 'three/addons/postprocessing/RenderPass.js';
import { UnrealBloomPass } from 'three/addons/postprocessing/UnrealBloomPass.js';
import { OutputPass } from 'three/addons/postprocessing/OutputPass.js';

const PALETTE = {
  idle: new THREE.Color('#38d9ff'),
  thinking: new THREE.Color('#ffb340'),
  speaking: new THREE.Color('#8ff6ff'),
  error: new THREE.Color('#ff4566'),
  listening: new THREE.Color('#5dffb0'),
};
const SPEED = { idle: 0.35, thinking: 1.8, speaking: 0.9, error: 0.12, listening: 0.6 };
const TAU = Math.PI * 2;

export function createReactor(canvas) {
  const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, powerPreference: 'high-performance' });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 1.5));
  renderer.setClearColor(0x02060c, 1);

  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(42, 1, 0.1, 100);
  camera.position.set(0, 0, 12);

  const root = new THREE.Group();
  root.position.y = 0.45;   // sit above the console
  scene.add(root);

  // Every material registered here follows the state colour.
  const tinted = [];
  const surface = (opacity) => track(new THREE.MeshBasicMaterial({
    color: PALETTE.idle.clone(), transparent: true, opacity,
    blending: THREE.AdditiveBlending, depthWrite: false, side: THREE.DoubleSide,
  }), opacity);
  const line = (opacity) => track(new THREE.LineBasicMaterial({
    color: PALETTE.idle.clone(), transparent: true, opacity,
    blending: THREE.AdditiveBlending, depthWrite: false,
  }), opacity);
  function track(material, baseOpacity) {
    material.userData.base = baseOpacity;
    tinted.push(material);
    return material;
  }

  const arc = (inner, outer, start, length, opacity, segments = 48) =>
    new THREE.Mesh(new THREE.RingGeometry(inner, outer, segments, 1, start, length), surface(opacity));

  // ── Heart: bright core, glow sprite, wireframe icosahedron, reactor triangle ──
  const heart = new THREE.Group();
  root.add(heart);

  const core = new THREE.Mesh(new THREE.SphereGeometry(0.3, 32, 32), surface(0.95));
  heart.add(core);

  const glow = new THREE.Sprite(new THREE.SpriteMaterial({
    map: radialTexture(), color: PALETTE.idle.clone(), transparent: true,
    blending: THREE.AdditiveBlending, depthWrite: false,
  }));
  glow.material.userData.base = 0.45;
  tinted.push(glow.material);
  glow.scale.setScalar(2.4);
  heart.add(glow);

  const ico = new THREE.LineSegments(new THREE.EdgesGeometry(new THREE.IcosahedronGeometry(0.9, 1)), line(0.55));
  heart.add(ico);

  const triangle = new THREE.Mesh(new THREE.RingGeometry(1.18, 1.27, 3, 1), surface(0.7));
  triangle.rotation.z = Math.PI / 2;
  heart.add(triangle);

  // ── Coil ring: ten heavy segments, like the reactor's copper windings ──
  const coils = new THREE.Group();
  for (let i = 0; i < 10; i++) {
    coils.add(arc(1.55, 1.9, (i / 10) * TAU + 0.05, TAU / 10 - 0.1, 0.32, 12));
  }
  root.add(coils);

  root.add(arc(2.08, 2.1, 0, TAU, 0.7, 160));

  // ── Tick ring: 180 graduations, every 15th long ──
  const tickPts = [];
  for (let i = 0; i < 180; i++) {
    const a = (i / 180) * TAU;
    const long = i % 15 === 0;
    const r0 = long ? 2.24 : 2.32;
    const r1 = long ? 2.62 : 2.44;
    tickPts.push(Math.cos(a) * r0, Math.sin(a) * r0, 0, Math.cos(a) * r1, Math.sin(a) * r1, 0);
  }
  const tickGeo = new THREE.BufferGeometry();
  tickGeo.setAttribute('position', new THREE.Float32BufferAttribute(tickPts, 3));
  const ticks = new THREE.LineSegments(tickGeo, line(0.45));
  root.add(ticks);

  // ── Data ring: broken arcs of uneven length, counter-rotating ──
  const dataRing = new THREE.Group();
  let start = 0;
  const lengths = [0.9, 0.25, 0.5, 1.4, 0.18, 0.7, 0.35, 1.0];
  for (const len of lengths) {
    dataRing.add(arc(2.82, 2.9, start, len, 0.35));
    start += len + 0.12;
  }
  root.add(dataRing);

  // ── Outer frame: hairline circle and three fast brackets ──
  root.add(arc(3.38, 3.395, 0, TAU, 0.35, 200));
  const brackets = new THREE.Group();
  for (let i = 0; i < 3; i++) {
    brackets.add(arc(3.46, 3.56, (i / 3) * TAU, 0.32, 0.7, 16));
  }
  root.add(brackets);

  // ── Radar sweep ──
  const sweep = arc(0.5, 3.36, 0, 0.55, 0.035, 32);
  root.add(sweep);

  // ── Gyroscope: tilted orbit rings with satellites ──
  const gyro = new THREE.Group();
  const orbits = [];
  [[2.55, 1.15, 0.2], [2.95, -0.9, 0.7], [3.9, 1.35, -0.4]].forEach(([r, tiltX, tiltY], i) => {
    const pivot = new THREE.Group();
    pivot.rotation.set(tiltX, tiltY, 0);
    const ring = new THREE.Mesh(new THREE.TorusGeometry(r, 0.005, 6, 220), surface(0.22));
    const sat = new THREE.Mesh(new THREE.SphereGeometry(0.05 + i * 0.01, 12, 12), surface(1));
    sat.userData = { radius: r, phase: i * 2.1, speed: 0.6 - i * 0.12 };
    pivot.add(ring, sat);
    gyro.add(pivot);
    orbits.push(sat);
  });
  root.add(gyro);

  // ── Particle halo ──
  const COUNT = 1600;
  const pos = new Float32Array(COUNT * 3);
  for (let i = 0; i < COUNT; i++) {
    const r = 2.1 + Math.pow(Math.random(), 0.6) * 3.4;
    const a = Math.random() * TAU;
    pos[i * 3] = Math.cos(a) * r;
    pos[i * 3 + 1] = Math.sin(a) * r;
    pos[i * 3 + 2] = (Math.random() - 0.5) * 0.9;
  }
  const dustGeo = new THREE.BufferGeometry();
  dustGeo.setAttribute('position', new THREE.BufferAttribute(pos, 3));
  const dustMat = new THREE.PointsMaterial({
    color: PALETTE.idle.clone(), size: 0.024, transparent: true, opacity: 0.45,
    blending: THREE.AdditiveBlending, depthWrite: false,
  });
  dustMat.userData.base = 0.45;
  tinted.push(dustMat);
  const dust = new THREE.Points(dustGeo, dustMat);
  root.add(dust);

  // ── Backdrop: faint polar grid ──
  const grid = new THREE.PolarGridHelper(9, 24, 12, 96);
  grid.rotation.x = Math.PI / 2;
  grid.position.z = -2.5;
  for (const m of [grid.material].flat()) {
    m.transparent = true;
    m.opacity = 0.05;
    m.depthWrite = false;
    m.color = PALETTE.idle.clone();
    m.userData.base = 0.05;
    tinted.push(m);
  }
  scene.add(grid);

  // ── Post-processing: bloom is what makes it a hologram ──
  const composer = new EffectComposer(renderer);
  composer.addPass(new RenderPass(scene, camera));
  const bloom = new UnrealBloomPass(new THREE.Vector2(512, 512), 0.75, 0.35, 0.12);
  composer.addPass(bloom);
  composer.addPass(new OutputPass());

  // ── State ──
  let state = 'idle';
  const color = PALETTE.idle.clone();
  let speed = SPEED.idle;
  let level = 0;           // output pulse, 0..1, decays
  let flicker = 0;
  const pointer = new THREE.Vector2();
  const motion = reducedMotion ? 0.25 : 1;

  function resize() {
    const w = window.innerWidth;
    const h = window.innerHeight;
    renderer.setSize(w, h, false);
    composer.setSize(w, h);
    bloom.resolution.set(w / 2, h / 2);
    camera.aspect = w / h;
    camera.position.z = w / h < 1 ? 17 : 12;
    camera.updateProjectionMatrix();
  }
  window.addEventListener('resize', resize);
  resize();

  window.addEventListener('pointermove', (e) => {
    pointer.set((e.clientX / window.innerWidth) * 2 - 1, (e.clientY / window.innerHeight) * 2 - 1);
  });

  const timer = new THREE.Timer();
  let t = 0;
  renderer.setAnimationLoop((time) => {
    timer.update(time);
    if (document.hidden) return;
    const dt = Math.min(timer.getDelta(), 0.05);

    speed += (SPEED[state] * motion - speed) * Math.min(1, dt * 3);
    t += dt * speed;
    level = Math.max(0, level - dt * 1.8);
    flicker = state === 'error' ? Math.random() * 0.35 : 0;

    color.lerp(PALETTE[state], Math.min(1, dt * 4));
    const breath = 0.85 + Math.sin(t * 2.2) * 0.08 + level * 0.4 - flicker;
    for (const m of tinted) {
      m.color.copy(color);
      m.opacity = Math.min(1, m.userData.base * breath);
    }

    heart.scale.setScalar(1 + level * 0.18 + Math.sin(t * 3) * 0.015);
    glow.scale.setScalar(2.4 + level * 1.2);
    ico.rotation.set(t * 0.7, t * 0.9, 0);
    triangle.rotation.z = Math.PI / 2 - t * 0.6;
    coils.rotation.z = t * 0.25;
    ticks.rotation.z = -t * 0.08;
    dataRing.rotation.z = -t * 0.45;
    brackets.rotation.z = t * 1.3;
    sweep.rotation.z = -t * 1.6;
    dust.rotation.z = t * 0.05;
    gyro.rotation.y = t * 0.15;
    for (const sat of orbits) {
      const a = sat.userData.phase + t * sat.userData.speed * 2;
      sat.position.set(Math.cos(a) * sat.userData.radius, Math.sin(a) * sat.userData.radius, 0);
    }

    // Gentle parallax so the hologram feels like it floats in the room.
    root.rotation.x += (pointer.y * 0.18 - root.rotation.x) * dt * 2;
    root.rotation.y += (pointer.x * 0.25 - root.rotation.y) * dt * 2;

    composer.render();
  });

  return {
    setState(next) {
      if (PALETTE[next]) state = next;
    },
    /** Kick the pulse — called for every chunk of streamed text. */
    pulse(amount = 0.35) {
      level = Math.min(1, level + amount);
    },
  };
}

function radialTexture() {
  const size = 256;
  const c = document.createElement('canvas');
  c.width = c.height = size;
  const g = c.getContext('2d');
  const grad = g.createRadialGradient(size / 2, size / 2, 0, size / 2, size / 2, size / 2);
  grad.addColorStop(0, 'rgba(255,255,255,1)');
  grad.addColorStop(0.15, 'rgba(255,255,255,0.55)');
  grad.addColorStop(0.45, 'rgba(255,255,255,0.12)');
  grad.addColorStop(1, 'rgba(255,255,255,0)');
  g.fillStyle = grad;
  g.fillRect(0, 0, size, size);
  const tex = new THREE.CanvasTexture(c);
  tex.colorSpace = THREE.SRGBColorSpace;
  return tex;
}
