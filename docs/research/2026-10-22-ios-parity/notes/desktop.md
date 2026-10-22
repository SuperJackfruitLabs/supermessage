# Desktop against iOS — evidence

This is a code reading at `f64629f`, 22 Oct 2026. Nothing was built or run.

These claims were rechecked by hand:
- the edit and delete callers;
- the `data-appearance` writers;
- the plugin list;
- the recovery `enable()` call;
- `decisionReply.ts`;
- the 45-command count.

Everything else is as read. iOS paths are relative to `apple/Supermessage/` or
`apple/SupermessageKit/`.

**Command ceiling.** `src-tauri/src/lib.rs:215-261` registers 45 commands. Three
of them have no caller in `src/`: `edit_message`, `delete_message` and
`roster_state`.

The UniFFI layer has the following, which desktop cannot reach
(`crates/supermessage-ffi/src/lib.rs:337-835, 1117-1196`):
- `set_room_notifications` and `set_room_pinned`;
- ignore, unignore and `ignored_users`;
- `report_event`, `report_room` and `report_user`;
- `ensure_recovery`;
- `known_people` and `direct_room_with`;
- `room_inviter`;
- `account`;
- `send_permission_answer`;
- `notification_for(_row)`;
- `agent_avatar`;
- `attachment_mark_voice` and `attachment_stage_path`;
- `people_matching`.

The plugins are opener and dialog. Debug builds add wdio and mcp. There is no
notification, tray or deep-link plugin.

## Navigation, onboarding, room list, header
| ID | Feature | iOS | Desktop | Evidence | Gap | Plumbing |
|---|---|---|---|---|---|---|
| N1 | Destinations | PhoneShell.swift:26-64; AppDestination.swift:7-26; SplitShell.swift:118-122 | Missing | +page.svelte:733-794 (roster only); Search button in the room header :1015-1022 | No Needs you or Agents; search only from inside a room | UI (`rosterSections` wrapped) |
| N2 | Needs you inbox | NeedsYouView.swift:11-33,87-101 | Partial (judgement) | Waiting arrangement (ArrangementMenu.svelte:29) | No inbox, badge or empty state | UI |
| N3 | Agents view | AgentsView.swift:11-28 | Missing | "Machine" arrangement is the closest | — | UI |
| — | Multi-pane | SplitShell.swift:10-40 | Full | +page.svelte:129-151,271-348 | — | — |
| — | Spaces | SpaceMenu.swift | Full | SpacesRail (+page.svelte:725-732); SpaceInvitePanel :1185-1192 | — | — |
| — | Login errors | LoginView.swift:115; ErrorPresenter.swift | Partial | login/+page.svelte:26-39 | Non-403 API errors read "Could not reach the homeserver" | UI (`err.message` available) |
| O1 | Welcome and demo | WelcomeView.swift; FirstRunDemoView.swift | Missing | — | — | UI (demo is local) |
| — | Sign out | AccountPanel.swift:154-191 | Partial | +page.svelte:640-657,785-792 | No confirm or warning | UI |
| R1 | Agent avatars and badge | RoomAvatar.swift:12-60; RoomRowView.swift:100-106 | Partial | roster/RoomRow.svelte | Initials; dot beside the name; three lines | UI (port AgentAvatarStyle; `agent_avatar` needs a command) |
| R2 | Read / mute / pin | RoomListView.swift:260-330 | Missing | Automatic read only (readTracking.ts) | No row actions | Mark read: UI; mute and pin: command |
| R3 | Filters | RosterFilterChips.swift:15 | Missing | RoomList.svelte | — | UI |
| — | Arrangement | RosterArrangement.swift:18 | Full | ArrangementMenu.svelte:29 (adds Machine) | No agent-state toggle | — |
| — | Invitations | RoomListView.swift:122-136; InvitationView.swift:15-39 | Partial | RoomRow.svelte pill; InvitationPanel (+page.svelte:1071-1076) | No inviter, no Report room | command (`room_inviter`) |
| — | New room / DM | NewRoomPanel.swift | Partial | NewRoomPanel.svelte:110-152 | Raw id list; no reuse of an existing DM | command (`known_people`, `direct_room_with`) |
| A1 | Status pill | RoomScreen.swift:226-246; RosterViews.swift:135-147 | Missing | +page.svelte:976-992 (connection word) | — | UI (`roster_state` unwrapped) |
| D11 | One name | RoomScreen.swift:199 | Partial | Timeline.svelte:1136; typingView.ts | Live turn labelled with the room name (+page.svelte:1050-1053) | UI |
| — | Room info | RoomInfoPanel.swift:108-137,183-203,243-249 | Partial | RoomInfoPanel.svelte:192-299 | No Leave, runtime, mode/pin, block or report | Leave and runtime: UI; mode/pin: command |

## Timeline
| ID | Feature | iOS | Desktop | Evidence | Gap | Plumbing |
|---|---|---|---|---|---|---|
| T1 | Agent card and long-read | TimelineRowView.swift:276-400; TimelineChrome.swift:84,180,197 | Missing | Timeline.svelte:1102-1143 | No avatar, badge, surface or long-read | UI (`senderAvatar` in DTO, ipc.ts:263) |
| T2 | Rhythm | TimelineGrouping.swift:4-16 | Partial (judgement) | Timeline.svelte:1102 (16/24px) | — | UI |
| T3 | Reactions and receipts | TimelineRowView.swift:316-347,542 | Partial | ReactionsRow (Timeline.svelte:1183); SeenMarker.svelte | Detached; "Seen by N" | UI |
| T4 | Reveal times | TimelineCollectionView.swift:541-594 | Missing | Timeline.svelte:1138,1176 | No hover time | UI |
| T5 | Quote jump | TimelineCollectionView.swift:921-942 | Missing | ReplyQuote.svelte | Inert | UI |
| T6 | Turn record and What I did | WhatIDidFooter.swift; TimelineCollectionView.swift:614 | Partial | AgentReasoning outside the list (+page.svelte:1044) | Tool steps dropped | UI (liveStore holds both) |
| D5 | Churn | TimelineGrouping.swift:61-95 | Partial | timelineGrouping.ts:361 | Same verb only | UI or core |
| D6 | Hide redundant quote | TimelineGrouping.swift:237 | Missing | Timeline.svelte:1144 | — | UI |
| D7 | Quiet metadata | TimelineRowView.swift:334-349 | Partial (judgement) | Timeline.svelte:1125 | Receipt sentence | UI |
| — | New line / day separators / jump to newest / streaming / turn error / voice / images / notice / undecryptable / typing | — | Full | UnreadMarker.svelte:14; Timeline.svelte:1189-1222,1363-1406,1483-1538,1597-1633,1711-1745; pacer.ts; TypingIndicator.svelte | — | — |
| — | Message menu | TimelineCollectionView.swift:966-984,1070-1146 | Partial | MessageActions.svelte:87-116 | No copy, edit, delete or report | Edit and delete: need a wrapper (commands.rs:276-293); report: command |
| — | Scroll on open | TimelineFollow.swift | Full (code-level) | timelineFollow.ts; Timeline.svelte:823 (7072ffd) | Runtime not re-measured | — |

## Agents, decisions, composer
| ID | Feature | iOS | Desktop | Evidence | Gap | Plumbing |
|---|---|---|---|---|---|---|
| A2 | Live card | LiveTurnView.swift:28-160; TimelineCollectionView.swift:642 | Partial | LiveTurn, LiveActivity and AgentReasoning are three strips outside the list (+page.svelte:1044-1062) | No elapsed time; not one card; not in the timeline | UI |
| A3 | Is on it | Acknowledgement.swift; TimelineView.swift:125,191 | Missing | — | — | UI |
| A4 | Stop | blocked | Blocked | — | — | AgentPod |
| G1 | Decision card | DecisionCard.swift:344-568; DecisionReceipt.swift | Partial | DispatchCard.svelte:143-154,184-200,240-246; decisionReply.ts:13 (comment `null`) | No typed feedback, no hierarchy, silent failure (Timeline.svelte:978-981) | UI (`sendGateDecision` takes a comment) |
| — | Permission requests | DecisionCard.swift:503-506 | Partial | decisionReply.ts:15-17 (plain message) | Not the shared builder | command (`send_permission_answer`) |
| C1 | Layout | ComposerView.swift:19,184-201 | Partial | Composer.svelte:744-800 | "+" outside the field; no mic | UI |
| C2 | Voice recording | VoiceRecorder.swift | Missing | — | — | command (`attachment_mark_voice`) |
| C3 | Thumbnail, error on chip | ComposerView.swift:624-702 | Partial | StagedAttachmentChip.svelte; Composer.svelte:636-642 | — | command (judgement) |
| C4 | @ picker | ComposerView.swift:512-539 | Partial | MentionMenu.svelte | Agents not marked | UI |
| D12 | Draft and attachment safety | Outbox.swift | Partial | Composer.svelte:397-401,507-575 | Failed attachment discarded | UI |
| — | Captions, drafts, reply banner | — | Full | Composer.svelte:518; draftTracker.ts; ReplyBanner.svelte | — | — |
| — | Edit mode | ComposerView.swift:53,557-567 | Missing | — | — | wrapper |

## Motion, notifications, safety, settings
| ID | Feature | iOS | Desktop | Evidence | Gap | Plumbing |
|---|---|---|---|---|---|---|
| M1 | Motion | RoomListView.swift:166; WhatIDidFooter.swift:25 | Partial | animateList; LiveTurn.svelte:240; app.css:70 (reduced motion) | Messages don't animate in | UI |
| P2 | Notifications | LocalNotifier.swift; NotificationAnswerer.swift | Missing | No plugin | — | plugin plus command over `core::notification` |
| — | Tray / badge / title count | PhoneShell.swift:50 | Missing | app.html (fixed title) | — | UI or tray |
| — | Block | SafetyStore.swift; RoomInfoPanel.swift:243-245 | Missing | — | — | command (session.rs:1799-1805) |
| — | Report | ReportSheet.swift | Missing | — | — | command (session.rs:1819-1836) |
| — | Recovery | RecoveryView.swift; Session.swift:685-729 | Partial | RecoveryPanel.svelte:47 (`enable()` never referenced) | No enable; no key at sign-in | UI; command (`ensure_recovery`) |
| — | Search | SearchPanel.swift:35-59 | Partial | SearchPanel.svelte; +page.svelte:1179 | No room scope | UI |
| — | Account panel | AccountPanel.swift:50-163 | Missing | +page.svelte:772-792 (two menu items) | — | command (`account`); rest UI |
| — | Themes | Appearance.swift:11-104 | Missing | tokens.css:127,175-286 | Nothing sets `data-appearance` or `data-accent` | UI |
| — | Font scaling | Dynamic Type | Missing (judgement) | — | — | UI |
| — | Connection banner, outbox, caches | — | Full | ConnectionBanner.svelte; mediaCache.svelte.ts; avatarCache.svelte.ts | — | — |
| — | Error presentation | ErrorPresenter.swift | Partial | Composer.svelte:235,345; Timeline.svelte:980 (`console.error`) | Silent failures | UI |

## The 2026-10-03 desktop audit, re-checked
| Claim | Verdict | Evidence |
|---|---|---|
| Only the revamp's typography landed | Mostly still true | Commit 7072ffd (2026-10-04) added undecryptable grouping, scroll fixes and core names. No T, A, G, N or R item landed. |
| No edit or delete UI | Confirmed | lib.rs:238-239; commands.rs:276-293; no caller |
| No themes | Confirmed | No writer of `data-appearance` |
| No notifications | Confirmed | No plugin |
| No block or report UI | Confirmed | No command |
| Recovery enable not wired | Confirmed | RecoveryPanel.svelte:47 |
| Long rooms open short of the newest message | Refuted at code level | 7072ffd; runtime not re-measured |
| Newest message's hover bar clipped | Refuted at code level | Timeline.svelte:1334-1353 (`pb-8`) |
| Every non-403 login error reads "network" | Changed | Store errors are now separate (auth/password.rs:79-108, a90cea0); other API errors are still "network" |
| Raw `@agent_…` ids shown | Refuted at code level | `senderShort`; core typing label |
| An edited progress message doesn't engage the live components | Confirmed at code level | live.svelte.ts:18-25 listens only to the live, thought and tool channels |

**Desktop features iOS lacks:**
- drag-and-drop attach;
- media save and download;
- a searchable emoji picker;
- "Jump to unread";
- in-app matrix.to routing;
- the room aliases list;
- space invitations in the rail;
- the "Machine" arrangement.

**iOS features desktop lacks:**
- pull to refresh;
- the full-screen room avatar;
- help links;
- answering from a notification.
