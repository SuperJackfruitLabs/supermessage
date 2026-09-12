---
title: Encryption
description: What is encrypted, what a placeholder means, and the one thing that is deliberately not end-to-end.
---

Rooms are end-to-end encrypted by default, through the Matrix Rust SDK and vodozemac. A
plaintext-only Matrix client was never on the table.

## "Encrypted message" placeholders

An event the client cannot decrypt renders as a placeholder saying so, not as a blank.

The common reason is ordinary: a **fresh device has no keys for messages sent before it existed**.
That is how Matrix works, not a fault. Verifying the device brings history within reach.

A placeholder is also what you see if keys were never shared with your device — so it is a real
signal, and worth noticing when it appears on a message you expected to read.

## "Encryption enabled"

Turning encryption on in a room is one of the three state changes the timeline will interrupt you
for — alongside the room being created and the room being replaced. Everything else about room
state happens silently.

It is shown because it is a genuine security transition. A change in whether your words are
readable by the server is not housekeeping.

## What is not end-to-end encrypted

**Live agent output.** While a turn is being written, partial text streams over to-device
messages.

Be clear-eyed about the trade: this is the mechanism that makes live output temporary and keeps
it out of room history, which is what you want for half-written text. The finished turn lands in
the room as a real message and gets the room's encryption.

If a room's contents are sensitive enough that in-flight partial text matters, that is worth
knowing before you put an agent in it.

## Device verification

Device verification is part of the Matrix flow. It is how a new device earns access to history it
was not present for.

## Next

- [Working with agents](/use/agents/) — what streams, and what is durable
