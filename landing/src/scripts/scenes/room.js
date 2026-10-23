// The post room both scenes stand in, and the pieces they share. The homeserver is a wall of
// pigeonholes, one of them the room; SuperMessage is a reading desk with a screen and no shelves
// (it keeps no store of its own); an agent is a pod across the floor from it.
import { CatmullRomCurve3, Group, Mesh, TubeGeometry, Vector3 } from 'three';
import { box, mat, plinth } from './kit.js';

export const CREAM = '#f4eee2', AMBER = '#e8a33d', OFF = '#2a2540', NEUTRAL = '#8e86ab';
// AMBER is the page's --color-signal: the one thing waiting on a person, as on the rest of the page.

/** A material that glows in `color` by `k` (0 is dark). */
export function glow(m, color, k) {
  if (color.isColor) m.color.copy(color);
  else m.color.set(color);
  m.emissive.copy(m.color).multiplyScalar(k);
}

const COLS = 4, ROWS = 3, CW = 0.42, CH = 0.3, T = 0.035, DEPTH = 0.44;
const W = COLS * CW + (COLS + 1) * T, H = ROWS * CH + (ROWS + 1) * T;
const ROOM = 6; // row 2 (from the top), column 3: the room this scene is about

export function postRoom(root, accent) {
  const top = plinth(4.3, 2.1, root);
  const wood = mat('#8a5a3b'), dark = mat('#6b4129'), back = mat('#5c3a26');
  // The pigeonholes: a hollow carcass on a base, so each hole is a real gap.
  const cx = 0.1, cz = -0.5, y0 = top + 0.1;
  box(W + 0.06, 0.1, DEPTH + 0.04, dark, cx, top + 0.05, cz, root);
  box(W + 0.06, T * 1.4, DEPTH + 0.06, dark, cx, y0 + H + T * 0.7, cz, root);
  box(W, H, T, back, cx, y0 + H / 2, cz - DEPTH / 2 + T / 2, root);
  for (let c = 0; c <= COLS; c++) box(T, H, DEPTH, wood, cx - W / 2 + T / 2 + c * (CW + T), y0 + H / 2, cz, root);
  for (let r = 0; r <= ROWS; r++) box(W, T, DEPTH, wood, cx, y0 + T / 2 + r * (CH + T), cz, root);
  const hole = (i) => new Vector3(cx - W / 2 + T + CW / 2 + (i % COLS) * (CW + T), y0 + H - T - CH / 2 - Math.floor(i / COLS) * (CH + T), cz);
  // Other rooms hold a few earlier messages, lying flat.
  for (const [i, n] of [[0, 2], [3, 1], [5, 3], [9, 1], [10, 2]]) {
    const p = hole(i);
    for (let k = 0; k < n; k++) box(CW - 0.08, 0.012, DEPTH - 0.12, mat(CREAM), p.x, p.y - CH / 2 + 0.012 + k * 0.014, p.z + 0.02, root);
  }
  // The room's frame, which lights when you are signed in and it is yours to read.
  const rp = hole(ROOM), fz = cz + DEPTH / 2 + 0.008;
  const rim = mat('#ffffff', true);
  glow(rim, OFF, 0);
  for (const [w, h, x, y] of [[CW + T, 0.02, 0, CH / 2 + T / 2], [CW + T, 0.02, 0, -CH / 2 - T / 2], [0.02, CH + T, -CW / 2 - T / 2, 0], [0.02, CH + T, CW / 2 + T / 2, 0]]) {
    box(w, h, 0.012, rim, rp.x + x, rp.y + y, fz, root).castShadow = false;
  }
  // Where a message comes to rest in the room: standing in the hole, leaning back.
  const slot = (k) => ({ p: new Vector3(rp.x - 0.03 + k * 0.07, rp.y - CH / 2 + 0.12 - k * 0.03, cz + 0.06 + k * 0.08), rx: -0.28 });

  // SuperMessage: a reading desk with a screen, front left.
  const desk = new Group();
  desk.position.set(-1.3, top, 0.42);
  desk.rotation.y = 0.5;
  box(0.72, 0.05, 0.44, wood, 0, 0.38, 0, desk);
  for (const x of [-0.32, 0.32]) for (const z of [-0.18, 0.18]) box(0.04, 0.36, 0.04, dark, x, 0.18, z, desk);
  box(0.06, 0.16, 0.05, mat('#3a3354'), 0, 0.48, -0.08, desk);
  const frame = new Group();
  frame.position.set(0, 0.62, -0.07);
  frame.rotation.x = -0.18;
  box(0.5, 0.34, 0.03, mat('#3a3354'), 0, 0, 0, frame);
  desk.add(frame);
  root.add(desk);
  const lines = [0.7, 0.5, 0.62].map((f, i) => {
    const l = box(0.42 * f, 0.034, 0.006, mat('#ffffff', true), -0.21 * (1 - f), 0.09 - i * 0.08, 0.018, frame);
    l.castShadow = false;
    glow(l.material, OFF, 0);
    return l.material;
  });
  const deskAt = (x, y, z) => desk.localToWorld(new Vector3(x, y, z));
  desk.updateMatrixWorld();

  // An agent: a squat pod front right, with its light on. It is not part of SuperMessage.
  const pod = new Group();
  pod.position.set(1.35, top, 0.38);
  pod.rotation.y = -0.45;
  box(0.46, 0.5, 0.4, mat('#4a5a6d'), 0, 0.25, 0, pod);
  const podLed = box(0.2, 0.035, 0.012, mat('#ffffff', true), 0, 0.4, 0.206, pod);
  glow(podLed.material, '#70cdab', 0.6);
  podLed.castShadow = false;
  root.add(pod);
  pod.updateMatrixWorld();
  const podAt = (x, y, z) => pod.localToWorld(new Vector3(x, y, z));

  return { top, rim, lines, slot, deskAt, podAt, accent, hole: rp };
}

/** A message card: cream, with a tab whose colour says whose turn it is. */
export function card(root, w = 0.32, h = 0.22) {
  const g = new Group();
  box(w, h, 0.01, mat(CREAM), 0, 0, 0, g);
  const tab = box(w * 0.36, 0.03, 0.012, mat('#ffffff', true), -w * 0.26, h / 2 - 0.03, 0.002, g);
  tab.castShadow = false;
  for (let i = 0; i < 2; i++) box(w * (0.7 - i * 0.2), 0.014, 0.012, mat('#b9ab9d'), -w * 0.08, -i * 0.04 - 0.01, 0.002, g);
  root.add(g);
  return { g, tab: tab.material };
}

/** Moves `g` along a raised arc from `a` to `b` (each { p, rx }), `p` of the way (0 to 1). */
export function fly(g, a, b, p, lift = 0.35) {
  g.position.lerpVectors(a.p, b.p, p);
  g.position.y += Math.sin(p * Math.PI) * lift;
  g.rotation.x = a.rx + (b.rx - a.rx) * p;
}

/** A cable along the floor that lights from its start, `k` of the way. */
export function cable(root, pts, accent) {
  const SEG = 48, R = 6;
  const path = new CatmullRomCurve3(pts.map((p) => new Vector3(...p)));
  root.add(new Mesh(new TubeGeometry(path, SEG, 0.016, R), mat('#4a4266')));
  const lit = new Mesh(new TubeGeometry(path, SEG, 0.02, R), mat('#ffffff', true));
  glow(lit.material, accent, 0.5);
  lit.castShadow = false;
  root.add(lit);
  return (k) => lit.geometry.setDrawRange(0, Math.round(k * SEG) * R * 6);
}
