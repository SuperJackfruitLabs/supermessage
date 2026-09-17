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

**No download button, and no waitlist.** What exists is unsigned desktop previews on GitHub
Releases and no public mobile build, and the status bar says exactly that in the first line a
visitor reads. The two calls to action go to the docs and the source. A landing page that implies
more than you can install is the one mistake worth avoiding here — and the opposite mistake,
saying there is nothing when there is, is the one this page made until September 2026.

**It follows the visitor's light or dark preference**, as the docs do. Both palettes are generated
from `design/tokens.toml` into `src/styles/tokens.css`; nothing on the page is a colour literal.

**One mark.** The header, favicon, `apple-touch-icon.png` and `og.png` all show the mark in
`assets/logo.svg`. `public/favicon.svg` is written by `scripts/generate-tokens.py` and
`apple-touch-icon.png` by `assets/build-icon.py`; the link preview is rendered from `og/` — see
the README there.

**The three pages share no layout**, deliberately (see `src/pages/delete-account.astro`), but they
do share `src/components/ShareMeta.astro`, which carries the canonical URL, icons and link-preview
tags. A page without it has no preview when it is pasted anywhere.

`src/pages/404.astro` exists so Pages answers a missing path with a 404. Without a `404.html` it
treats the site as a single-page app and serves the home page, with a 200, for any URL at all.
