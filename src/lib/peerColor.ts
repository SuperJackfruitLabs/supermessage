/**
 * Which of the seven person colours a user is drawn in.
 *
 * A port of the core's `peer_color::peer_color_index` (32-bit FNV-1a over the
 * UTF-8 bytes, modulo 7), because the desktop draws a name per row and cannot
 * round-trip to the core for each. `peerColor.test.ts` pins the same vectors
 * as the Rust test, so the two cannot drift without one of them failing.
 * The colours are `--color-peer-0` … `--color-peer-6` (design/tokens.toml).
 */
export const PEER_COUNT = 7;

const encoder = new TextEncoder();

export function peerColorIndex(userId: string): number {
  let hash = 0x811c9dc5;
  for (const byte of encoder.encode(userId)) {
    hash ^= byte;
    hash = Math.imul(hash, 0x01000193) >>> 0;
  }
  return hash % PEER_COUNT;
}

/** The CSS colour for a person's name. */
export function peerColorVar(userId: string): string {
  return `var(--color-peer-${peerColorIndex(userId)})`;
}
