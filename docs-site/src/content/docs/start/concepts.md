---
title: Concepts
description: Rooms, spaces, timelines and suite events — the vocabulary the rest of the docs assumes.
---

Most of this is ordinary Matrix. The last two entries are not.

## Homeserver

Where your account lives. supermessage is a client; it stores nothing centrally of its own, and
you can point it at any homeserver.

## Room

A conversation. People and agents are both members of it. Rooms are encrypted by default — see
[Encryption](/use/encryption/).

## Space

A room that contains other rooms: a folder for conversations. supermessage shows spaces as a
rail you can filter by, so a fleet of agent rooms does not drown the room you actually talk in.

## Timeline

The sequence of a room. Every entry is classified in the Rust core before it reaches the screen,
into one of thirteen kinds — an ordinary message, a sticker, a poll, a redaction, an
undecryptable event, a membership change, a state change, a custom suite event, and so on.

This matters more than it sounds. The alternative, which this client used to do, is to hand the
UI a raw event-type string and let it guess; the result was rooms full of lines reading
`Unsupported event (m.room.name)`. State changes are now **suppressed unless they are something
you must know** — the room being created, encryption being turned on, the room being replaced.

## Turn

One stretch of work by an agent, arriving as `dev.agentpod.turn.v1`.

While it is being written it streams over to-device messages, which are temporary by design. The
durable record is the message that lands in the room when the turn finishes.

## Permission request

`dev.agentpod.permission.v1` — an agent asking before it does something. It renders with your
answer attached once you have given one, so the room keeps the record of who decided what.

## Approval gate

`dev.kaambaan.gate.v1` — a [kaambaan](https://docs.kaambaan.dev) card that has reached a point
where a human has to answer. The gate is the product decision that work does not simply continue
because an agent thinks it should.

## Fallback body

Every suite event above carries a plain-text body. That is what Element shows. It is not a
nicety — it is the rule that keeps these rooms open to other clients.

## Next

- [Rooms and spaces](/use/rooms/)
- [Working with agents](/use/agents/)
