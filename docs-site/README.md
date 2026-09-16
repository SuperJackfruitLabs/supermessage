# supermessage docs site

The user-facing documentation for `docs.supermessage.dev`. Astro + Starlight.

```sh
npm install
npm run dev     # local preview
npm run build   # -> dist/
```

**Not published yet.** The client has no public build, and documentation for software nobody can
install is documentation nobody can check. The site is built and ready; publishing waits for
TestFlight. Every page carries a "not released yet" notice until then.

## Why this is not a workspace member

It uses npm and sits at the repo root, outside the pnpm workspace. Astro 7 brings Vite 8, which
re-resolves a shared tree's vitest and breaks vitest-based suites — in superpipeline the same
arrangement took out 556 tests on a change that touched no product code. The same choice was made
in all three repos for the same reason.

## Claims are checked

`crates/supermessage-core/tests/docs_claims.rs` reads these pages and fails if one names a suite
event type no renderer handles, a roster state the roster does not produce, or an internal link
that does not resolve — and if a page lacks a title or description.

This exists because the audit that produced this site found the alternative's cost.
`docs/agentpod-events.md` named two event types that exist in neither this client nor agentpod's
hub, and `docs/matrix-events.md` described as future work a refactor that had already shipped.
Both files' own headers claimed they were written against the code.

```sh
cargo test -p supermessage-core --test docs_claims
```
