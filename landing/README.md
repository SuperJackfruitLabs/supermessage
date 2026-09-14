# supermessage landing page

The public page at `supermessage.dev`. Astro, one page, no framework.

```sh
npm install
npm run dev     # http://localhost:4325
npm run build   # -> dist/
```

## How it deploys

Cloudflare Pages, wired to this repository through the dashboard — there is no
`wrangler.toml` and no workflow, so this section is the only record of it.

| | |
|---|---|
| Root directory | `landing` |
| Build command | `npm ci && npm run build` |
| Build output | `dist` |
| Production branch | `main`, automatic deployments on |

**The build command has to install, and that is not the default.** Cloudflare
detects `packageManager: pnpm` in the repo root and runs `pnpm install`
*there* — 613 packages of the root workspace. But this directory is
deliberately outside that workspace (see the next section), so
`landing/node_modules` stays empty and the build fails with:

```
> astro build
sh: 1: astro: not found
```

which reads like a broken dependency and is really a working directory. Since
Pages runs the build command with the cwd already set to the root directory,
`npm ci` there installs from this project's own `package-lock.json` and fixes
it. `ci` rather than `install` so the lockfile is authoritative and a drifted
`package.json` fails the build instead of silently resolving something else.

## Why npm, and why it sits at the repo root

Same reason as `docs-site/`: Astro 7 brings Vite 8, and putting that in the shared tree
re-resolves the root `vitest` and breaks vitest-based suites. This repo's only pnpm project is
the root itself, so a sibling directory with its own `npm` install cannot interact with it.

## What is deliberate about the page

**The hero is a room transcript**, not a headline over a gradient. The one thing that makes this
client different is that an agent's turn, its request for permission, and a gate waiting on a
human all arrive *in* the conversation — so the page shows that sequence rather than describing
it.

**Every label in that transcript is real.** The event types are the ones
`crates/supermessage-core/src/custom_events.rs` registers, and the field names — `Did`,
`Wants to`, `Card`, `Stage` — are the ones its renderers actually emit. The four roster words are
`AgentState::word`'s. If any of those change, this page becomes wrong; a comment at the top of
`src/pages/index.astro` says so.

**Amber is spent on exactly one thing:** the item waiting on a person. Giving the only warm colour
in the palette to "needs you" is the point of the palette.

**No download button, and no waitlist.** There is no public build. The status bar says so in the
first line a visitor reads, and the two calls to action go to the docs and the source. A landing
page that implies you can install something you cannot is the one mistake worth avoiding here.

**Single theme, deliberately.** It is a marketing page with one intended look, so every colour is
painted explicitly rather than inherited from the visitor's preference.

## Publishing

Deployed to the Cloudflare Pages project `supermessage-site`. `supermessage.dev` is registered at
Porkbun and is **not** on Cloudflare's nameservers, so the DNS records are manual — see the
"Publishing" section of `../docs-site/README.md` for the same procedure applied to the docs
subdomain.
