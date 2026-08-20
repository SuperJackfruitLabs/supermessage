# AgentPod events: what supermessage consumes

**Status:** contract, Aug 2026. Written against what the client actually reads,
not against what either side plans to send.
**Audience:** whoever changes what AgentPod emits, and whoever changes what this
client renders.

`docs/matrix-events.md` §G covers the *Matrix* taxonomy and treats suite events
as future work. This file is the concrete other half: the two channels AgentPod
already uses, field by field, and what each one does on screen today.

There are two of them, and the difference between them is the whole point.

## 1. The live channel — to-device, and therefore temporary

Three to-device event types carry a turn while it is being written:
`dev.agentpod.live` (the answer), `dev.agentpod.thought` (the reasoning), and
`dev.agentpod.tool.update` (each tool call as it moves). See
`core::live` for the wire structs and the ordering rules.

**Nothing here is room history.** To-device messages are not stored on the
homeserver, are not paginated, and are not visible to any other client or to
this one after a restart. That is the correct design for a stream of partial
text, and it is also a hard ceiling: anything that should still be readable
tomorrow cannot live here.

What the client does with it:

| Field | Effect |
|---|---|
| `dev.agentpod.live.text` / `.done` | The answer, revealed by `StreamingText` at a paced rate rather than as it arrives. `done` drains the buffer and drops the streamed copy — the real message is landing on the timeline and says it better. |
| `dev.agentpod.thought.text` | The reasoning, in a collapsed disclosure. **Kept after `done`** until the next turn starts, because a record that vanishes when the answer appears is one nobody has had time to read. |
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

## Adding a field

1. Add it to the wire struct in `core::live` or to the renderer in
   `core::custom_events`, optional and defaulted, so an old sender still
   deserialises.
2. Bound it in the core if a sender controls it. The boundary is where the
   guarantee is made; a host must not be the first place a length is checked.
3. Render it on both hosts, or on neither. Two clients disagreeing about what
   an event means is the failure this whole layer exists to prevent.
