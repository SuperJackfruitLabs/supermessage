# Android and desktop against iOS — parity verification

**Status:** verification record, 23 Oct 2026, against `main` at `c949915` (after PR #148). A
dated record: it will not be edited to look current, except that the rows marked "pending #149
merge" are finalised once Android M7 merges.

**Supersedes:** [the 2026-10-22 parity matrix](../2026-10-22-ios-parity/README.md), which
assessed `main` at `f64629f`, before any of the parity work. That matrix is left as written. It
is now out of date in most rows: between 22 and 23 Oct the roadmap
(`docs/superpowers/plans/2026-10-22-ios-parity-roadmap.md`) merged the platform milestones M0
and M2 to M7 on desktop and M0 and M2 to M6 on Android, plus the core's M1 moves and two core
batches ([#140], [#141]). Android M7 is in review. This record
is the M8 step of that roadmap: the matrix's audit repeated against `main`, the G5 runtime pass
as far as it could be run, and the result per row.

**Question it answers:** for each row of the matrix, does desktop and does Android now match
iOS, what evidence says so, which milestone changed it, and what is still unverified?

**Audience:** the maintainer deciding whether the parity work is done, and what to run by hand.

**Working evidence:** the per-platform audit halves (the iOS reference and the platform's
file:line for every row) and the desktop screenshots are kept in the maintainer's working notes,
not published, because they record a live account. Section 9 lists where this record corrects
them.

## 1. The short version

- **No row is missing on either platform.** Every matrix row is now at parity, partial with a
  stated reason, or not applicable with an agreed equivalent. Desktop: 52 at parity, 3 partial,
  5 not applicable or blocked, of 60. Android: 57 at parity (two of them pending #149 merge),
  2 partial, 1 blocked, of 60. The seven bugs of the matrix's section 2 are all closed.
- **That is a source-and-test verdict on Android, not a runtime one.** G5 asked for every row
  to be run once on the phone AVD, the tablet AVD and desktop Linux. Desktop Linux was run,
  read-only, against the owner's live account. **No Android row has ever been seen against an
  account**: the emulator has never been signed in, and agents have no credentials. The tablet
  AVD has never been run by any milestone. G5 is therefore met for desktop Linux only.
- **Every write is unverified on both platforms.** Sending, answering, deleting, blocking,
  reporting, leaving and signing out were never exercised against the live account, by rule.
  Section 10 is the consolidated list the owner has to run.
- **One real defect, in the core, on every platform including iOS (D1):** a pending decision
  never lights Needs you. The cards render and can be answered in their room, but the Needs you
  inbox, its badge, the header pill's Needs you state, the roster dot, the desktop title count
  and tray dot, and the Android widget can show only invitations. Fixing it is an owner
  decision (section 6).
- **The desktop live pass broke its read-only rule:** opening rooms marked five rooms read on
  the owner's account (section 7).

## 2. Method

**Audit (both platforms).** The matrix's method, repeated: for every row, re-find the iOS
reference in the current tree, then compare the platform's source with it. The iOS line numbers
in the matrix were stale, because many rules moved into the core in M1, so the iOS references
were re-found first (in the desktop half; `apple/` did not change between its base and
`c949915`). Desktop was read at `9968c6b`, Android at `c949915`; `git diff --stat 9968c6b
c949915 -- src src-tauri crates apple` is empty, so both readings describe `c949915`. Android
M7 is not on `main`; its rows were read from `origin/parity/m7-android` at `d0cb39a`.

**G5 runtime pass, desktop Linux.** A debug build from this worktree, `pnpm dev`, and the
embedded WebDriver on `127.0.0.1:4445`, on KDE Wayland, signed in to the owner's account. Every
row with something to see signed in was looked at, read-only, and 35 screenshots were taken.
They are kept in the working notes and not published, because they show a live account. Local
settings that were changed to look at a state (roster filter, appearance mode, the demo flags)
were restored and checked afterwards. Nothing was sent: the edit, report and sign-out flows were
opened and cancelled. The exception is in section 7.

**G5 runtime pass, Android.** Not run. No emulator was started and Gradle was not run for M8.
Every Android verdict is from source, with the evidence column naming the instrumented, JVM or
Roborazzi test that covers the row, as recorded in the M2 to M7 verification tables and checked
against the code. Those tests were not re-run for M8. Their recorded counts: M6, 599
instrumented tests passing at both heights; the M7 branch, 605 instrumented with no failures at
both heights, 432 kit unit tests and 466 app unit tests. M6 merged as #148 on green CI, with all
three instrumented-test shards passing.

**What could not be run, and why:**

| Not run | Why |
|---|---|
| Any Android row against an account | The emulator has never been signed in; agents have no credentials, and the owner's account is read-only to them |
| The tablet AVD | Never run by any milestone; G5 requires it |
| Android on a real phone | Needed for voice levels, haptics, lock-screen answers, push in Doze and TalkBack |
| Every write, on both platforms | The owner's account is read-only to agents (owner ruling M3 Q14) |
| A real agent turn | Starting one needs a send |
| Desktop on macOS and Windows | G5 leaves them to CI. PR CI does not build the desktop app for either (section 11) |
| Desktop on GNOME | Only KDE was available |
| Desktop voice recording | This machine lacks `gst-plugins-good`, so WebKitGTK's `MediaRecorder` cannot start |
| Desktop at 600 px, 1905 px and 150 % text | Not repeated: M2 to M5 recorded them, and nothing since changed the layout code |
| iOS | Built on CI only; never run here |

**Legend.**
- ✅ at parity with iOS; ⚠️ partial; ❌ missing; ➖ not applicable on that platform, or blocked
  on every platform.
- **RUN**: seen in the running desktop app on 23 Oct, read-only. **RUN (M5)**: seen by an
  earlier milestone signed out, not re-run. **TEST**: covered by named unit or instrumented
  tests, stories or previews only.
- **D1**: the row's UI is at parity, but a real pending decision cannot reach it (section 6).
- "Unchanged" means the matrix already had the platform at parity.

## 3. Results by verdict

| | Desktop | Android |
|---|---|---|
| ✅ parity | 52 | 57 (P3 and P4 pending #149 merge) |
| ⚠️ partial | 3: C2 voice, C3 composer thumbnail, P2 notifications off Linux | 2: C2 voice waveform, P1 push needs a distributor |
| ❌ missing | 0 | 0 |
| ➖ n/a or blocked | 5: deep links, A4 Stop, P1, P3, M2 haptics | 1: A4 Stop |
| Rows with live evidence | most, read-only (see the Evidence column) | none |
| Matrix bugs | B5, B6 closed | B1, B2, B3, B4 closed |

The working halves count 62 desktop rows and 65 Android rows: they list the matrix's bugs as
rows, and the Android half splits O1 into the welcome and the demo. The verdicts are the same.

## 4. Row by row

The "Changed by" column names the milestone and PR that moved the row since the matrix. The
working notes have the file:line for each row on iOS and each platform.

### Navigation and onboarding

| | Feature | Desktop | Android | Changed by |
|---|---|---|---|---|
| N1 | Chats / Needs you / Agents / Search | ✅ RUN | ✅ TEST | M5 ([#142], [#147]) |
| N2 | Needs you inbox, badge, "caught up" | ✅ RUN, D1 (lists the 10 invitations, no decisions) | ✅ TEST, D1 | M5 ([#142], [#147]); core views M1.1 [#130] |
| N3 | Agents view, status, last activity | ✅ RUN (15 agents, all Quiet) | ✅ TEST | M5 ([#142], [#147]); M1.1 [#130] |
| — | Adaptive multi-pane shell | ✅ RUN, unchanged | ✅ TEST; tablet AVD not run | Android M5 [#147] |
| — | Spaces switching | ✅ RUN, unchanged | ✅ TEST | Android M2 [#135] (menu); readable names M1.6 [#134], M5 [#147] |
| — | Deep links (`supermessage://`) | ➖ (matrix.to routed in-app) | ✅ RUN (M5) signed out, TEST | Android M5 [#147] |
| O1 | Welcome and first-run demo | ✅ RUN (welcome; the local demo over the live app) | ✅ RUN (M5) welcome signed out; demo TEST | M5 ([#142], [#147]); demo script M1.6 [#134] |
| — | Login, session restore | ✅ RUN (restore); error words TEST | ✅ TEST, unchanged | Desktop M0 [#127] |
| — | Sign out, with confirm | ✅ RUN (confirm opened, cancelled) | ✅ TEST, unchanged | Desktop M2 [#132] |

### Room list, header, room info

| | Feature | Desktop | Android | Changed by |
|---|---|---|---|---|
| R1 | Agent avatars, badge, status dot, two lines | ✅ RUN (no dot seen: every room was Quiet) | ✅ TEST | M4 ([#139], [#143]); style M1.5 [#128] |
| R2 | Mark read / mute / pin from the row | ✅ RUN (menu opened, nothing chosen) | ✅ TEST | Desktop M3 [#133], Mark read M6 [#144]; Android M2 [#135], Mark read M5 [#147]; core [#141] |
| R3 | Filters (All, Unread) | ✅ RUN | ✅ TEST | M5 ([#142], [#147]) |
| — | Arrangement Recent / Waiting | ✅ RUN, unchanged (adds Machine) | ✅ TEST | Android M2 [#135] |
| — | Invitations as a row, with inviter | ✅ RUN | ✅ TEST | M3 ([#133], [#137]) |
| — | New room / DM, known people first | ✅ RUN | ✅ TEST, unchanged | Desktop M3 [#133] |
| A1 | Header status pill | ✅ RUN (Quiet only), D1 | ✅ TEST, D1 | Desktop M4 [#139]; Android header M2 [#135], pill M4 [#143]; status M1.1 [#130] |
| D11 | One name per person | ✅ RUN | ✅ TEST | M4 ([#139], [#143]); cast M1.2 [#131] |
| — | Room info: members, runtime, mute/pin, Leave, block/report | ✅ RUN | ✅ TEST | M3 ([#133], [#137]) |

### Timeline

| | Feature | Desktop | Android | Changed by |
|---|---|---|---|---|
| T1 | Agent card, Agent badge, long read | ✅ RUN | ✅ TEST | M4 ([#139], [#143]) |
| T2 | Rhythm (2 within a run, 12 between) | ✅ RUN (2/12 px) | ✅ TEST (dp not re-measured) | M2 ([#132], [#135]) |
| T3 | Reactions on the corner, receipts as faces | ✅ RUN (reaction); faces TEST | ✅ TEST | M2 ([#132], [#135]) |
| T4 | Reveal every message's time | ✅ RUN (shown times); hover TEST, as WebKitGTK ignores synthetic hover | ✅ TEST | M2 ([#132], [#135]) |
| T5 | Tap a quote to jump and highlight | ✅ RUN | ✅ TEST | M2 ([#132], [#135]) |
| T6 | "What I did", "Thought for 12s · 2 steps" | ✅ RUN (an older turn record); the new footer TEST | ✅ TEST | M4 ([#139], [#143]); record M1.3 [#134] |
| D5 | Membership churn collapsed across verbs | ✅ RUN | ✅ TEST | Core M1.4 [#138] |
| D6 | Reply quote hidden under its parent | ✅ RUN | ✅ TEST | Core M1.4 [#138] |
| D7 | Quiet metadata | ✅ RUN | ✅ TEST | M2 ([#132], [#135]) |
| — | Labelled new-messages line | ✅ RUN, unchanged | ✅ TEST | Android M2 [#135] |
| — | Day separators, jump to newest, turn error card, voice notes, transcripts, m.notice | ✅ RUN (day separators, jump); the rest TEST | ✅ TEST | Unchanged |
| — | Images with captions | ✅ TEST, unchanged (no image in the rooms opened) | ✅ TEST | Android M0 [#127] (B3) |
| — | Sender faces | ✅ RUN | ✅ TEST | M2 ([#132], [#135]), M4 ([#139], [#143]) |
| — | Undecryptable placeholder | ✅ TEST, unchanged | ✅ TEST | Android M2 [#135] |
| — | Message menu (react, reply, copy, edit, delete, report; swipe to reply) | ✅ RUN (opened and cancelled); swipe ➖ | ✅ TEST | Desktop M0 [#127] (Copy, Delete), M2 [#132] (Edit), M3 [#133] (Report); Android M2 [#135], Report M3 [#137] |
| — | Streaming text smoothing | ✅ TEST, unchanged | ✅ TEST | Android M2 [#135] |

### Agents and decisions

| | Feature | Desktop | Android | Changed by |
|---|---|---|---|---|
| A2 | Live turn as one card in the timeline | ✅ TEST | ✅ TEST | M4 ([#139], [#143]) |
| A3 | "Sent to X", then "X is on it" | ✅ TEST | ✅ TEST | M4 ([#139], [#143]); M1.2 [#131] |
| A4 | Stop | ➖ blocked on AgentPod | ➖ blocked on AgentPod | None: no cancel event exists |
| G1 | Decision card | ✅ RUN (three live cards, none pressed) | ✅ TEST | M2 ([#132], [#135]); desktop failure line M0 [#127] |
| — | Permission requests (Allow once / Reject) | ✅ TEST | ✅ TEST | Desktop M3 [#133]; Android M0 [#127] (B1) |

### Composer

| | Feature | Desktop | Android | Changed by |
|---|---|---|---|---|
| C1 | Floating capsule, attach inside, send ⇄ mic | ✅ RUN | ✅ TEST | M2 ([#132], [#135]) |
| C2 | Voice recording | ⚠️ RUN (mic offered); cannot record on this machine | ⚠️ TEST; waveform never seen with real levels | M6 ([#144], [#148]) |
| C3 | Image thumbnail, errors on the chip | ⚠️ errors on the chip TEST; no thumbnail | ✅ TEST | M2 ([#132], [#135]) |
| C4 | `@` picker, agents marked | ✅ RUN | ✅ TEST | M2 and M4 |
| D12 | Failed send keeps the text; attachment rule | ✅ TEST | ✅ TEST | M0 [#127] (B2); re-staging M1.7 [#129] |
| — | Captions, per-room drafts, reply banner | ✅ TEST, unchanged | ✅ TEST, unchanged | None |
| — | Edit mode | ✅ RUN (opened, cancelled) | ✅ TEST, unchanged | Desktop M2 [#132] |
| — | Any-file attach | ✅ RUN (button; picker not opened), unchanged | ✅ TEST | Android M2 [#135] |

### Notifications, safety, settings, motion

| | Feature | Desktop | Android | Changed by |
|---|---|---|---|---|
| P2 | Local notifications with actions | ⚠️ Linux full (M6's real-server test on KDE; not re-run); macOS and Windows informational | ✅ TEST | M6 ([#144], [#148]); core policy [#140] |
| P1 | Background push | ➖ | ⚠️ UnifiedPush only, needs a distributor | Android M6 [#148] |
| P3 | Live Activity analogue | ➖ (the tray's working line stands in, per G4) | ✅ ongoing notification for a running turn, **pending #149 merge** | Desktop M7 [#146]; Android M7 (#149) |
| P4 | Widgets, counts | ✅ RUN (title, tray menu read over dbusmenu), D1 | ✅ Glance widgets, **pending #149 merge**, D1 | Desktop M6 [#144] (title), M7 [#146] (tray); Android M7 (#149); widget view [#140] |
| — | Block / unblock, blocked list | ✅ RUN (member actions present) | ✅ TEST | M3 ([#133], [#137]) |
| — | Report message / room / user | ✅ RUN (sheet opened, cancelled) | ✅ TEST | M3 ([#133], [#137]) |
| — | Recovery: new key at sign-in | ✅ RUN (panel) | ✅ TEST, unchanged | Desktop M0 [#127] (B5) |
| — | Search, this room or everywhere | ✅ RUN (read-only searches) | ✅ TEST | M2 ([#132], [#135]); desktop destination M5 [#142] |
| — | Account panel | ✅ RUN | ✅ TEST | M2 and M3 |
| — | Themes: System / Light / Dark, Black, accents | ✅ RUN (cycled, then restored) | ✅ TEST | M2 ([#132], [#135]) |
| — | Connection status | ✅ RUN (no line while connected), unchanged | ✅ TEST | Android M0 [#127] |
| M1 | Motion | ✅ TEST | ✅ TEST | M2 ([#132], [#135]) |
| M2 | Haptics | ➖ | ✅ TEST (which haptic is requested, not that a motor fires) | Android M2 [#135] |

### The matrix's bugs

| # | Platform | Bug | Now | Closed by |
|---|---|---|---|---|
| B1 | Android | Permission requests could not be answered | ✅ | M0 [#127] |
| B2 | Android | An attachment needed text | ✅ | M0 [#127] |
| B3 | Android | Images never rendered | ✅ | M0 [#127] |
| B4 | Android | App lifecycle not wired | ✅ (the observer itself has no test of its own) | M0 [#127] |
| B5 | Desktop | No recovery set-up at sign-in | ✅ | M0 [#127] |
| B6 | Desktop | A failed decision send was silent | ✅ | M0 [#127], M2 [#132] |
| B7 | Docs | README overstated Android | ✅ | M0 [#127]; README rows kept by each milestone since |

## 5. Partial and not-applicable rows, and why

| Row | Platform | Verdict | Reason |
|---|---|---|---|
| C2 voice | Desktop | ⚠️ | Built and tested (`voiceRecorder.test.ts`, stories). The mic is offered on Linux, but this machine lacks `gst-plugins-good`, so recording cannot start and no note was ever recorded. The mic is hidden wherever the webview cannot record Ogg/Opus, which is expected on macOS and Windows (not probed). |
| C2 voice | Android | ⚠️ | Built and tested, and one real recording was made on the emulator's virtual microphone. On API 36 `MediaRecorder.getMaxAmplitude()` throws, and the fallback `AudioRecord` meter reads silence there, so the waveform has never shown real levels. Needs a real phone. |
| C3 thumbnail | Desktop | ⚠️ | Failures are drawn on the chip, as on iOS. A staged image has no preview: M2 deferred the preview bytes to the core. The owner can decide whether one is wanted. |
| P2 notifications | Desktop | ⚠️ | Linux has actions, replacement and withdrawal. macOS and Windows use `tauri-plugin-notification` without actions: clicking does nothing, and sign-out cannot withdraw a note. Neither was ever compiled on a branch. |
| P1 push | Android | ⚠️ | UnifiedPush only, the G3 outcome: push works only with a distributor such as ntfy installed. iOS needs nothing installed. Hub FCM is an AgentPod charter item (section 11). |
| Deep links | Desktop | ➖ | Desktop routes matrix.to in-app and has no widgets to link from. |
| P1 push | Desktop | ➖ | No background push on desktop; the app runs, or keeps running in the tray. |
| P3 | Desktop | ➖ | Per G4, the tray's working line stands in for the Live Activity. The turn-ended note was dropped (section 8). |
| M2 haptics | Desktop | ➖ | No haptics on desktop. |
| A4 Stop | Both (and iOS) | ➖ | Blocked until AgentPod defines a cancel event; no platform has a Stop. |
| Swipe to reply | Desktop | ➖ | Within the message-menu row: desktop replies from the menu. |

## 6. Defects

### D1. A pending decision never lights Needs you, on any platform, iOS included

**Seen on desktop.** A room on the owner's account shows three live decision cards
reading "Awaiting your decision", with Approve, Request changes and Reject. At the same time the
Needs you inbox lists only the 10 invitations, with no decisions and no badge. The window title
stays "supermessage", the tray says "Nothing needs you", the room header shows "2 members" and
no Needs you pill, and the roster row previews the message text, not a pending line.

**Cause, in the core.** Every Needs you surface reads the roster's `AgentState::NeedsYou`, which
comes only from `RoomPreview.pending`:
- `crates/supermessage-core/src/roster.rs:134-135` (`state_for`) returns Needs you only when
  `preview.pending` is set; `roster.rs:447-466` (`needs_you_inbox`) builds the inbox's
  decisions and badge from that state.
- `crates/supermessage-core/src/room_preview.rs:78-86` sets `pending` only when the room's
  **last event type** is in the decision set.
- `room_preview.rs:16-24`: `decision_bearing_event_types()` returns an empty set. Its comment
  says it must stay empty because "there is no gate schema to send yet".
- `room_preview.rs:239-247` pins it empty with a test.

The comment is out of date: `dev.agentpod.permission.v1` and `dev.superpipeline.gate.v1` exist
([`docs/agentpod-events.md`](../../agentpod-events.md), around line 279), and gate cards render.
Adding the types would not be enough on its own, either. The hub is moving the decision onto the
prose message as a key, and a check on the last event's type can never match that form.

**Reach.** iOS reads the same core function
(`apple/SupermessageKit/RosterArrangement.swift:86-88` calls `rosterNeedsYouInbox`), so this is
parity-neutral: all three platforms are equally affected. The affected surfaces are N2 (inbox
and badge), A1 (the pill's Needs you state), the R1 status dot, the desktop title count and tray
dot (P4), and the Android Needs you widget (P4, M7). The cards themselves render and can be
answered in their room, and notifications are worded by the core's own path, so neither is
affected. The Needs you surfaces are proven only with fake rows (`inboxView.test.ts`,
`NeedsYouTest`, `theBadgeCountsDecisionsOnly` and the like).

**Not fixed, and an owner decision.** The likely fix is to decide "pending" from the core's own
decision state (the one the timeline's decision card uses to tell "Awaiting your decision" from
answered), not from the last event type, so that an answered gate stops counting. It is a core
change that alters iOS, and "amber means a pending decision" is a contract shared with
Superpipeline. That is why it was left out of the parity work. Until it is decided, no Needs you
count, badge, pill, dot, title count or widget can pass a live check.

### Other defects and known gaps

- **Android M6, known open (from its verification table, not reproduced in M8):**
  - An answer past its 9 s budget posts "wasn't sent" only if it later definitely fails, so a
    hung send can end in silence.
  - With notifications revoked, the pusher stays registered, so room ids, event ids and counts
    keep flowing to the distributor's server.
  - `remotePush` is false in a fresh process until the next registration, so the roster path
    and a push can both post one message.
  - Recording starts even when audio focus is refused, and a hold the system cancels sends
    instead of cancelling.
  - A session revoked remotely while the app is dead runs no sign-out hook.
- **Android Mark read from the row** calls `markRoomReadFromList`, which builds a timeline per
  call and has never run against a real room.
- **Android M7 was NOT READY at its whole-branch review.** Widgets read the snapshot once per
  Glance session, so a write within about 45 s is never drawn. A widget's failure shows amber,
  including after sign-out. A push across sign-out merges the old account into the snapshot. The
  ongoing card survives sign-out. One decision can be answered twice across surfaces. A fix round
  is running; P3 and P4 must be re-checked after #149 merges.
- **Desktop notifications:** a note posted while 16 others are waiting, or on a notification
  server without actions, cannot be clicked to open its room. A single shared click listener
  would lift this, but that is a redesign.
- **iOS, double answer across surfaces:** a decision answered from its notification stays
  answerable from the widget, and the reverse. Android fixed this in M7 (an accepted answer
  cancels the notification and marks the widget card sent); iOS still has the gap.

Observations, not defects:
- The desktop Machine arrangement heads its first section "Elsewhere 14 agents" above a list
  that includes people's rooms. The wording is the core's.
- Roster "Mark read" is offered on a room with nothing unread, as on iOS.
- A `supermessage://` link to a room outside the selected space shows "Room unavailable" on
  Android, as on iOS. Whether to switch the space instead is still an open question.
- The narrow "All" chip on Android has a wider gap than iOS's 8 dp.

## 7. The read-only breach in the desktop live pass

The desktop live pass was meant to send nothing. It sent read markers. **Five rooms on the
owner's account were marked read**, one of them with 4 unread messages. Read markers cannot be
unsent.

Opening a room marks it read by design (`src/lib/components/Timeline.svelte:789-821`,
`readTracking.ts`). The agent installed a page-level guard that rejected `mark_room_read` over
`ipc://`, believing that blocked it. It did not: when the custom-protocol fetch fails, Tauri
falls back to `window.ipc.postMessage`, which was not wrapped, so the command still ran. The
evidence is in the working notes' screenshots: one room showed "4 unread" before it was opened
and none after, and the Unread chip went from 12 to 11 after a restart. From the second session on,
`window.ipc.postMessage` was guarded too, and nothing further was blocked or sent.

No message, reaction, edit, delete, report, block, mute, pin, leave, join, sign-out or answer
was sent. Earlier milestones accepted "opening a room" as a read-only action, but the M8 brief
forbade it, so this is recorded as a breach.

**The rule from now on:** a live pass wraps both IPC paths before the first room opens, or never
opens a room with unread messages.

## 8. Owner rulings and deliberate divergences

Where a platform differs from iOS on purpose. The decision gates are the roadmap's; the rest
were taken during the milestones, by the owner or by default on the stated recommendation, and
the owner can reverse any of them.

**Decision gates:**
- **G1, Android navigation:** Material 3's `NavigationSuiteScaffold`, with a bottom bar on
  phones and a rail on tablets, and iOS's four destinations and badge rule.
- **G2, desktop navigation:** sidebar destinations above the roster, with Search among them, so
  it can be reached with no room open.
- **G3, Android push:** UnifiedPush with an `event_id_only` pusher, because the hub has no FCM.
  Hub FCM becomes an AgentPod charter item.
- **G4, desktop system surfaces:** a window-title count and a tray with the Needs you and
  working lines, in place of widgets and the Live Activity.
- **G5, what counts as verified:** met for desktop Linux only (section 2).

**Divergences and rulings:**
- **The desktop turn-ended notification was dropped, following iOS.** A failed-turn note doubled
  the agent's own message, and fired even on steps the agent recovered from. iOS alerts only for
  Needs you, so desktop now does the same. It can return once the core can tell real news apart
  (section 11). The Android ongoing notification likewise shows the running turn only, with no
  "finished" note.
- **Widget ages follow iOS's `WidgetAge` exactly:** "4m", "2h", "3d", with a one-minute minimum
  (`DateComponentsFormatter`'s abbreviated style). An earlier ruling of "4 min" was wrong and was
  corrected.
- **Android failure notices are never amber.** The core files "Your answer wasn't sent" as a
  decision; Android posts it without amber, as iOS does, because amber means a pending decision
  only.
- **Android chips** are drawn at iOS's 28 dp, with a 48 dp touch target (Android's
  accessibility minimum).
- **Android navigation-bar labels** stop growing at 1.3× text size, so "Needs you" is not cut
  off. TalkBack still reads the full label. This departs from Material guidance, and is close to
  iOS's fixed tab-bar labels.
- **Back on Needs you, Agents or Search** goes to Chats first on Android.
- **Desktop tray on GNOME without AppIndicator:** "keep running in the tray" is disabled where
  no StatusNotifier watcher runs, so the window can never hide into a tray that cannot be seen.
- **Arrangement:** desktop keeps a third, Machine, arrangement; Android folds Machine into
  Waiting.
- **Platform idioms, not gaps:** row actions are a context menu on desktop and a swipe on
  Android; time reveal is hover on desktop and a swipe on Android; spaces are a rail on desktop
  and a menu on Android; desktop also has drag-and-drop attach.
- **Notifications follow the selected space on every platform**, because the core filters the
  roster stream by space. Desktop follows iOS here.
- **A plain "decision" notification has no answer buttons on any platform**; the core's only
  action for it is "Open". Permission and gate notifications do have Approve, Allow once and
  Reject.
- **The "Stopped" word for a timed-out turn comes from the core**, so all three platforms say
  the same.
- **Excluded from Android M7 by plan:** lock-screen widget families, background roster refresh
  and agent faces on widgets (iOS draws none).
- **No write probes against the owner's account**, not even with ids meant to be rejected
  (M3 Q14). A desktop M6 check broke this once, with a made-up room id that was rejected; the
  rule was then repeated in every brief.

## 9. Corrections to the working evidence

The milestone that changed each row was checked against the merged PRs' commits. Where the
working halves differ, this record is right:

| Row | Half says | Merged history says |
|---|---|---|
| Desktop login errors | M2, core `a90cea0` | M0 [#127] (`a90cea0` predates the matrix) |
| Desktop message menu | M2 for Copy, Edit, Delete and Report | Copy and Delete M0 [#127], Edit M2 [#132], Report M3 [#133] |
| Desktop sender faces | M4 | Wired in M2 [#132], drawn in the card header in M4 [#139] |
| Android spaces | M5 | The menu in M2 [#135]; readable names from the core in M1.6 [#134], used in M5 [#147] |
| Android connection status | M2 | M0 [#127] |
| Android Mark read from the row | Core `mark_room_read_from_list` | Core [#141], used by Android in M5 [#147] |
| Android half, D6 | "Remote CI has not run on the M6 or M7 branches" | M6 merged as [#148] on green CI, all three instrumented shards passing. M7 has no PR yet. |

## 10. What the owner still has to check live

Consolidated from both halves and the milestone verification tables, which keep the
step-by-step detail. The blocking items come first.

**Decide first**
1. D1: whether the core should decide "pending" from its decision state (section 6). Until then,
   no Needs you count, badge, pill, dot, title count or widget can be seen for a real gate.

**Set up**
2. Install `gst-plugins-good` (and the base plugins) on the Linux desktop, for voice recording.
3. Install a UnifiedPush distributor such as ntfy on the phone or emulator. Registering the
   pusher is a write to the account.
4. Sign the phone AVD in, then the tablet AVD.

**A real agent turn, on each platform** (the M4 checklists, about 10 minutes each):
5. The live card and its glide, "Sent to" then "is on it", "What I did" in the answer, the
   header pill in Working, "Stopped" on a time-out, and the `@` menu from the room's cast.
6. A bridge agent that answers by editing a progress message, and a room with several agents
   and several people. On Android, also font scale 2.0, and the dock never covering the newest
   message.
7. "Read by" faces on an own message someone else has read.

**Decisions and notifications**
8. Approve, Reject and typed feedback on a real card, the receipt, and the failure line while
   offline, on both platforms.
9. Desktop, KDE and GNOME: click a note's body to open its room, answer a permission note from
   its buttons, and the offline failure path. macOS and Windows: a note appears, and the mic is
   hidden or shown.
10. Android, on a real phone: the permission prompt after a fresh sign-in, an ntfy round trip
    with the app closed and in Doze, a lock-screen answer with the unlock prompt, the offline
    "wasn't sent", no second sound, and sign-out while offline (then check the old pusher).

**Voice**
11. Desktop: record, stage and send a note to a test room, then the 600 px and Largest views.
12. Android: the first-use prompt, record, send, play back, the Hermes transcript, real waveform
    levels, and a phone call during a recording.

**Writes, on both platforms**
13. Delete (confirm and effect), save and fail an edit, Copy to the clipboard, Mark read, Mute,
    Pin, Leave, Block and Unblock with the Blocked list, send a report (with and without block,
    and a refused one), Accept and Decline an invitation (room and space), open and create a DM,
    and join by address.
14. A failed attachment send, then Retry; a real image and file.
15. Sign out, then sign in as another account: nothing from the first account may remain.

**First run and layout**
16. A fresh profile or install: the welcome, sign-in, the new recovery key first, then the demo;
    a relaunch must not show the demo again; restore with a key.
17. Desktop: a literal 600 px window, 1905 px with a vertical scrollbar, and 150 % text; System
    mode following an OS theme change; the pre-paint script in a production bundle; Help links
    opening in the system browser.
18. Android: the M5 checklist signed in (destinations and badges, chips, pull to refresh, space
    names, font scale 2.0), a link from another app with the app closed and open, and tablet
    portrait and landscape.
19. Android device feel: haptics with touch feedback on and off, motion with Remove animations
    on and off, TalkBack with the quote action and the report actions, dragging the report sheet
    down while sending, Black on an OLED, and the launch colour with Dark chosen on a light
    phone.

**Desktop system surfaces (M7)**
20. A real second launch; the first-hide notice, clicking it; notifications while the window is
    hidden; GNOME with and without AppIndicator; a tray screenshot; the macOS template icon and
    Dock reopen; Windows.

**After #149 merges (Android M7)**
21. Place both widgets on a real launcher, answer from a widget on a locked phone, tap a widget
    row, sign out with a widget placed, and watch the ongoing notification during a real turn
    (including a bridge agent and a room with several agents).

## 11. Follow-ups

**For the Core track:**
- **D1**, the pending-decision source (section 6). Owner decision first.
- **Telling a finished turn apart:** the core cannot yet distinguish "finished with nothing to
  say" (a ✅-only finish) from an ordinary answered turn, because `Done` only means the stream
  ended. This blocks the desktop turn-ended note, and limits iOS's Live Activity the same way.
- **`send_gate_decision_to`** looks the room up with `get_room`, which also returns rooms the
  user was invited to or has left. The homeserver rejects the send, but the core should refuse
  first.
- **A decoder from notification user info to a `PlannedNotification`.** Without one, Android's
  `AndroidPoster.noteFromExtras` rebuilds the answers in Kotlin.
- **A room's space**, so that a link to a room outside the selected space could switch to it
  (currently "Room unavailable" on Android and iOS).
- **Notifications from every space**, if the owner wants them. Today the core filters the roster
  stream by the selected space.
- **Composer preview bytes** for the desktop staged-image thumbnail (C3), if a preview is wanted.
- **One turn per room:** the turn book holds one turn per room, and an event from a second agent
  joins it (issue [#136]).

**Elsewhere:**
- **AgentPod:** a charter item for hub FCM, so Android need not depend on a distributor. Not
  filed yet; it belongs in AgentPod.
- **CI:** PR CI never builds the desktop app for Windows or macOS. A Tauri bump broke the Windows
  build once, caught only by a cross-compile check in review. A `cargo check --target` job, or a
  release dry run, would catch that class of break.
- **iOS:** the double answer across notification and widget (section 6).
- **Android emulator:** CI runs on `swiftshader_indirect`, which emulator 36.4.9 and later list
  as deprecated. Host-GPU mode fails two focus-return tests that pass on the software renderer.

## 12. Rows pending #149 merge

Android M7 is the only unmerged milestone. These entries are placeholders until it merges, and
are re-checked against the fixed branch then:
- **P3, Android:** the ongoing notification for a running turn (`notify/TurnNotifier.kt`,
  `kit/notify/TurnNotifications.kt` on `d0cb39a`).
- **P4, Android:** the Needs you and Agents Glance widgets (`widgets/`, `kit/widgets/` on
  `d0cb39a`); the Needs you widget also carries the D1 caveat.
- **The Android ✅ count** in sections 1 and 3 (57 includes P3 and P4).
- **The M7 defects** in section 6: re-checked after the fix round.

[#127]: https://github.com/SuperJackfruitLabs/supermessage/pull/127
[#128]: https://github.com/SuperJackfruitLabs/supermessage/pull/128
[#129]: https://github.com/SuperJackfruitLabs/supermessage/pull/129
[#130]: https://github.com/SuperJackfruitLabs/supermessage/pull/130
[#131]: https://github.com/SuperJackfruitLabs/supermessage/pull/131
[#132]: https://github.com/SuperJackfruitLabs/supermessage/pull/132
[#133]: https://github.com/SuperJackfruitLabs/supermessage/pull/133
[#134]: https://github.com/SuperJackfruitLabs/supermessage/pull/134
[#135]: https://github.com/SuperJackfruitLabs/supermessage/pull/135
[#136]: https://github.com/SuperJackfruitLabs/supermessage/issues/136
[#137]: https://github.com/SuperJackfruitLabs/supermessage/pull/137
[#138]: https://github.com/SuperJackfruitLabs/supermessage/pull/138
[#139]: https://github.com/SuperJackfruitLabs/supermessage/pull/139
[#140]: https://github.com/SuperJackfruitLabs/supermessage/pull/140
[#141]: https://github.com/SuperJackfruitLabs/supermessage/pull/141
[#142]: https://github.com/SuperJackfruitLabs/supermessage/pull/142
[#143]: https://github.com/SuperJackfruitLabs/supermessage/pull/143
[#144]: https://github.com/SuperJackfruitLabs/supermessage/pull/144
[#146]: https://github.com/SuperJackfruitLabs/supermessage/pull/146
[#147]: https://github.com/SuperJackfruitLabs/supermessage/pull/147
[#148]: https://github.com/SuperJackfruitLabs/supermessage/pull/148
