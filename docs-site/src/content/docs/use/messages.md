---
title: Messages
description: Sending, editing, replying, reacting, and what each message type looks like.
---

Here is the deliberately ordinary half of the client. You already know how a conversation should
behave; we would rather spend your attention on the agents than on relearning Send.

## Sending

Write Markdown when you need it. We convert it to formatted HTML on the way out, so emphasis and
structure survive when somebody opens the message in Element.

Mention somebody as you type and the message carries that mention with it. The reader gets both
the visible name and the Matrix metadata that makes it an actual mention.

## Editing and deleting

Edit a message and the SDK folds the replacement into the original, adding an **edited** marker
instead of a second copy. Delete one and we leave a tombstone rather than a blank.

You can now tell both that the conversation changed and where it changed; no mystery gap required.

## Replies

Reply to a message and its parent appears above your body, so the answer keeps its context even
after the room moves on.

When the parent is something that cannot be quoted as text — a sticker, a poll, a redacted
message, an undecryptable one — you get a label saying what it was rather than an empty quote.

## Reactions

Choose a reaction from the emoji picker and it joins the count on the message it targets. The
timeline stays a conversation rather than becoming a list of reaction events.

## Attachments

Pick a file and we stage it before sending, which gives you a chance to discard the wrong one.
Images, files, audio, and video are all handled; recipients download the media on demand.

## How message types render

We switch on the Matrix **msgtype** rather than treating every message as the same bubble:

| msgtype | Renders as |
|---|---|
| `m.text` | An ordinary bubble |
| `m.notice` | A one-line system notice, or a de-emphasised bubble for longer or formatted output — never suppressed |
| `m.emote` | `* Alice waves`, not a bubble |
| `m.image` | An image, with dimensions where the sender gave them |
| `m.audio` | An audio player, with length and waveform where the sender gave them |
| `m.file`, `m.video` | A file row with name, size and type |

`m.notice` matters more here than in most clients: bots and bridges are supposed to use it, and it
is how most agent traffic arrives. We de-emphasise it so the machines do not shout over the people.

## Typing

When you type, the room gets a typing indicator; when somebody else types, you see theirs. That
small bit of motion is enough to tell you the conversation has not stalled.

## What you will not see

State changes are **suppressed unless they are something you must know**: the room being created,
encryption being turned on, the room being replaced. Renames, topic edits, avatar changes, power
level adjustments and join-rule changes all happen silently.

Membership changes show as one-line system text, with runs of them collapsed — a room that ten
agents joined is one line, not ten.

## Next

- [Working with agents](/use/agents/)
- [Encryption](/use/encryption/)
