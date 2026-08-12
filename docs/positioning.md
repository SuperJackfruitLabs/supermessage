# supermessage — Positioning in the Synthetic Organization Suite

**Status:** Decided (Aug 2026). Companion to [tech-stack.md](tech-stack.md).
**Sources:** `synthetic_organization_platform_strategy.docx` + `synthetic_organization_layer_reference.docx` (strategy snapshot 12 Aug 2026); surveys of the `agentpod` and `kaambaan` repos on the same date.

## The suite at a glance

The platform is "an operating system for **synthetic organizations**" — organizations where humans and AI agents are both first-class members. Four product surfaces answer four questions:

| Surface | Question | Status (Aug 2026) |
|---|---|---|
| **AgentPod** | What is everyone *actually doing*, and where? | Built, heavy active dev (v0.1.22): fleet console, node-agent, 6 harnesses, ACP sessions, posture, changesets |
| **Kaambaan** | What is everyone *working on*? | Working v0 (P0→P14 in one sprint): cards/tasks/runs/gates, REST+MCP, approvals, metering |
| **Communication** | What is everyone *talking about*? | **Matrix chosen; layer unbuilt — this is where supermessage lives** |
| **Organization** | Who is everyone, and what are they allowed to do? | Unbuilt (P1 seed next): Principal/Team/Role + identity mappings |
| **Knowledge** | What does the organization *know*? | Unbuilt (P4): provenance-backed canonical knowledge |

Join key across all planes: a shared **run identity** (`runId` minted by Kaambaan, executed by AgentPod, correlated to ACP session, policy, artifacts — and to `matrixRoomId`/`matrixEventId`).

## Where supermessage sits

The strategy's Communication layer (priority **P2**) is defined as: *"Matrix + build bridge/client"* — core nouns Room, DM, Thread, Mission room; technology stance **Matrix Application Service**. It explicitly says:

- **Use Matrix as the communication substrate. Do not build a proprietary Slack clone or chat server.**
- Communication should feel like Slack/Discord/Teams **for a mixed human-agent organization**.
- Build ourselves: the Application Service bridge, Principal↔Matrix identity mapping, **agent-aware event schemas/cards in the client**, mission-room automation, deep links back to Kaambaan/AgentPod.
- Ownership: the Matrix layer owns rooms/DMs/threads/conversation history. It must **not** own cards/tasks/gates, org membership, or ACP transcripts.

**supermessage is the client half of that layer** — the human-facing, agent-aware Matrix client of the suite. The server half (an Application Service bridge that masquerades agent identities and emits rich events) is a companion component that lives outside this repo (org/agentpod side).

## What already exists today (why now is the right time)

- **`id.agentpod.dev` Synapse is live**, and Hermes/OpenClaw agents on the fleet **already have Matrix accounts** (`@analyst-echo:id.agentpod.dev`, …). AgentPod already indexes each station's mxid. A Matrix client can DM real agents **today with zero changes to agentpod**.
- **Kaambaan has the HITL gap supermessage can fill.** Its docs name a Slack-like chat surface as the missing channel for approvals ("Approve / Request changes / Reject from chat"), planned but unbuilt. It already exposes everything a chat client needs: notifications feed + gate-resolution REST + board WebSocket + outbound `work.available` push webhooks.
- **AgentPod's own Matrix ladder** anticipates us: level A (mxid display) shipped; level B (Matrix as IdP) and **level C (fleet events → Matrix rooms)** explicitly deferred, unbuilt. Approval-reach notifications ("a PWA plus push") are undecided — supermessage with working mobile push is a candidate answer.

## Boundaries (from the strategy's invariants — hard rules)

- Matrix conversation ≠ ACP execution transcript ≠ Kaambaan work activity. supermessage is **not** an ACP client and **not** a work-state board; it renders links and projections of those, never their truth.
- Don't attach a whole Matrix room to one run: correlate via `missionId/cardId/taskId/runId` + `matrixRoomId/matrixEventId`.
- Agent identity ≠ Station ≠ ACP Session ≠ Kaambaan Run — render them as distinct linked objects.
- Don't build a homeserver (Synapse stays) and don't own org membership (the P1 Organization layer will).
- Custom "rich card" event types must be **versioned, documented, suite-shared schemas** with plain-text fallback so Element/Cinny remain usable clients. Never client-private hacks.

## The wedge (near-term differentiators, in order)

1. **Daily-driver client for the human↔agent org** — dogfood against `id.agentpod.dev` from M0: real agents, real DMs, real rooms. Generic-client quality is the baseline entry fee.
2. **Agent-aware rendering** — custom events embedding Kaambaan cards/runs, permission requests, station status; deep links into the AgentPod console and Kaambaan boards. This is the product.
3. **Approvals from chat** — gate/permission notifications delivered as Matrix messages with Approve/Reject actions, wired to Kaambaan's REST gate resolution (and later ACP permission flow). The missing HITL channel — nobody else has this.
4. **Fleet/mission awareness** — presence derived from org/work/runtime state (not free-form); mission rooms with automation; eventually level-C fleet events in rooms.

## Tensions & calls

- **E2EE vs knowledge extraction.** The Knowledge layer (P4) must read conversation history as evidence; org rooms will be server-readable by design (and Application Service bridges don't mix with E2EE anyway). Call: keep vodozemac E2EE available in the client, but **drop full E2EE UX from the critical path** for the org use case; org rooms are unencrypted, E2EE reserved for external/DM contexts. Milestone M1 is descoped accordingly (see below).
- **"Only then consider a custom client" vs starting now.** The strategy sequences the custom client *after* virtual agents and mention→response work (P2). That's correct for the *suite*; but client groundwork (Tauri mobile, push, timeline UX) takes months and blocks nothing. Call: supermessage proceeds **as a parallel track** — generic Matrix client now, converging with P2 when the AS bridge lands. It also de-risks mobile push, which the whole suite needs for approval reach.
- **Application Service bridge ownership.** Not supermessage. When built (P2), candidate tech must respect the suite's license posture: **Ruma/ruma-appservice (MIT)** in Rust is the natural choice; **avoid mautrix (AGPL)**. supermessage co-designs the event schemas with it.

## Milestone adjustments (supersedes tech-stack.md M0–M4)

- **M0 — spine (unchanged scope, new target):** Tauri + rust-sdk sync against **`id.agentpod.dev`** (OIDC + password), room list, virtua timeline, send/receive. Dogfood with real agent users immediately.
- **M1 — agent-aware client (was E2EE):** custom event rendering framework + schema drafts (card/run/permission/station), deep links, graceful plain-text fallback. E2EE becomes "available, not blocking".
- **M2 — daily driver (unchanged):** media, replies/reactions/edits, receipts/typing, iOS keyboard fix, Android 16KB/ring fix, F7 mobile skin + desktop skins.
- **M3 — push + approvals:** push-gateway deployment (unmodified Sygnal, or own minimal Rust gateway — license stance in tech-stack.md); FCM/APNs; Kaambaan gate notifications → Matrix → approve/reject actions end-to-end. Then iOS NSE.
- **M4 — mission surfaces:** spaces/mission rooms, presence-from-org-state, fleet event rooms (level C consumer), multi-account, settings polish.

## Where the suite is heading (and what P2 will ask of supermessage)

Suite sequencing: **P0** finish AgentPod↔Kaambaan spine (bridge, runId, permission interruption) → **P1** organization seed (Principal/Team/Role + identity mappings incl. `AgentIdentity.matrixUserId`) → **P2 Matrix communication projection** (Application Service, virtual agent users, team/mission rooms, rich links) → P3 scheduler → P4 knowledge → P5+ evaluation/governance hardening.

By P2, supermessage must already be: a solid mobile+desktop Matrix client with working push, a custom-event rendering surface, and deep-link conventions. Everything before P2 is runway to get there; everything after P2 amplifies it (knowledge extraction, mission automation, governance approvals converging into chat).
