---
title: Notifications
description: Answering a permission request from the Lock Screen, how a push stays private, and why nothing arrives blank.
---

An agent usually needs an answer while you are somewhere else. We therefore ask more of a
notification than "something happened": it should carry the answer you can safely give.

The iOS path described here is implemented and covered by local tests, but the complete remote
push, decrypt, and Lock Screen answer flow has not yet been verified on a physical device.

## Answering without opening the app

We derive a notification's actions from the row it represents:

| what arrived | what the notification offers |
|---|---|
| an ordinary message | tapping it opens the room |
| a **permission request** | Allow once · Reject |
| an **approval gate** | Approve · Request changes · Reject |
| a decision that must be read first | Open |

Choose an action and the implemented path sends the same decision the room would, to the same
place. The answer does not depend on that room being open, so you can act without navigating back
through the app first.

Permission requests and gates arrive **time-sensitive**, so they reach you through Focus. Nothing
else does.

## A push does not carry what was said

The configured hub gateway receives a message push with **ids only**: the room, the event, and an
unread count. Not the message.

The app's **Notification Service Extension** is built to fetch that event, decrypt it on your
device, and word the notification from the decrypted row. That design keeps message text out of
the triggering push; Apple receives identifiers rather than the room's contents.

The client contract also accepts **counts** with an agent's answer — seven steps, one failed — and
relays them into the Agents widget. The widget does not derive those numbers from message text.

:::note
The [fleet Live Activity](/use/widgets/#the-fleet-live-activity) is the one exception in the suite,
and a deliberate one: its pushes carry agent names, step titles and a pending decision's question
in plaintext. It is called out on that page rather than buried here.
:::

## One wording, two processes

Two things post notifications: the app, from a row it is already showing, and the extension, from
an event it has only a push for.

We send both through **the same decision in the core**. That gives a permission request the same
Allow and Reject whether the app or extension saw it first, instead of letting two processes race
to tell you different stories.

## Quiet events never interrupt

A reaction, an edit, a turn card — these are not news. They are kept quiet at **three layers**,
each catching what the one before cannot:

| layer | covers | cannot cover |
|---|---|---|
| the hub's gateway | pushes for events the hub itself sent | events it did not send — another client's edit, a person's reaction |
| your account's push rules | unencrypted reactions, edits and suite cards, whoever sent them | **encrypted** events: the homeserver sees only that there is one |
| the extension | everything still arriving, including encrypted events and races | — |

We install the push rules idempotently, never touch a rule outside our own prefix, and leave one
alone if you disabled it. A failed installation is logged rather than made fatal; the extension's
quiet fallback still protects the presentation path.

### Never a blank notification

The honest failure here is worth describing, because an earlier build had it. Told to suppress a
push it could not drop, the extension emptied it — and iOS shows an empty notification as a
**blank banner**.

Without Apple's filtering entitlement an extension cannot discard a push at all. Instead of
emptying it, we show a short true line — "… reacted ✅ to a message", "… finished · 4 steps",
"… edited a message", "Sent from your other device" — **quietly**: passive, no sound, no actions.
A build with the filtering entitlement drops the push instead. In either configuration, the code
never deliberately produces a blank notification.

## Background behaviour

Once a pusher is registered, the app **pauses syncing in the background** so the extension can
take the store's lock and decrypt. The extension becomes the main writer while the app is away;
the architecture is designed for push handling after a force-quit, though that full path still
needs the physical-device verification noted above.

A background launch restores quietly, and the stores are closed before the app can be suspended
mid-write.

## Next

- [Widgets and the Lock Screen](/use/widgets/) — the recap, and the live card
- [Working with agents](/use/agents/) — what these notifications are about
