// "How it works", one beat per step, in the post room.
//   1. Sign in to a Matrix homeserver: the desk's line to the pigeonholes lights, and the room's
//      frame lights. The desk has no shelves: SuperMessage keeps no store of its own.
//   2. Agents post into the room: a card leaves the agent's pod and lands in the room's hole,
//      its tab amber (waiting on you), and the desk's screen shows it.
//   3. You answer on the card: a reply slip goes from the desk into the room, and the card's tab
//      turns from amber to grey. Answered is not resolved: it is no longer waiting on you, but no
//      receipt has arrived yet, so it does not turn into one.
import { Group } from 'three';
import { clamp, smooth } from './common.js';
import { AMBER, CREAM, NEUTRAL, OFF, cable, card, fly, glow, postRoom } from './room.js';

export default {
  view: { look: [0.08, 0.76, 0.0], dist: 7.1, rise: 0.36, yaw: -0.24, aspect: 4 / 3, fov: 28 },
  shadow: { left: -2.6, right: 2.6, top: 2.4, bottom: -1.4 },
  beats: 3,
  build(scene, { accent, night }) {
    const root = new Group();
    scene.add(root);
    const r = postRoom(root, accent);
    const lit = 0.7 + 0.4 * night;
    const link = cable(root, [r.deskAt(0.1, 0.02, -0.2).toArray(), [-0.9, r.top + 0.02, -0.1], [-0.6, r.top + 0.02, -0.25]], accent);

    const gate = card(root);
    const reply = card(root, 0.22, 0.15);
    glow(reply.tab, accent, 0.4);
    const fromPod = { p: r.podAt(0, 0.56, 0.05), rx: -Math.PI / 2 };
    const fromDesk = { p: r.deskAt(0, 0.82, 0.05), rx: -0.2 };
    const inRoom = r.slot(0), replyRoom = r.slot(1);

    return {
      set(beat, t) {
        const b = (k) => (k < beat ? 1 : k > beat ? 0 : t);
        // 1: signed in.
        const sign = smooth(clamp(b(1) / 0.8)), signed = b(1) >= 0.8;
        link(sign);
        glow(r.rim, signed ? accent : OFF, signed ? lit : 0);
        // 2: the agent's card, into the room.
        const post = smooth(clamp(b(2) / 0.85)), posted = b(2) >= 0.85;
        gate.g.visible = b(2) > 0;
        fly(gate.g, fromPod, inRoom, post, 0.45);
        // 3: your answer, into the room.
        const ans = smooth(clamp(b(3) / 0.85)), answered = b(3) >= 0.85;
        reply.g.visible = b(3) > 0;
        fly(reply.g, fromDesk, replyRoom, ans, 0.3);
        glow(gate.tab, answered ? NEUTRAL : AMBER, answered ? 0.15 : 0.55);
        // The desk's screen: the room's messages once signed in, the card once it is there.
        const show = [signed, posted, answered];
        r.lines.forEach((m, i) => glow(m, show[i] ? (i === 1 ? (answered ? NEUTRAL : AMBER) : CREAM) : OFF, show[i] ? 0.5 : 0));
      },
    };
  },
};
