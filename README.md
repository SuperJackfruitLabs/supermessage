# supermessage

A cross-platform Matrix chat client — iOS, Android, Windows, macOS, and Linux from a single codebase.

Stack: **Tauri 2 + matrix-rust-sdk (Rust core) + Svelte 5**. See [docs/tech-stack.md](docs/tech-stack.md) for the full architecture, decisions, risks, and milestones.

## Status: early. Read this before installing.

supermessage today is a **capable Matrix reader with a reply box.** Everything
it can send to a homeserver is: a plain-text message, a plain-text reply, a
reaction, a typing notice and a read receipt.

It cannot yet send a file or an image, edit or delete your own messages,
create or join a room, invite anyone, search, change any setting, edit your
profile, or notify you when it is closed. **Encrypted rooms render a
placeholder** — E2EE is deliberately not on the critical path, so most DMs on
most homeservers will not be readable here.

[docs/parity-gap-analysis.md](docs/parity-gap-analysis.md) is an honest,
code-grounded account of where this stands against Element, Cinny,
FluffyChat and Nheko, and what each gap would cost to close. Read it before
deciding whether this is usable for you. If you want a general-purpose Matrix
client today, use one of those.

## What it is for

supermessage is built for rooms whose other occupants are AI agents as often
as people. That is the reason it exists, and it is the only area where it is
ahead of anything else:

- **Agent-aware rendering.** A registry that turns structured suite events
  into first-class timeline objects instead of "unsupported message", with a
  plain-text fallback so Element, Cinny and every other client stay usable in
  the same rooms. The framework is built and tested; the only renderer that
  ships today is a demo one, because the real schemas belong to another team
  and are still being designed in the open
  ([kaambaan#34](https://github.com/rakeshgangwar/kaambaan/issues/34)).
- **Approvals from chat** — *not yet working.* When an agent needs a human
  decision, the timeline is where that decision should be made. The card that
  renders it is built, unit-tested, and **unreachable in this build**: no
  event type exists yet for it to render. It is a slot, not a feature.
- **A reading surface, not a chat log.** Agents write at length — plans,
  findings, reports. Message bodies are set for reading; the chrome around
  them is set for scanning. This part is real and shipped. See
  [the design spec](docs/superpowers/specs/2026-08-13-console-design.md).

It talks to any homeserver and needs no particular server-side software.
Everything above degrades to plain text when the other side does not speak
the same event types.

## Building

Requires [Rust](https://rustup.rs), [Node](https://nodejs.org) 22+ and
[pnpm](https://pnpm.io) 10+, plus the
[Tauri 2 platform prerequisites](https://v2.tauri.app/start/prerequisites/)
for your OS.

```sh
pnpm install
pnpm tauri dev          # run the app
pnpm test               # frontend unit tests
pnpm check              # svelte-check
cd src-tauri && cargo test
```

Release binaries for Linux, macOS and Windows are built by
[`.github/workflows/release.yml`](.github/workflows/release.yml) on a `v*`
tag. They are currently **unsigned**, so macOS Gatekeeper and Windows
SmartScreen will warn on first run.

## Licence

[MIT](LICENSE).

Dependencies are held to a permissive-licence policy — permissive licences
plus unmodified MPL-2.0, with GPL, AGPL and LGPL refused. This is enforced in
CI by [`src-tauri/deny.toml`](src-tauri/deny.toml) rather than left to
review, because the case that matters is a transitive dependency changing
licence during a routine bump.
