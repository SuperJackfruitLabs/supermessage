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
  roster: '[aria-label="Rooms"]',
  timeline: '[data-testid="timeline"], main',
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
    const names = readdirSync(DIR).filter((n) => n.endsWith(".html"));
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
        "usage: dom-baseline.mjs normalise <label> <surface>  (raw HTML on stdin)\n" +
          "       dom-baseline.mjs diff <before-label> <after-label>\n" +
          "       dom-baseline.mjs list",
      );
      process.exit(2);
  }
}
