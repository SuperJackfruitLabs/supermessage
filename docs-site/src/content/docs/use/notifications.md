---
title: Notifications
description: Answering a permission request from the Lock Screen, how a push stays private, and why nothing arrives blank.
---

An agent that needs an answer is usually asking while you are somewhere else. So a notification
has to do more than say something happened: it has to be the thing you answer.

## Answering without opening the app

A notification carries the actions its row deserves:

| what arrived | what the notification offers |
|---|---|
| an ordinary message | tapping it opens the room |
| a **permission request** | Allow once · Reject |
| an **approval gate** | Approve · Request changes · Reject |
| a decision that must be read first | Open |

Answering from the notification sends the same decision the room would, to the same place. You do
not need the room open — or even to have opened the app since the request arrived.

Permission requests and gates arrive **time-sensitive**, so they reach you through Focus. Nothing
else does.

## A push does not carry what was said

The hub is the push gateway, and a message push carries **ids only**: the room, the event, an
unread count. Not the message.

The app's **Notification Service Extension** then fetches that event and decrypts it on your
device, and words the notification from the decrypted row. So the text you read on your Lock
Screen was assembled locally, and the push that triggered it told Apple nothing about the
contents.

One push carries slightly more: an agent's answer that ended a turn with tool calls adds **counts**
— seven steps, one failed — which is what the Agents widget reads. Counts, not text.

:::note
The [fleet Live Activity](/use/widgets/#the-fleet-live-activity) is the one exception in the suite,
and a deliberate one: its pushes carry agent names, step titles and a pending decision's question
in plaintext. It is called out on that page rather than buried here.
:::

## One wording, two processes

Two things post notifications: the app, from a row it is already showing, and the extension, from
an event it has only a push for.

They go through **the same decision in the core**. If they decided separately, the same permission
request would offer Allow and Reject from one and only Open from the other, and whichever arrived
second would win.

## Quiet events never interrupt

A reaction, an edit, a turn card — these are not news. They are kept quiet at **three layers**,
each catching what the one before cannot:

| layer | covers | cannot cover |
|---|---|---|
| the hub's gateway | pushes for events the hub itself sent | events it did not send — another client's edit, a person's reaction |
| your account's push rules | unencrypted reactions, edits and suite cards, whoever sent them | **encrypted** events: the homeserver sees only that there is one |
| the extension | everything still arriving, including encrypted events and races | — |

Installing the push rules is idempotent, never touches a rule outside its own prefix, and leaves
one alone if you disabled it. If it fails, it is logged rather than fatal — the third layer still
holds.

### Never a blank notification

The honest failure here is worth describing, because an earlier build had it. Told to suppress a
push it could not drop, the extension emptied it — and iOS shows an empty notification as a
**blank banner**.

Without Apple's filtering entitlement an extension cannot discard a push at all. So instead of
emptying it, the app shows a short true line — "… reacted ✅ to a message", "… finished · 4 steps",
"… edited a message", "Sent from your other device" — **quietly**: passive, no sound, no actions.
With the entitlement it drops the push instead. Either way you never get a blank one.

## Background behaviour

Once a pusher is registered the app **pauses syncing in the background**, so the extension can
take the store's lock and do the decrypting. That is why the extension is the main writer while
the app is away, and why notifications keep working after a force-quit.

A background launch restores quietly, and the stores are closed before the app can be suspended
mid-write.

## Next

- [Widgets and the Lock Screen](/use/widgets/) — the recap, and the live card
- [Working with agents](/use/agents/) — what these notifications are about
