---
title: Rooms and spaces
description: Finding the conversation you want when some of the participants are machines.
---

A room list fills up quickly when agents join the people already there. We give you three roster
arrangements so "most recent first" does not have to answer every question badly.

## Three arrangements

| View | Shows |
|---|---|
| **Recent** | Newest first, one list — for finding a room you already have in mind |
| **Waiting** | What owes you an answer, above everything else |
| **Machine** | Grouped by the machine the agent runs on |

Choose **Waiting** after you have been away. Rooms blocked on *you* rise to the top, so your first
scroll becomes an answerable queue instead of an archaeology project.

## What the roster says a room is doing

Each room can be in one of four states:

| State | Means |
|---|---|
| **needs you** | Owes you an answer |
| **active** | Spoke recently enough to count |
| **idle** | Nothing lately, but within living memory |
| **quiet** | Silent long enough that its absence is the fact |

**This is not a health check.** We do not know whether a process is running, so every state above
says only when the room last spoke or what it said. For the machine's actual health, follow the
question to [AgentPod](https://docs.agentpod.dev) instead of reading more into a quiet room.

## Spaces

Spaces are rooms that contain rooms. Pick one from the rail and the roster narrows to its
conversations, keeping a fleet of agent rooms from burying the two conversations you meant to
find.

## Joining and leaving

Join from an invitation, a room ID, or an alias. You can also create a room, invite people, and
leave it; each action changes the same Matrix membership other clients see.

Hide invitations when you want them out of the way without accepting or rejecting them. They are
withheld from the roster, not acted on behind your back.

## Searching

Search every room your account can see, or narrow the search to one room. We ask your homeserver,
then show readable message hits newest first — a quicker route back to the decision you half
remember from three weeks ago.

Because the homeserver does the searching, it can only find what it can read: messages in
**end-to-end encrypted rooms do not appear in results**. Results are plain-text messages, newest
first, up to twenty at a time.

## Blocking and reporting

Both are Matrix, not something invented here.

Block a person and we add them to your Matrix ignore list as account data. Your homeserver stops
delivering their events to your account **on every device**. The same action works on an agent:
the agent keeps running, but its messages stop reaching you.

Expect your timelines to empty and refill when you block someone. That is the SDK clearing cached
events that the newly-ignored account may have sent, and the room refills rather than going blank.

Report a message, room, or person and the report reaches **your homeserver's administrator** — not
anyone in the room, and not us. Add a reason when context will help; the result is still a Matrix
report to the server that owns the account.

## Marking read

Mark a room read and we send the receipt for its latest message-like event, not for whichever
state change happened to pass through last. Your unread boundary now follows the conversation.

## Next

- [Messages](/use/messages/)
- [Working with agents](/use/agents/)
- [Notifications](/use/notifications/)
