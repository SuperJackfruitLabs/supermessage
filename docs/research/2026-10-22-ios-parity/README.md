# Android and desktop against iOS — parity audit

**Status:** assessment, 22 Oct 2026, against `main` at `f64629f`. A dated record: it
will not be edited to look current.

**Update, 23 Oct 2026:** the Desktop cells of R1, A1, D11, T1, T6, D5, D6, A2 and A3 were
corrected after M4 (branch `parity/m4-desktop`); each names its evidence and what is still
live pending. The rest of the document is unchanged.

**Question it answers:** what does the iOS app do that Android and desktop do not,
how big is each gap, and in what order should they be closed?

**Audience:** the maintainer planning the Android and desktop parity work.

**How it was determined:** by reading the code. Each platform was compared with
iOS against one checklist, built from the iOS revamp spec
(`docs/superpowers/specs/2026-09-23-ios-revamp.md`, items D/T/A/G/N/R/C/M/P/O) plus
the iOS surfaces outside it. Per-platform evidence tables with file:line citations
are in [`notes/android.md`](notes/android.md) and [`notes/desktop.md`](notes/desktop.md).

The most important claims were checked again by hand: the four Android bugs, the
desktop edit/delete, themes, notification and recovery claims, the 45-command
count, and the Swift-only Kit logic. **Nothing was run.** Gestures, animation,
scroll feel and haptics are static readings on every platform, including iOS.
That matches the revamp spec's own note that those need a device pass.

Neither iOS nor the comparison is perfect. iOS has its own unbuilt items: no Stop
(A4, blocked on AgentPod), no mentions in Needs you, no "Error" status despite the
spec, and no device verification on any platform.

## 1. The short version

- **Android has the whole core surface; desktop doesn't.** The Swift and Kotlin
  UniFFI interfaces match method for method, 64 each. Android's gaps are therefore
  Compose UI work plus a thin `CoreClient` wrapper. Desktop can only reach the
  core through its 45 Tauri commands, and about 15 core capabilities iOS uses have
  no command at all. These include mute/pin/notification mode, block, report,
  `send_permission_answer`, `known_people`/`direct_room_with`, `room_inviter`,
  `account`, `ensure_recovery`, notifications and voice marking.
- **Neither platform has the iOS revamp.** Desktop got its typography (and, on
  2026-10-04, scroll and name fixes). Android got the captions and the notice
  rows. The agent experience the revamp exists for is missing on both:
  - agent cards (T1);
  - one live card inside the timeline (A2);
  - "Sent to … / … is on it" (A3);
  - the "Thought for 12s · 2 steps" record (T6);
  - the header status pill (A1);
  - the Needs you inbox and the Agents view (N1–N3).
- **The biggest structural finding:** much of what makes iOS feel like iOS is
  presentation logic in Swift. It lives in `SupermessageKit`, not in the Rust core:
  - `Acknowledgement`;
  - `RoomStatus`, `NeedsYouInbox`, `AgentDirectory` and `RosterFilter` (in
    `RosterViews.swift`);
  - `AgentAvatarStyle`;
  - `TurnRecordLabel`;
  - the long-read and hide-the-quote rules in `TimelineGrouping`;
  - `FirstRunDemo`;
  - `SpaceNames`.

  That is about 770 lines, with no core equivalent (checked by grep). Timeline
  grouping already exists three times (Swift, Kotlin, TypeScript) and has already
  drifted: Android and desktop collapse membership churn only within one verb.
  Porting these rules twice more would contradict the project's rule that the app
  "decides nothing that the core already decides". **Recommendation: move them
  into the core first, then build both UIs on top.** This is the same move the
  shared view-model migration made for render classification and previews.
- **Android has four real bugs** that need no design work (§2).

## 2. Bugs found along the way (verified by hand)

| # | Platform | Bug | Evidence | Size |
|---|---|---|---|---|
| B1 | Android | **AgentPod permission requests can never be answered.** The buttons only enable for gates. | `DecisionCard.kt:350`: `answerable = subject != null && …`; permission requests have no subject. `CoreClient.kt` has no `sendPermissionAnswer` wrapper. iOS fixed exactly this. | S |
| B2 | Android | **An attachment can't be sent without text.** | `Composer.kt:179`: `canSend = text.isNotBlank()`; the attachment is ignored. | S |
| B3 | Android | **Images never render.** The row draws a grey box with the alt text. | `TimelineRow.kt:655-687` (`ImageRow` has no image load); the Kit's `MediaCache` is never used by the app. | S |
| B4 | Android | **App lifecycle not wired.** No reseed on return to the foreground, no typing-off when backgrounded, no sync pause. | `Session.scenePhaseChanged` (`kit/Session.kt:351`) has no caller. | S |
| B5 | Desktop | Desktop never sets up recovery at sign-in. iOS (`Session.swift:714`) and Android (`kit/Session.kt:713`) call `ensure_recovery` after signing in and show the new key once; desktop has no command for it. Its panel's `enable()` is defined but never called. iOS and Android have no "enable" button either, so the parity fix is `ensure_recovery`, not that button. | `RecoveryPanel.svelte:47`; no `ensure_recovery` in `src-tauri/src/lib.rs` | S |
| B6 | Desktop | A failed decision send is silent: the error goes only to `console.error`. *Corrected 2026-10-22 during M0:* a failed attachment being dropped is **not** a gap. The core consumes the staged file before sending (`attachments.rs`, `take_for_send`, to prevent duplicate sends), and iOS behaves the same way (`Outbox.swift:38-50`): the error shows and the text stays. *Superseded 2026-10-22, during M1 planning: iOS re-stages a failed attachment (`StagedAttachment.swift:143-178`); the M0 correction was wrong; tracked as M1.7.* | `Timeline.svelte:978-981` | S |
| B7 | Docs | README overstates Android: "editing/deleting own messages: Implemented" (there is no delete UI), and "permission cards Implemented" (see B1). | `README.md:33,36` | S |

## 3. The matrix

**Status key:**
- ✅ — at parity with iOS.
- ◐ — partial.
- ✗ — missing.
- n/a — doesn't apply to the platform.
- ⛔ — blocked on all platforms.

The "Work" column is the plumbing each gap needs:
- *UI* — UI work only.
- *wrap* — a `CoreClient` or `ipc.ts` wrapper over something that already exists.
- *cmd* — a new Tauri command over existing core code.
- *core* — new core logic, including moving Swift Kit rules into the core.

### Navigation and onboarding
| | Feature | Android | Desktop | Work |
|---|---|---|---|---|
| N1 | Chats / Needs you / Agents / Search destinations | ✗ (three text buttons above one list) | ✗ (one roster; search only from inside a room) | core (lists) + UI |
| N2 | Needs you inbox with badge and "caught up" state | ✗ | ◐ ("Waiting" arrangement only) | core + UI |
| N3 | Agents view with status and last activity | ✗ | ✗ | core + UI |
| — | Adaptive / multi-pane shell | ◐ (panes work; no destinations) | ✅ | UI |
| — | Spaces switching | ✗ (Kit `SpacesStore` unused) | ✅ (rail) | UI |
| — | Deep links (`supermessage://` for widgets) | ✗ | n/a (desktop routes matrix.to in-app; iOS doesn't) | UI |
| O1 | Welcome screen and local first-run demo | ✗ | ✗ | core (demo script) + UI |
| — | Login, session restore | ✅ | ◐ (non-403 errors still read "Could not reach the homeserver") | UI |
| — | Sign out, red with confirm | ✅ | ◐ (no confirm or warning) | UI |

### Room list, header, room info
| | Feature | Android | Desktop | Work |
|---|---|---|---|---|
| R1 | Generated agent avatars with badge, status dot on the avatar, two-line rows | ◐ (initials, three lines, runtime still in the row) | ✅ after M4 (avatar tiles with badge, dot on the avatar, two lines; `avatarView.test.ts`, RoomAvatar/RoomRow stories; seen in the running app, read-only) | core (`AgentAvatarStyle`) + UI |
| R2 | Mark read / mute / pin from the row | ✗ (mute and pin only in room info) | ✗ | Android UI; desktop cmd |
| R3 | Filters (iOS now: All, Unread) | ✗ | ✗ | core + UI |
| — | Arrangement Recent / Waiting | ◐ (fixed at Waiting; setters unused) | ✅ | UI |
| — | Invitations as a row, with inviter | ◐ | ◐ (no inviter, no report) | desktop cmd (`room_inviter`) |
| — | New room / DM with known people first | ✅ | ◐ (raw id list) | cmd (`known_people`, `direct_room_with`) |
| A1 | Header status pill (Working / Needs you / Active / Idle / Quiet) | ✗ — **no room header at all**, and no way into room info from inside a room | ✅ after M4 (header pill or member count; `roomHeaderView.test.ts`, StatusPill stories; Quiet pill and "2 members" seen in the running app; Working pill live pending, owner) | core (`RoomStatus`) + UI |
| D11 | One name per person (header, typing, sender) | ◐ (typing line ignores the cast) | ✅ after M4 (typing line and live card named from the room's cast; `the typing line is asked with the room's cast`, `writerName` tests; live pending, owner) | core (`RoomCast`) + UI |
| — | Room info: members, runtime, mute/pin, Leave with confirm, block/report | ◐ (no block or report) | ◐ (no Leave, runtime, mute/pin, block or report) | Android wrap; desktop UI + cmd |

### Timeline
| | Feature | Android | Desktop | Work |
|---|---|---|---|---|
| T1 | Agent message card (avatar, name, Agent badge, soft surface) and serif long-read view | ✗ | ✅ after M4 (agent card, "Agent" tag, serif Read dialog; `messageView.test.ts`, Timeline/LongReadDialog stories; seen in the running app at 1920, 948, 632 Largest) | core (long-read rule) + UI |
| T2 | Rhythm (2pt within a run, 12pt between turns) | ◐ (2/8dp) | ◐ (16/24px) | UI |
| T3 | Reactions on the bubble corner; receipts as small faces | ◐ ("Read by …" sentences) | ◐ ("Seen by N") | UI |
| T4 | Reveal every message's time | ✗ | ✗ (no hover time) | UI |
| T5 | Tap a quote to jump to it and highlight | ✗ | ✗ | UI |
| T6 | "What I did" footer and "Thought for 12s · 2 steps" in the answer row | ◐ (record lost on room switch) | ✅ after M4 (`recordAnchor.test.ts`, WhatIDid stories; a real turn is in the owner's live pass) | core (`TurnRecordLabel`, retention) + UI |
| D5 | Membership churn collapsed across verbs, expandable | ◐ (same verb only) | ✅ after M1b (`timelineGrouping.test.ts`, MembershipStretch stories) | **core** (three copies today) |
| D6 | Reply quote hidden when the parent is directly above | ✗ | ✅ after M4 (`a reply under its parent drops the quote`) | core + UI |
| D7 | Quiet metadata | ◐ (raw event type on decision cards) | ◐ | UI |
| — | Labelled new-messages line | ◐ (unlabelled divider) | ✅ (plus "Jump to unread") | UI |
| — | Day separators, jump to newest, turn error card, voice notes and transcripts, m.notice rows | ✅ | ✅ | — |
| — | Images with captions | ◐ (**B3**) | ✅ | UI |
| — | Sender faces | ✗ (`Session.faces` unused) | ✗ (avatar in DTO, not drawn) | UI |
| — | Undecryptable placeholder with recovery hint | ◐ | ✅ | UI |
| — | Message menu: react, reply, copy, edit, delete, report; swipe to reply | ◐ (long-press silently edits or replies; no menu, copy, delete or report) | ◐ (reply and react only; edit/delete commands have no wrapper) | Android UI + wrap; desktop wrap + cmd |
| — | Streaming text smoothing | ◐ (no markdown while streaming) | ✅ | UI |

### Agents and decisions
| | Feature | Android | Desktop | Work |
|---|---|---|---|---|
| A2 | Live turn as one activity card in the timeline: step, elapsed time, step count, markdown | ◐ | ✅ in code after M4 (one live card in the timeline; `liveTurn.test.ts`, `live.test.ts`, `liveCardView.test.ts`, probe story); live pending, owner | core (elapsed time, step summary) + UI |
| A3 | "Sent to X" → "X is on it" | ✗ | ✅ in code after M4 (`ackView.test.ts`, AcknowledgementDock stories; the core decides the words); live pending, owner | core (`Acknowledgement`) + UI |
| A4 | Stop | ⛔ | ⛔ | AgentPod cancel event first |
| G1 | Decision card: headline, Details disclosure, full-width Approve, Request changes with typed feedback, Reject last, receipt | ◐ (has typed feedback; flat layout; no failure state) | ◐ (one button per option; no typed feedback; silent failure) | UI |
| — | Permission requests (Allow once / Reject) | ✗ (**B1**) | ◐ (sent as a plain message, not the shared builder) | Android wrap; desktop cmd |

### Composer
| | Feature | Android | Desktop | Work |
|---|---|---|---|---|
| C1 | Floating capsule, attach inside, send ⇄ mic | ✗ (stock Material row) | ◐ | UI |
| C2 | Voice message recording | ✗ (no RECORD_AUDIO) | ✗ | Android wrap (`attachmentMarkVoice`); desktop cmd |
| C3 | Image thumbnail in the composer; errors on the chip | ◐ | ◐ | UI (desktop may need preview bytes) |
| C4 | `@` picker for people and agents with status | ✗ (`collectMentions` gets an empty member list) | ◐ (agents not marked) | UI |
| D12 | Failed send keeps the text; text never sent without its attachment; a failed file must be attached again (the core consumes it, as on iOS) *Superseded 2026-10-22, during M1 planning: iOS re-stages a failed attachment (`StagedAttachment.swift:143-178`); the M0 correction was wrong; tracked as M1.7.* | ◐ (**B2**) | ✅ (corrected during M0) | — |
| — | Captions, per-room drafts, reply banner | ✅ | ✅ | — |
| — | Edit mode | ✅ | ✗ | desktop wrap |
| — | Any-file attach | ◐ (images and video only) | ✅ (plus drag and drop) | UI |

### Notifications, safety, settings, motion
| | Feature | Android | Desktop | Work |
|---|---|---|---|---|
| P2 | Local notifications with actions (answer gates and permissions without opening the app) | ✗ | ✗ (no notification plugin) | Android UI over existing FFI; desktop plugin + cmd |
| P1 | Background push | ✗ (no FCM / UnifiedPush) | n/a | Android: client side is small (`registerPusher` is platform-neutral), but **the hub gateway's FCM support is unverified** (AgentPod) |
| P3 | Live Activity analogue (ongoing notification / Live Updates) | ✗ | n/a | UI (local) or hub work (remote) |
| P4 | Widgets (Needs you count, agent status) | ✗ (Glance) | n/a; title or tray count also missing | UI over the core's `widget*` functions |
| — | Block / unblock, blocked list | ✗ (event handler is a no-op: "iOS-only for now") | ✗ | Android wrap; desktop cmd |
| — | Report message / room / user | ✗ | ✗ | Android wrap; desktop cmd |
| — | Recovery: enable, restore, new key at sign-in | ✅ | ◐ (**B5**; no `ensure_recovery`) | desktop UI + cmd |
| — | Search with this-room / everywhere scope | ◐ (scope never used) | ◐ (no room scope) | UI |
| — | Account panel: identity, appearance, chats settings, blocked list, help links | ◐ (identity and recovery only) | ✗ (two menu items) | UI; desktop cmd (`account`) |
| — | Themes: System / Light / Dark, Black, accents | ◐ (follows the system; tokens generated, unused) | ✗ (tokens exist; nothing sets `data-appearance`) | UI |
| — | Connection status | ✗ (store fed, never drawn) | ✅ | UI |
| M1 | Motion: messages spring in, cards expand, decision → receipt | ◐ | ◐ | UI |
| M2 | Haptics | ✗ (no calls at all) | n/a | UI |

**Where the other platforms are ahead:**
- Desktop has:
  - drag-and-drop attach;
  - save and download of media;
  - a searchable emoji picker;
  - "Jump to unread";
  - in-app matrix.to routing;
  - the room aliases list;
  - space invitations in the rail.
- Android has nothing user-facing that iOS lacks.

## 4. Recommended order

The aim is that every step is visible to a daily user. Each step should also
shrink the next one, not duplicate it.

1. **Bugs (B1–B7). About a day per platform, no design.** Android:
   - answer permission requests;
   - send an attachment without text;
   - load images and sender faces;
   - call `scenePhaseChanged`;
   - draw the connection status.

   Desktop:
   - wrap `edit_message`/`delete_message` and add Copy/Edit/Delete to the menu;
   - set up recovery at sign-in (`ensure_recovery`);
   - surface send failures.

   Fix the README rows.
2. **Move the iOS presentation rules into the core (core, M).** Move:
   - `RoomStatus`, `NeedsYouInbox`, `AgentDirectory` and `RosterFilter`;
   - `Acknowledgement` with `RoomCast`;
   - `TurnRecordLabel` and the 20-room turn-record retention;
   - `AgentAvatarStyle`;
   - the long-read and quote-hiding rules;
   - cross-verb churn collapse;
   - the first-run demo script.

   Expose them through UniFFI and Tauri, and switch iOS to the core versions so
   there is one implementation. Each move can be its own PR with its tests.
3. **The agent experience on both platforms (UI, M each).** Room header with
   status pill (Android needs the header itself), agent cards (T1), one live card
   inside the timeline (A2), "is on it" (A3), turn record and "What I did" (T6).
   This is what the revamp was for, and the most visible difference today.
4. **Navigation (UI, M each).** Needs you, Agents, Unread filter. On Android:
   a navigation bar or rail, the spaces menu, and the room-list swipe actions. On
   desktop: sidebar destinations and row context menus (mute and pin need commands).
5. **Decisions (UI, S each).** G1 layout on both, typed feedback on desktop, and
   the shared permission builder on desktop (`send_permission_answer` command).
6. **Notifications.** Android local notifications with answer actions over the
   existing FFI (M). Desktop notification plugin with a window-title or tray
   count (M). **Android background push needs a decision first:** FCM through the
   AgentPod hub (unverified there) or UnifiedPush. That is cross-repo and should
   go through `charter/`.
7. **Composer (UI, M).** Android:
   - capsule layout;
   - `@` picker;
   - voice recording;
   - thumbnail;
   - any-file attach.

   Desktop: mic and recording (needs a command), thumbnail, errors on the chip.
8. **Safety, account and appearance (S–M).** Block and report on both (wrappers
   on Android, commands on desktop), the account panel, the theme and accent
   chooser (tokens already generated on both), sign-out confirmation on desktop.
9. **Polish.** T3–T5, D6/D7, rhythm, motion, Android haptics, the welcome and
   demo (O1), then Android widgets and the ongoing-notification analogue to Live
   Activity.

Each UI step should ship with previews or stories for the new states, as the
repo requires. Instrumented tests should run under `scripts/android-ci-parity.sh`
at both heights. Desktop claims should be checked in the running app over the
embedded WebDriver before they are believed.

## 5. Open questions

- **Android push transport.** FCM through the hub, or UnifiedPush? This needs
  AgentPod's view of its gateway; the local agentpod checkout mentions FCM only
  in archived docs.
- **Phone navigation.** Should Android follow iOS's four tabs, or Material's
  navigation suite (bar on phones, rail on tablets)? The spec doesn't say.
- **Desktop shape.** Should the desktop "Needs you" and "Agents" be sidebar
  destinations, roster sections or both? It's a layout decision, not a copy of
  the phone tabs.
- **Runtime verification.** None of this was exercised. A device and app pass
  per step is part of the work, not a separate phase.
