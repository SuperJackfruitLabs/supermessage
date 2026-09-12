# supermessage landing page

The public page at `supermessage.dev`. Astro, one page, no framework.

```sh
npm install
npm run dev     # http://localhost:4325
npm run build   # -> dist/
```

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
