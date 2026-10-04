---
title: Working with agents
description: Turns, permission requests and approval gates — the part that is not like other Matrix clients.
---

This is why supermessage exists. Everything else is a competent Matrix client; this is the part
that is not available elsewhere.

## Three things an agent sends

| Event | Label | What it is |
|---|---|---|
| `dev.agentpod.turn.v1` | **Turn** | What an agent did during one stretch of work |
| `dev.agentpod.permission.v1` | **Permission** | It wants to do something and is asking first |
| `dev.superpipeline.gate.v1` | **Approval** | A card has reached a point a human must answer |

Each renders as a card in the timeline, in sequence with everything else said in the room.

## Answering

A permission request or a gate arrives with a prompt and a set of options. You answer in the
room, and the answer stays attached to the request — so the record of who decided what lives
where the decision was made, not in a separate audit tool.

A request whose options are missing or malformed renders as a **description of the request**
rather than as a card with no buttons. An unanswerable prompt is worse than a plain sentence.

### Answered is not resolved

Sending your answer to a gate does not close it. The board has to accept it, and when it does the
hub says so in the room — a readable line for every client ("Approved by someone — the board has
it.") and a structured **receipt** beside it.

Until that receipt arrives the card shows your answer as **waiting for the board**. It is not a
nicety: decisions once landed for hours while every one of them was being refused at the other
end, and a card that had read "Approved" would have been wrong about all of them.

When the receipt lands, the card becomes a receipt: no buttons, no amber, naming who decided.

Three things that keep a receipt trustworthy:

- **Only an outcome from the identity that posted the gate counts.** Anyone in a room can send
  this shape, and closing someone else's approval card is exactly what a forged one would be for.
- **Order does not matter.** Gate first, outcome first, or the gate paginated in underneath its
  outcome later — the card reads correctly whenever both are loaded, and stays correct when the
  room is re-entered.
- **If the outcome leaves the timeline** — redacted, or paginated out — the card is drawn pending
  again rather than keeping a conclusion nothing supports.

## When a turn fails

An agent that could not finish — a usage limit, a rate limit, a model that refused the request —
posts one message saying so. Where AgentPod attached the details, it renders as an error card
rather than as a plain message: what went wrong ("Usage limit reached · kimi-coding / k2p6"), the
provider's own words, and every model the agent fell back to, with repeated identical attempts
folded into one line ("×4").

Other clients see the same message as a readable sentence. A card whose details are missing or
malformed is shown as that sentence here too.

## Voice notes

A voice note recorded on iPhone is sent as a Matrix voice message, with its length and a
waveform, so agents that transcribe voice will pick it up. When AgentPod posts the
transcript, it appears directly under the note it belongs to — under your own note on your
side — with a small "Transcript" caption, the detected language and the note's length. A long
transcript opens at six lines with **Show more**. Other clients see the same transcript as a
plain reply that starts "Transcript:".

## An agent answering out loud

Where AgentPod is set to speak an agent's replies, the agent's **text** arrives first and a voice
message of the same words follows seconds later. supermessage folds the two into **one message**:
the ordinary bubble, with the voice note's player above the text it speaks.

The text row is the primary one — it arrives first, it is what every other client shows, and
reactions, replies, edits and copy all address it. An edit keeps the pairing, and the player shows
beside the edited words.

The pairing is honest about every way it can be incomplete:

- **Only the voice has loaded** — the text is further back than pagination has reached, or never
  came — and the voice note is drawn standalone. When the text arrives, the two fold together.
- **Either side is redacted** and the other stands alone, as what it is.
- **Two voice messages naming the same text**: the earlier one pairs, the other stays standalone.

It is deliberately **not** sent as a reply-to. A client that does not know the pairing key would
quote the whole answer a second time above the audio; such a client simply shows two messages,
which is the plain-text fallback.

**Anyone who can send to a room can put that key on a message**, so the pairing requires the voice
message and the text to share a sender. Nobody can fold someone else's words into their own audio.
A row already drawn as something else — an error card, a transcript, a decision — is never paired:
those were decided from the event itself, and a voice note does not outrank them.

## Live output

While an agent is working, partial text streams in over to-device messages — the answer as it is
written, the reasoning behind it, and each tool call as it moves.

Two consequences worth knowing:

- **It is not history.** To-device messages are not stored on the homeserver, not paginated, and
  not visible to any other client, or to this one after a restart. What survives is the message
  the agent posts when the turn finishes.
- **It is paced.** Text is revealed at a readable rate rather than at the rate it arrives.

Reasoning is kept in a collapsed disclosure **after** the answer appears, rather than being
cleared — a record that vanishes the moment the answer lands is one nobody had time to read.

## Other clients still work

Every one of these events carries a plain-text fallback body. Open the room in Element and you
get a readable sentence.

This is a rule the project holds itself to, not an accident: rooms stay open to clients that know
nothing about the suite.

## Versioning

Events carry a schema version. If an agent sends a version newer than this client knows, the card
is still rendered on a best-effort basis and **flagged as newer** — rather than silently
pretending nothing changed, and rather than refusing to show anything.

The reasoning is recorded in the code: putting the version only in the event type would make a
client one minor version behind treat a purely additive change as a wholly unknown event.

## A note on safety

Event payloads come from outside, and the renderers treat them that way. A renderer reads **named
fields, one level at a time**, and never descends into nested structures — which is what makes a
huge or deeply nested payload harmless without needing a depth or size guard.

Everything a renderer produces is **text only**. None of it is ever routed into markup, a link
target, an image source, or a style.

## Where the other planes are

supermessage is the conversation. The work itself lives elsewhere:

- [AgentPod](https://docs.agentpod.dev) — where agents run, and whether that machine is healthy
- [superpipeline](https://docs.superpipeline.dev) — what they are working on, and the gates they wait at
