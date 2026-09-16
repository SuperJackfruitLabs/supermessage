---
title: Working with agents
description: Turns, permission requests and approval gates — the part that is not like other Matrix clients.
---

This is why supermessage exists. Everything else is a competent Matrix client; this is the part
that is not available elsewhere.

## Three things an agent sends

| Event | Label | What it is |
|---|---|---|
| `dev.agentpod.turn.v1` | **Turn** | What an agent did during one stretch of work |
| `dev.agentpod.permission.v1` | **Permission** | It wants to do something and is asking first |
| `dev.superpipeline.gate.v1` | **Approval** | A card has reached a point a human must answer |

Each renders as a card in the timeline, in sequence with everything else said in the room.

## Answering

A permission request or a gate arrives with a prompt and a set of options. You answer in the
room, and the answer stays attached to the request — so the record of who decided what lives
where the decision was made, not in a separate audit tool.

A request whose options are missing or malformed renders as a **description of the request**
rather than as a card with no buttons. An unanswerable prompt is worse than a plain sentence.

## Live output

While an agent is working, partial text streams in over to-device messages — the answer as it is
written, the reasoning behind it, and each tool call as it moves.

Two consequences worth knowing:

- **It is not history.** To-device messages are not stored on the homeserver, not paginated, and
  not visible to any other client, or to this one after a restart. What survives is the message
  the agent posts when the turn finishes.
- **It is paced.** Text is revealed at a readable rate rather than at the rate it arrives.

Reasoning is kept in a collapsed disclosure **after** the answer appears, rather than being
cleared — a record that vanishes the moment the answer lands is one nobody had time to read.

## Other clients still work

Every one of these events carries a plain-text fallback body. Open the room in Element and you
get a readable sentence.

This is a rule the project holds itself to, not an accident: rooms stay open to clients that know
nothing about the suite.

## Versioning

Events carry a schema version. If an agent sends a version newer than this client knows, the card
is still rendered on a best-effort basis and **flagged as newer** — rather than silently
pretending nothing changed, and rather than refusing to show anything.

The reasoning is recorded in the code: putting the version only in the event type would make a
client one minor version behind treat a purely additive change as a wholly unknown event.

## A note on safety

Event payloads come from outside, and the renderers treat them that way. A renderer reads **named
fields, one level at a time**, and never descends into nested structures — which is what makes a
huge or deeply nested payload harmless without needing a depth or size guard.

Everything a renderer produces is **text only**. None of it is ever routed into markup, a link
target, an image source, or a style.

## Where the other planes are

supermessage is the conversation. The work itself lives elsewhere:

- [AgentPod](https://docs.agentpod.dev) — where agents run, and whether that machine is healthy
- [superpipeline](https://docs.superpipeline.dev) — what they are working on, and the gates they wait at
