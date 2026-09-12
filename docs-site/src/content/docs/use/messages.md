---
title: Messages
description: Sending, editing, replying, reacting, and what each message type looks like.
---

The ordinary half of the client. It is ordinary on purpose — the interesting parts should be the
agents, not relearning how to send a message.

## Sending

Markdown is supported and converted to formatted HTML on the way out, so what you write reads the
same in Element as it does here.

Mentions are collected as you type.

## Editing and deleting

Messages can be edited and deleted. An edited message is folded into the original by the SDK and
carries an **edited** marker rather than appearing twice.

A deleted message leaves a tombstone, not a blank. A gap where a message used to be is worse than
a line saying one was removed.

## Replies

A reply quotes its parent above the body.

When the parent is something that cannot be quoted as text — a sticker, a poll, a redacted
message, an undecryptable one — you get a label saying what it was rather than an empty quote.

## Reactions

Reactions aggregate onto the message they target rather than appearing as separate timeline
entries, and there is an emoji picker.

## Attachments

Files are staged before they are sent, so you can discard one you picked by mistake. Images,
files, audio and video are all handled, and media is downloaded on demand.

## How message types render

The client switches on the Matrix **msgtype**, which most clients get wrong in at least one
direction:

| msgtype | Renders as |
|---|---|
| `m.text` | An ordinary bubble |
| `m.notice` | A **de-emphasised** bubble — automated output, never suppressed |
| `m.emote` | `* Alice waves`, not a bubble |
| `m.image` | An image, with dimensions where the sender gave them |
| `m.file`, `m.audio`, `m.video` | A file row with name, size and type |

`m.notice` matters more here than in most clients: it is what bots and bridges are supposed to
use, so it is the msgtype most agent traffic actually arrives as. De-emphasised, because a room
where the machines shout as loudly as the people is a room nobody reads.

## Typing

Typing indicators are sent and shown.

## What you will not see

State changes are **suppressed unless they are something you must know**: the room being created,
encryption being turned on, the room being replaced. Renames, topic edits, avatar changes, power
level adjustments and join-rule changes all happen silently.

Membership changes show as one-line system text, with runs of them collapsed — a room that ten
agents joined is one line, not ten.

## Next

- [Working with agents](/use/agents/)
- [Encryption](/use/encryption/)
