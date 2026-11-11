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
`fixtures/ecosystem-identity/matrix_gate_events.json`, vendored from treaty
unchanged and read by `core::gate_v2`'s tests. `scripts/check-treaty-corpus.sh` fails CI when
the copy differs from the pinned treaty package.

Every board-room type is also held to treaty's per-type corpus,
`fixtures/treaty-matrix-events/examples.json`: `@superjackfruitlabs/treaty-matrix-events`'s
`examples.json` at the version the same script pins, beside the JSON Schemas it is checked
against. `core::treaty_corpus` runs each case through this client's own code: a card, notice or
receipt it reads must read every valid case and no case the schema refuses; an answer it sends
must be one some valid card builds, and no invalid answer may ever be built. A type the corpus
gains that this client neither reads, sends nor lists as not read fails the test.

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

#### A choice of versions

charter `decisions/2026-11-09-a-gate-may-offer-a-choice-of-variants.md`,
fixture version 8 (entries tagged `variant: "choice"`). An agent may draft up
to three versions; the person approves one of them, or sends the set back.

- **The wire.** `subject: { variants: [{ id, label, subject }] }` with **no**
  top-level `id` or `digest`; each version's `subject` is the usual envelope
  (`id`, `digest`, `schema`, `revision`, `expires_at`, `preview`,
  `preview_complete`). The four options are the publishing gate's. A client
  that predates variants finds no subject binding, refuses the gate.v2 and
  draws the v1 card, which never approves.
- **Which card.** `core::gate_v2` reads the versions when `subject` holds
  `variants`. More than 3, none, an `id` or `digest` beside them, a version
  without an id or label, a version whose subject has no id or digest, or two
  versions sharing an id or a subject id: the whole gate.v2 is refused and the
  v1 card is drawn.
- **Each version judged alone.** `GateV2Decision.variants` holds a
  `GateVariantV2 { id, label, subject: SubjectV2, approvable, withheld }` per
  version, in the gate's order; the gate's own `subject` and `preview` are
  empty. A version is `approvable` when its whole preview is drawable and the
  board said `preview_complete: true` (the same rule as a single subject); one
  that is not still shows its preview as far as it goes, with `withheld`
  "Too long to show here — choose it on the board." The gate keeps its
  approving options while any version is approvable; when none is, they are
  withheld as usual, with "None of these versions can be shown in full here.
  Open the card to choose one." Request changes and reject are always offered.
- **The card.** iOS and Android draw a section per version (a radio mark, its
  label, its preview) and nothing picked to begin with; the approve buttons
  stay disabled until a version marked `approvable` is picked. No editing: a
  change is Request changes. The desktop draws no versions; its card says
  "Choose a version on your phone or the board." and offers request changes
  and reject (the only legacy options a variant gate carries).
- **The answer.** `send_gate_decision_v2` takes `chosen_variant`. An approval
  must name a version the gate offers that is `approvable`, and carries that
  version's `subject {id, digest}`; with none, an unknown id or a withheld
  version it is refused. Request changes and reject carry no binding (a
  picked version beside them is not sent), and leave out `comment` when there
  is none, exactly as the fixture's accepts. A chosen version on a gate that
  offers none is refused.
- **Notifications.** A notification cannot show the versions (§7 of the
  charter decision), so a variant gate never offers Approve: `GATE_REVIEW`,
  with Request changes and Reject answered without a binding. The notification
  path sends no chosen version, so the core refuses an approval whatever the
  user info says.
- **The receipt.** See §7: `chosen_variant` names the version approved.

#### More than one approver — a quorum

charter `decisions/2026-11-09-a-gate-may-need-more-than-one-approver.md`,
fixture version 9 (entries tagged `variant: "quorum"`). A stage may need two
to five approvals before its gate passes. superpipeline counts them; this
client shows the count (`core::gate_quorum`).

- **The wire.** A gate.v2 may carry `quorum: { required, from, approvals:
  [{ principal_id, at, variant_id? }] }`: `required` 2–5, `from` `deciders`
  or `admins`, each person once, at most 32. A quorum that is not that
  (required out of range, an unknown `from`, an approval with no time, one
  person twice) refuses the whole gate.v2 and the v1 card is drawn: a card
  that dropped the quorum would say one approval closes it.
- **The count.** `GateV2Decision.quorum` is a `GateQuorum { required, from,
  approvals, progress, approved_by_you }`. `progress` is the line a card
  draws under its preview, worded in the core: "1 of 2 approvals. Waiting
  for 1 more.", "Needs 2 approvals." with none yet, and on a variant gate,
  where approvals count per version, each version that has any:
  "“Short”: 1 of 2 approvals. Waiting for 1 more."
- **Progress events.** For each approval short of the quorum the hub replies
  to the gate ("Approved by rakesh — 1 of 2. Waiting for 1 more.") and sends
  `dev.agentpod.gate.progress.v2` beside it: `gate_id`, `board_id`,
  `card_id`, `approved_by`, `approval`, `quorum`, and an `m.reference` to
  the gate's event. The structured event is never a row
  (`ItemView::None`) and never a notification (`NotNews`); the reply is the
  readable line. `embedded::reconcile` folds every progress event into the
  gate's card: only one from the gate's own sender, with the same
  `required`, and only when it has more approvals than the card already
  shows. A redelivered or reordered event carries fewer and never walks the
  count back.
- **You approved.** The approvals name Accounts principals, which this
  client does not know its reader by. Instead, the reader's own decision.v2
  (`is_own`, an approving option of this gate) rides on its row
  (`timeline::own_gate_decision_from_raw`, which also stores nobody else's).
  On a quorum gate still open, that reader is offered no approving option
  (and the legacy options the desktop answers with lose theirs too), with
  `approvals_withheld` "You approved. It passes once enough others approve
  too." Request changes and reject stay: one of either still closes the
  gate. A gate without a quorum is never changed by the reader's answer, as
  §7 says (answered is not resolved). The one wrong case is an approval the
  board refused for another reason. The hub says so in the room, and the
  card points to the board.
- **The card.** iOS, Android and the desktop draw `progress` under the
  preview (`decision-quorum-progress`). The approval that reaches the quorum
  resolves the gate, and its outcome (§7) closes the card as usual.

#### An approval that waits for its release time

charter `decisions/2026-11-09-an-approval-may-wait-for-its-release-time.md`,
fixture version 10 (entries tagged `variant: "release"`). An approved gate
acts at its release time: the later of its subject's own not-before time and
the approval's time plus the stage's `holdFor`. Until then the approval is
held and may be pulled back (`core::gate_release`).

- **Before approval.** A gate.v2 may carry `release: { hold_for_minutes?,
  not_before? }`: the hold 5–10080 whole minutes, the time ISO, and at least
  one of them. Anything else refuses the whole gate.v2 and the v1 card is
  drawn, as for a malformed quorum. `GateV2Decision.release` carries it, and
  `gate_release_line(release, now_ms)` words it at the device's clock, as a
  deadline is: "If approved, it waits 30 min before it goes ahead.", "If
  approved, it goes ahead in 3 d at the earliest." (nothing once that time
  has passed and no hold is left), or both joined.
- **Held.** The outcome receipt may carry `held_until`. For an approval
  (`decision` starting `approve`) the receipt's `CustomEventOutcome.held` is
  then `GateHold { gate_id, held_until }`. The card stays a receipt with no
  buttons, says `gate_held_line(held_until, now_ms)` ("Goes ahead in 25 min
  unless pulled back.", then "Going ahead now."), and iOS and Android offer
  **Pull back**.
- **Pull back.** `send_gate_pull_back(room_id, gate_id, comment,
  in_reply_to)` sends `dev.superpipeline.gate.pull_back.v2`, an `m.text`
  with `m.reference` to the gate's event and a required comment of at most
  8192 bytes. superpipeline judges who may (a decider of the gate, or an
  owner or admin). The hub replies either way, so the card only says
  "Pull-back sent". The desktop sends no pull-back. It says "To pull it
  back, use your phone or the board."
- **Settled.** The hub's `dev.agentpod.decision.released.v2` (outcome
  `released` or `blocked`, with a reason) and
  `dev.agentpod.decision.pulled_back.v2` settle the held receipt, but only
  from the identity that posted the gate, with the same gate and board. The
  receipt then says "Approved by rakesh — it went ahead", "… — it did not go
  ahead: <reason>", or "Pulled back by rakesh — returned for changes:
  <comment>" (decision `request_changes`), and Pull back is gone. Neither
  notice is a row (`ItemView::None`) or a notification (`NotNews`): the
  readable reply beside each says it.

#### A gate decided by machine checks

charter `decisions/2026-11-09-a-gate-may-be-decided-by-checks.md` §7,
fixture version 11 (entries tagged `variant: "checks"`). A stage may name
machine checks (`ci`, `verdict`) that run before any person is asked.
superpipeline runs them and decides; this client shows them and lets a
workspace owner or admin override one (`core::gate_checks`).

- **On the gate.** A gate.v2 may carry `checks: [{ name, provider, status,
  url? }]`: 1 to 5, names unique, 1–40 characters without surrounding
  spaces or "/", provider `ci | verdict`, status `pending | passed | failed
  | overridden`. Anything else (an empty list, six, a slash, an unknown
  provider or status, two of one name) refuses the whole gate.v2 and the v1
  card is drawn, as for a malformed quorum. A `url` is kept only when it is
  `https://` (`custom_events::safe_url`); any other is dropped and the check
  kept. `GateV2Decision.checks` carries them, each `GateCheck` with its plain
  `line` ("build (CI) — passed", "claims (verdict) — overridden"), and
  `overridable` always false: by the time a gate opens every check is over,
  and an override must reference a card of overdue checks. iOS, Android and
  the desktop draw the lines under the preview (`decision-checks`), the
  phones with "Open the result" beside a check that links one.
- **A card of overdue checks.** When a check set's stage escalates, the hub
  posts `dev.agentpod.checks.waiting.v2`: an `m.text` with `board_id`,
  `card_id`, `card_title`, `stage_key` and `checks` (the same shape), and
  `deep_link?`. It draws as a card (`CustomEventDecision.checks_waiting`,
  `ChecksWaitingDecision`), prompt "Checks overdue on “<title>”", with
  **Override** on each check pending or failed (`overridable`) and nothing
  else to answer; a message missing a field, or with a list as above, stays
  prose (it has no v1 form). Its identity is `checks:<board>:<card>:<stage>`,
  so a copy the hub posts again below for an admin who just joined retires
  the one above (§5e). It notifies as a DECISION that only opens.
- **Who may override.** A workspace owner or admin, by the Accounts role at
  the time, never the producer of the work and never an agent — the board's
  to judge. The client holds no workspace role, so it shows Override with
  "Admins only" (as an option that `requires: workspace-admin`) and the
  card's `override_note`. A refusal comes back as the hub's reply in the
  room: "Only a workspace owner or admin can override a check. Nothing has
  changed." (or `SEPARATION_OF_DUTIES`, `CHECK_NOT_OPEN`, `CHECK_NOT_FOUND`,
  `INVALID_COMMENT` in their own words).
- **Override.** `send_check_override(room_id, checks, check_name, comment,
  in_reply_to)` sends `dev.superpipeline.check.override.v2`: an `m.text`
  with body "Override <name>: <comment>", `schema_version`, `board_id` and
  `card_id` copied from the card, `check_name`, a required `comment` of at
  most 8192 bytes, and `m.reference` to the card's event. The core refuses a
  check the card does not offer Override on. Success posts nothing from the
  answer itself: the board's `check.overridden` is what is said. The desktop
  sends no override; it says a workspace owner or admin overrides on their
  phone or the board.
- **The notices.** `dev.agentpod.check.failed.v2` (`check { name, provider,
  status: failed, detail?, url? }`, `returned_to` a stage or null) and
  `dev.agentpod.check.overridden.v2` (`overridden_by`, `comment`, `at`) are
  never rows (`ItemView::None`) or notifications (`NotNews`): the readable
  reply beside each says it. When one comes from the identity that posted a
  card of overdue checks, references that card's event (`m.reference`) and
  names its board, card and stage, it settles the card: an override marks
  the check "… — overridden by rakesh" and withdraws its Override; a failure
  that could not go back marks it failed and keeps Override; a failure that
  sent the card back closes it as a receipt, "Returned for changes — CI
  check “build” failed" (decision `returned`); and a card with no check left
  to override is a receipt listing them (decision `settled`).
- **Reminders and escalations** of overdue checks are §5e's notices with
  `decision_kind` `checks` and `decision_id` the check set's arrival. A
  reminder stands alone (nothing is posted for checks before an escalation),
  and an escalation replies to the card of overdue checks or stands alone.

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

### 5e. When nobody answers — deadlines, reminders, escalations, re-posts

charter `decisions/2026-11-08-unanswered-decisions-remind-then-escalate.md`,
fixture version 7. superpipeline owns the clock; this client only shows it
(`core::decision_timeout`).

- **The deadline** (§5). gate.v2, question.v2, delivery.v2 and attention.v2
  may carry `deadline: { remind_at?, escalate_at? }`, ISO 8601 times, the key
  absent when the stage has no timeout. `parse_deadline` keeps a half only
  when it is a real instant in the hub's `ISO_TIME` shape
  (`YYYY-MM-DDTHH:MM[:SS[.fff]]` then `Z` or `±HH:MM`); a bad half is dropped
  and the other kept, and an empty, camelCase (`remindAt`) or unusable
  deadline is no deadline — the card itself still draws. It rides on
  `CustomEventDecision.deadline` (`DecisionDeadline { remindAt, escalateAt }`,
  absent from the JSON when `None`). It is separate from `subject.expires_at`
  and nothing resolves on it.
- **Its words.** `decision_timeout::deadline_line(deadline, now_ms)` (FFI
  `decision_deadline_line`) says "Escalates in 3 h" until the escalation,
  "Escalated" after it, and for a deadline that only reminds "Reminder in
  45 min" until then and nothing after. Units are the hub's (`waitedFor`):
  minutes under an hour (never "0 min"), hours under 48, then days, rounded
  down. The host passes its own clock, because a row is projected once and a
  relative time fixed then would go stale. iOS/macOS and Android append it to
  the pending card's "Asked by …" line ("Asked by Hermes · Escalates in 3 h");
  the desktop carries the field but does not draw it yet.
- **The reminder and the escalation notices** (§2–3). An `m.text` reply
  (`m.relates_to.m.in_reply_to` → the card's message) declaring
  `suite_event_type` `dev.superpipeline.decision.reminded.v1` or
  `dev.superpipeline.decision.escalated.v1`, with `schema_version`,
  `board_id`, `card_id`, `decision_kind` (`gate | question | delivery |
  attention | checks`), `decision_id`, `waiting_since`, and `reminded_at` or
  `escalated_at` (plus `deep_link?` on an escalation). A notice missing any of
  those, with an unknown kind, or related by `m.reference` instead of a reply
  stays the ordinary message it is — except that a notice of kind `checks`
  may stand alone (§5a, machine checks). `timeline::row_from_parts` sets the
  item's `detail` to the notice type, and it draws as a quiet system line —
  `ItemView::System { kind: Notice }`, the hub's sentence without its trailing
  `[Open the card](…)` markdown link (the card above has the link). It is not
  a card of its own, but it keeps Needs you lit (§8). It is not attached to
  the card: a system line has no slot for one.
- **Notifications.** A reminder is not news: `NotNews`, with its own sentence
  as the quiet line. An escalation notifies as a DECISION, titled with the
  room and subtitled "Escalated", "Open" only — for an admin it just invited
  it may be the first sight of the card, and nothing on it can be answered
  from a notification.
- **Re-posted cards.** An admin an escalation invited cannot read the card
  posted before they joined, so when one joins the hub posts the whole card
  again below (the same envelope), moves the decision's record to the new
  event, replies "Moved below …" to the old card and refuses an answer to it
  (agentpod `decision-repost.ts`). `embedded::reconcile` mirrors that by
  identity: of the prose carriers of one board decision (`gate:`, `question:`,
  `delivery:`, `attention:`, `checks:` identities) from one sender, the latest in the
  timeline is the card, and every earlier one is drawn as a receipt with no
  buttons saying "Moved below — answer the copy below." (`outcome.decision`
  `moved`). A copy from any other sender retires nothing, a decision that is
  resolved shows its outcome on every copy, and if the newer copy leaves the
  timeline the older one is the card again. A permission is the station's
  and never re-posted, so two carriers of one permission are left as they
  were. The hub's "Moved below" reply is an ordinary message.
- **The expired gate** (§7): §7 below.

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
| `decision` | string | superpipeline's `GateDecision`: `approve`, `request_changes`, `reject` — or `expired`, when the board closed a subject-bound gate whose subject passed its `expires_at` (`decided_by` is then `null`) |
| `decided_by` | string or null | Who answered, as the hub names them (a handle, not an mxid) |
| `chosen_variant` | `{ id, label }`, optional | The version an approval of a choice chose (§5a, "A choice of versions"). Only its `label` is read: one line, bounded at 80 characters |
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
know. An approval that carries `chosen_variant` names the version as the hub
does: `Approved "With the thread" for automatic delivery by rakesh`; any other
decision ignores it. An `expired` gate reads as the hub's whole sentence and
names nobody: "Expired — what this gate approves passed its expiry before
anyone approved it, so it can no longer be approved here."
(`gate_outcome::EXPIRED_SUMMARY`, the hub's `OUTCOME_SENTENCES`). It closes the gate's card, never a follow-up's.

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

A decision's reminder or escalation notice (§5e) keeps the room pending:
`pending_decision_type` reads an accepted notice as the card it names by
`decision_kind` (gate → gate.v2, question → question.v2, delivery →
delivery.v2, attention → attention.v2), so the roster line still reads
"Approval needed". The hub posts a notice only while its decision is open, so
a notice never claims a decision that is not owed — even when the card itself
is not loaded. A notice the corpus refuses is ordinary prose and lights
nothing. A re-posted card is a decision card, so the room stays pending.

## Adding a field

1. Add it to the wire struct in `core::live` or to the renderer in
   `core::custom_events`, optional and defaulted, so an old sender still
   deserialises.
2. Bound it in the core if a sender controls it. The boundary is where the
   guarantee is made; a host must not be the first place a length is checked.
3. Render it on both hosts, or on neither. Two clients disagreeing about what
   an event means is the failure this whole layer exists to prevent.
