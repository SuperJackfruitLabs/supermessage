# AgentPod events: what supermessage consumes

**Status:** contract, Aug 2026; event names corrected 2026-09-12. Written against
what the client actually reads, not against what either side plans to send.

The correction: this file named the live channel `dev.agentpod.live` and the
reasoning channel `dev.agentpod.thought`. Neither string exists on either side.
Both implementations have always agreed on `dev.agentpod.stream.delta` and
`dev.agentpod.thought.delta` — verified against `core::live` here and against
`apps/hub/src/services/matrix-as/` in agentpod. `text` and `done` are *fields*
on those events, not event subtypes, which is how the wrong names read as
plausible.
**Audience:** whoever changes what AgentPod emits, and whoever changes what this
client renders.

`docs/matrix-events.md` §G covers the *Matrix* taxonomy and treats suite events
as future work. This file is the concrete other half: the two channels AgentPod
already uses, field by field, and what each one does on screen today.

There are two of them, and the difference between them is the whole point.

## 1. The live channel — to-device, and therefore temporary

Three to-device event types carry a turn while it is being written:
`dev.agentpod.stream.delta` (the answer), `dev.agentpod.thought.delta` (the
reasoning), and `dev.agentpod.tool.update` (each tool call as it moves). See
`core::live` for the wire structs and the ordering rules.

Each carries `room_id`, `session_id` and a monotonic `seq`. A new turn restarts
at `seq: 1`, which is what distinguishes a fresh answer from more of the last
one.

**Nothing here is room history.** To-device messages are not stored on the
homeserver, are not paginated, and are not visible to any other client or to
this one after a restart. That is the correct design for a stream of partial
text, and it is also a hard ceiling: anything that should still be readable
tomorrow cannot live here.

What the client does with it:

| Field | Effect |
|---|---|
| `dev.agentpod.stream.delta` — `text`, `done` | The answer, revealed by `StreamingText` at a paced rate rather than as it arrives. `done` drains the buffer and drops the streamed copy — the real message is landing on the timeline and says it better. |
| `dev.agentpod.thought.delta` — `text`, `done` | The reasoning, in a collapsed disclosure. **Kept after `done`** until the next turn starts, because a record that vanishes when the answer appears is one nobody has had time to read. |
| `dev.agentpod.tool.update` | One row per `tool_call_id`, merged on later reports. |

### Tool calls: two fields AgentPod does not send yet

`ToolUpdateToDeviceEventContent` accepts `input` and `output`, both optional
strings, bounded by the core at 4000 characters (`live::bound_tool_text`) and
rendered as a disclosure under the tool's row.

**No harness fills them in today.** A row with neither stays a plain row rather
than a disclosure that opens onto an empty box, so adding them is additive: a
harness that starts sending them needs no client change, and one that never
does loses nothing.

`kind` and `locations` *are* sent and are rendered — `locations` as "Touched".

## 2. The turn card — a room event, and therefore permanent

`dev.agentpod.turn.v1` is an ordinary `MsgLikeKind::Other` room event, rendered
by `custom_events::TurnActivityRenderer`. Being a room event is the difference
that matters: it survives a restart, it is there when a reader scrolls back,
and every client in the room sees it.

| Field | Effect |
|---|---|
| `schema_version` | Above `1.0` renders best-effort and is flagged "shown from a newer version". |
| `counts.total` / `.failed` / `.omitted` | The headline row — "7 things, 1 failed" — because a reader scanning a room wants that before the list. |
| `tools[].title` / `.status` | One row each. `title` goes through `custom_events::tool_title`, which unwraps `bash -lc`, folds continuation lines, and shortens deep absolute paths from the front so the filename survives the field cap. |
| `reasoning` | **New, and not sent yet.** Long-form prose, bounded at 4000 characters, rendered as a collapsed disclosure on the card. |

### Why `reasoning` belongs here and not on the live channel

Both channels can carry reasoning; only this one keeps it. A reader who asks
"why did it do that?" an hour later, or on another device, or by scrolling back
— which is when the question is usually asked — is looking at the turn card,
because the to-device stream that produced the answer no longer exists
anywhere.

The client renders it on both hosts today. **Until AgentPod includes it, the
card looks exactly as it does now.** The live card's reasoning is the stopgap,
and it is bounded by the next turn.

## 3. The turn error card — a key on an ordinary message

When a turn fails, the hub posts **one** `m.room.message` (`m.text`) whose
`body` is a complete sentence for any client — "This agent reported an error:
You've reached your weekly (7-day) usage limit…". The same event's `content`
carries `dev.agentpod.turn_error` (agentpod `TurnErrorCard`,
`packages/contract/src/matrix-events.ts`), parsed by `core::turn_error` and
drawn as `ItemView::TurnError` instead of the plain bubble.

| Field | Effect |
|---|---|
| `schema_version` | Required and numeric, or the key is ignored. Above `1` is read best-effort, without a "newer version" note: the `body` beside it is always complete. |
| `kind` | The headline's wording — `quota` is "Usage limit reached", `bad_request` "Request rejected", and so on (`TurnErrorKind::label`). A kind this build does not know reads "Error". |
| `message` | The card's body text, bounded at 4000 characters, selectable. Required. |
| `harness` | Required, bounded at 100. Carried on the card; not drawn today. |
| `provider` / `model` | The headline's second half — "· kimi-coding / k2p6". Bounded at 200 each. |
| `retryable` | Carried; not drawn today. A non-boolean is absent. |
| `attempts[]` | The fallback chain, first the model asked for. Malformed entries are skipped, at most 16 are read, and consecutive identical attempts (same provider, model, kind and message) fold into one line with a count — "×4". Collapsed to its first line when there is more than one. |

A key that does not parse leaves the message as the ordinary bubble of its
`body`; it never drops or blanks the message. Anyone in the room can send this
key, so every value is bounded again here and drawn as text only. The ❌
reaction the hub puts on the prompting message is unaffected.

## 4. The voice transcript — a key on a reply to the note

**Sending.** A recording from iOS goes as an MSC3245 voice message:
`m.audio` with `org.matrix.msc3245.voice`, MSC1767's
`org.matrix.msc1767.audio` block (duration and a waveform) and `info.duration`
in milliseconds (`core::attachments::StagedAttachments::mark_voice`, FFI
`attachment_mark_voice`). Hermes transcribes only audio flagged as voice, and
the hub reads `info.duration` to refuse a note over five minutes before
downloading it. Android and the desktop do not record.

**Receiving.** Once the hub has transcribed a note, it posts an `m.notice`
that **replies** to it (`m.relates_to.m.in_reply_to`), whose `body` is
`Transcript: <text>` and whose `content` carries `dev.agentpod.voice_transcript`,
parsed by `core::voice_transcript` and drawn as `ItemView::VoiceTranscript`: a
quiet block directly under the note, on the note's side (trailing under your
own note, `onOwnNote`), captioned "Transcript · hi · 0:42", clamped to six
lines with "Show more". Without the key the same notice is a one-line system
line from the agent, detached from the note.

| Field | Effect |
|---|---|
| `schema_version` | Must be exactly `1`, or the key is ignored. Read strictly, unlike the turn error card: the `body` beside it already says the whole transcript. |
| `text` | Required, non-empty once trimmed, at most 20000 characters (code points). Selectable. |
| `language` | Optional, at most 16 ASCII letters, digits, `-`, `_` or spaces; anything else refuses the key. The caption's middle part. |
| `seconds` | Optional integer `0..=3600`. Drawn as `m:ss`. |

Present-but-invalid optional fields refuse the whole key; `null` is absent.
The side comes from the reply's parent sender when the parent loaded, and
from the notice's own sender otherwise. On iOS a transcript does not raise a
notification of its own — the note already did.

## 5. A permission request or a gate — a key on the prose that asks it

AgentPod sends a permission request as an ordinary prose `m.room.message`
("Allow Run the migration? Reply 1 …") for every client, and the structured
request for a client that draws buttons. That second half used to be a
separate event beside the prose (`dev.agentpod.permission.v1`; a gate's is
`dev.superpipeline.gate.v1`). For push it is moving **onto the prose message**,
under one key, the way the turn error card and the transcript already ride:

| Key on the prose message | Its value | Drawn as |
|---|---|---|
| `dev.agentpod.permission` | The object the separate event's `content` carries (agentpod `PermissionRequestEvent`) | The permission card |
| `dev.superpipeline.gate` | The gate schema's object | The approval card |

The key names are this client's reading of the contract; when this landed
(2026-09-28) the hub's `feat/push-gateway` branch had not published its
constant, so **both forms are read**: the separate event exactly as before,
and the key (`core::embedded`). A key whose value does not render leaves the
message the sentence it is. The prose carrying the key becomes the card
(`ItemView::CustomEvent`) with the prose as its fallback body; its
`TimelineItemDto` carries the event type in `detail` and the object in
`custom_payload`.

**One card per decision.** A hub mid-migration may send both forms. The core
keeps one: while a prose message carrying a decision is in the timeline, a
separate event for the same decision renders as `ItemView::None`, and draws
again if the prose goes (`embedded::reconcile`, applied to the materialised
timeline and sent as `Set` ops in the same envelope). "The same decision" is
`session_id` + `request_seq` for a permission request and `gate_id` for a
gate. A gate answered from an embedded card references the prose message's
event id.

On iOS the same event notifies through the core too
(`core::notification::notification_for_row`): a permission request with Allow
once and Reject gets the PERMISSION category, a gate with Approve or Reject the
GATE category, anything else DECISION ("Open" only).

## 6. A gate's outcome — the room saying the board accepted an answer

When superpipeline accepts an answer to a gate, the hub says so in the gate's
room (agentpod#614, #615): a readable line for every client ("Approved by
rakesh — the board has it.") and a structured receipt beside it.

| Field | Type | Meaning |
|---|---|---|
| `suite_event_type` | `"dev.superpipeline.gate.outcome.v1"` | Declares the receipt. On an `m.room.message` it is **required** — it is how the prose form is told apart from prose that merely mentions the type |
| `gate_id` | string | The gate resolved — superpipeline's id, the same one the gate carries |
| `board_id` | string | The gate's board |
| `decision` | string | superpipeline's `GateDecision`: `approve`, `request_changes`, `reject` |
| `decided_by` | string or null | Who answered, as the hub names them (a handle, not an mxid) |
| `m.relates_to` | `{ rel_type: "m.reference", event_id }` | The gate's event — whichever the hub recorded as the gate's (`matrix_gate_events.event_id`: the legacy `dev.superpipeline.gate.v1` companion while legacy events are on, otherwise the prose) |

It arrives either as a **custom event** of type
`dev.superpipeline.gate.outcome.v1` (what #615 sends, via `sendCustomEvent`)
or as an **`m.room.message`** carrying the same keys. Both are read
(`core::gate_outcome`); both arrive on a `TimelineItemDto` with `detail` the
outcome type and `custom_payload` the event's `content`. The custom event
renders as `ItemView::None` — the readable line beside it says the same thing
— and the prose form stays the ordinary message it is.

**What it does to the card.** `embedded::reconcile`, the pass that already
keeps one card per decision, also turns every carrier of a resolved gate into
a receipt: `CustomEventView::Rendered` with `decision: None` and `outcome:
Some(CustomEventOutcome { decision, decided_by, summary, prompt })`. Hosts
draw it without buttons or amber, naming `decided_by`.

**Matching an outcome to its card**, in order:

1. **Same sender.** Only an outcome from the identity that posted the gate
   counts. Anyone in a room can send this shape; closing someone's approval
   card is what a forged one would be for. The hub speaks both as the room's
   speaker (the board's, in a board room).
2. **`gate_id`**, unless both sides carry a `board_id` and they differ. It
   closes every carrier of the gate — the prose card on screen and the hidden
   companion alike.
3. **The reference**, only when the outcome carries no `gate_id`: the gate
   whose carrier has that event id, and through its identity every other
   carrier.

**Order does not matter.** The pass runs over the whole materialised timeline
after every batch, so a gate is a receipt whenever both events are loaded —
outcome first on a cold sync, gate first in live sync, or the gate brought in
under its outcome by back-pagination — and stays one when its row is
re-projected or the room is re-entered. If the outcome leaves the timeline
(redacted, paginated out) the card is drawn pending again.

**Answered is not resolved.** The reader's own
`dev.superpipeline.gate.decision.v1` references the gate too and closes
nothing: on 2026-09-28 decision events landed for hours while every resolve
was refused (agentpod#613). A host keeps its per-device "answered" state —
set when a decision send lands — and draws it as waiting for the board, which
the outcome then replaces.

**Notifications.** The prose line notifies as an ordinary message; the custom
event is suppressed as `NotNews`. Neither is ever a PERMISSION or GATE
notification.

## Adding a field

1. Add it to the wire struct in `core::live` or to the renderer in
   `core::custom_events`, optional and defaulted, so an old sender still
   deserialises.
2. Bound it in the core if a sender controls it. The boundary is where the
   guarantee is made; a host must not be the first place a length is checked.
3. Render it on both hosts, or on neither. Two clients disagreeing about what
   an event means is the failure this whole layer exists to prevent.
