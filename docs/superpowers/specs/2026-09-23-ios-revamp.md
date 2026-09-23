# iOS revamp — 2026-09-23

The owner uses supermessage daily on iOS and does not want to. This spec is the
work list from the design review of that date (TestFlight build 9 screenshots,
plus a study of Slack, iMessage, Telegram, WhatsApp, Signal, Linear, Claude,
ChatGPT, Cursor, Devin and Copilot). iOS first; the token changes reach every
platform because tokens are shared.

It **supersedes `docs/design-language.md` §1 (three faces) and §6 (motion is
almost none)**. Everything else there stands — above all: amber (`signal`) means
a pending decision and nothing else, and every value comes from
`design/tokens.toml`.

## Principles

1. **One voice.** SF Pro for everything said and every label; sentence case;
   New York serif only in the long-read view (`ThemeType.longread`); mono only
   for code, paths, ids and keys.
2. **Alive, calm, fast.** Motion and haptics that confirm something happened,
   never decoration. Always honour Reduce Motion.
3. **Agents are labelled, delegated participants.** A badge, a card, a status —
   never a raw user id.
4. **What needs you is obvious and finishable.**
5. **Draw on the palette, never on system defaults.** No pure black, no stock
   grey panels: `Theme.surface`/`surfaceSunken`/`surfaceRaised`.

## Work items

### Defects (from the build 9 screenshots)
- D1 Files-picker attachment could not be read at send (done: copy under access).
- D2 Agent shown as raw `@agent_…` id: fetch members on open (done, core).
- D3 Self-membership lines showed raw ids (done, core).
- D4 Content runs under the navigation bar, text clipped, no edge effect.
- D5 Membership lines ~130pt apart; churn not collapsed across verbs.
- D6 Reply quote shown when the parent is the row directly above.
- D7 Loud metadata: uppercase mono labels, dates, "Read by …" sentences.
- D8 Reactions detached below the timestamp instead of on the bubble.
- D9 Dark mode is #000 with system-grey panels instead of the palette.
- D10 A "Done" button beside Back in the room header.
- D11 One person, three names (header, typing line, sender).
- D12 Attachment errors float above the composer; text must not be sent
  without its attachment, and a failed send keeps the draft.

### Design
- T1 Agent messages as a card: avatar, name, **Agent** badge, soft surface,
  readable measure. Long reports open in a serif long-read view.
- T2 Rhythm: 2pt within a run, 12pt between turns, one-line system rows.
- T3 Reactions on the bubble corner; receipts as a tiny avatar.
- T4 Swipe the timeline left to reveal every message's time (iMessage).
- T5 Tap a quote to jump to the original and highlight it.
- T6 "What I did" footer: the finished turn's tool calls, collapsed, under the
  agent's message.
- A1 Room header status pill from a fixed vocabulary: Working, Needs you,
  Idle, Quiet, Error.
- A2 The live turn as an activity card: current step, elapsed time, steps.
- A3 Acknowledgement: "Sent to <agent>" → "<agent> is on it" once it reacts or
  starts; never a silent gap.
- A4 Stop — **blocked**: AgentPod's Matrix bridge has no cancel event, and a
  client-private one is forbidden (AGENTS.md). Needs a suite schema first.
- G1 Decision card: plain-language headline, requester, one-line summary,
  details behind a disclosure, full-width Approve, secondary Request changes,
  destructive Reject last; success haptic; receipt state.
- N1 Tab bar: Chats, Needs you, Agents, Search.
- N2 Needs you: a finishable inbox (pending decisions, mentions, invitations)
  with a celebratory empty state.
- N3 Agents: every agent room with status and last activity.
- R1 Room list: generated agent avatars with a badge, two-line rows, status
  dot on the avatar, runtime moved to room info.
- R2 Swipe actions: mark read, mute, pin (core APIs exist).
- R3 Filters: All, Unread, Agents, Needs you.
- C1 Composer: floating capsule, attach inside, send ⇄ mic.
- C2 Voice messages (m.audio through the existing attachment path).
- C3 Image attachment thumbnail in the composer; errors on the chip.
- C4 `@` picker listing people and agents with status.
- M1 Motion: messages spring in, cards expand/collapse, decision → receipt.
- M2 Haptics: send (light), request arrives in open room (warning), approve
  (success), reaction (selection), turn done (success).
- P1 Push: register an `event_id_only` pusher when a gateway is configured
  (`SMPushGatewayURL` in Info.plist). **The gateway itself is not deployed**;
  until it is, only local notifications work.
- P2 Local notifications for messages and requests while the app is alive,
  with actions: Allow once / Reject (authentication required) on permission
  requests; gates open the app.
- P3 Live Activity: an agent working, step count, on the Lock Screen and
  Dynamic Island.
- P4 Widgets: Needs you count, agent status.
- O1 First run: a welcome screen, and a local demo room where a sample agent
  asks a permission and the reader approves it. Nothing is sent anywhere.

## Rules for implementers
- Tokens only; no hex in views. New type roles go through `tokens.toml`.
- The app parses nothing and decides nothing that the core already decides.
- Every change keeps Dynamic Type working at accessibility sizes.
- Previews for new states; baselines are re-recorded once, at the end.

## Status (2026-09-23)

Built and tested on CI (PR #82) and on the owner's Mac with build products on
an external APFS sparse bundle: Rust workspace green, 265 iOS Kit tests green,
the app and UI-test targets compile, Android and web green, previews
re-recorded from reviewed renders.

| Item | State |
|---|---|
| D1–D12, T1–T6, A1–A3, G1, N1–N3, R1–R3, C1–C4, M1–M2, O1 | Implemented. Runtime behaviour (gestures, haptics, animation) needs a device pass. |
| A4 Stop | **Blocked**: needs a suite-shared cancel event from AgentPod. |
| P1 Push | Client half done (`core::push`, `event_id_only`). **Blocked** on a deployed gateway, the APNs key and the push entitlement in the App Store profile. |
| P2 Local notifications | Done; only while the app is alive until P1 lands. |
| P3/P4 Live Activity, widgets | Done behind `SM_EXTENSIONS`; **blocked** on the widget App ID, App Group and its profile. |
| Desktop typography | Done: serif and capitals gone, mono kept only for code, ids and keys. |
| Mentions in Needs you | Not included: the core does not expose mentions yet. |
| Streaming stutter (2026-09-24) | Fixed. The list animated each self-sizing resize, so every new line snapped and glided. `TimelineCollection` lays out without animation while a turn streams, and `StreamingText` spreads each delta over the expected gap to the next. Checked by `StreamingStutterTests` with the fix on and off: 14 animating frames with it, 156 without. |
| Top fade, tab bar (2026-09-24) | The fade depends on scroll state: 28pt while scrolling, 10pt at rest, none when nothing is beneath. The tab bar is hidden inside a room. |
| Device pass, build 15 (2026-09-24) | Fixed from the reader's screenshots:<br>• The header says **Active** (green) when the list's dot is green; it used to say Idle.<br>• The fade is opaque only under the bar and reads "scrolling" from the scroll view's live state.<br>• A finished turn's reasoning survives leaving the room (20 rooms, cleared on sign-out).<br>• The new-messages line is labelled, and a reply's quote hides across it.<br>• Leave room is red and asks first.<br>• A tap on the timeline dismisses the keyboard.<br>• Agent members are named from their id in the core.<br>• Spaces are chosen from the title, and the chips are All and Unread.<br>• Id-shaped space names read "Runtime xxxxxx" until agentpod#554. |
