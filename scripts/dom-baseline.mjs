#!/usr/bin/env node
/**
 * Serialise the running app's DOM, so a refactor can be proven not to have
 * changed it.
 *
 * This exists because the 386 vitest tests are pure-module tests in a node
 * environment — `vite.config.js` pins `environment: "node"` and there are
 * zero component render tests. `timelineActionAnchor.test.ts` documents the
 * reason. Extraction moves markup, which is exactly what nothing covers, so
 * a pure extraction must produce identical DOM and the diff IS the test.
 *
 * Normalisation strips what legitimately varies between runs. Everything
 * structural stays — see the comments per rule, each of which is a decision
 * about what "identical" is allowed to mean.
 */
import { mkdirSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { argv } from "node:process";
import { pathToFileURL } from "node:url";

const DIR = "scripts/dom-baseline";

/**
 * The surfaces worth comparing, and the selector that finds each.
 *
 * Two, not more: the roster and the reading surface are where all fifteen
 * components end up rendering, and a selector that matches nothing produces
 * an empty capture that diffs clean against another empty capture — a gate
 * that passes by finding nothing is worse than no gate, which is why
 * `normalise` refuses empty input.
 */
export const SURFACES = {
  // The roster side: the spaces rail and the room list together, so one
  // capture covers SpacesRail, RoomList and its rows.
  roster: "div.flex.shrink-0.bg-surface-sunken",
  // The whole room pane: header, timeline, typing strip and composer. Four
  // of the fifteen components in one capture.
  //
  // Chosen after looking at the running app. The first guess —
  // `[data-testid="timeline"], main` — matched NOTHING: there is no
  // data-testid anywhere in this app and no <main>. An empty capture diffs
  // clean against another empty one, so the gate would have passed by
  // finding nothing.
  roompane: "section",
};

export function normalise(html) {
  return (
    html
      // Svelte scopes styles with a per-file hash. Moving markup to a new
      // file changes that hash BY DESIGN, so it cannot be part of the
      // comparison — a diff that counted it would be red on every single
      // extraction and would therefore be ignored by the third one.
      .replace(/\s?s-[A-Za-z0-9_-]{6,}/g, "")
      .replace(/\sclass="\s*"/g, "")
      // Media resolves to a fresh blob/data URI per run.
      .replace(/(src|href)="(blob:|data:)[^"]*"/g, '$1="[resolved]"')
      // Absolute timestamps, and the relative times rendered from them.
      .replace(/\d{4}-\d{2}-\d{2}T[\d:.]+Z?/g, "[time]")
      .replace(
        />[^<]*\b(just now|\d+\s*(m|h|d|minutes?|hours?|days?)( ago)?)\b[^<]*</gi,
        ">[relative]<",
      )
      // Collapse whitespace, so reformatting is not a difference.
      .replace(/>\s+</g, "><")
      .replace(/\s+/g, " ")
      .trim()
  );
}

function capturePath(label, surface) {
  return join(DIR, `${label}.${surface}.html`);
}

/**
 * A fingerprint of normalised markup: a hash, a length, and a tag histogram.
 *
 * Storing the whole serialised DOM would mean routing tens of kilobytes per
 * surface per task through whatever is driving the capture. The hash answers
 * the only question the gate asks — did this change? — and the histogram
 * makes a failure readable without it. When a diff does appear, pull the
 * full markup then; that is the one time it is worth the bytes.
 */
export function fingerprint(html) {
  const text = normalise(html);
  const tags = {};
  for (const m of text.matchAll(/<([a-z][a-z0-9-]*)/gi)) {
    const t = m[1].toLowerCase();
    tags[t] = (tags[t] ?? 0) + 1;
  }
  return { length: text.length, tags, digest: cyrb53(text) };
}

/**
 * A synchronous, non-cryptographic digest (cyrb53).
 *
 * Not sha256, and deliberately: the digest has to be computable INSIDE the
 * webview, and SubtleCrypto is async — the MCP bridge times out on a script
 * that returns a promise. cyrb53 is sync, deterministic and has ample
 * collision resistance for "did this markup change", which is the only
 * question asked. It is not used for anything security-bearing.
 *
 * Kept here as well as in the capture snippet so a fingerprint recorded from
 * raw HTML is comparable with one computed in the webview.
 */
export function cyrb53(text, seed = 0) {
  let h1 = 0xdeadbeef ^ seed;
  let h2 = 0x41c6ce57 ^ seed;
  for (let i = 0; i < text.length; i++) {
    const ch = text.charCodeAt(i);
    h1 = Math.imul(h1 ^ ch, 2654435761);
    h2 = Math.imul(h2 ^ ch, 1597334677);
  }
  h1 = Math.imul(h1 ^ (h1 >>> 16), 2246822507) ^ Math.imul(h2 ^ (h2 >>> 13), 3266489909);
  h2 = Math.imul(h2 ^ (h2 >>> 16), 2246822507) ^ Math.imul(h1 ^ (h1 >>> 13), 3266489909);
  return (4294967296 * (2097151 & h2) + (h1 >>> 0)).toString(16).padStart(14, "0");
}

function cmdRecord(args) {
  const [label, surface] = args;
  if (!label || !surface) {
    console.error(
      "usage: dom-baseline.mjs record <label> <surface>\n" +
        "       stdin: raw HTML, or a fingerprint JSON computed in the webview",
    );
    process.exit(2);
  }
  const raw = readFileSync(0, "utf8");
  if (raw.trim().length === 0) {
    console.error(
      `::error::empty capture for ${surface} — the selector matched nothing.\n` +
        `An empty capture diffs clean against another empty one, so this is\n` +
        `refused rather than written. Check SURFACES.${surface}.`,
    );
    process.exit(1);
  }
  mkdirSync(DIR, { recursive: true });
  // Either a fingerprint computed in the webview, or raw markup to fingerprint
  // here. The webview path exists so 60KB of markup per surface per task does
  // not have to travel through whatever is driving the capture.
  let fp;
  const trimmed = raw.trim();
  if (trimmed.startsWith("{")) {
    fp = JSON.parse(trimmed);
    for (const k of ["length", "tags", "digest"]) {
      if (!(k in fp)) {
        console.error(`::error::fingerprint JSON is missing "${k}"`);
        process.exit(1);
      }
    }
  } else {
    fp = fingerprint(raw);
  }
  const out = join(DIR, `${label}.${surface}.json`);
  writeFileSync(out, JSON.stringify(fp, null, 2) + "\n");
  console.log(`wrote ${out}  ${fp.length} bytes normalised, digest ${fp.digest}`);
}

function cmdCompare(args) {
  const [a, b] = args;
  if (!a || !b) {
    console.error("usage: dom-baseline.mjs compare <before-label> <after-label>");
    process.exit(2);
  }
  let failed = false;
  for (const surface of Object.keys(SURFACES)) {
    const pa = join(DIR, `${a}.${surface}.json`);
    const pb = join(DIR, `${b}.${surface}.json`);
    let fa, fb;
    try {
      fa = JSON.parse(readFileSync(pa, "utf8"));
      fb = JSON.parse(readFileSync(pb, "utf8"));
    } catch (err) {
      console.error(`  ${surface}: MISSING CAPTURE — ${err.message}`);
      failed = true;
      continue;
    }
    if (fa.digest === fb.digest) {
      console.log(`  ${surface}: identical (${fa.length} bytes, digest ${fa.digest})`);
      continue;
    }
    failed = true;
    console.error(
      `  ${surface}: DIFFERS — ${fa.length} vs ${fb.length} bytes ` +
        `(digest ${fa.digest} vs ${fb.digest})`,
    );
    const tags = new Set([...Object.keys(fa.tags), ...Object.keys(fb.tags)]);
    for (const t of [...tags].sort()) {
      const x = fa.tags[t] ?? 0;
      const y = fb.tags[t] ?? 0;
      if (x !== y) console.error(`      <${t}>: ${x} -> ${y}`);
    }
    console.error(
      `      tag counts alone may match while attributes differ — the digest is\n` +
        `      the authority. Re-capture the full markup to see where.`,
    );
  }
  process.exit(failed ? 1 : 0);
}

function cmdNormalise(args) {
  const [label = "unlabelled", surface = "surface"] = args;
  const raw = readFileSync(0, "utf8");
  if (raw.trim().length === 0) {
    console.error(
      `::error::empty capture for ${surface} — the selector matched nothing.\n` +
        `An empty capture diffs clean against another empty one, so this is\n` +
        `refused rather than written. Check SURFACES.${surface}.`,
    );
    process.exit(1);
  }
  mkdirSync(DIR, { recursive: true });
  const out = capturePath(label, surface);
  writeFileSync(out, normalise(raw));
  console.log(`wrote ${out} (${normalise(raw).length} bytes normalised)`);
}

function cmdDiff(args) {
  const [a, b] = args;
  if (!a || !b) {
    console.error("usage: dom-baseline.mjs diff <before-label> <after-label>");
    process.exit(2);
  }
  let failed = false;
  for (const surface of Object.keys(SURFACES)) {
    const pa = capturePath(a, surface);
    const pb = capturePath(b, surface);
    let ta, tb;
    try {
      ta = readFileSync(pa, "utf8");
      tb = readFileSync(pb, "utf8");
    } catch (err) {
      console.error(`  ${surface}: MISSING CAPTURE — ${err.message}`);
      failed = true;
      continue;
    }
    if (ta === tb) {
      console.log(`  ${surface}: identical (${ta.length} bytes)`);
      continue;
    }
    failed = true;
    console.error(`  ${surface}: DIFFERS (${ta.length} vs ${tb.length} bytes)`);
    // The first divergence with context. A 100KB unified diff is unreadable
    // and nobody would read it, so this prints the one place to look.
    let i = 0;
    while (i < Math.min(ta.length, tb.length) && ta[i] === tb[i]) i++;
    const from = Math.max(0, i - 60);
    console.error(`    first divergence at byte ${i}:`);
    console.error(`      ${a}: …${ta.slice(from, i + 140)}`);
    console.error(`      ${b}: …${tb.slice(from, i + 140)}`);
  }
  process.exit(failed ? 1 : 0);
}

function cmdList() {
  try {
    const names = readdirSync(DIR).filter((n) => n.endsWith(".json") || n.endsWith(".html"));
    if (names.length === 0) console.log("  (no captures yet)");
    for (const n of names.sort()) console.log(`  ${n}`);
  } catch {
    console.log("  (no captures yet)");
  }
}

/**
 * Only act as a CLI when run directly.
 *
 * Without this guard, importing `normalise` from the test file runs the
 * switch below, hits the default branch and exits 2 before a single
 * assertion executes — which is exactly what happened the first time.
 */
const runDirectly = argv[1] && import.meta.url === pathToFileURL(argv[1]).href;

if (runDirectly) {
  const [, , cmd, ...args] = argv;
  switch (cmd) {
    case "record":
      cmdRecord(args);
      break;
    case "compare":
      cmdCompare(args);
      break;
    case "normalise":
      cmdNormalise(args);
      break;
    case "diff":
      cmdDiff(args);
      break;
    case "list":
      cmdList();
      break;
    default:
      console.error(
        "usage: dom-baseline.mjs record <label> <surface>   (raw HTML on stdin)\n" +
          "       dom-baseline.mjs compare <before> <after>\n" +
          "       dom-baseline.mjs normalise <label> <surface> (writes full HTML)\n" +
          "       dom-baseline.mjs diff <before> <after>       (full-HTML diff)\n" +
          "       dom-baseline.mjs list",
      );
      process.exit(2);
  }
}
