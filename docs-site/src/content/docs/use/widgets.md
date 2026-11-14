---
title: Widgets and the Lock Screen
description: A Home Screen recap of what happened while you were away, a live Lock Screen card of what needs you now, and why they are different things.
---

We use two surfaces for two different promises. Keeping them separate is how we avoid calling an
old snapshot live.

**The Home Screen is a recap.** WidgetKit defers requested reloads — five minutes in the recorded
device observation — and budgets them to a few dozen a day. So we show what happened while you
were away instead of pretending an old widget is current.

**The Lock Screen is designed to be live**, through a Live Activity pushed by the hub rather than
updated by the app. The client-side token path is implemented, but the full hub-to-device flow has
not yet been verified on a physical device.

## The Agents widget: since you last opened

Open the Agents widget and you get one row per agent that **did something since you last opened the
app**: failed first, then finished, then said. We leave quiet agents out, so the recap spends its
small space on something that happened.

"Finished · 7 steps" and "Failed at step 4 of 7" come from the counts the hub adds to an answer's
push, not from anything the widget computed.

## The Needs you widget, and answering from it

The Needs you widget collects pending decisions and offers the answers it can send safely: Allow,
Reject, or Approve.

A widget extension has no access to the core — no keychain, and a tight memory ceiling — so the
button runs **in the app's process**. Before sending, we ask whether the answer is still owed. A
stale widget therefore opens no second decision after you answered elsewhere.

### Answered is not resolved

This is the rule worth knowing. Tapping **Approve** sends your decision; it does not close the
gate. Only the board's own receipt says the gate is over.

So a tapped gate stays visible, marked answered and out of the count, and reads **"waiting for the
board"** — never "Approved". The distinction earned its place: decisions once landed for hours
while every one of them was being refused on the other side, and a UI that had said "Approved"
would have been lying for all of them.

A permission request has no receipt — the answer *is* the message — so once sent it is simply
shown as sent.

## Refresh, honestly

A decision arriving or leaving asks WidgetKit to reload at once. Everything else waits — at most
once every fifteen minutes. Three processes write the snapshot under a lock:
the app while it runs, the extension once per push (the only writer while the app is suspended),
and a background refresh task catching up.

The widget itself **decides nothing** — not even when "active" becomes "idle". The snapshot
carries each moment as a frame, so there is one answer to what a row says rather than one per
process.

## The fleet Live Activity

The intended Live Activity is one card for the whole fleet — not one per turn and not one per
decision. It shows what needs you and what is happening now on the Lock Screen and, where there is
one, more simply in the Dynamic Island.

The hub contract keeps it present while any agent is **active**: a turn is running, a decision is
pending, or the agent spoke within the last fifteen minutes. It ends after every agent has been
quiet past that. That remote behavior remains part of the unverified end-to-end path above.

**The hub, not the app, owns starting, updating, and ending it.** SuperMessage relays ActivityKit's
tokens to the hub and deletes them when you sign out. With that division, the design does not need
the app to stay awake to keep the card moving.

:::caution[This is the one place your words reach Apple]
The Live Activity's pushes carry **agent names, the current step's title, and a pending decision's
question and option labels in plaintext**, readable by Apple in transit. That is a deliberate
trade, taken so the Lock Screen card can say something useful rather than "an agent needs you".

Everything else holds the opposite line: message pushes carry ids only, and the notification text
you read is assembled on your device after local decryption. See
[Notifications](/use/notifications/#a-push-does-not-carry-what-was-said).
:::

## Next

- [Notifications](/use/notifications/) — answering from the Lock Screen, and what stays private
- [Working with agents](/use/agents/) — turns, permissions and gates themselves
