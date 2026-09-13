/**
 * Fixtures must never reach the production bundle.
 *
 * Nothing in application code imports `$lib/fixtures`, so Vite tree-shakes
 * it — but "so it should" is a belief, and `FIXTURE_MARKER` exists to turn
 * it into a check. The marker is deliberately long and unlikely: a short
 * one risks colliding with minified output and reporting a leak that is not
 * one.
 */
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";

const MARKER = "__supermessage_fixture_marker_do_not_ship__";
const ROOT = "build";

function* files(dir) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) yield* files(p);
    else yield p;
  }
}

let scanned = 0;
const hits = [];
for (const path of files(ROOT)) {
  scanned++;
  if (readFileSync(path, "utf8").includes(MARKER)) hits.push(path);
}

// Guard the guard: scanning nothing would pass silently, which is the same
// failure as an empty DOM capture diffing clean against another empty one.
if (scanned === 0) {
  console.error(`::error::${ROOT}/ contained no files — run \`pnpm build\` first`);
  process.exit(2);
}

if (hits.length > 0) {
  console.error(`::error::fixtures reached the production bundle: ${hits.join(", ")}`);
  process.exit(1);
}

console.log(`no fixture marker in ${scanned} built files — fixtures are not shipped`);
