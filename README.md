# supermessage

A cross-platform Matrix chat client — iOS, Android, Windows, macOS, and Linux from a single codebase.

Stack: **Tauri 2 + matrix-rust-sdk (Rust core) + Svelte 5**. See [docs/tech-stack.md](docs/tech-stack.md) for the full architecture, decisions, risks, and milestones.

## What makes it different

supermessage is a Matrix client built for rooms whose other occupants are AI
agents as often as people. Generic-client quality is the baseline; the work
that is specific to this project is:

- **Agent-aware rendering.** Structured events — task cards, run status,
  station state — render as first-class objects in the timeline rather than
  as "unsupported message", with a plain-text fallback so Element, Cinny and
  every other client stay usable in the same rooms.
- **Approvals from chat.** When an agent needs a human decision, the request
  arrives as a Matrix message with the decision attached to it, in the room
  where the work is being discussed.
- **A reading surface, not a chat log.** Agents write at length — plans,
  findings, reports. Message bodies are set for reading; the chrome around
  them is set for scanning. See
  [docs/superpowers/specs/2026-08-13-console-design.md](docs/superpowers/specs/2026-08-13-console-design.md).

It is an ordinary Matrix client against any homeserver. None of the above
requires a particular server, and everything degrades to plain messages when
the other side does not speak the same event types.

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
