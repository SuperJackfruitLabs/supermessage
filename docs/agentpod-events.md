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

## Adding a field

1. Add it to the wire struct in `core::live` or to the renderer in
   `core::custom_events`, optional and defaulted, so an old sender still
   deserialises.
2. Bound it in the core if a sender controls it. The boundary is where the
   guarantee is made; a host must not be the first place a length is checked.
3. Render it on both hosts, or on neither. Two clients disagreeing about what
   an event means is the failure this whole layer exists to prevent.
