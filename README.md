# supermessage

A cross-platform, agent-aware Matrix chat client for iOS, Android, Windows,
macOS, and Linux. The platforms share a Rust Matrix core: desktop uses
**Tauri 2 + Svelte 5**, iOS uses **SwiftUI**, and Android uses **Kotlin/Compose**
through UniFFI. See [AGENTS.md](AGENTS.md) for the current architecture and
[docs/tech-stack.md](docs/tech-stack.md) for the original decisions.

## Status: early, with active desktop and native clients

The table describes implemented source paths on this branch, reviewed in
September 2026. It is not a promise that every platform has a current signed
release, or that every operation has been exercised against a live homeserver.

| Capability | Desktop (Linux/macOS/Windows) | iOS | Android |
|---|---|---|---|
| Password login, persistent session, room and timeline sync | Implemented | Implemented | Implemented |
| Messages, replies, reactions, typing and read receipts | Implemented | Implemented | Implemented |
| File/image sending and editing/deleting own messages | Implemented | Implemented | Implemented |
| New conversations, room joining and accepting invitations | Implemented | Implemented | Implemented |
| Message search | Homeserver search | Homeserver search | Homeserver search |
| Encryption and key recovery | SDK crypto and recovery UI | SDK crypto and recovery UI | SDK crypto and recovery UI |
| AgentPod turn/permission cards and Superpipeline gates | Implemented; gate sender uses shared core | Implemented | Implemented |
| Background push notifications when closed | Not implemented | Not implemented | Not implemented |

Search depends on homeserver support and is not an encrypted local-history
search. Encrypted events can still show undecryptable placeholders when keys
are unavailable; recovery support does not establish full cross-client E2EE
parity. Platform-specific account and room settings differ.

The implementation paths are the desktop [routes](src/routes/+page.svelte),
[composer](src/lib/components/Composer.svelte) and [timeline](src/lib/components/Timeline.svelte),
the [iOS app](apple/Supermessage) and [Android app](android/app/src/main/kotlin/dev/supermessage),
and the shared [Rust core](crates/supermessage-core/src). Frontend and Rust
regression tests cover contracts; they do not establish live delivery,
background execution or complete device/platform validation. Windows, macOS
and mobile need their own build and device checks.

[docs/parity-gap-analysis.md](docs/parity-gap-analysis.md) is a dated August
assessment with a drift note. Use it for comparison context, not as today's
capability list.

## What it is for

supermessage is built for rooms whose occupants include both people and AI
agents:

- **Agent-aware rendering.** Production renderers handle AgentPod turns,
  permission requests and Superpipeline gates, with plain-text fallback for
  clients that do not recognize those event schemas.
- **Approvals from chat.** Gate choices use the shared core's structured
  Matrix decision sender, carrying the gate identifier and the gate event
  reference. AgentPod permission replies remain ordinary chat text. Resolving
  a Superpipeline gate also requires the AgentPod Application Service and its
  human-identity mapping; local tests do not prove that live integration.
- **A reading surface for long-form agent output.** Message bodies are set for
  reading and surrounding controls for scanning. See the
  [design language](docs/design-language.md).

Ordinary Matrix chat does not require suite services. Suite-specific decision
handling requires a compatible bridge on the receiving side.

## Building

Requires [Rust](https://rustup.rs), [Node](https://nodejs.org) 22+ and
[pnpm](https://pnpm.io) at the version pinned in `package.json`, plus the
[Tauri 2 platform prerequisites](https://v2.tauri.app/start/prerequisites/)
for your OS.

```sh
pnpm install --frozen-lockfile
pnpm tauri dev             # run the app
pnpm test                  # frontend unit tests
pnpm check                 # svelte-check
cargo test --workspace     # core, FFI and desktop shell
```

Release binaries for Linux, macOS and Windows are built by
[`.github/workflows/release.yml`](.github/workflows/release.yml) on a `v*`
tag. That workflow does not publish mobile store releases. Desktop binaries
are currently **unsigned**, so macOS Gatekeeper and Windows SmartScreen will
warn on first run. Native build instructions and prerequisites are in
[AGENTS.md](AGENTS.md); source availability is not release availability.

## Licence

[MIT](LICENSE).

Dependencies are held to a permissive-licence policy — permissive licences
plus unmodified MPL-2.0, with GPL, AGPL and LGPL refused. This is enforced in
CI by [`src-tauri/deny.toml`](src-tauri/deny.toml) rather than left to
review, because the case that matters is a transitive dependency changing
licence during a routine bump.
