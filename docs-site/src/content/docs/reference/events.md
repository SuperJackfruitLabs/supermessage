---
title: Event types
description: Every suite event SuperMessage draws, how each arrives, the answer it sends, what closes it, and the limits it applies to a payload.
---

The shapes are specified in [Treaty's Matrix catalogue](https://treaty.superjackfruit.com/matrix/), which has a page per
type with its JSON Schema. This page says what SuperMessage does with each one. For a worked
example of sending them, see [Sending cards to a room](/build/cards/).

Every card is drawn from the event alone, in the shared Rust core, so the desktop app, iPhone and
Android draw the same thing.

## Cards

| Event | Card | How it arrives | Answer it sends |
|---|---|---|---|
| `dev.agentpod.turn.v1` | Turn | Its own event | None |
| [`dev.agentpod.permission.v1`](https://treaty.superjackfruit.com/matrix/permissions/agentpod-permission-v1/) | Permission | Its own event, or under `dev.agentpod.permission` on a prose message | A text message: the option's name |
| [`dev.superpipeline.gate.v1`](https://treaty.superjackfruit.com/matrix/cards/superpipeline-gate-v1/) | Approval | Its own event, or under `dev.superpipeline.gate` on a prose message | A [gate decision, v1](https://treaty.superjackfruit.com/matrix/answers/superpipeline-gate-decision-v1/) |
| [gate.v2](https://treaty.superjackfruit.com/matrix/cards/superpipeline-gate-v2/) | Approval | A prose message that declares it in `suite_event_type` | A [gate decision, v2](https://treaty.superjackfruit.com/matrix/answers/superpipeline-gate-decision-v2/) |
| [permission.v2](https://treaty.superjackfruit.com/matrix/permissions/agentpod-permission-v2/) | Permission | The same | A [permission answer, v2](https://treaty.superjackfruit.com/matrix/permissions/agentpod-permission-answer-v2/) |
| [question.v2](https://treaty.superjackfruit.com/matrix/cards/superpipeline-question-v2/) | Question | The same | A [question answer, v2](https://treaty.superjackfruit.com/matrix/answers/superpipeline-question-answer-v2/) |
| [delivery.v2](https://treaty.superjackfruit.com/matrix/cards/superpipeline-delivery-v2/) | Delivery | The same | A [delivery answer, v2](https://treaty.superjackfruit.com/matrix/answers/superpipeline-delivery-answer-v2/) |
| [attention.v2](https://treaty.superjackfruit.com/matrix/cards/superpipeline-attention-v2/) | Waiting card | The same | An [attention answer, v2](https://treaty.superjackfruit.com/matrix/answers/superpipeline-attention-answer-v2/) |
| [checks.waiting.v2](https://treaty.superjackfruit.com/matrix/cards/agentpod-checks-waiting-v2/) | Checks overdue | The same | A [check override, v2](https://treaty.superjackfruit.com/matrix/answers/superpipeline-check-override-v2/), with a required comment |
| `dev.supermessage.demo.note.v1` | Note | Its own event | None. This client's own namespace, for testing a renderer. |

Every v1 answer and every v2 answer is an `m.room.message`. The structured ones carry their type
in `suite_event_type` and an `m.reference` relation to the card they answer.

A version 2 card falls back to its version 1 form when its own fields do not validate: a gate.v2
message carrying a v1 gate draws the v1 card, and a permission.v2 message carrying a v1 request
draws the v1 request. question.v2, delivery.v2, attention.v2 and checks.waiting.v2 have no v1
form, and without valid fields they stay prose.

## Receipts and notices

These close or settle a card. A receipt or notice sent as its own event draws nothing: the
readable line posted beside it is what every client shows. Reminders and escalations are the
exception, because the prose message is itself the line.

| Event | What it does |
|---|---|
| [`dev.superpipeline.gate.outcome.v1`](https://treaty.superjackfruit.com/matrix/receipts/superpipeline-gate-outcome-v1/) | Turns a gate (v1 or v2) into a receipt. With `record_live_url`, `switch_to_automatic` or `settled_elsewhere` it closes a delivery follow-up instead, and never the gate. |
| [Permission outcome, v1](https://treaty.superjackfruit.com/matrix/permissions/agentpod-permission-outcome-v1/) | Turns a permission (v1 or v2) into a receipt |
| [Attention outcome, v1](https://treaty.superjackfruit.com/matrix/receipts/superpipeline-attention-outcome-v1/) | Turns a waiting card into a receipt |
| [Gate progress, v2](https://treaty.superjackfruit.com/matrix/notices/agentpod-gate-progress-v2/) | How many approvals a quorum gate has. The gate's card shows the count. |
| [Decision released, v2](https://treaty.superjackfruit.com/matrix/notices/agentpod-decision-released-v2/) and [pulled back, v2](https://treaty.superjackfruit.com/matrix/notices/agentpod-decision-pulled-back-v2/) | Settles a gate's receipt that was held until a release time |
| [Check failed, v2](https://treaty.superjackfruit.com/matrix/notices/agentpod-check-failed-v2/) and [overridden, v2](https://treaty.superjackfruit.com/matrix/notices/agentpod-check-overridden-v2/) | Settles a card of overdue checks |
| [Decision reminded, v1](https://treaty.superjackfruit.com/matrix/notices/superpipeline-decision-reminded-v1/) and [escalated, v1](https://treaty.superjackfruit.com/matrix/notices/superpipeline-decision-escalated-v1/) | Drawn as a quiet line in the timeline |

A gate's outcome counts only from the account that posted the gate. Whatever order a card and
its receipt load in, the card is drawn from both; when the receipt leaves the timeline, the card
is pending again.

## One card per decision

| Rule | Detail |
|---|---|
| The prose message wins | A separate event and a prose message carrying the same decision draw one card, the prose message's |
| The latest copy is the card | When the same sender posts a board decision again below, earlier copies read *Moved below — answer the copy below.*, with no buttons |
| What makes two carriers the same | A permission: `session_id` and `request_seq`. A gate: `gate_id`. A question: `elicitation_id`. A follow-up: `gate_id`. A waiting card: `board_id`, `card_id`, `wait_id`. Overdue checks: `board_id`, `card_id`, `stage_key`. |

## Keys on a message

| Key | On | What it draws |
|---|---|---|
| `dev.agentpod.permission` | `m.room.message` | A permission request, v1 |
| `dev.superpipeline.gate` | `m.room.message` | A gate, v1 |
| `suite_event_type` | `m.room.message` | Declares a v2 card, an answer, a receipt or a notice |
| `dev.agentpod.turn_error` | `m.room.message` | An error card for a failed turn |
| `dev.agentpod.voice_transcript` | A notice | A transcript under the voice note it belongs to |
| `dev.agentpod.voice_reply` | `m.audio` | A voice note folded into the text it speaks, from the same sender |

## Fallbacks

| Case | What shows |
|---|---|
| A known type, its renderer produced rows | The card |
| A known type with nothing usable, or an unknown type | The event's `body` |
| No `body` either | `Custom event (` and the type, then `)` |
| `schema_version` above what the renderer knows | The card, best effort, flagged as newer |
| Missing `schema_version` | Read as the baseline version |

## Limits

| What | Limit |
|---|---|
| A card's payload, serialized | 8 KiB. Over it, the payload is dropped whole and the `body` shows. |
| A version 2 card's payload, serialized, with `body` and the v1 card removed | 32 KiB. Over it, a gate falls back to its v1 card. |
| Rows on one card | 12 |
| A row's label | 60 characters |
| A row's value, a prompt | 300 characters |
| Buttons on one card | 4 |
| Reasoning or a handoff summary | 4,000 characters |
| A link | `https://` only, printable ASCII, at most 2,048 characters |
| A gate's v1 option ids | `approve`, `request_changes`, `reject`; others are dropped |
