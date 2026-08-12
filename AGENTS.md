# AGENTS.md — supermessage

Guidance for AI coding agents working in this repository. Read this first; it summarizes the project's purpose, current state, and decided architecture. For full detail, read the two docs in `docs/` — they are the source of truth.

## Project overview

supermessage is a **cross-platform Matrix chat client** targeting **iOS, Android, Windows, macOS, and Linux from a single codebase**.

It is the **Communication layer (client)** of the Synthetic Organization suite (AgentPod + Kaambaan + Matrix + org control plane) — the human-facing, agent-aware Matrix client for a mixed human/AI-agent organization. Generic-client quality is the baseline; the differentiators are agent-aware rendering of suite events (Kaambaan cards/runs, permission requests, station status), approvals from chat (Kaambaan gate resolution), and fleet/mission awareness. See `docs/positioning.md`.

**Current status: pre-implementation (decisions taken Aug 2026).** The repository contains documentation only — there is no source code, no build system, no package manifest, and no test suite yet. Do not invent build/test commands or scaffolding conventions; when implementation begins (milestone M0), this file must be updated with the real commands.

## Repository layout

```
README.md            — one-paragraph summary and stack pointer
docs/tech-stack.md   — full stack decision record: choices, rationale, risks, protocol choices, milestones
docs/positioning.md  — suite context, boundaries (hard rules), near-term wedge, milestone adjustments
```

## Decided technology stack

| Layer | Choice | License |
|---|---|---|
| App shell | Tauri 2 (one Rust + webview codebase for all 5 OS targets) | MIT/Apache-2.0 |
| Matrix SDK | matrix-rust-sdk, as a plain crate in the Tauri core (no UniFFI/FFI bridge) | Apache-2.0 |
| Frontend | Svelte 5, SPA mode, no SSR | MIT |
| Styling | Tailwind CSS v4 design tokens | MIT |
| Headless primitives | Bits UI (Radix-equivalent for Svelte) | MIT |
| Mobile skin | Framework7 Svelte v9 (fallback: Konsta UI Svelte) | MIT |
| Desktop skins | Per-OS token themes over Tauri native chrome (Fluent-inspired Windows, hand-rolled HIG macOS, libadwaita CSS vars Linux) | — |
| Message list | virtua (Svelte virtualizer) for the inverted chat timeline | MIT |
| JS ↔ Rust bridge | Tauri commands/events + Svelte stores | — |
| Push infra | Self-hosted push gateway (unmodified Sygnal, or own minimal Rust gateway at M3) + FCM/APNs | AGPL-3.0 if Sygnal — infrastructure only, not linked |

## Architecture rules (from docs/tech-stack.md — treat as binding)

- The Matrix client lives **entirely in the Rust core** (tokio). The webview is a dumb renderer: Svelte stores mirror core state streamed over Tauri events; user intents go down as Tauri commands. Use windowed/delta updates to bound IPC serialization cost.
- Exactly one `matrix_sdk::Client` per logged-in account, owned by the core.
- UI skins are the **only** platform-branched layer (~20% of UI code). All logic, state, and chat behavior is shared.

## Product boundaries (hard rules from docs/positioning.md)

- Matrix conversation ≠ ACP execution transcript ≠ Kaambaan work activity. supermessage is **not** an ACP client and **not** a work-state board; it renders links and projections of those, never their truth.
- Correlate rooms to work via `missionId/cardId/taskId/runId` + `matrixRoomId/matrixEventId`; never attach a whole Matrix room to one run.
- Agent identity, Station, ACP Session, and Kaambaan Run are distinct linked objects — render them as such.
- Do not build a homeserver (Synapse stays) and do not own org membership (the P1 Organization layer will). The Application Service bridge (server half) lives outside this repo.
- Custom "rich card" event types must be **versioned, documented, suite-shared schemas** with plain-text fallback so Element/Cinny remain usable clients. Never client-private hacks.

## Matrix protocol choices

- **Auth:** native OIDC (MSC3861) primary — PKCE via system browser, refresh tokens; legacy password/SSO login as fallback.
- **Sync:** Simplified Sliding Sync (MSC4186) via the SDK's SyncService; `/sync` v3 fallback for older servers.
- **E2EE:** SDK crypto (vodozemac): cross-signing, SSSS key backup, emoji/SAS device verification. Never hand-roll crypto.
- **Media:** authenticated media endpoints (spec ≥1.11).
- **Push:** `event_id_only` pushes via own Sygnal → FCM/APNs; the app fetches and decrypts content itself. iOS phase 2: Notification Service Extension in Swift linking the Rust SDK.

## Build, test, and development commands

None exist yet — the repo has no code, no `Cargo.toml`, no `package.json`, and no CI. When M0 scaffolding lands, document here the real commands (expected shape per the stack: Cargo for the Rust/Tauri core, a JS package manager + Tauri CLI for the frontend, Tauri mobile commands for iOS/Android), plus lint/format/test invocations. Until then, any command an agent needs should be established as part of the task and recorded here.

## Testing strategy

No test suite exists yet and no strategy is prescribed in the docs. Two quality requirements from `docs/tech-stack.md` will shape it:

- **Per-engine visual QA** across WebKit (iOS/macOS), WebView2 (Windows), and WebKitGTK (Linux, including the oldest supported distro version).
- A **native-feel behavior budget** (non-negotiable checklist): iOS keyboard webview-resize fix, safe-area handling, haptics via `tauri-plugin-haptics`, native popup context menus (no HTML dropdowns for OS-level actions), platform scrollbar discipline, system font stacks/Dynamic Type, strict `user-select` discipline.

Add real testing instructions here once the first code exists.

## Security and license considerations

- **No copyleft dependencies.** All runtime dependencies must be permissively licensed (MIT / Apache-2.0 / BSD). This hard requirement eliminated Flutter/matrix-dart-sdk and trixnity (both AGPL-3.0).
- **AGPL projects are reference-only, never copy code:** Element X apps, trixnity-messenger/Tammy, mautrix. If an Application Service bridge is ever co-designed, prefer Ruma/ruma-appservice (MIT); avoid mautrix (AGPL).
- **Sygnal (push gateway) is AGPL-3.0** in its maintained element-hq form; the Apache-2.0 matrix-org original is archived. It is deployed infrastructure, not a dependency — the client never talks to it (the homeserver POSTs to it). Run it unmodified; a minimal own Rust push gateway is an M3 option (see docs/tech-stack.md license section).
- E2EE via vodozemac only; never hand-roll cryptography. Note the product call (docs/positioning.md): org rooms are unencrypted by design (knowledge extraction, AS-bridge incompatibility); E2EE stays available for external/DM contexts but is not on the critical path.
- Push content is fetched and decrypted by the app itself (`event_id_only` pushes) — do not route message content through the push gateway.

## Milestones (docs/positioning.md supersedes docs/tech-stack.md on ordering)

- **M0 — spine:** Tauri scaffold; Rust core syncs a real account on `id.agentpod.dev` (OIDC + password); Svelte stores mirror room list/timeline; virtua message list; send/receive plaintext. Dogfood immediately against real agent users.
- **M1 — agent-aware client:** custom event rendering framework + schema drafts (card/run/permission/station), deep links, graceful plain-text fallback. E2EE is "available, not blocking".
- **M2 — daily driver:** media, replies/reactions/edits, receipts/typing, iOS keyboard fix, Android 16KB/ring fix, Framework7 mobile skin + desktop skins.
- **M3 — push + approvals:** Sygnal deployment; FCM/APNs; Kaambaan gate notifications → Matrix → approve/reject end-to-end; then iOS NSE.
- **M4 — mission surfaces:** spaces/mission rooms, presence-from-org-state, fleet event rooms, multi-account, settings polish, store submissions.

## Known risks to keep in mind when writing code

- No production Tauri-**mobile** Matrix client exists yet — mobile integration is trailblazing; spike the push path early.
- iOS keyboard does not resize WKWebView (the #1 "web tell" in chat apps) — the planned fix is ~200 lines of objc2 Rust resizing the webview frame; treat as core work.
- aws-lc-rs crashes on Android 16KB-page devices — force the `ring` TLS backend until the upstream matrix-rust-sdk fix lands (Google Play requires 16KB support).
- IPC cost of streaming timelines to the webview — use windowed/delta updates.
- Framework7 is single-maintainer — keep the skin isolated (~20% of UI) so the Konsta fallback stays viable.

## Related repositories (not part of this workspace's code)

The suite's other surfaces live in sibling repos: **AgentPod** (fleet console/node-agent, agents already have Matrix accounts on `id.agentpod.dev`) and **Kaambaan** (cards/tasks/runs/gates, REST+MCP, approvals, notifications). supermessage integrates with them via links, projections, and the Matrix room/event IDs — it must not own their state.
