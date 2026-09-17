# supermessage docs site

The user-facing documentation for `docs.supermessage.dev`. Astro + Starlight.

```sh
npm install
npm run dev     # local preview
npm run build   # -> dist/
```

## How it deploys

CI deploys `dist/` to the Cloudflare Pages project `supermessage-docs` on every push to `main` that
touches this directory — the `deploy-docs` job in `.github/workflows/ci.yml`. The project is not
connected to Git, unlike the landing page's `supermessage-site`; the job is what keeps it current.

It used to be deployed by hand, and the live site went stale the same way that always happens:
docs.supermessage.dev went on naming `dev.kaambaan.gate.v1` and linking docs.kaambaan.dev long after
`main` had moved to superpipeline, because nothing fails when a page nobody redeploys drifts.

The job needs a `CLOUDFLARE_API_TOKEN` secret with Pages edit rights on the account. Read the long
comment on the job before changing how it calls wrangler.

**The desktop app has unsigned previews on GitHub Releases, and there is no public mobile build.**
The home page and "What supermessage is" say so in an "Early preview" notice, which should change
when that does.

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
