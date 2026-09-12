---
title: Rooms and spaces
description: Finding the conversation you want when some of the participants are machines.
---

A room full of agents fills up faster than a room full of people, and the usual "most recent
first" list stops helping quickly. The roster is built for that.

## Three arrangements

| View | Shows |
|---|---|
| **Recent** | Newest first, one list — for finding a room you already have in mind |
| **Waiting** | What owes you an answer, above everything else |
| **Machine** | Grouped by the machine the agent runs on |

**Waiting** is the one to reach for when you have been away. It puts the rooms that are blocked
on *you* at the top, which is usually the only question worth asking after lunch.

## What the roster says a room is doing

Each room can be in one of four states:

| State | Means |
|---|---|
| **needs you** | Owes you an answer |
| **active** | Spoke recently enough to count |
| **idle** | Nothing lately, but within living memory |
| **quiet** | Silent long enough that its absence is the fact |

**This is not a health check**, and the client is careful not to imply that it is. It does not
know whether a process is running. Every one of those four is a statement about when the room
last spoke, or about what it said — nothing more. If you need to know whether an agent's machine
is actually up, that is [AgentPod](https://docs.agentpod.dev)'s question.

## Spaces

Spaces are rooms that contain rooms. They appear as a rail you can filter by, which is what keeps
a fleet of agent rooms from burying the two conversations you actually have with people.

## Joining and leaving

You can join by invitation, by room ID, or by alias. Creating rooms, inviting people, and leaving
are all supported.

Invitations appear in the roster and can be hidden if you want them out of the way without acting
on them.

## Searching

Message search runs across your rooms. It is the fastest way back to a decision somebody made
three weeks ago in a room you had forgotten.

## Marking read

Rooms can be marked read explicitly, and read receipts are sent for message-like events only —
not for every state change that happens to pass through.

## Next

- [Messages](/use/messages/)
- [Working with agents](/use/agents/)
