// "What it's like to use": a turn, in the post room. You are signed in, so the room is lit. While
// the agent is still writing, its live text goes straight to the desk as a small light that never
// passes the pigeonholes (to-device messages, not room history), and shows on the screen. Then the
// finished turn lands in the room as a card, and the live line on the screen gives way to it: only
// the finished turn stays.
import { Group, IcosahedronGeometry, Mesh } from 'three';
import { mat } from './kit.js';
import { clamp, smooth } from './common.js';
import { CREAM, OFF, cable, card, fly, glow, postRoom } from './room.js';

export default {
  view: { look: [0.08, 0.76, 0.0], dist: 7.1, rise: 0.36, yaw: -0.24, aspect: 4 / 3, fov: 28 },
  shadow: { left: -2.6, right: 2.6, top: 2.4, bottom: -1.4 },
  beats: 1,
  build(scene, { accent, night }) {
    const root = new Group();
    scene.add(root);
    const r = postRoom(root, accent);
    const lit = 0.7 + 0.4 * night;
    cable(root, [r.deskAt(0.1, 0.02, -0.2).toArray(), [-0.9, r.top + 0.02, -0.1], [-0.6, r.top + 0.02, -0.25]], accent)(1);
    glow(r.rim, accent, lit);

    const bead = new Mesh(new IcosahedronGeometry(0.045, 0), mat('#ffffff', true));
    bead.material.emissive.copy(accent).lerp(bead.material.color, 0.4);
    root.add(bead);
    const turn = card(root);
    glow(turn.tab, accent, 0.4);
    const fromPod = { p: r.podAt(0, 0.56, 0.05), rx: -Math.PI / 2 };
    const toDesk = { p: r.deskAt(0, 0.66, 0.0), rx: 0 };
    const inRoom = r.slot(0);

    return {
      set(_beat, t) {
        // The live text: pod to desk, low over the floor, then gone.
        const a = smooth(clamp(t / 0.4)), arrived = t >= 0.4;
        bead.visible = t > 0 && !arrived;
        fly(bead, fromPod, toDesk, a, 0.12);
        // The finished turn: pod to the room.
        const b = smooth(clamp((t - 0.5) / 0.42)), landed = t >= 0.92;
        turn.g.visible = t >= 0.5;
        fly(turn.g, fromPod, inRoom, b, 0.45);
        const liveLine = arrived && !landed;
        glow(r.lines[0], CREAM, 0.5);
        glow(r.lines[1], liveLine ? accent : OFF, liveLine ? 0.6 : 0);
        glow(r.lines[2], landed ? CREAM : OFF, landed ? 0.5 : 0);
      },
    };
  },
};
