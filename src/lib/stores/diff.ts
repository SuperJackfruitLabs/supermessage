// The single place diff application lives on the frontend.
//
// Mirrors the wire format emitted by the Rust core
// (`src-tauri/src/core/dto.rs::DiffOp`/`DiffEnvelope`) and the semantics of
// its `apply_ops` function operation-for-operation, including how
// out-of-range indices are handled (skipped, never thrown). If this file and
// `dto::apply_ops` ever diverge, resyncs silently corrupt state, since the
// core's resync snapshot is maintained by folding the same op stream through
// `apply_ops`.

/** Mirrors `DiffOp<T>` from `src-tauri/src/core/dto.rs`, tag-for-tag. */
export type DiffOp<T> =
  | { op: "append"; values: T[] }
  | { op: "clear" }
  | { op: "pushFront"; value: T }
  | { op: "pushBack"; value: T }
  | { op: "popFront" }
  | { op: "popBack" }
  | { op: "insert"; index: number; value: T }
  | { op: "set"; index: number; value: T }
  | { op: "remove"; index: number }
  | { op: "truncate"; length: number }
  | { op: "reset"; values: T[] };

/** Mirrors `DiffEnvelope<T>` from `src-tauri/src/core/dto.rs`. */
export type DiffEnvelope<T> = {
  channel: string;
  subject: string;
  seq: number;
  ops: DiffOp<T>[];
};

/**
 * Applies a batch of ops to `items`, returning a new array. Never mutates
 * `items` or any of the arrays it hands back.
 *
 * Agrees with `dto::apply_ops` operation for operation, including out-of-
 * range handling: `set`/`remove` on an out-of-bounds index are no-ops,
 * `popFront`/`popBack` on an empty list are no-ops, and `insert` is a no-op
 * when `index > length` but a valid append when `index === length`.
 */
export function applyOps<T>(items: T[], ops: DiffOp<T>[]): T[] {
  let result = items.slice();

  for (const op of ops) {
    switch (op.op) {
      case "append":
        result = result.concat(op.values);
        break;
      case "clear":
        result = [];
        break;
      case "pushFront":
        result = [op.value, ...result];
        break;
      case "pushBack":
        result = [...result, op.value];
        break;
      case "popFront":
        if (result.length > 0) result = result.slice(1);
        break;
      case "popBack":
        if (result.length > 0) result = result.slice(0, -1);
        break;
      case "insert":
        if (op.index <= result.length) {
          result = [
            ...result.slice(0, op.index),
            op.value,
            ...result.slice(op.index),
          ];
        }
        break;
      case "set":
        if (op.index >= 0 && op.index < result.length) {
          result = result.slice();
          result[op.index] = op.value;
        }
        break;
      case "remove":
        if (op.index >= 0 && op.index < result.length) {
          result = [
            ...result.slice(0, op.index),
            ...result.slice(op.index + 1),
          ];
        }
        break;
      case "truncate":
        result = result.slice(0, op.length);
        break;
      case "reset":
        result = op.values.slice();
        break;
    }
  }

  return result;
}

/**
 * Tracks a materialized list built from a stream of `DiffEnvelope`s and
 * detects a dropped envelope via its sequence number, so the app can force a
 * resync instead of silently applying partial state.
 *
 * Sequence numbers start at 1 (see `dto::SeqCounter`).
 */
export class DiffTracker<T> {
  #items: T[] = [];
  #expectedSeq = 1;

  get items(): T[] {
    return this.#items;
  }

  /**
   * Applies `env` if it is the next expected envelope. Returns `"gap"`
   * without touching state if `env.seq` is ahead of what's expected (an
   * envelope was missed) — applying partial state on a gap is exactly the
   * corruption this class exists to prevent. Returns `"ok"` and ignores
   * `env` if it is behind what's expected (a duplicate).
   */
  apply(env: DiffEnvelope<T>): "ok" | "gap" {
    if (env.seq > this.#expectedSeq) {
      return "gap";
    }
    if (env.seq < this.#expectedSeq) {
      return "ok";
    }

    this.#items = applyOps(this.#items, env.ops);
    this.#expectedSeq += 1;
    return "ok";
  }

  /** Resets the tracked state after a resync, e.g. following a "gap". */
  reset(items: T[], seq: number): void {
    this.#items = items.slice();
    this.#expectedSeq = seq + 1;
  }
}
