---
title: Encryption
description: What is encrypted, what a placeholder means, and the one thing that is deliberately not end-to-end.
---

We create rooms with end-to-end encryption by default, using the Matrix Rust SDK and vodozemac.
You do not have to switch encryption on after the conversation has already started.

## Rooms with agents in them

There is one deliberate exception, and you should know it before creating the room.

**A room you create with an agent among the invitees is created unencrypted.** An agent here is
an account whose ID begins `@agent_`. Some agents read through a bridge; others run a Matrix client
with encryption switched off. SuperMessage cannot tell those cases apart, and Matrix cannot turn
encryption off once it is on, so we leave the new room in the clear rather than silently lock an
agent out.

The rule applies when the room is created. Inviting an agent into a room that is already
encrypted does not change the room.

**Rooms an agent opens with you — a direct message from an agent, for instance — are created by
AgentPod, not by this client**, so whether they are encrypted is AgentPod's decision. The
"Encryption enabled" line described below is how you can tell for any room.

## "Encrypted message" placeholders

When this device cannot decrypt an event, we show a placeholder saying so rather than a blank.

The common reason is ordinary: a **fresh device has no keys for messages sent before it existed**.
That is how Matrix works, not a rendering fault. If the account has recovery set up, enter its
recovery key on the new device to restore room keys from the encrypted backup.

You will also see the placeholder when keys were never shared with this device. Treat it as a real
signal, especially on a message you expected to read.

## "Encryption enabled"

Turning encryption on is one of the three state changes we interrupt the timeline for — alongside
the room being created and the room being replaced. Everything else about room state stays quiet.

It is shown because it is a genuine security transition. A change in whether your words are
readable by the server is not housekeeping.

## What is not end-to-end encrypted

**Live agent output.** While a turn is being written, partial text streams over to-device
messages.

Here is the trade: this mechanism keeps half-written output temporary and out of room history, but
it is not end-to-end encrypted. The finished turn lands as a real room message and gets whatever
encryption that room uses.

If a room's contents are sensitive enough that in-flight partial text matters, that is worth
knowing before you put an agent in it.

## Recovery on a new device

At sign-in, SuperMessage sets up encrypted key backup and account recovery when the account does
not already have them. We show the recovery key once and do not store it, so save it somewhere you
control.

On a new device, enter that recovery key to fetch the secrets that unlock the backup. This is the
path that can restore history the device was not present for; device verification alone does not
supply those room keys.

## Next

- [Working with agents](/use/agents/) — what streams, and what is durable
