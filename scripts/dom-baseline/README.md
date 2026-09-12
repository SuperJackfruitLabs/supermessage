# DOM baselines

A refactor that only moves markup must not change what the app renders. The
386 vitest tests cannot check that: `vite.config.js` pins
`environment: "node"` and there are **zero component render tests** —
`src/lib/components/timelineActionAnchor.test.ts` documents why. So the gate
for the P2a extraction is a before/after diff of the real app's DOM.

## Usage

Start the app with the MCP bridge, with a session already in the keyring:

```bash
pnpm tauri:mcp
```

For each surface in `SURFACES` (see `../dom-baseline.mjs`), read
`document.querySelector(sel).outerHTML` from the webview and pipe it in:

```bash
node scripts/dom-baseline.mjs normalise before roster   < roster.raw.html
node scripts/dom-baseline.mjs normalise before timeline < timeline.raw.html
```

Then, after a task:

```bash
node scripts/dom-baseline.mjs diff before after-task-7
```

Exit 0 and "identical" on both surfaces is the pass. A non-zero exit prints
the first divergence with context.

`node scripts/dom-baseline.mjs list` shows what has been captured.

## What the normaliser deliberately ignores, and why

| Ignored | Reason |
|---|---|
| Svelte scoped-class hashes (`s-aB3dEf9`) | **Change by design** when markup moves to a new file. A diff that counted them would be red on every extraction and ignored by the third one. |
| `blob:` / `data:` URLs | Media resolves to a fresh URI per run. |
| ISO timestamps and relative times | Move between runs. |
| Whitespace between tags | Reformatting is not a rendering change. |

Everything else is structural and counts: elements, attribute values
(including `aria-*`), real class names, text content, sibling order.

`scripts/tests/test_dom_baseline.mjs` asserts both directions and is
mutation-proven — stripping `aria-*` too, dropping the hash rule, or
collapsing all classes each fail it. A normaliser that strips too much
reports "identical" for a broken refactor, which is worse than no gate.

## The captures are not committed

`.gitignore` drops `scripts/dom-baseline/*.html`. They are
machine-specific, and they contain real message content from whichever
account was signed in.

## Baseline provenance — fill this in when capturing

A baseline is only comparable against the same content. If the account's
rooms change mid-project, the comparison is void and the gate silently
becomes decorative.

- **Captured:** _(date)_
- **Account:** _(matrix id)_
- **Room open for the timeline capture:** _(room id / name)_
- **What that room contained:** _(a sender run / a reply / reactions / a
  dispatch card — note which were actually present)_
- **Appearance:** light
