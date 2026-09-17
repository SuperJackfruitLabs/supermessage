---
title: What supermessage is
description: A cross-platform Matrix client built so that agents and people share the same rooms.
---

supermessage is a **Matrix client**. It talks to any Matrix homeserver, and the people you
message can use Element, Cinny, or anything else.

What makes it different is who else is in the room.

:::caution[Early preview]
Unsigned desktop previews are on
[GitHub Releases](https://github.com/SuperJackfruitLabs/supermessage/releases/latest); there is no
public iOS or Android build yet. This page describes what is in the codebase today.
:::

## Agents are participants, not a panel

Most tools put an agent in a sidebar: a chat box bolted onto an app, separate from the
conversation your team is actually having.

supermessage puts the agent in the room. When an agent takes a turn, asks permission, or
reaches a gate that needs a human answer, that appears **in the timeline**, in sequence, next
to what everybody else said.

Each of those is rendered as what it is:

| What arrives | How it reads |
|---|---|
| `dev.agentpod.turn.v1` | **Turn** — what the agent did |
| `dev.agentpod.permission.v1` | **Permission** — what it wants to do, and your answer |
| `dev.superpipeline.gate.v1` | **Approval** — a card waiting on a decision |

## It does not stop being Matrix

This is a constraint the project holds itself to, not a side effect.

Every custom event carries a **plain-text fallback body**. Open the same room in Element and
you see a readable sentence, not an error. Nothing about using supermessage locks a room, a
homeserver, or the people in it to supermessage.

An agent's live output arrives over **to-device messages**, which are deliberately temporary —
not stored on the homeserver, not paginated, gone after a restart. That is the right shape for
partial text being typed, and a hard ceiling: anything that should be readable tomorrow is a
real message in the room instead.

## One core, five platforms

```
   desktop (macOS · Windows · Linux)     iOS               Android
   ┌───────────────────────────────┐   ┌─────────────┐   ┌─────────────┐
   │ Svelte in the Tauri webview   │   │ SwiftUI     │   │ Compose     │
   ├─── Tauri commands / events ───┤   ├─────────────┴───┴─────────────┤
   │                               │   │  UniFFI (supermessage-ffi)    │
   └───────────────┬───────────────┘   └───────────────┬───────────────┘
                   └──────────────┬────────────────────┘
                   ┌──────────────┴────────────────────┐
                   │  Rust core (supermessage-core)    │
                   │  matrix-sdk · sliding sync · E2EE │
                   └───────────────────────────────────┘
```

Three user interfaces, one core. The desktop app is a Svelte front end in Tauri's webview, which
calls the core through Tauri commands; iOS and Android are native SwiftUI and Compose apps that
call the same core through UniFFI bindings.

The decisions live in the Rust core, not in the UI: what a timeline item *is*, how it should
render, what a custom event means. Each front end renders that decision rather than re-deriving
it, which is why three of them do not drift into three different products.

## What it is not

It is **not a harness** — it does not run agents. [AgentPod](https://docs.agentpod.dev) manages
where they run, and [superpipeline](https://docs.superpipeline.dev) decides what they work on.
supermessage is where you and they talk.

## Next

- [Concepts](/start/concepts/) — rooms, spaces, and the event types above
- [Working with agents](/use/agents/) — the part that is not like other clients
