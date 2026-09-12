/**
 * The normaliser decides what "identical DOM" is allowed to mean, so it is
 * the one piece of the gate that can quietly make the gate worthless.
 *
 * Strip too much and a broken refactor reports identical. Strip too little
 * and every extraction is red, which means by the third one nobody looks.
 * Both directions are asserted.
 */
import assert from "node:assert/strict";

import { normalise } from "../dom-baseline.mjs";

let passed = 0;
function check(name, fn) {
  fn();
  passed++;
  console.log(`  ok  ${name}`);
}

// ---------------------------------------------------------------- ignores --

check("a scoped-class hash change alone is not a difference", () => {
  // Svelte rehashes when markup moves between files. This is THE case the
  // normaliser exists for: without it, every extraction is a false red.
  assert.equal(
    normalise('<div class="row s-aB3dEf9">x</div>'),
    normalise('<div class="row s-Zq7Yt2X">x</div>'),
  );
});

check("a class attribute left empty by stripping is dropped", () => {
  assert.equal(normalise('<i class="s-aB3dEf9"></i>'), normalise("<i></i>"));
});

check("whitespace reformatting is not a difference", () => {
  assert.equal(normalise("<ul>\n  <li>a</li>\n</ul>"), normalise("<ul><li>a</li></ul>"));
});

check("a fresh blob URL per run is not a difference", () => {
  assert.equal(
    normalise('<img src="blob:http://localhost/abc-123">'),
    normalise('<img src="blob:http://localhost/zzz-999">'),
  );
});

check("a moved clock is not a difference", () => {
  assert.equal(
    normalise("<time>2026-09-12T11:00:00Z</time>"),
    normalise("<time>2026-09-12T18:42:07.123Z</time>"),
  );
  assert.equal(normalise("<span>3 minutes ago</span>"), normalise("<span>just now</span>"));
});

// ----------------------------------------------------------------- catches --

check("a changed element is a difference", () => {
  assert.notEqual(
    normalise('<div class="row"><span>x</span></div>'),
    normalise('<div class="row"><em>x</em></div>'),
  );
});

check("a changed attribute VALUE is a difference", () => {
  // The a11y-relevant case, and the one a too-greedy strip would swallow.
  assert.notEqual(
    normalise('<button aria-label="Room info">i</button>'),
    normalise('<button aria-label="Info">i</button>'),
  );
});

check("a dropped attribute is a difference", () => {
  assert.notEqual(
    normalise('<div class="group relative">x</div>'),
    normalise('<div class="group">x</div>'),
  );
});

check("a changed real class is a difference", () => {
  // Token renames must show up: rounded-md -> rounded-control was a real
  // change in P1 and the gate has to be able to see that kind of thing.
  assert.notEqual(
    normalise('<div class="rounded-control s-aB3dEf9">x</div>'),
    normalise('<div class="rounded-card s-aB3dEf9">x</div>'),
  );
});

check("changed text content is a difference", () => {
  assert.notEqual(normalise("<p>Approval needed</p>"), normalise("<p>Approval requested</p>"));
});

check("a reordered sibling is a difference", () => {
  assert.notEqual(
    normalise("<ul><li>a</li><li>b</li></ul>"),
    normalise("<ul><li>b</li><li>a</li></ul>"),
  );
});

console.log(`\ndom-baseline normaliser: ${passed} assertions passed`);
