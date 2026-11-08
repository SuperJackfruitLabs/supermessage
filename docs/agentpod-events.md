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

Turn state itself (start, steps, end, the record kept after) is `core::turns`, fed from these events, from the agent's reply landing on the timeline, and from a 30-minute time limit. Hosts read it from `CoreEvent::Turn` and `Session::turn`; they do not rebuild it.

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
downloading it. The desktop and Android record voice messages as well.

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
from the notice's own sender otherwise. A transcript never raises a
notification of its own — the note already did — whether it is drawn
standalone or folded (`core::notification` reads the row's
`voice_transcript`, not its view).

**Under its note, wherever it lands** (`core::voice_transcript::reconcile`,
run over the whole timeline after every batch, beside the voice reply's
pairing). The notice arrives seconds after the note, and anything can land in
between: on 2026-09-30 the sender typed "Hi?" a second after the note, the
transcript arrived after "Hi?" — and after the unread divider — and was drawn
there, reading as a reply to "Hi?". Now:

- **Both loaded** — the notice's `m.in_reply_to` names a voice message drawn
  as a note: the note's row is `ItemView::Audio { transcript: Some(..) }`,
  and every host draws the transcript directly under the note in that same
  row (the same quiet block, on the note's side). The notice's row stays in
  the list as `ItemView::None`. Both change by `Set` at their own indices —
  nothing is inserted or moved, so nothing scrolls.
- **Only the notice loaded** — the note is further back than pagination has
  reached: the notice is drawn standalone (`ItemView::VoiceTranscript`), as
  above, and folds in when the note arrives.
- **Either side goes** — a redacted notice takes the transcript off the note;
  a redacted or paginated-out note leaves the transcript standalone. An edit
  of the notice re-folds with the new words.
- **One per note** — a second transcript of the same note stays standalone,
  seen for what it is rather than replacing the first.
- A note drawn as something else (an agent's spoken answer folded into its
  text, §4a) keeps its transcript standalone.
- The **unread divider** is hidden while nothing visible follows it (its only
  new message was a transcript now drawn further up) and drawn again when
  something does.

The notice's row carries `TimelineRow.voice_transcript` (the note's event id,
the transcript and its standalone side) so the fold can be undone after the
raw event is gone; no host reads it. Three linear passes, one hash lookup per
row.

**Playing an agent's voice note on iOS.** AgentPod's speech service writes
Ogg/Opus with libsndfile, which ends every stream on a *shorter* packet (the
tail that does not fill a 20 ms frame goes out as 2.5, 5 or 10 ms). iOS plays
Opus only from CAF, so `core::opus_container::ogg_to_caf` remuxes; a CAF whose
`desc` says "0 frames per packet" (a per-packet table) is refused by Apple's
decoder for a short note (`AVAudioPlayer.prepareToPlay()` false — "This audio
can't be played here.") and loses its odd packet in a long one. So the remux
brings an odd packet at either **end** up to the stream's packet length with
empty Opus frames (RFC 6716 §3.2.1: zero-length, concealed by the decoder),
which the end trim or a longer pre-skip then discards: every sample the Ogg
had is kept, byte for byte, and the CAF says one packet length, as Apple's own
files do. A stream that changes frame length in the middle keeps a per-packet
table. Android plays the Ogg itself; the desktop remuxes only where WebKit has
no Ogg.

## 4a. The voice reply — a key on the voice message that speaks an answer

When an agent answers a voice note (or the station always speaks), the hub
posts the agent's ordinary **text** reply first and then, seconds later, an
MSC3245 voice message of the same words, sent as the agent: `m.audio` with
`org.matrix.msc3245.voice`, `org.matrix.msc1767.audio` (duration and a
waveform), `url` (or `file` in an encrypted room), and one namespaced key naming
the text it speaks (agentpod `packages/contract/src/matrix-events.ts`,
`VOICE_REPLY_CONTENT_KEY` / `VoiceReply`):

```json
"dev.agentpod.voice_reply": {
  "schema_version": 1,
  "text_event_id": "$text…",
  "voice": "bf_emma",
  "seconds": 4
}
```

| Field | Effect |
|---|---|
| `schema_version` | Must be exactly `1`, or the key is ignored. |
| `text_event_id` | Required string, 1–255 characters (code points). The text message this voice speaks. Compared, never displayed. |
| `voice` | Required string, 1–64 characters: the speech service's voice id or blend (`af_heart:60+af_bella:40`). Not drawn. |
| `seconds` | Optional integer `0..=3600`. Only used when the audio's own metadata carries no length. |

Unknown fields are ignored; `null` is absent; anything else outside the
contract refuses the whole key (`core::voice_reply::parse_voice_reply`), and
the message is the ordinary voice note it would have been without it.

**Plain-text fallback.** The voice message is deliberately *not* an
`m.in_reply_to`: a client that does not know the key would quote the whole
answer again above the note. Element, Cinny and every other client show two
messages — the text, then a voice note — and both are complete on their own.

**The pairing rule** (`core::voice_reply::reconcile`, run over the
materialised timeline after every batch, next to `embedded::reconcile`, and
sent as `Set` ops in the same envelope):

- **Both loaded, same sender.** The *text* row becomes the one message:
  `ItemView::Bubble { voice: Some(VoiceReplyPlayer) }` — the voice note's
  player (retitled "Voice reply", read as "Voice reply, 4 seconds") drawn
  **first**, then the text in the ordinary message style, as a voice note sits
  above its transcript. The voice row stays in the list but is
  `ItemView::None`, which every host drops before grouping, so it neither
  draws nor splits the agent's run. The voice message's sender must be the
  text's: nobody can fold someone else's message into their own audio.
- **Only the voice loaded** (the text is past the pagination edge, or never
  came): the voice row is drawn standalone, as the voice note it is. When the
  text arrives — normally *before* the voice, seconds apart — the rows update
  in place: the text row gets a `Set` at its own index, the voice row a `Set`
  to `None`. No row moves, is removed or is re-inserted, so a host's scroll
  anchor is untouched.
- **Either side goes.** A redacted voice leaves the plain text (and the
  ordinary "Message deleted" line where the voice was); a redacted text leaves
  the voice note standalone beside its "Message deleted".
- **An edit** (`m.replace`) of the text is folded into the text row by the SDK
  and keeps the pairing, with the new words under the player.
- **One voice per text.** The earlier of two voices naming one text pairs; the
  other stays a standalone voice note.
- **Never over a decided view.** A text drawn from its raw event — a turn
  error card, a transcript, a suite decision — is not paired.

**What addresses what.** The paired bubble's primary event is the **text**:
reactions, replies, edits, copy and "jump to" all use the row's own
`item.event_id`. The player alone uses `VoiceReplyPlayer.event_id` — the
voice message — to fetch and play the audio. A reaction someone else's client
puts on the voice message itself is not shown while the pair is folded.
`TimelineRow.voice_reply` carries the key on the voice row so the pairing
survives the row being hidden; no host reads it.

**Cost.** Three linear passes per batch, the text rows found through a hash
map keyed by event id — never a search per row; a 20,000-row room is pinned
by `a_long_room_pairs_in_linear_time`.

**Notifications.** The text already notified. The voice reply is quiet at all
three layers of §6: the hub marks it a quiet hub event (layer 1); the
`agentpod_voice_reply` account rule matches its `schema_version` (layer 2);
and `core::notification` returns no notification for a row carrying the key,
paired or standalone, and "<agent> replied with a voice message" as the quiet
fallback line when a push cannot be dropped (layer 3).

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

### 5a. gate.v2 — options and a subject from the gate

charter `decisions/2026-11-07-gate-v2-is-the-decision-envelope.md`. The hub
also puts gate.v2 fields on the same prose message, beside the unchanged
`dev.superpipeline.gate` card, marked `suite_event_type:
"dev.superpipeline.gate.v2"`. The wire is pinned by
`fixtures/ecosystem-identity/matrix_gate_events.json`, vendored from AgentPod
unchanged and read by `core::gate_v2`'s tests.

- **Which card.** A message whose v2 fields validate is drawn from v2
  (`core::gate_v2`, registered as `dev.superpipeline.gate.v2`); anything the
  corpus refuses — an option with no `meaning`, a subject with no `digest`, no
  `body`, duplicate ids, `needsComment` — reads the v1 card exactly as before.
  Both are the same decision (`gate:<gate_id>`), so the legacy v1 event that
  follows stays hidden and an outcome closes either.
- **Options by meaning.** `CustomEventDecision.gate_v2` carries every option
  with its `meaning` (`approve | reject | revise | choose | defer`) and
  `needs_comment`; no list of ids is applied. Hosts draw approve as the main
  action(s), revise with a comment prompt, reject as destructive, the rest
  neutral.
- **The preview.** A subject-bound gate carries its preview blocks
  (`text | field | link`), drawn verbatim. The core withholds every approving
  option, and says why (`approvals_withheld`), when `preview_complete` is not
  `true`, a block is of a kind it cannot draw or past the hub's bounds, or a
  link is not a safe `https://` URL. Revise, reject and the card's link stay.
- **Hosts without v2.** `CustomEventDecision.options` for a v2 gate holds only
  superpipeline's three ids and never an approval of a subject, so the desktop
  and the widgets answer with `decision.v1` as before and cannot approve a
  post they do not show. The desktop card says when the gate offers more than
  it can send ("Open the card, or answer on your phone, for the rest of this
  one", or the core's withheld-approvals line).
- **Notifications.** A gate.v2 notification answers with `decision.v2`
  (`NotificationAnswer::GateV2`, sent with `send_gate_decision_v2_to`): each
  action sends the gate's one option of its meaning (`GateAnswers.v2`, carried
  in the user info under `sm.gateV2.*`). A notification shows no preview, so a
  subject-bound gate never offers Approve: it gets the `GATE_REVIEW` category
  (Request changes, typed / Reject / Open), as does any gate with no approval
  to offer. The core refuses to rebuild an approving answer for a
  subject-bound gate whatever the user info says.
- **The answer.** iOS and Android answer a v2 card with
  `Core::send_gate_decision_v2`, handing back the card's `gate_v2`; the core
  refuses an option not offered or withheld and an empty comment where one is
  needed, and sends `dev.superpipeline.gate.decision.v2`: `gate_id`,
  `option_id`, `comment` (`null` when none), `subject: {id, digest}` for a
  subject-bound gate, and the `m.reference` to the gate's event.

### 5b. question.v2 and delivery.v2 — questions and follow-ups in the envelope

charter `decisions/2026-11-07-the-envelope-covers-questions-and-follow-ups.md`.
Both ride on a prose `m.room.message` with a real `body` and a
`suite_event_type` marker, and both are pinned by the same fixture (version 4).

- **An option may ask for input.** Beside `needs_comment`, a question's or a
  follow-up's option may carry `input: {kind: "text" | "url", label,
  required, max_length}` (snake_case on the Matrix wire — `maxLength` is
  refused). `core::gate_v2::OptionInput` carries it to the hosts, which draw a
  field and refuse a required one empty; the core refuses it again
  (`checked_input`): empty when required, over `max_length` characters, or —
  for `url` — not an `https://` URL `safe_url` accepts. A gate.v2 option's
  `input` is not read: a `decision.v2` has no field for its text.
- **question.v2** (`core::question_v2`, registered as
  `dev.superpipeline.question.v2`, label "Question"). Carries
  `elicitation_id`, `question`, the card fields and `options` with `meaning`
  (`choose`, or `approve`/`reject` on a strict decision). A question with no
  options carries `free_text: {max_length}` (1–2000) and is answered by typing.
  The corpus's refusals — an option with no meaning, `maxLength`, duplicate
  ids, `free_text` beside options, no body — and a question with neither
  options nor `free_text` stay prose. The v1 card under
  `dev.superpipeline.elicitation` is never drawn by this client, so a question
  without valid v2 fields reads exactly as before: prose, answerable by
  replying a number or a label. `CustomEventDecision.question_v2` holds the
  question; `options` holds only the options a plain reply answers (each id is
  its label, which the hub matches as a typed reply), so the desktop answers
  those and says when the rest needs a phone or the board.
- **question.answer.v2.** `Core::send_question_answer_v2(room, question,
  option_id?, text?, in_reply_to)`: `elicitation_id`, `option_id` (plus
  `text` when that option has an input) or `text` alone for a free-text
  question, the `m.reference` to the question's message, and a real `body`
  (the label; "Label: text"; or the text).
- **delivery.v2** (`core::delivery_v2`, label "Delivery"). The follow-up a
  subject-bound gate resolved `approve_manual` gets: `gate_id`, the subject
  and its preview (drawn verbatim, as gate.v2's), and the options
  `record_live_url` (meaning `defer`, a required `url` input) and
  `switch_to_automatic` (meaning `approve`). Any other option, or
  `record_live_url` without a required `url` input, leaves it prose.
  `switch_to_automatic` is withheld by gate.v2's rule — offered only when the
  preview is complete and every block drawable — with
  `approvals_withheld` saying why. `CustomEventDecision.delivery_v2` is a
  `GateV2Decision`; `options` is empty. Its identity is `delivery:<gate_id>`,
  not the gate's.
- **delivery.answer.v2.** `Core::send_delivery_answer_v2(room, delivery,
  option_id, text?, in_reply_to)`: `gate_id`, `option_id`, `text` (the live
  `https://` URL) for `record_live_url`, and the `m.reference` to the
  follow-up. Body: "Live at <url>", or the option's label.
- **Notifications.** A question or a follow-up notifies in the DECISION
  category ("Open" only): their text cannot be checked from a lock screen.
- **A question bound to a subject** (fixture version 5, `variant:
  "subject-bound"`; charter
  `decisions/2026-11-07-strict-decisions-and-permissions-bind-their-subject.md`).
  A strict decision (`schema: "salaam-operation/v1"`) or a board-dispatched
  harness permission (`"harness-permission/v1"`) is a question.v2 carrying
  gate.v2's `subject {id, digest, schema, revision, expires_at, preview,
  preview_complete}`. It is held to its whole snake_case shape
  (`core::gate_v2::parse_bound_subject`): a known `schema`, a whole-number
  `revision` and an explicit boolean `preview_complete` — `previewComplete`,
  a subject with no digest or another schema leaves the question prose. The
  preview is drawn verbatim (`QuestionV2Decision.subject`, a `SubjectV2`), and
  gate.v2's rule applies: no approve-meaning option unless the preview is
  complete and every block drawable, with `approvals_withheld` saying why. The
  answer copies `subject {id, digest}` back exactly; the core refuses an
  approval on a withheld preview even if a host puts the option back.
  `CustomEventDecision.options` never holds an approval of a subject (a typed
  reply only declines one), and the desktop says to open the card or answer
  on a phone for the rest.
- **Scope and requires.** Any envelope option may carry `scope: "once" |
  "run" | "always"` and `requires: "workspace-admin"`
  (`GateOptionV2.scope`/`.requires`). A value this build does not know refuses
  the option, and the decision with it. Hosts show them as a caption under the
  option — "Once", "For this run", "Always", "Admins only", joined by " · " —
  and never judge them: the producer does.
- **Notifications.** A subject-bound question notifies in the DECISION
  category too: a notification shows no preview, so it never approves.
- **A SuperToolkit approval request** (fixture version 6, `variant:
  "approval-request"`; charter
  `decisions/2026-11-08-waiting-cards-and-broker-approvals-reach-chat.md`
  §3–4). A SuperToolkit write held for approval is hosted on a board but on
  **no card**: a question.v2 with `card_id`, `run_id` and `stage_key` empty,
  `approval_request_id` equal to `elicitation_id`, and a `stk-capability/v1`
  subject (`core::gate_v2::STK_CAPABILITY_SCHEMA`) whose preview is the
  service, capability, target, every argument in full, the station and the
  requesting agent. Options are `allow_once` (`approve`, `scope: once`,
  `requires: workspace-admin`) and `deny` (`reject`); there is no "always".
  The subject rule above applies unchanged, and the answer carries
  `subject {id, digest}`. What differs: `QuestionV2Decision.approval_request_id`
  is set, the card draws one row, "Request: SuperToolkit approval", in place
  of a card's title and stage, offers no link, and its withheld note reads
  "This card can't show everything this request would do, so it can't be
  allowed from here." Half of one stays prose: no `approval_request_id` (or
  one that is not the elicitation's), a subject of another schema, a link
  (`deep_link`) to a card that does not exist, an approving option not limited
  to workspace admins, an `stk-capability/v1` subject or a request id on a
  question asked on a card. Only owners and admins may allow it —
  superpipeline judges that, and SuperToolkit re-checks the digest and expiry
  before it acts; this client never does. The desktop says "To allow this, a
  workspace owner or admin answers on their phone." Its answer is a
  question.answer.v2 like every other, carrying `schema_version: 1` where the
  corpus's approval answers omit it (the hub reads none).

### 5c. permission.v2 — a harness permission in AgentPod's envelope

charter `decisions/2026-11-07-strict-decisions-and-permissions-bind-their-subject.md`
§5–6, fixture version 5. The permission's prose message keeps the v1 card under
`dev.agentpod.permission`, unchanged, and is also marked
`suite_event_type: "dev.agentpod.permission.v2"` (AgentPod's namespace: AgentPod
judges a harness permission).

- **The request** (`core::permission_v2`, registered as
  `dev.agentpod.permission.v2`, label "Permission"): `request_id`
  (`<session_id>:<request_seq>`), `session_id`, `request_seq`, `title`,
  `tool_kind?`, `options [{id, label, meaning, scope?}]` (ACP's kinds:
  allow_once → approve/once, allow_always → approve/always, reject_once →
  reject/once, reject_always → reject/always) and a `harness-permission/v1`
  subject whose `id` is the `request_id`. The preview is the tool and kind,
  the command or raw input, each diff and the locations, drawn verbatim. A
  message whose v2 fields do not validate — no `request_id`, an option with no
  meaning, a subject of another schema or for another request, an unknown
  scope — reads its v1 card exactly as before. The identity is
  `permission:<session>:<seq>`, the v1 card's, so a legacy separate event is
  still hidden beside it.
- **Withholding.** Every allowing option is withheld unless the preview is
  complete and drawable; `approvals_withheld` then reads "This card can't show
  everything the agent would do. Answer it in the console to allow it."
  `CustomEventDecision.permission_v2` is a `PermissionV2Decision`; `options`
  holds only the non-allowing options, each id its name (a plain reply, as for
  v1), so the desktop can reject but never allow, and says "To allow this,
  answer on your phone or in the console."
- **permission.answer.v2.** `Core::send_permission_answer_v2(room, permission,
  option_id, in_reply_to)` (the room need not be open): `request_id`,
  `option_id`, `subject {id, digest}` copied from the card, the `m.reference`
  to the prose message, and the option's label as `body`. The core refuses an
  option the request did not offer and an allowing option on a withheld
  preview, even one a host put back. The hub judges the rest (session,
  sequence, digest, pending, unexpired, the answerer's grant) and records who
  answered.
- **Notifications.** A permission.v2 notifies in the DECISION category
  ("Open" only): no Allow from a lock screen, because it cannot show the
  preview, and no Reject either, because the v1 action sends an unbound typed
  name and a bound reject is not wired through the notification answer yet. A
  v1-only permission keeps Allow once / Reject as before.

### 5d. attention.v2 — a card waiting on a person

charter `decisions/2026-11-08-waiting-cards-and-broker-approvals-reach-chat.md`
§1–2, fixture version 6. When a card parks for a person for a reason other than
a question or a review, the hub posts one prose `m.room.message` into the
board's room, marked `suite_event_type: "dev.superpipeline.attention.v2"`. There
is no v1 card: a message whose fields do not validate stays prose.

- **The card** (`core::attention_v2`, registered as
  `dev.superpipeline.attention.v2`, label "Waiting card"): `schema_version`,
  `board_id`, `card_id`, `card_title`, `stage_key`, `stage_name`, `wait_id`,
  `reason` (`repeated-failure | blocked | not-authorised`), `detail`,
  `summary` and `failure_count` (each `null` when the park gave none, never
  absent), `options`, and `deep_link?`. It draws "Card", "Stage", "Why" (the
  reason in words: "Failed twice", "Failed 3 times", "Failed repeatedly",
  "Blocked", "Not authorised" — `attention_v2::reason_words`) and "Summary"
  rows, the agent's detail verbatim below them, and the card's link. The
  prompt is "“<title>” is waiting on you". The identity is
  `attention:<board>:<card>:<wait>`: the same card parking again is a new wait
  and a new decision.
- **Options, by meaning.** Only `resume_here` (`approve`), `resume_at:<stageKey>`
  (`choose`, one per earlier stage) and `archive` (`reject`). Each resume has a
  **required** `text` input of at most 2,730 characters (the board's comment
  limit); archive has none. A forward move or any other option, a resume
  whose instruction is optional, an input spelled `maxLength` or longer than
  the board takes, a reason this build does not know, a missing `wait_id`, and
  a `subject` (nothing is frozen on a waiting card) all leave the message
  prose. `CustomEventDecision.attention_v2` is an `AttentionV2Decision`;
  `options` is empty: no plain reply answers a waiting card.
- **attention.answer.v2.** `Core::send_attention_answer_v2(room, attention,
  option_id, text, in_reply_to)` (the room need not be open): `{msgtype,
  body, suite_event_type, board_id, card_id, wait_id, option_id, text?,
  m.relates_to: m.reference → the card's message}`, exactly the corpus's
  shape, with no `schema_version`. Board, card and wait are copied from the
  card, so an answer can name no other wait; the board refuses a stale one
  (`WAIT_CHANGED`). The core refuses an option the card did not offer and a
  resume with no instruction or one over its limit; text beside `archive`
  is dropped. The body is the option's label.
- **Apps.** iOS/macOS and Android draw it with the decision card: Resume here
  leads, Resume at <stage> is neutral, Archive is the quiet rejection, and
  each resume asks for its instruction in the input prompt step 2 added. The
  local receipt reads "Resumed by you", "Resumed at Plan by you", "Archived by
  you". The desktop answers none of it and says "Resume or archive on your
  phone or the board."
- **Notifications.** DECISION category, "Open" only: a resume needs an
  instruction a notification cannot check, and archiving a card unread is not
  a lock-screen choice.

## 6. Quiet events — what must never interrupt, at three layers

Some events are not news: a reaction (an agent's 👀/✅/❌ on the reader's
message), an edit, a turn card (`dev.agentpod.turn.v1`), and the legacy
separate permission/gate events whose prose message already notifies. On
TestFlight build 30 each still arrived as a push, and the extension, told to
suppress it, emptied it — which iOS shows as a **blank notification**. Three
layers now keep them quiet, each catching what the one before cannot:

| Layer | Where | Covers | Cannot cover |
|---|---|---|---|
| 1. Hub gateway | AgentPod hub, `/_matrix/push/v1/notify` | Drops pushes for events the hub itself sends (turn cards, the hub's own reactions/edits) | Events the hub did not send: a harness-mode agent posting its own, another client's edit, a human's reaction |
| 2. Account push rules | `core::push::quiet_push_rules`, installed by `Session::register_pusher` (every launch that registers a pusher, after login or restore) | Unencrypted events of type `m.reaction`, `dev.agentpod.turn.v1`, `dev.agentpod.permission.v1`, `dev.superpipeline.gate.v1`, any event whose `content.dev\.agentpod\.voice_reply.schema_version` is `1` (an agent's spoken answer, §4a), and any event whose `content.m\.relates_to.rel_type` is `m.replace` (an edit) — whoever sent them | **Encrypted events**: the homeserver sees only `m.room.encrypted`, never the type or the relation. Org rooms are unencrypted by design; encrypted DMs are not |
| 3. The extension | `core::notification` + `NotificationService.swift` | Anything still pushed — encrypted events, races, an account whose rules failed to install | — |

**Layer 2** writes six account override rules, ids under
`dev.supermessage.quiet.` (`reaction`, `agentpod_turn`, `agentpod_permission`,
`superpipeline_gate`, `agentpod_voice_reply`, `edit`), each with empty
`actions` — the spec's don't-notify since v1.7. The voice reply rule is
`event_property_is` on `content.dev\.agentpod\.voice_reply.schema_version`
= `1`, so a voice note that only mentions the key, or a later schema, still
notifies. The edit rule is the spec's own
`.m.rule.suppress_edits` condition (`event_property_is`, key escaped as
`content.m\.relates_to.rel_type`); the reaction rule is `.m.rule.reaction`'s.
tuwunel 1.9.1's ruma has both server-default rules and evaluates
`event_property_is` with escaped keys, so these add nothing on a fresh
account — they are for a stored ruleset that predates those defaults, and for
the suite's own types, which no server default knows. Installing is
idempotent: the core reads the rules from the homeserver and writes only the
missing or changed ones; it never touches a rule outside its prefix, and
leaves one of its own alone if somebody disabled it. Failure is logged, not
fatal — layer 3 still holds.

**Layer 3.** The core still decides `suppress` (Reaction, Edit, Redaction,
Filtered, Own, NotNews), and now also a short honest line for it,
`NotificationDto::fallback_title`/`fallback_body`: "Krishna reacted ✅ to a
message", "Krishna finished · 4 steps" (from the card's `counts`), "Krishna
edited a message", "Sent from your other device". Without Apple's
`com.apple.developer.usernotifications.filtering` entitlement the extension
cannot drop a push, so it shows that line **quietly**: interruption level
passive, no sound, relevance score 0, no actions. With the entitlement
(`SM_NSE_FILTERING=YES`, `apple/SupermessageNotificationService/nse-filtering.yml`,
not yet requested from Apple) it drops the push instead. Either way, never a
blank notification (`RemotePresentation` in SupermessageKit).

## 7. A gate's outcome — the room saying the board accepted an answer

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

**A follow-up's receipt.** A delivery.v2 follow-up (§5b) is settled by the
same receipt, referencing the follow-up's message, with `decision`
`record_live_url`, `switch_to_automatic` or `settled_elsewhere` (the board
stopped owing it: a link recorded on the web, the approval expired, the card
archived; `decided_by` is then `null`). Those three close only a follow-up's
card, and every other decision only a gate's: the follow-up names the gate it
follows, and the gate's own `approve_manual` receipt — the one that prompts
it — must not close it on arrival.

**Wording.** `summary` uses the hub's own words: Approved, Approved for
automatic delivery, Approved for manual delivery, Changes requested, Rejected,
Live link recorded, Switched to automatic delivery, Settled on the board — "by
<decided_by>" when named — and "Decided" for a decision this build does not
know.

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
event is suppressed as `NotNews` with the quiet line "… posted an update"
(§6). Neither is ever a PERMISSION or GATE notification.

### 7a. A permission's outcome

The hub closes a harness permission with `dev.agentpod.permission.outcome.v1`,
a custom event (with `suite_event_type` repeated) sent beside a prose line:
`{request_id, session_id, request_seq, outcome: "selected" | "expired" |
"cancelled", option_id | null, decided_by | null, m.relates_to: m.reference →
the permission's prose message}`. `core::permission_v2::resolved_permissions`
reads it in the same reconcile pass as gate outcomes and turns the card — the
v2 card, or a v1 card from an older hub — into a receipt, whichever arrived
first. It closes a card only when it comes from the identity that posted the
permission, references exactly that card's event, and names the same session
and sequence; an outcome with no `m.reference`, an unknown outcome word, a
quoted reply, someone else's outcome, or a gate's outcome closes nothing. The
event itself is never drawn (`ItemView::None`) and notifies as `NotNews`.

**Wording.** By the option's meaning and scope when the card knew them:
Allowed once / Allowed for this run / Allowed always / Allowed, Rejected /
Rejected for this run / Rejected always, Chose "<label>" — "by <decided_by>"
when named. A v1 card's option is said in the hub's words, "<name> — answered
by <decided_by>"; an expiry reads "Expired unanswered" and a withdrawal
"Withdrawn" (the hub's "expired unanswered" / "was withdrawn").

### 7b. A waiting card's outcome

The hub closes a waiting card with `dev.superpipeline.attention.outcome.v1` — its
own type, because `gate.outcome.v1` is keyed by gate and a wait has none — a
custom event (with `suite_event_type` repeated) sent beside a prose line:
`{board_id, card_id, wait_id, decision, decided_by | null, m.relates_to:
m.reference → the card's message}`. `decision` is the option taken
(`resume_here`, `resume_at:<key>`, `archive`) or `settled_elsewhere` when the
wait ended anywhere but this room (the sweep stopped listing it, or the board
answered `WAIT_CHANGED`). `core::attention_v2::resolved_attention` reads it in
the same reconcile pass and turns the card into a receipt, whichever arrived
first. It closes a card only when it comes from the identity that posted the
card, references exactly that card's message, and names the same board, card
and wait; an outcome with no wait, no `m.reference`, no decision, a quoted
reply, someone else's outcome or a gate's outcome closes nothing. The event is
never drawn and notifies as `NotNews`.

**Wording.** "Resumed by <who>", "Resumed at <stage> by <who>" (the stage as the
card's option named it, "Resume at Plan" → "Plan", or its key), "Archived by
<who>", and "Settled on the board" for `settled_elsewhere`, which names nobody.
The hub's own line says "Resumed (Resume at Plan) by <who> — the board has it."

## 8. Needs you — which rooms owe an answer

charter `decisions/2026-11-08-waiting-cards-and-broker-approvals-reach-chat.md`
("decisions light the Needs you inbox"). Every Needs you surface — the inbox
and its badge, the pill, the roster's amber line and status dot, the desktop
title count and tray dot, the widgets — reads `RoomPreview.pending`
(`core::room_preview`), which is set when the room's latest event type is in
`decision_bearing_event_types()`: `dev.superpipeline.gate.v1`,
`dev.agentpod.permission.v1`, `dev.superpipeline.gate.v2`,
`dev.superpipeline.question.v2`, `dev.superpipeline.delivery.v2`,
`dev.agentpod.permission.v2` and `dev.superpipeline.attention.v2`. That set was
empty until fixture version 6, so no real decision ever lit Needs you (defect D1
of the 2026-10-23 parity verification).

A decision is carried on a prose `m.room.message`, which the room list's latest
event classifies as an ordinary message. `core::rooms::room_preview` therefore
reads that same latest event's raw content and, for someone else's message,
reports the decision it carries with `room_preview::pending_decision_type` —
the timeline's own reading (`embedded::embedded_suite_event`), so a card the
room draws as prose is not pending either. **Pending means the room's newest
message is an open decision card.** Every way a decision ends puts a newer
message after it — the reader's answer, from this device or another, and the
hub's receipt line beside every outcome — so an answered or settled decision
stops counting without any per-device state. The rule errs one way only: a
decision followed by unrelated chatter stops counting though its card still
waits; amber never claims a decision that is not owed. The line still reads
"Approval needed" for every kind.

## Adding a field

1. Add it to the wire struct in `core::live` or to the renderer in
   `core::custom_events`, optional and defaulted, so an old sender still
   deserialises.
2. Bound it in the core if a sender controls it. The boundary is where the
   guarantee is made; a host must not be the first place a length is checked.
3. Render it on both hosts, or on neither. Two clients disagreeing about what
   an event means is the failure this whole layer exists to prevent.
