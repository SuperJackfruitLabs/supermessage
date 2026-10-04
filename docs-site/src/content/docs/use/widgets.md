---
title: Widgets and the Lock Screen
description: A Home Screen recap of what happened while you were away, a live Lock Screen card of what needs you now, and why they are different things.
---

Two surfaces, and the split between them is deliberate rather than incidental.

**The Home Screen is a recap.** Widgets cannot be live: WidgetKit defers the reloads a push asks
for — measured at five minutes — and budgets them to a few dozen a day. A widget that pretends to
be current is a widget that lies.

**The Lock Screen is live**, as a Live Activity pushed by the hub. It works with the app closed,
which a local one cannot: a local activity freezes the moment iOS suspends the app.

## The Agents widget: since you last opened

One row per agent that **did something since you last opened the app**. Failed first, then
finished, then said. Quiet agents are left out entirely — a recap of nothing happening is not a
recap.

"Finished · 7 steps" and "Failed at step 4 of 7" come from the counts the hub adds to an answer's
push, not from anything the widget computed.

## The Needs you widget, and answering from it

Pending decisions, answerable in place: Allow, Reject, Approve.

A widget extension has no access to the core — no keychain, and a tight memory ceiling — so the
button runs **in the app's process**. The app asks whether that answer is still owed before
sending it, so a decision answered elsewhere ten seconds ago cannot be answered again from a stale
widget.

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

A decision arriving or leaving reloads the widgets at once. Everything else waits — at most once
every fifteen minutes. The snapshot the widgets read is written by three processes under a lock:
the app while it runs, the extension once per push (the only writer while the app is suspended),
and a background refresh task catching up.

The widget itself **decides nothing** — not even when "active" becomes "idle". The snapshot
carries each moment as a frame, so there is one answer to what a row says rather than one per
process.

## The fleet Live Activity

One card for the whole fleet — not one per turn, and not one per decision. It shows what needs you
and what is happening now, on the Lock Screen and, where there is one, simply in the Dynamic
Island.

It appears while any agent is **active**: a turn is running, a decision is pending, or the agent
spoke within the last fifteen minutes. It ends when every agent has been quiet past that.

**The hub starts, updates and ends it — never the app.** The app's only job is relaying
ActivityKit's tokens to the hub, and deleting them when you sign out. That is what makes the card
live while the app is closed.

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
