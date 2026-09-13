#!/usr/bin/env node
/**
 * Compare the STRUCTURE of a Svelte component's markup across two git revs.
 *
 * The DOM baseline is the better gate, but it needs a running app against a
 * live account — and a live account drifts. Real messages arrived mid-project
 * and the room's date dividers went from "1 SEP" to "11 SEP"/"12 SEP",
 * which voids the comparison through no fault of the code.
 *
 * This is the static half, and it answers a narrower question that needs no
 * app at all: did the markup's SHAPE change, or only the expressions inside
 * it? A props-down conversion should change only the latter — `{store.x}`
 * becomes `{x}` — and if the element tree, the attribute names and the
 * literal text are identical, the rendered DOM cannot differ except through
 * the values now arriving by prop.
 *
 * What it deliberately ignores: the contents of `{...}` expressions, since
 * changing those IS the conversion. What it keeps: every tag, every
 * attribute name, every class literal, and all static text.
 */
import { execSync } from "node:child_process";

function markupOf(source) {
  // Everything after </script>, minus the <style> block.
  const afterScript = source.slice(source.lastIndexOf("</script>") + 9);
  const styleAt = afterScript.indexOf("<style");
  return styleAt === -1 ? afterScript : afterScript.slice(0, styleAt);
}

function shape(markup) {
  return (
    markup
      // The expressions are what a props-down conversion changes.
      .replace(/\{[^{}]*(\{[^{}]*\}[^{}]*)*\}/g, "{EXPR}")
      // Svelte comments carry rationale, not structure.
      .replace(/<!--[\s\S]*?-->/g, "")
      .replace(/\s+/g, " ")
      .trim()
  );
}

const [, , rev, ...files] = process.argv;
if (!rev || files.length === 0) {
  console.error("usage: markup-shape.mjs <git-rev> <file...>");
  process.exit(2);
}

let differing = 0;
for (const file of files) {
  let before;
  try {
    before = execSync(`git show ${rev}:${file}`, { encoding: "utf8", stdio: ["pipe", "pipe", "pipe"] });
  } catch {
    console.log(`  ${file}: new file, nothing to compare`);
    continue;
  }
  const after = execSync(`cat ${file}`, { encoding: "utf8" });
  const a = shape(markupOf(before));
  const b = shape(markupOf(after));
  if (a === b) {
    console.log(`  ${file}: shape identical (${a.length} chars)`);
  } else {
    differing++;
    console.error(`  ${file}: SHAPE DIFFERS (${a.length} vs ${b.length} chars)`);
    let i = 0;
    while (i < Math.min(a.length, b.length) && a[i] === b[i]) i++;
    console.error(`      first divergence at ${i}:`);
    console.error(`        ${rev}: …${a.slice(Math.max(0, i - 50), i + 110)}`);
    console.error(`        now:   …${b.slice(Math.max(0, i - 50), i + 110)}`);
  }
}
process.exit(differing > 0 ? 1 : 0);
