---
title: Concepts
description: Rooms, spaces, timelines and suite events — the vocabulary the rest of the docs assumes.
---

We can borrow most of our vocabulary from Matrix. Near the end, we add the suite events that make
an agent feel like a participant rather than an integration bolted onto the room.

## Homeserver

This is where your account lives. SuperMessage is a client, not another central service, so you
can point it at any homeserver.

## Room

This is the conversation: people and agents are both members. We encrypt rooms you create by
default, unless you invite an agent while creating one — see [Encryption](/use/encryption/).

## Space

A space is a room that contains other rooms — a folder for conversations. We show spaces as a
rail you can filter by, so a fleet of agent rooms does not bury the room you meant to open.

## Timeline

The timeline is the sequence of a room. Before an entry reaches your screen, the Rust core
classifies it into one of thirteen kinds — an ordinary message, a sticker, a poll, a redaction,
an undecryptable event, a membership change, a state change, a custom suite event, and so on.

That classification buys you a quieter room. The client used to hand the UI a raw event-type
string and let it guess; the result included lines reading `Unsupported event (m.room.name)`.
Now we **suppress state changes unless they are something you must know** — the room being
created, encryption being turned on, or the room being replaced.

## Turn

One stretch of work by an agent, arriving as `dev.agentpod.turn.v1`.

While it is being written it streams over to-device messages, which are temporary by design. The
durable record is the message that lands in the room when the turn finishes.

## Permission request

`dev.agentpod.permission.v1` — an agent asking before it does something. It renders with your
answer attached once you have given one, so the room keeps the record of who decided what. Its
shape is Treaty's [permission card](https://treaty.superjackfruit.com/matrix/permissions/agentpod-permission-v1/); the newer
[v2](https://treaty.superjackfruit.com/matrix/permissions/agentpod-permission-v2/) is answered with a
[permission answer](https://treaty.superjackfruit.com/matrix/permissions/agentpod-permission-answer-v2/).

## Approval gate

`dev.superpipeline.gate.v1` — a [SuperPipeline](https://docs.superpipeline.dev) card that has reached a point
where a human has to answer. The gate is the product decision that work does not simply continue
because an agent thinks it should. Its shape is Treaty's
[gate card](https://treaty.superjackfruit.com/matrix/cards/superpipeline-gate-v1/), answered with a
[gate decision](https://treaty.superjackfruit.com/matrix/answers/superpipeline-gate-decision-v1/).

## Gate outcome

`dev.superpipeline.gate.outcome.v1` — the board saying it accepted an answer to a gate. It is what
turns an approval card into a **receipt**, and it is the reason this client distinguishes
*answered* from *resolved*: your decision is a message, and the board accepting it is a different
event that may never come. Its shape is Treaty's
[gate outcome](https://treaty.superjackfruit.com/matrix/receipts/superpipeline-gate-outcome-v1/). See
[Working with agents](/use/agents/#answered-is-not-resolved).

## Voice reply

An agent's spoken answer. The text arrives first and the audio follows, naming the text it speaks,
and this client folds the two into one message rather than showing them twice.

## Fallback body

Every suite event above carries a plain-text body. Open the room in Element and that is what you
see. We treat this as the rule that keeps the room open to other clients, not as an optional
nicety.

## Next

- [Rooms and spaces](/use/rooms/)
- [Working with agents](/use/agents/)
- [Notifications](/use/notifications/)
