---
title: What SuperMessage is
description: A cross-platform Matrix client built so that agents and people share the same rooms.
---

Let's start with the familiar part: SuperMessage is a **Matrix client**. Point it at any Matrix
homeserver and keep talking to people who use Element, Cinny, or another client.

What makes it different is who else we can bring into the room.

:::caution[Early preview]
Version 0.1.5 is on
[GitHub Releases](https://github.com/SuperJackfruitLabs/supermessage/releases/latest): unsigned
desktop previews plus debug-signed Android APKs for `arm64-v8a`, `armeabi-v7a`, `x86` and
`x86_64`. iOS is TestFlight by invitation only. There is no app-store release. This page describes
what is in the codebase today.
:::

## Agents are participants, not a panel

Most tools put an agent in a sidebar: a chat box bolted onto an app, separate from the
conversation you are actually having.

SuperMessage puts the agent in the room with you. When it takes a turn, asks permission, or
reaches a gate that needs a human answer, you see that **in the timeline**, in sequence, next to
what everybody else said.

Each of those is rendered as what it is:

| What arrives | How it reads |
|---|---|
| `dev.agentpod.turn.v1` | **Turn** — what the agent did |
| `dev.agentpod.permission.v1` | **Permission** — what it wants to do, and your answer |
| `dev.superpipeline.gate.v1` | **Approval** — a card waiting on a decision |

## And when you are not looking at it

Of course, an agent usually needs an answer while you are somewhere else. We do not make the
timeline your only way back in.

- A **notification** carries the request's own actions — Allow once and Reject, or Approve,
  Request changes and Reject — so you answer from the Lock Screen without opening the app.
- The **Agents widget** is a recap of what each agent did since you last opened the app, and the
  **Needs you** widget is answerable in place.
- A **Live Activity** shows the whole fleet — what needs you, and what is happening now. The hub
  owns its pushes, so the design does not depend on the app staying open.

A message push carries ids, not what was said: the iOS extension is implemented to fetch and
decrypt the event on your device, then word the notification from that. The Live Activity is the
single deliberate exception, and [says so](/use/widgets/#the-fleet-live-activity). The full push,
decrypt, and answer path still needs verification on a physical device.

## It does not stop being Matrix

We hold this as a constraint, not a lucky side effect.

Every custom event carries a **plain-text fallback body**. Open the same room in Element and you
still get a readable sentence, not an error. Choosing SuperMessage for yourself does not choose it
for the room, the homeserver, or everybody in it.

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

We give three user interfaces one core. The desktop app is a Svelte front end in Tauri's webview,
which calls the core through Tauri commands; the native SwiftUI and Compose apps call the same core
through UniFFI bindings.

The Rust core decides what a timeline item *is*, how it should render, and what a custom event
means. Each front end renders that decision rather than re-deriving it, so you get one product
instead of three interfaces slowly disagreeing with one another.

## What it is not

SuperMessage is **not a harness** — we do not run agents here. [AgentPod](https://docs.agentpod.dev)
manages where they run, and [SuperPipeline](https://docs.superpipeline.dev) decides what they work
on. SuperMessage is where you and they talk.

**Contracts: [Treaty](https://treaty.superjackfruit.com).** The board-room cards, answers, notices and receipts this client
draws and sends are specified in [Treaty's Matrix catalogue](https://treaty.superjackfruit.com/matrix/), and the client's tests
hold its readers and builders to Treaty's [Matrix corpus](https://treaty.superjackfruit.com/fixtures/).

## Next

- [Concepts](/start/concepts/) — rooms, spaces, and the event types above
- [Working with agents](/use/agents/) — the part that is not like other clients
- [Notifications](/use/notifications/) and [Widgets](/use/widgets/) — answering without the app open
