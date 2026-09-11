# Parity gap analysis

**Status:** Assessment, 14 Aug 2026. Written against commit `b7a1278` on `main`.
**Question it answers:** where does supermessage stand against other Matrix clients, and what would it take to close the gap?
**Audience:** the maintainer deciding what to build next quarter.

## How this was determined

Everything below is grounded in code, not in plans. The authoritative surface is
the `tauri::generate_handler!` list in `src-tauri/src/lib.rs`: the webview is a
dumb renderer that can only reach the homeserver through a registered Tauri
command, so **the command list is a hard ceiling on what this client can do.**
Seventeen commands are registered, and `src/lib/ipc.ts` exposes exactly those
seventeen and no others. Everything else was cross-checked against
`src-tauri/src/core/`, the six components in `src/lib/components/`, and the two
routes.

Documentation was deliberately not treated as evidence, and that turned out to
matter. `AGENTS.md` still describes M0 as the current state and reports "49 Rust
tests, 51 frontend tests"; the real numbers are 186 Rust (170 unit + 16
integration, 1 ignored) and 311 frontend, all passing, and the client is well
past M0. In the other direction, the most recent commit on `main`
(`b7a1278`, "Spec: the roster preview line and its core contract") adds a
detailed §6.1.1 to the console-design spec describing a roster preview line and
the three-field `RoomSummary` contract behind it — **and none of it is
implemented**. Two of those fields (`last_message_is_own`, `last_event_type`) do
not exist in `core/dto.rs`; the third, `last_message`, exists and is passed
`None` unconditionally at `core/rooms.rs:266`; and `RoomList.svelte` renders no
preview line at all. Read specs here as intent, in both directions.

Where the code does not settle a question, this document says "not determined"
rather than guessing. The app was never run: it auto-restores a real session
against a live homeserver and can send real messages to real people.

**One caveat with a shelf life.** At the time of writing there is uncommitted
work in the working tree implementing the roster preview line (gap #6):
`core/timeline.rs` has gained a `MessagePreview` type and a
`PREVIEW_MAX_CHARS` bound. It is not on `main`, it was not reviewed for this
assessment, and every claim below reflects `b7a1278`. If that work has landed by
the time you read this, treat gap #6 and step 1 of the recommended order as
already answered — nothing else in this document depends on it.

---

## 1. Verdict

supermessage is a **competent read-only Matrix reader with a reply box**: the
complete set of things it can write to a homeserver is a plain-text message, a
plain-text reply, a reaction toggle, and a read receipt. Measured against
Element Web, Element X, Cinny, FluffyChat or Nheko it is missing not a list of
features but whole categories — you cannot send a file, edit or delete anything
you said, create or join a room, invite anyone, search, change a setting, edit
your profile, or receive a notification when the app is closed. What it has that
none of them have is a hardened, well-tested agent-event rendering framework and
a reading surface genuinely designed for long-form agent output — but its
signature element, the dispatch card's decision row, is unreachable in this
build, because the schema it renders belongs to another team.

---

## 2. What works today

The seventeen registered commands, and what each one actually delivers.

### Session

| Command | What it does | Limits |
|---|---|---|
| `login` | `m.login.password` against a named homeserver | Password only. No SSO, no OIDC, no registration, no 3PID, no token login. Deliberate: `id.agentpod.dev` advertises no SSO/OIDC and `/_matrix/client/v1/auth_metadata` 404s (`AGENTS.md`, "Matrix protocol choices"). An `AuthProvider` seam is described but the code path is password-only. |
| `restore_session` | Restores from the OS keyring; no password on relaunch | Single account. `Session` owns one `matrix_sdk::Client`; nothing in the core models a second. |
| `logout` | Clears session, keyring entry and local stores | Only user-reachable escape hatch. No account menu exists (`+page.svelte:419`). |
| `core_status` | Platform, TLS provider, SDK-ready flag | Explicitly labelled an M0 smoke test. |

### Room list

| Command | What it does | Limits |
|---|---|---|
| `rooms_resync` | Full room-list snapshot for gap recovery | The steady-state path is a `VectorDiff` stream on `sm://rooms/diff`, not this. |
| `room_avatar` | Room avatar as a `data:` URI | 96px thumbnail. Resolves the DM two-person fallback that `avatarUrl` alone can't express. Returns `null` unless the bytes sniff to PNG/JPEG/GIF/WebP (`core/media.rs:sniff_mime`). |

Rooms are filtered with `new_filter_non_left()`, so **invited rooms appear in
the roster**, but no command accepts or declines an invite. What the timeline
does when an invite row is selected is not determined from the code.

The roster renders: avatar (or a glyph/initial fallback), the parsed room name,
a role chip, a relative timestamp, and an unread badge. It renders **no message
preview** — `lastMessage` is always `null`. The spec calls this "the most
visible functional gap the client has" and it is still open.

### Timeline

| Command | What it does | Limits |
|---|---|---|
| `timeline_subscribe` | Focuses one room, streams `VectorDiff`s | **Exactly one room at a time.** Subscribing replaces the previous subscription. There is no background timeline, so nothing is known about any other room beyond its `RoomSummary`. |
| `timeline_paginate_back` | Back-pagination by up to `count` events | Backwards only. |
| `timeline_resync` | Full snapshot for gap recovery | Carries the room id so a stale snapshot can be discarded mid-switch. |

The rendering itself is the strongest part of the client. `core/timeline.rs`
(3578 lines) projects `matrix_sdk_ui`'s `TimelineItemContent` taxonomy into a
semantic `kind`, and `timelineItemView.ts` turns that into a render decision
with **no fall-through case** — the "`Unsupported event (m.room.name)` in every
room" class of bug is structurally gone. Coverage:

- `m.text` as a bubble, `m.notice` as a **de-emphasised** bubble (correct, and
  most clients get this wrong), `m.emote` as `* Alice waves`
- HTML `formatted_body`, sanitised **twice** in the core before crossing IPC:
  `matrix_sdk_ui`'s allowlist pass, then `harden_formatted_body`, which strips
  `<img>`/`<mx-reply>` and narrows `<a href>` schemes because ruma-html 0.8.0
  has a bug that can skip its own scheme check. This is more careful than the
  norm.
- Reply quotes, including a distinct rendering for "parent didn't load" and for
  "parent loaded but has nothing to quote" (redacted/sticker/poll/undecryptable)
- Reactions aggregated per key with a `byMe` flag
- Edits: the SDK folds `m.replace` in and an `edited` marker renders
- Redactions as "Message deleted"; undecryptable events as "Encrypted message —
  this device has no key for it"
- Membership changes as system lines, with **runs collapsed** into one sentence
- State events suppressed by default; only `m.room.create`, `m.room.encryption`
  and `m.room.tombstone` produce a line
- Profile changes suppressed; date dividers; "Beginning of the room"
- Stickers, polls, live location, call invites and RTC notifications each render
  a **named placeholder** — visible, labelled, and inert
- A "Seen"/"Seen by N" marker on the reader's own latest message only
- Virtualised with virtua `shift: true`, so prepending history doesn't jerk the
  scroll; `followBottom` so incoming messages don't yank you out of history

### Sending — the whole of it

| Command | What it does | Limits |
|---|---|---|
| `send_message` | Plain text | **Plain text only.** No markdown parsing, no `formatted_body` on send, no attachments, no msgtype other than `m.text`. The `matrix-sdk` `markdown` feature is enabled in `Cargo.toml` and unused on this path. |
| `send_reply` | Plain-text reply to an event id | Same limits. Rejects local-echo ids. |
| `toggle_reaction` | Add/remove a reaction key | Six hard-coded quick reactions (`👍 ❤️ 😂 🎉 😮 🙏`) plus toggling keys already present. **No emoji picker** — an arbitrary reaction cannot be sent. |
| `set_typing` | Typing notice, correctly scoped and stopped | Genuinely well done: stopped on send, on room switch, on pause, on unmount. |
| `mark_room_read` | Public read receipt on the latest known event | Core performs; `readTracking.ts` decides. No private receipts, no `m.fully_read` marker. |

The clearest single view of the ceiling is the hover action row on a message
(`Timeline.svelte:847`). It contains a **Reply** button and six emoji. That is
the complete set of things you can do to a message in this client — no edit, no
delete, no copy, no forward, no quote, no pin, no permalink, no overflow menu.

Every room-scoped command verifies its `room_id` against the focused timeline
and fails with a `roomChanged` error rather than acting on the wrong room. This
is a real wrong-recipient hazard that was found and closed, and the composer
surfaces it to the user by name.

### Media and room info

| Command | What it does | Limits |
|---|---|---|
| `media_fetch` | An event's media as a `data:` URI | **Thumbnail only, images only.** 640px `MediaFormat::Thumbnail`. Returns `null` unless the bytes sniff to PNG/JPEG/GIF/WebP. An `m.file`, `m.audio` or `m.video` renders an informative row (name, size, mimetype) with **no download and no playback**. A PDF, a log file, a voice note: you can see it exists and nothing more. |
| `room_info` | Name, topic, canonical alias, alt aliases, room id, joined members, active count | **Read-only.** Nothing writes any of it. |
| `member_avatar` | A member's avatar as a `data:` URI | Same 96px/format limits. |

### Ambient

`sm://connection` drives a connection banner. `sm://typing` drives a typing
indicator. `matrix.to` and `matrix:` links inside message bodies are
intercepted: a **room addressed by id that you are already in** is selected
in-app; everything else — a room by alias, a user id, a room you're not in —
opens in the system browser, because no alias resolution, no profile surface and
no join flow exist. The main window refuses any navigation outside its own
origin (`is_app_origin`), so a malicious link cannot replace the app.

---

## 3. The gap table

Ordered by what each absence costs the user, not by what it costs to build.
"User" here means the maintainer dogfooding a fleet of agents on
`id.agentpod.dev`, which is who this client is actually for today.

Size: **S** ≈ under a week, **M** ≈ one to three weeks, **L** ≈ over a month,
for one person, including tests to this codebase's standard.

| # | Capability | Current state | Why it matters | Size |
|---|---|---|---|---|
| 1 | **Push notifications** | Nothing. No `tauri-plugin-notification`, no push gateway, no pusher registration, no `m.push_rules` handling. `capabilities/default.json` grants only `core:default` and `opener:default`. | You learn that an agent needs you only by having the app open and looking at it. The stated product is *approvals from chat*; an approval channel nobody is notified on is not a channel. This is the single largest gap and the long pole. | L |
| 2 | **Sending files and images** | Nothing. No upload command, no file picker, no drag-drop, no paste handler. | Cannot send a log, a screenshot, a config, a diff. In a fleet-operations client this comes up hourly, and the workaround is to leave the app. | M |
| 3 | **Media download and playback** | Metadata only. Images: a 640px thumbnail, four formats. Files/audio/video: a labelled row, no bytes. | An agent sends you an artifact and you cannot open it. Combined with #2, media is effectively one-way and lossy in both directions. | M |
| 4 | **Editing and redacting your own messages** | Nothing. Edits *received* render correctly; you cannot make one. No redaction path at all. | Every typo is permanent. A command sent to the wrong agent cannot be unsent. This is the most embarrassing single line in this table: the SDK exposes both as one call each, and the timeline already renders both correctly. | S |
| 5 | **Room creation, join by alias, invites** | Nothing. No create, join, leave, invite, or knock command. Invited rooms appear in the roster (`new_filter_non_left`) with no way to accept or decline. | The client can only be used with rooms someone else created and someone else invited you to, from some other client. You cannot onboard a new agent, start a DM, or set up a mission room. | M |
| 6 | **Roster message preview** | `RoomSummary.last_message` is always `None`. Spec §6.1.1 (landed in the most recent commit) designs it in full, including two fields that do not exist. Being implemented in the working tree as of writing — see the caveat above. | A roster where no row says anything about the conversation. The spec's own words: "the most visible functional gap the client has." Cheapest high-visibility item here. | S |
| 7 | **Search** | Nothing. No local index, no `/search` call. | No way to find what an agent said last week. Back-pagination in one focused room is the only retrieval mechanism, and history is not persisted for search. | M–L |
| 8 | **Mentions, pills, autocomplete** | Nothing. No autocomplete of users/rooms/emoji, no pill rendering, no `m.mentions`, no highlight-on-mention. A user-id link opens the browser. | In a multi-agent room you cannot address anyone. Separately, `docs/positioning.md` names mention→response as part of the P2 convergence — this is not only ergonomics, it's on the integration path. | M |
| 9 | **Jump-to-unread and read markers** | `kind: "readMarker"` is classified and returns `{ render: "none" }`. No unread line, no jump button. Unread *counts* work in the roster. | You know a room has 14 unread and get dropped at the bottom with no line showing where you left off. Cheap and felt constantly. | S |
| 10 | **Markdown input** | Nothing. `send_message` sends `body` only; the `markdown` SDK feature is compiled in and unused. | Code blocks, links and lists — the substance of talking to agents about work — arrive as flat text, while *received* HTML renders beautifully. The asymmetry is jarring. | S |
| 11 | **Emoji picker** | Six hard-coded quick reactions; toggling an existing key. | You can react with one of six things. No arbitrary reaction, no emoji in composed text beyond the OS picker. | S |
| 12 | **Room settings** | Read-only. `room_info` returns name/topic/aliases/members; nothing writes. No power levels, no join rules, no history visibility, no notification settings per room. | Cannot rename a room, set a topic, or change who may join. Everything requires another client. | M |
| 13 | **Profile editing** | Nothing. No display-name or avatar command, no account settings surface at all. | Your own identity is whatever some other client set. There is no settings screen of any kind — the only account control is "Sign out". | S |
| 14 | **Threads** | Nothing anywhere in the codebase — the word does not appear. Not projected, not rendered, not sendable. The timeline is built with the default focus (no `TimelineFocus` is set); **how matrix-sdk-ui 0.18 surfaces a thread reply under that focus is not determined** from the code, so whether such replies appear inline or vanish is an open question worth answering empirically. | A busy fleet room becomes an interleaved mess. Partly mitigated by the intended dispatch-card model, but only partly — and either possible behaviour above is bad: inline means structure is silently lost, absent means messages are silently missing. | L |
| 15 | **Moderation** | Nothing. No kick, ban, ignore, report, or power-level change. | No recourse against a misbehaving agent or a spamming bridge except leaving from another client. | M |
| 16 | **E2EE and device verification** | Partial, and the partial part is the dangerous part. The SDK's default `e2e-encryption` feature is on, so encrypted events **decrypt if this device happens to hold the key**; the encryption sync runs; the store is encrypted at rest. But there is **no verification (SAS/emoji), no cross-signing bootstrap, no key backup, no recovery, no per-device UI, and no way to enable encryption on a room** — zero commands touch any of it. Undecryptable events render "Encrypted message — this device has no key for it", which the real-account run confirmed happens. | Inside the org this costs little and that is a deliberate product call, not an oversight: org rooms are unencrypted by design because the Knowledge layer must read history and Application Service bridges don't mix with E2EE (`AGENTS.md`, "Security and license considerations"). Outside the org it is blocking: a fresh login loses all encrypted history with no backup to restore from, other clients will flag the session unverified and may withhold keys, and any homeserver that defaults DMs to encrypted produces a wall of placeholders. **The deferral is defensible for the org use case and indefensible for a public release.** | L |
| 17 | **Spaces** | Nothing. `m.space.child`/`m.space.parent` are suppressed as ordinary state. | No hierarchy. Tolerable at 16 rooms; not at 100. M4 by plan and that ordering looks right. | M |
| 18 | **Polls, voice messages, stickers, location** | Named inert placeholders for all four when received. None sendable. | Low cost in an agent fleet. The placeholders are honest, which is the right interim answer. | M each |
| 19 | **Multi-account** | Nothing. One `Session`, one `Client`, one keyring entry. | Costs nothing today (one account) and a lot later — it is the kind of thing that is cheap to design for and expensive to retrofit, and the core is currently built squarely around a single session. | L |
| 20 | **SSO/OIDC and registration** | Password only. Neither exists. | Costs nothing, and is no longer waiting on anything: the homeserver is tuwunel, advertises no SSO or OIDC, and both discovery endpoints 404. **matrix-authentication-service is not coming** — it is Synapse-family and the homeserver was swapped on 2026-08-16 (`charter → decisions/2026-08-30-matrix-identity-without-mas.md`). It becomes relevant only if a second homeserver is targeted, or if the suite's issuer turns out to be able to drive `m.login.token` — unverified. | M (not on the roadmap) |
| 21 | **Presence** | Nothing. Matrix presence not consumed. A per-room connection dot exists, which is sync health, not presence. | Deliberately deferred, with a reason: presence should derive from org/work/runtime state, not from Matrix's free-form presence (`AGENTS.md`, M4). Do not build the Matrix version — it would have to be thrown away. | S (Matrix), L (org-derived) |

---

## 4. Where supermessage is ahead

Genuinely ahead, with the "actually built" and "slot waiting on a schema" lines
drawn explicitly, because they run right through the middle of the
differentiator.

**The agent-aware rendering framework — built, tested, and empty.**
`customEvents.ts` (511 lines, 506 lines of tests) is a real extension seam: a
registry keyed on Matrix event type, a four-step fallback chain (renderer →
plain-text `body` → generic placeholder, never silence), and a versioning
decision that is properly argued rather than assumed — major version in the
event type string so an unknown major is just a `Map` miss, minor version as a
`schema_version` field inside `content` so an additive change degrades to a
subtle "newer version" note. Renderer output is bounded (12 fields, 300/60
chars) and renderers are forbidden from recursing, with the reasoning written
down. Payloads are capped at 8KiB in the core before crossing IPC. The registry
ships with exactly one renderer, `dev.supermessage.demo.note.v1`, in a namespace
this app owns precisely so it can never be mistaken for a suite schema. **No
suite event schema is invented anywhere in this codebase**, which is the hard
rule, and it has held.

The core half is less visible and more impressive. `matrix_sdk_ui`'s own
`default_event_filter` ends in an unqualified `_ => false` with no exception for
an unrecognized message-like type — meaning a custom Kaambaan event would have
been **dropped before it ever became a timeline item at all**, and
`docs/matrix-events.md` §G's entire "arrives as `MsgLikeKind::Other`" premise
would have been quietly false. `timeline_event_filter` in `core/timeline.rs`
adds that case back, and the fix was verified empirically: an integration test
with a real SDK-built custom event through a mocked homeserver timed out under
the default filter and passes under the override. Finding that class of bug
before the schema exists is the kind of thing that pays off later.

**The dispatch card and its decision slot — the card is built, the decision is
unreachable.** The card renders: the timeline's only bordered object,
left-aligned regardless of sender, with amber (`--color-signal`) reserved
application-wide to mean exactly one thing, "the operator owes someone an
answer". The decision path is implemented end to end *up to the send*:
`boundDecision` treats the renderer's own output as hostile and validates it
structurally and totally (the `typeof !== "object"` arm exists because a
*function* can carry `prompt` and `options` as properties, and there is a test
named for that), options cap at four, `id` is deliberately not truncated because
a silently shortened identifier would be a confidently wrong answer.

And then `onDecide` calls `console.warn`. No shipped renderer sets `decision`,
so `boundDecision` returns `null` for every real event and the button branch
never executes. This is the spec's requirement — §7.1, "do not ship a visible
button that does nothing" — not a bug. It is blocked on two things that belong
to Kaambaan, not here (`rakeshgangwar/kaambaan#34`): the inbound schema whose
renderer would set `decision`, and the outbound decision event type. The comment
at `Timeline.svelte:584` records why it must be a Matrix event and not the REST
call an earlier version assumed — the client holds one credential, gate
resolution needs a human session, and resolving as a bridge identity would void
Kaambaan's separation-of-duties check.

**Be clear about what this means.** The single feature that justifies this
client existing is a well-built, well-tested, hostile-input-hardened socket with
nothing plugged into it. That is not a criticism of the engineering — building
the socket before the plug was the correct call, and refusing to invent the
schema was the correct call. But it means the differentiator currently
differentiates on architecture, not on anything a user can do.

**The editorial reading surface — built.** Peer and own messages are
deliberately asymmetric: a peer message is an editorial block (no bubble, serif,
`68ch`, mono sender line above), an own message is a tight right-aligned bubble
(sans, `52ch`, accent ground). *You type, they write.* Both sit inside one
centred `72ch` reading column. This is a real design response to a real
property — agents emit long-form prose and operators emit commands — and no
general-purpose Matrix client does it, because no general-purpose Matrix client
has that asymmetry to exploit.

**Parsed agent identity in the roster — built.** `roomIdentity.ts` parses the
`<glyph> <Name> — <Role>` names this homeserver mints ("🧠 Buddhimaan — Squad
Lead") into three fields rendered at three visual ranks: glyph as avatar
fallback, name as row title, role as a chip. An unstructured name degrades
silently to a plain name. Pure, unit-tested, no homeserver knowledge baked in.

**One Rust core — real as architecture, unverified as a claim.** matrix-sdk runs
as a plain crate inside the Tauri core with no UniFFI bridge; the webview
reaches it only through 17 commands; no SDK type crosses the IPC boundary
(`core/dto.rs`'s `project_diff` is the exhaustive match that enforces it).
That is a genuinely good structure and it is what would make five platforms
cheap. But **only Linux has ever been run.** `AGENTS.md` states nothing on
Windows, macOS or mobile is verified; iOS/macOS need a Mac that isn't available;
the Android project is scaffolded and committed (`src-tauri/gen/android/`) but
nothing in the repo evidences it having been built or launched. The mobile and
desktop skins are not started — Framework7 is not a dependency, and `bits-ui`
is declared but imported nowhere in `src/`. "One core across five platforms" is
a defensible design claim and not yet a shipping one.

**Not in the brief but worth naming: the security posture is above the norm.**
Double HTML sanitisation working around a known ruma-html bug, an origin lock on
webview navigation with lookalike-host tests, a byte cap on custom payloads, a
hostile-input validator on renderer output, and the wrong-recipient race in
room-scoped commands found and closed. Most clients this young have none of
this.

---

## 5. Comparison

**Read the provenance line before the table.** Cells were not all established
the same way, and the difference matters:

- **supermessage** — verified against this repo's code, as everywhere else in
  this document.
- **Element X, Cinny, FluffyChat** — source-verified this pass against cloned
  trees at known commits (Cinny `c434e0d`/v4.12.6; FluffyChat `92b44a7`/v2.8.0;
  element-x-ios `release/26.08.2`, element-x-android `v26.08.1`), plus release
  notes and issue trackers.
- **Element Web, Nheko** — **not researched this pass.** Cells are marked from
  general knowledge of two long-established clients where that knowledge is
  solid, and `?` wherever it is not. Treat these two columns as indicative and
  verify before relying on either.

`yes` / `part` / `no` / `?`. "part" always carries a note below.

| Capability | supermessage | Element Web | Element X | Cinny | FluffyChat | Nheko |
|---|---|---|---|---|---|---|
| E2EE encrypted rooms | **part** ¹ | yes | yes | yes | yes | yes |
| Device verification (SAS, cross-signing, key backup) | **no** | yes | yes | yes | yes | yes |
| Send files and images | **no** | yes | yes | yes | yes | yes |
| Edit and redact own messages | **no** | yes | yes | yes | yes | yes |
| Room create / join by alias / invite | **no** | yes | yes | yes | yes | yes |
| Push notifications | **no** | yes | yes ² | part ³ | yes ² | ? |
| Message search | **no** | yes | part ⁴ | yes ⁵ | part ⁶ | ? |
| Threads | **no** | yes | part ⁷ | part ⁸ | yes ⁹ | ? |
| Spaces | **no** | yes | yes ¹⁰ | yes | yes | ? |
| Media download and playback | **no** ¹¹ | yes | yes | yes | yes | yes |
| Mentions, pills, autocomplete | **no** | yes | part ¹² | yes | yes | ? |
| Room settings + profile editing | **no** ¹³ | yes | yes | yes | yes | yes |
| Markdown input + emoji picker | **no** ¹⁴ | yes | yes | yes | yes | yes |
| Multi-account | **no** | no | part ¹⁵ | no ¹⁶ | yes | ? |
| SSO/OIDC + in-client registration | **no** ¹⁷ | yes | part ¹⁸ | part ¹⁹ | part ²⁰ | ? |
| Jump-to-unread / read markers | **no** | yes | part ²¹ | yes | yes | ? |
| Polls / voice messages / stickers / location | **no** ²² | yes | part ²³ | part ²⁴ | yes ²⁵ | ? |
| Presence | **no** | yes | part ²⁶ | part ²⁷ | yes | ? |
| **Agent-aware custom event rendering** | **yes** ²⁸ | no | no | no | no | no |
| **Approve/reject a decision from the timeline** | **no** ²⁹ | no | no | no | no | no |

¹ Decrypts if this device happens to hold the key; no verification, no key
backup, no recovery, no way to enable encryption on a room. See gap #16.
² FCM/APNs plus UnifiedPush. ³ Cinny: foreground only. ⁴ Element X: a local
Tantivy index, forward-only, **default-off on both platforms** — closer to "no"
in practice. ⁵ Cinny: server-side `/search`; does not cover encrypted rooms.
⁶ FluffyChat: per-room client-side only, no global search. ⁷ Element X: behind
Labs, default-off, and the sole remaining Labs feature. ⁸ Cinny: can start and
reply, rendered inline, no thread panel (issue #257; gated behind an in-flight
SDK rewrite). ⁹ FluffyChat: since v2.3.0, no thread list. ¹⁰ Element X: GA
including create and manage as of iOS 26.02.0 / Android 26.01.0 — the widely
cited "read-only spaces" status is out of date. ¹¹ Image thumbnails only, four
formats, 640px; no download, no playback, no non-image bytes at all. ¹² Element
X: no `:`-triggered emoji autocomplete. ¹³ Read-only room info panel; no writes
of any kind and no settings screen. ¹⁴ Six fixed quick reactions; no picker, no
markdown on send. ¹⁵ Element X: Android experimental and default-off; iOS none.
¹⁶ Cinny: multi-session code is commented out. ¹⁷ Password only; the target
homeserver advertises neither and both discovery endpoints 404. ¹⁸ Element X:
OAuth yes, registration partial, legacy SSO unverified. ¹⁹ Cinny: SSO and
registration yes, native OIDC partial. ²⁰ FluffyChat: SSO/OIDC yes, registration
punted to an external browser. ²¹ Element X: jump button default-off. ²² All
four render as named inert placeholders; none sendable. ²³ Element X: stickers
render-only (no picker); polls, voice and live location yes. ²⁴ Cinny: no polls;
voice playback only; location render-only. ²⁵ FluffyChat: static location only.
²⁶ Element X: iOS sends presence; no display UI found. ²⁷ Cinny: displays
others' presence, cannot set your own. ²⁸ A registry, a versioning scheme, a
four-step fallback chain and a hostile-input validator — see §4. No other client
has this because no other client needs it. ²⁹ Built, unit-tested and unreachable
pending `kaambaan#34`. Nobody else has it either — this is the intended
differentiator, unclaimed by anyone including us.

**What the table says.** On the seventeen rows that describe ordinary Matrix
client work, supermessage scores one "part" and sixteen "no", against four
clients that score mostly "yes". There is no row where we beat a mature client
at its own game. The two rows where we lead are rows nobody else is contesting —
and on the second of them, we do not yet lead either; we have the only
implementation, and it is switched off.

The useful corollary is that the comparison clients are not uniformly ahead of
each other either: Element X gates threads behind Labs and ships search
default-off; Cinny has no polls and commented-out multi-account; FluffyChat has
no global search. **Nobody has everything, which is licence to choose** — the
question is which subset, not how to catch up on all of it. §6 argues for a
deliberately small one.

<details>
<summary>Sources checked, and one that should not be trusted</summary>

Source trees read: `cinnyapp/cinny` @ `c434e0d` (v4.12.6); `krille-chan/fluffychat`
@ `92b44a7` (v2.8.0) plus `famedly/matrix-dart-sdk`; `element-x-ios`
`release/26.08.2` and `element-x-android` `v26.08.1` — the decisive files were
`FeatureFlags.kt`, `LabsPresenter.kt`, `AppSettings.swift`, `LabsScreen.swift`,
`UserSessionFlowCoordinator.swift`. Also: element.io/blog
(`element-x-and-pro-updates`, `spaces-has-landed-on-element-x`, 30 Mar 2026);
both element-x repos' `CHANGES.md`, releases and issue labels; cinny issue #257
and PRs #2492/#2787/#3020/#2320; fluffychat `CHANGELOG.md` and issues
#2874/#3037; the `matrix-sdk-search` README.

**Do not use the matrix.org ecosystem client pages for this.** The rendered
`matrix.org/ecosystem/clients/<name>/` pages returned byte-identical feature
lists for all five clients — they render a legend of trackable features, not
per-client marks. The raw front-matter in the repo does carry real per-client
values, but it is self-reported and stale: it claims Cinny has both threads and
multi-account, and Cinny's own source contradicts both.
`matrix-org.github.io/canitmatrix` carries platform lists only, no feature data.

Element Web and Nheko were not reached before this pass was cut short, which is
why their columns carry the caveat above rather than citations.
</details>

---

## 6. Recommended order of attack

### First, the strategy question: is parity even the right goal?

**Mostly no.** Chasing Element Web's feature list is a losing race — it has a
decade and a team — and every week spent on it is a week not spent on the only
thing that makes this client worth having. The evidence in this repo supports a
deliberate "do not close most of this gap": threads, spaces, multi-account,
polls, stickers, location, voice messages, Matrix presence and full E2EE UX are
all either irrelevant to an agent fleet, actively wrong for it (Matrix presence
should be org-derived; org rooms are unencrypted by design), or M4-or-later by a
plan that still looks correct.

**But there is a floor, and two things sit under it.**

The first is that the differentiator is blocked on someone else. The dispatch
card's decision row, the roster's "Approval needed" state, and wedge #3
("approvals from chat — nobody else has this") are all one schema away, and that
schema is `rakeshgangwar/kaambaan#34`. Until it lands there is no agent-aware
work to do that isn't inventing schemas, which this codebase has correctly
refused to do. That makes the next quarter's agent-aware capacity *involuntarily
free* — and it should go into the parity floor rather than being idled.

The second is that dogfooding is the feedback loop, and the feedback loop is
currently eroding. A client where you cannot send a screenshot, fix a typo,
unsend a wrong command, open a file an agent sent you, find last week's
conversation, or learn anything happened while the window was closed is a client
you stop reaching for. If dogfooding stops, the agent-aware work loses the only
signal telling it whether it's right.

So: **build the parity floor that keeps dogfooding alive and that the wedge
actually requires. Do not build the long tail. Say so out loud, in the README,
so the scope stays decided rather than relitigated every month.**

### The order

**0. Unblock `kaambaan#34`.** Not engineering. It is the highest-leverage item
on this page and it costs a conversation: the inbound gate/permission schema and
the outbound decision event type. Everything the product is *for* is downstream
of it. Do this in week one regardless of what else happens.

**1. Roster preview line (S).** The spec's own "most visible functional gap",
designed down to the core contract in the most recent commit, with the field
list already written. Half a week for a visible daily improvement, and it puts
the `last_event_type` hook in place that the "Approval needed" row will key off
when #0 lands. **Already in progress in the working tree** — see the caveat at
the top; if it has landed, start at step 2.

**2. Start the push spike now (L, and it is the long pole).** Everything else on
this list is short; push is not. It needs a gateway deployed, FCM/APNs
credentials, a pusher registered, `event_id_only` handling that fetches content
client-side, and — the part with real unknown risk — a mobile build that has
never been produced. `AGENTS.md` flags that no production Tauri-mobile Matrix
client exists. Start it in parallel with the short items so its unknowns surface
early rather than in month three. Desktop notifications are a fraction of the
work and worth taking first as a stepping stone.

**3. Edit and redact (S).** One SDK call each, the timeline already renders both
correctly, and it removes the most embarrassing absence in the table.

**4. Sending files and images, plus download/open (M).** Restores media to
two-way. Take the file picker, drag-drop and paste in one pass; the download
side is `MediaFormat::File` plus a save dialog, and it shares the resolution
path `media_fetch` already has.

**5. Markdown input and an emoji picker (S each).** Both cheap, both felt every
message, and markdown closes an asymmetry that currently reads as broken —
received HTML renders well, sent text cannot contain a code block.

**6. Mentions and autocomplete (M).** Ergonomics *and* integration path:
`positioning.md` names mention→response as part of the P2 convergence, so this is
not purely a comfort item.

**7. Room creation, join by alias, and invites (M).** The point at which the
client stops requiring a second client to set anything up. It also makes the
`matrix:` link handling in `messageLinks.ts` complete — that module already
documents alias resolution and join as the two missing pieces.

**8. Jump-to-unread and the read marker (S).** `readMarker` is already
classified and suppressed; giving it a visual form and a jump affordance is
small.

**9. Search (M–L).** Real value, real cost, and the only item here whose right
implementation is genuinely unclear (server-side `/search` versus a local index
over the SQLite store). Defer until the items above are done and the answer is
better informed.

**Explicitly not next quarter, with reasons:** threads (L, and the dispatch-card
model partly substitutes — but log the silent structure loss on received thread
replies as a known bug); spaces (M4, 16 rooms doesn't need them); multi-account
(L, no second account exists — but *do* stop adding single-session assumptions
to the core); moderation (M, no incidents); polls/voice/stickers/location (the
placeholders are honest); Matrix presence (would be thrown away); full E2EE UX
(the product call is sound for org rooms).

**Three things that are not in the gap table and outrank half of it:**

- **Verify a second platform.** Four of the five target platforms have never run
  this code. Push lands on mobile. The Android 16KB-page/aws-lc-rs risk is
  documented, mitigated at runtime and **unverified on real hardware**. Finding
  out at push time that the mobile build doesn't start is a much worse quarter
  than finding out now.
- **Fix the README, this week, before anything else on this page.** It is MIT,
  it is public, and `.github/workflows/release.yml` builds Linux/macOS/Windows
  binaries on a `v*` tag. It currently asserts two things the code does not
  support. "It is an ordinary Matrix client against any homeserver" — it is
  not: you cannot send a file, create or join a room, invite anyone, or verify
  a device, and against a homeserver that defaults DMs to encrypted you get a
  wall of placeholders with no key backup to recover from. And "Approvals from
  chat. When an agent needs a human decision, the request arrives as a Matrix
  message with the decision attached to it" is written in the present tense
  about the one path that is unreachable by design. Both need to become
  statements of intent, or the scope needs to narrow to the suite explicitly.
  This costs an hour and it is the difference between an honest early-stage
  project and one that oversells — and this codebase's own documentation
  standard, which states limitations plainly everywhere else, is being broken
  in the one file most people will read.
- **Then decide whether E2EE UX moves up.** Shipping a general-purpose Matrix
  client with no device verification, no key backup and no recovery is
  defensible for an internal org tool and not for a public one. Scoping the
  README is the cheap answer; building it is the expensive one. Pick
  deliberately rather than by default.

---

## 7. What each gap costs

Sizes assume one person working to this codebase's actual standard — which is
high, and should be priced in: every extracted pure module has a test file
beside it, and non-obvious decisions get written down at length. An estimate
borrowed from a normally-tested codebase will be well under what these cost
here.

| Gap | Core (Rust) | Webview (Svelte) | Size | Notes on the estimate |
|---|---|---|---|---|
| Roster preview | Decode the latest message-like event in the room-list projection; 2 new `RoomSummary` fields | A third roster line | **S** | Spec §6.1.1 already specifies it fully. The decode logic can reuse `project_item`'s classification rather than duplicating it — that duplication is exactly why it was deferred. |
| Edit | `Timeline::edit` | An edit affordance and composer mode | **S** | Received edits already render. |
| Redact | `Timeline::redact` | A confirm affordance | **S** | Received redactions already render. |
| Markdown input | `RoomMessageEventContent::text_markdown` | Nothing required | **S** | The SDK feature is already compiled in. |
| Emoji picker | None | A picker component | **S** | Pure frontend. Watch the bundle size. |
| Jump-to-unread | Possibly `m.fully_read` | Render the marker; a jump button | **S** | `readMarker` is already classified. |
| Profile editing | 2 commands (display name, avatar) | A settings screen | **S** | Small, but it is the first settings surface — most of the cost is that there is nowhere to put it. |
| Desktop notifications | Push-rule evaluation, or naive unread | `tauri-plugin-notification`, capability grant | **S–M** | Worth doing as a stepping stone to #2 above. |
| Files/images send | Upload + attachment message send | Picker, drag-drop, paste, progress | **M** | Encrypted-room upload works through the same path; `MediaSource` is already modelled for it. |
| Media download/open | `MediaFormat::File` + save | Save dialog, open-with | **M** | Audio/video *playback* is more: a `data:` URI won't stream, so it needs a custom protocol handler or a temp file. Budget separately. |
| Room create/join/invite | 4–5 commands, incl. alias resolution | Create/join dialogs, invite UI, invite accept/decline in the roster | **M** | The invite-accept path is the fiddly part: invited rooms are already in the list with no state model for them. |
| Mentions/autocomplete | Member/room search; `m.mentions` on send | Autocomplete, pill rendering, highlight | **M** | Pills must go through the same hardening as `formatted_body`; do not add a second HTML path. |
| Room settings | Name/topic/avatar/join-rules/power-level writes | A settings panel | **M** | The read side already exists in `room_info`. |
| Moderation | Kick/ban/ignore/report | Member-list actions, confirms | **M** | Naturally follows room settings; share the power-level plumbing. |
| Spaces | Space hierarchy, `m.space.*` | A tree in the roster | **M** | The roster is currently a flat list with no grouping concept. |
| Polls / voice / stickers / location | Per-type send and render | Per-type UI | **M each** | Independent; not a bundle. Voice needs microphone capture in a webview, which is its own risk. |
| Search | Server `/search`, or a local index over the SQLite store | Search UI, result navigation | **M–L** | The design choice is the cost. Server-side search is cheap and weak; a local index is strong and needs history the client does not currently retain. |
| Threads | Thread-aware timeline (the SDK supports it; this projection does not) | A thread panel, thread roots in the timeline | **L** | Touches the timeline projection, the focused-timeline model, and the read-receipt logic simultaneously. Nothing here is currently thread-shaped. |
| Push notifications | Pusher registration, `event_id_only` fetch/decrypt, push rules; iOS NSE in Swift linking the Rust SDK | Notification settings | **L** | Plus infrastructure (gateway deploy, FCM/APNs) and a mobile build that has never been produced. The largest and riskiest item on the page — the estimate is genuinely uncertain, which is itself the argument for spiking it early. |
| E2EE + verification | Cross-signing bootstrap, SAS/emoji verification, SSSS key backup, recovery key, per-device state | Verification flow, device list, recovery UI | **L** | The SDK does the cryptography; the cost is the UX, and getting verification UX wrong is worse than not shipping it. |
| Multi-account | Session/client multiplexing throughout the core, per-account stores and keyring entries | Account switcher, per-account state | **L** | The core is built around exactly one `Client`. This is a refactor, not a feature. |
| SSO/OIDC + registration | The `AuthProvider` seam made real | Login flow branches, registration | **M** | Not blocked — **not planned.** MAS is not coming; see row 20. The seam stays because how a Matrix login relates to the suite's issuer is an open charter question, not because MSC3861 is pending. The code path is password-only. |
| Presence (org-derived) | Consume org/runtime state — depends on the P1 Organization layer | Presence indicators | **L** | Blocked on a layer that does not exist yet. The Matrix version is **S** and should not be built. |
