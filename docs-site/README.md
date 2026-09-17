# supermessage docs site

The user-facing documentation for `docs.supermessage.dev`. Astro + Starlight.

```sh
npm install
npm run dev     # local preview
npm run build   # -> dist/
```

## How it deploys

Cloudflare Pages project `supermessage-docs`, connected to this repository in the dashboard, the
same way as the landing page's `supermessage-site` — there is no workflow that deploys it.

| | |
|---|---|
| Root directory | `docs-site` |
| Build command | `npm ci && npm run build` |
| Build output | `dist` |
| Production branch | `main`, automatic deployments on |

The build command has to install, for the reason `landing/README.md` gives: Cloudflare sees
`packageManager: pnpm` at the repo root and installs *there*, which leaves this directory's
`node_modules` empty and fails with `astro: not found`.

It was deployed by hand once, and went stale the way that always happens: docs.supermessage.dev
went on naming `dev.kaambaan.gate.v1` and linking docs.kaambaan.dev long after `main` had moved to
superpipeline, because nothing fails when a page nobody redeploys drifts. A CI job replaced that
briefly; the Git connection replaced the job, and needs no API token in this repository.

CI's `docs` job still builds the site on every pull request that touches it, so a broken page fails
the PR rather than the deploy.

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
