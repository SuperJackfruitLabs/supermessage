# Matrix events: what supermessage must handle

**Status:** A plan from Aug 2026 that has since been **largely executed**. Audited against the
code on 2026-09-12; the dispositions below are kept as the record of what was decided, and this
header says what is now true. Read the tables as "what we said we would do", not as a to-do list.

**The premise is historical.** This document opens by saying the timeline renders
`Unsupported event (m.room.name)` as a visible line. It does not any more, and the refactor
argued for in "The structural problem" is the one that fixed it. `core::item_view` is where the
decision now lives — start there, not here, for what actually renders.

**What the audit found built** (each verified in the code, not inferred):

| Section | Row | Now |
|---|---|---|
| A | The DTO discriminant this doc proposes | `classify_content` returns 13 kinds — `message`, `sticker`, `poll`, `redacted`, `unableToDecrypt`, `customMessage`, `liveLocation`, `membership`, `profileChange`, `state`, `failedToParse`, `callInvite`, `rtcNotification` |
| A | `m.notice` de-emphasised (M1) | `ItemView::Bubble { muted: true }` |
| A | `m.emote` (M1) | `ItemView::Emote` |
| A | Media msgtypes (M2) | `ItemView::Image` and `ItemView::MediaFile`; `m.image` `m.file` `m.audio` `m.video` `m.location` all matched |
| A | Formatted HTML body (M1) | `formatted_html_body`, rendered as rich blocks |
| A | Replies (M2) | `reply_to_dto`, with `reply_parent_label` for parents that cannot be quoted |
| A | Edits (M2) | `edited` on the DTO |
| A | Reactions (M2) | `reaction_entries` / `project_reactions` |
| B | Membership lines, collapsed (M1) | `ItemView::System`, with run-collapsing in `timelineGrouping.ts` |
| B | Profile changes suppressed (M1) | `ItemView::None` |
| C | State suppressed unless it matters (M1) | `state_view` renders **only** `m.room.create`, `m.room.encryption`, `m.room.tombstone`; everything else is `ItemView::None` — exactly the rule this section asks for |
| E | Read marker (M2) | `ItemView::UnreadMarker` |
| F | Typing (M2) | `TypingIndicator.svelte` |
| F | Read receipts (M2) | `read_receipts()` with `TimelineReadReceiptTracking::MessageLikeEvents` |
| G | Suite events (M1) | `ItemView::CustomEvent`, and see `docs/agentpod-events.md` for the two channels that actually ship |

**Not audited, and therefore not claimed either way:** the M4 rows (polls, power levels, pinned
events, policy rules, VoIP), `m.direct` DM detection, `m.push_rules`, and key verification. Their
milestones are as written; nobody has checked them recently.

One stale pointer worth fixing where it is: several comments in `core::timeline` and
`timelineGrouping.ts` cite a `timelineItemView.ts` / `viewFor` that does not exist. That logic
moved into Rust as `core::item_view`.

## The structural problem

`core::timeline::project_event_item` reduces every event to a raw type string:

```rust
let kind = event.content().event_type_str().unwrap_or_else(|| "unknown".into());
let body = event.content().as_message().map(|m| m.body().to_string());
```

So the webview sees `"m.room.message"`, `"m.room.name"`, `"m.room.encrypted"` and classifies on those strings, with everything unrecognised falling through to `Unsupported event (<kind>)`.

But matrix-sdk-ui has already done the hard classification for us. `TimelineItemContent` distinguishes messages from membership changes from state changes from parse failures, with the aggregation of edits, reactions and redactions applied. Flattening that back down to an event-type string throws the work away and then guesses.

**The fix is to project the SDK's variants into a purposeful DTO discriminant**, so the webview switches on *what an item is for the reader*, not on a wire type name. Every table below is written against that model.

The variants, verified against matrix-sdk-ui 0.18 source (`timeline/event_item/content/mod.rs`):

```
TimelineItemContent
├── MsgLike(MsgLikeContent)   → Message | Sticker | Poll | Redacted
│                               | UnableToDecrypt | Other | LiveLocation
├── MembershipChange(RoomMembershipChange)
├── ProfileChange(MemberProfileChange)
├── OtherState(OtherState)    → 19 state-event variants, listed below
├── FailedToParseMessageLike { .. }
├── FailedToParseState { .. }
├── CallInvite
└── RtcNotification
```

## A. Message-like content

`m.room.message` also carries a **msgtype**, which we currently ignore entirely — every msgtype renders as a plain bubble. `m.notice` matters immediately: it is what bots and bridges are supposed to use, so most agent output in this org's rooms is arguably mis-rendered today.

| Content | Disposition | Milestone |
|---|---|---|
| `Message` / msgtype `m.text` | Plain bubble | **done** |
| `Message` / msgtype `m.notice` | Bubble, visually de-emphasised — it is automated output | **M1** |
| `Message` / msgtype `m.emote` | `* Alice waves`, not a bubble | M1 |
| `Message` / msgtype `m.image` `m.file` `m.audio` `m.video` | Media rendering; authenticated media endpoints (spec ≥1.11, confirmed available) | M2 |
| `Message` / msgtype `m.location` | Map link or coordinate chip | M4 |
| `Message` with `formatted_body` (HTML) | Sanitised rich text. **Security-sensitive** — must allowlist tags, never inject raw HTML | M1 |
| `Message` that is a **reply** (`m.in_reply_to`) | Quoted parent above the body; the SDK exposes the parent already | M2 |
| `Message` that is an **edit** (`m.replace`) | SDK folds it into the original; render an "edited" marker | M2 |
| `Sticker` | Image bubble | M2 |
| `Redacted` | "Message deleted" tombstone, not a blank | **M1** |
| `UnableToDecrypt` | "Encrypted message" placeholder | **done** |
| `Poll` | Read-only summary at first; voting later | M4 |
| `Other` (custom message-like) | **This is where suite events arrive** — see §G | **M1** |
| `LiveLocation` | Out of scope | — |

Reactions (`m.reaction`) never appear as timeline items — the SDK aggregates them onto the target event. Rendering them is a **M2** task against `EventTimelineItem::reactions()`.

## B. Membership and profile

`MembershipChange` carries a `RoomMembershipChange` with these kinds: `None`, `Error`, `Joined`, `Left`, `Banned`, `Unbanned`, `Kicked`, `Invited`, `KickedAndBanned`, `InvitationAccepted`, `InvitationRejected`.

| Content | Disposition | Milestone |
|---|---|---|
| `MembershipChange` | One-line centred system text ("Alice joined"). Collapse runs of them — a room with many agents will otherwise be mostly join noise | M1 |
| `ProfileChange` (display name / avatar) | Suppress by default. Almost always noise; a setting can reveal it | M1 |

## C. State events — the ones leaking today

All 19 `AnyOtherStateEventContentChange` variants, verified from source. **Most should never be a timeline row.** The current fallback prints a row for every one of them, which is why `m.room.name` is visible.

| State event | Disposition | Milestone |
|---|---|---|
| `RoomName` | Suppress in timeline; update the room-list name. Optionally one line: "Alice renamed the room" | **M1 — fix now** |
| `RoomTopic` | Suppress; surface in room info | M1 |
| `RoomAvatar` | Suppress; update the avatar | M1 |
| `RoomCanonicalAlias` | Suppress | M1 |
| `RoomCreate` | Render as the "beginning of the room" marker | M1 |
| `RoomEncryption` | One line: "Encryption enabled" — a genuine security transition the user should see | M1 |
| `RoomTombstone` | Prominent: the room is replaced, with a link to the successor | M2 |
| `RoomPowerLevels` | Suppress by default; audit view later | M4 |
| `RoomJoinRules`, `RoomGuestAccess`, `RoomHistoryVisibility` | Suppress; surface in room settings | M4 |
| `RoomPinnedEvents` | Suppress in timeline; a pinned-messages surface later | M4 |
| `RoomServerAcl`, `RoomThirdPartyInvite` | Suppress | M4 |
| `PolicyRuleRoom`, `PolicyRuleServer`, `PolicyRuleUser` | Suppress — moderation policy, not conversation | M4 |
| `SpaceChild`, `SpaceParent` | Suppress in timeline; drives the spaces tree | M4 |

The general rule worth encoding: **state events are suppressed unless they change something the reader must know about** (creation, encryption, tombstone). The current default is exactly inverted.

## D. Failures and calls

| Content | Disposition | Milestone |
|---|---|---|
| `FailedToParseMessageLike` / `FailedToParseState` | Muted "Unsupported event" line — this is the *only* legitimate use of that text, and it should carry the event type for debugging | M1 |
| `CallInvite`, `RtcNotification` | "Call started" line; no VoIP in scope | M4 |

## E. Virtual items (not real events)

| Item | Disposition | Status |
|---|---|---|
| `DateDivider` | Date separator | **done** |
| `ReadMarker` | Unread line. Currently silent | M2 |
| `TimelineStart` | "Beginning of the room" | M1 |

## F. Not timeline items at all

These arrive outside the timeline and need their own plumbing. None exist today.

| Event | Where it belongs | Milestone |
|---|---|---|
| `m.typing` (ephemeral) | Typing indicator under the timeline | M2 |
| `m.receipt` (ephemeral) | Read receipts / unread counts | M2 |
| `m.fully_read` (account data) | Drives the read marker | M2 |
| `m.tag` (room account data) | Favourites, low priority — room-list sections | M4 |
| `m.direct` (global account data) | DM detection; changes naming and list grouping | M2 |
| `m.push_rules` (global account data) | Notification settings | M3 |
| `m.presence` | Per `docs/positioning.md`, presence should derive from org/runtime state, **not** Matrix presence | M4 |
| Key verification (`m.key.verification.*`) | Device verification flow | M1/M2 per positioning |

## G. Suite events — the actual differentiator

`docs/positioning.md` makes this the product: Kaambaan cards and runs, permission requests with approve/reject, station status, mission and fleet events. They arrive as `MsgLikeKind::Other` (custom message-like) and today would render as "Unsupported message".

Binding constraints from `AGENTS.md`, restated because they shape the schema:

- Custom event types must be **versioned, documented, suite-shared schemas**.
- Every one must carry a **plain-text fallback body**, so Element and Cinny remain usable clients against the same rooms.
- Correlate to work via `missionId` / `cardId` / `taskId` / `runId` plus `matrixRoomId` / `matrixEventId`. Never attach a whole room to one run.
- Agent identity, Station, ACP Session and Kaambaan Run are distinct linked objects and must render as such.

That schema work is M1's first task and should be co-designed with Kaambaan rather than invented here.

## Recommended order

1. **Stop the leak.** Replace the "render a row for anything unrecognised" default with an explicit allow/suppress classification. This alone removes `Unsupported event (m.room.name)` and every sibling.
2. **Project the SDK's taxonomy** instead of an event-type string — the change that makes everything below cheap rather than a pile of string comparisons.
3. **`m.notice` and redactions** — the two that most affect how this org's rooms actually read today.
4. **Membership lines, with collapsing.**
5. **Suite custom events** — M1 proper.

Items 1 and 2 are one refactor and should be done together; doing 1 alone means writing string comparisons that 2 then deletes.
