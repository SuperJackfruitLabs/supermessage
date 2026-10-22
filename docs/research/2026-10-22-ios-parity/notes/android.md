# Android against iOS — evidence

Code reading at `f64629f`, 22 Oct 2026. Nothing built or run. B1–B4 in the report were
re-checked by hand; the rest is as read. Android paths are relative to
`android/app/src/main/kotlin/dev/supermessage/` unless prefixed `kit/`
(`android/kit/src/main/kotlin/dev/supermessage/kit/`); iOS paths are relative to `apple/`.

**Core surface.** The Swift and Kotlin `CoreInterface` method lists match exactly: 64 methods each
(`apple/Generated/supermessage_ffi.swift` against
`android/core/src/main/kotlin/uniffi/supermessage_ffi/supermessage_ffi.kt:2303`).

**FFI methods with no `kit/CoreClient.kt` wrapper:**
- `sendPermissionAnswer`, `sendGateDecisionTo`
- `ignoreUser`, `unignoreUser`, `ignoredUsers`
- `reportEvent`, `reportRoom`, `reportUser`
- `registerPusher`, `unregisterPusher`
- `notificationFor`
- `attachmentMarkVoice`
- `suspend`, `syncPause`, `syncResume`
- `agentAvatar`

**Kit API the app never uses:**
- `Session.spaces`, `.connection`, `.media`, `.faces` (`kit/Session.kt:184-191`)
- `Session.delete` (`:462`)
- `Session.scenePhaseChanged` (`:351`)
- `Session.fullAvatar`
- `RosterPreferences.setView` and `setShowsState` (`RosterPreferences.kt:73,81`)
- `kit/TimelineFollow.kt`

## Navigation and shell
| ID | Feature | iOS | Android | Evidence | Gap | Core/FFI |
|---|---|---|---|---|---|---|
| N1 | Tab bar | Supermessage/Navigation/PhoneShell.swift:26-70 | Missing | MainActivity.kt:212-233 (three TextButtons above the roster) | No destinations | UI only |
| N2 | Needs you inbox | Inbox/NeedsYouView.swift:11-57, :87; SupermessageKit/RosterViews.swift:65-96 | Missing | — | No inbox or badge | `NeedsYouInbox` is Swift Kit; FFI `rosterSections`, `rosterState` |
| N3 | Agents view | Agents/AgentsView.swift:11-103; RosterViews.swift:99-107 | Missing | — | No agent directory | `AgentDirectory`, `RoomStatus` are Swift Kit |
| — | Adaptive shell | RootView.swift:97-101; SplitShell.swift:75,151 | Partial | RootScaffold.kt:193-395 (info as a bottom sheet under 3 panes, :389) | No sidebar destinations | UI |
| — | Spaces | Rooms/SpaceMenu.swift:12-61 | Missing | kit/stores/SpacesStore.kt:45,60 unused | No picker | FFI `spacesList`, `spaceSelect` already wrapped |
| — | Deep links | Platform/AppLinks.swift:6-12; Notifications/WidgetSnapshot.swift:333 | Missing | AndroidManifest.xml:58-61 (MAIN/LAUNCHER only) | No scheme or intent filter | `parseMatrixLink` in FFI |

## Onboarding and auth
| ID | Feature | iOS | Android | Evidence | Gap | Core/FFI |
|---|---|---|---|---|---|---|
| — | Login | LoginView.swift:36-95 | Full | LoginScreen.kt:44; MainActivity.kt:582-608 | — | — |
| O1 | Welcome and first-run demo | Onboarding/WelcomeView.swift:35; FirstRunDemoView.swift:17-93; SupermessageKit/FirstRunDemo.swift | Missing | — | Straight to login | Swift Kit (local demo) |
| — | Session restore | RootView.swift:38-43 | Full | kit/Session.kt:213-254 | — | — |
| — | Sign out with confirm | AccountPanel.swift:157,185 | Full | Account.kt:180-220 | — | — |

## Room list
| ID | Feature | iOS | Android | Evidence | Gap | Core/FFI |
|---|---|---|---|---|---|---|
| R1 | Agent avatars, badge, dot on avatar, two lines | Rooms/RoomAvatar.swift:12-33,106; SupermessageKit/AgentAvatarStyle.swift:48; RoomRowView.swift:66-71 | Partial | RoomRow.kt:54-164 (initial :194, dot beside meta :264, harness/host in meta :171, three lines) | No agent faces or badge; runtime still in the row | `AgentAvatarStyle` is Swift Kit; FFI `agentAvatar` |
| R2 | Swipe: read / mute / pin / decline | RoomListView.swift:260-326 | Missing | — | Mute and pin only in room info | `markRoomRead`, `setRoomNotifications`, `setRoomPinned` already wrapped |
| R3 | Filters (All, Unread) | RosterFilterChips.swift:15; RosterViews.swift:14-56 | Missing | — | — | `RosterFilter` is Swift Kit |
| — | Arrangement and agent-state toggle | AccountPanel.swift:98-109 | Partial | RosterPreferences.kt:49-83 (setters unused) | Fixed at Waiting | UI |
| — | Invitations row | RoomListView.swift:122-136,363 | Partial | Roster.kt:82-98 ("N invitations hidden"); RoomRow.kt:113 | Text line, not an expandable row | UI |
| — | New room / DM / join | NewRoomPanel.swift:131,233-275 | Full | NewRoom.kt:83-346 | — | — |

## Room header and info
| ID | Feature | iOS | Android | Evidence | Gap | Core/FFI |
|---|---|---|---|---|---|---|
| A1 | Header status pill | Navigation/RoomScreen.swift:102-115,199-266 | Missing | MainActivity.kt:269-498 (Timeline plus Composer only) | **No room header**: no name, no status, no route to room info from the room | Swift Kit `RoomStatus` |
| D11 | One name per person | Stores/RoomCast.swift; TypingStore.swift:86-129 | Partial | kit/stores/TypingStore.kt:45-74 | Typing ignores the cast | Kit |
| — | Room info | Panels/RoomInfoPanel.swift:68-207 | Partial | RoomInfo.kt:105-345 (Leave red and confirmed :190-220) | No block or report; only reachable from the roster avatar (Roster.kt:126) | FFI `ignoreUser`, `report*`; needs wrappers |

## Timeline
| ID | Feature | iOS | Android | Evidence | Gap | Core/FFI |
|---|---|---|---|---|---|---|
| T1 | Agent card and long-read | TimelineRowView.swift:228,375-460; TimelineChrome.swift:197 | Missing | TimelineRow.kt:362-379 (only own messages boxed) | No badge, surface or long-read | `isAgent`/`isLongRead` are Swift Kit (TimelineGrouping.swift:261,277); serif token GeneratedThemeTokens.kt:549 |
| T2 | Rhythm | TimelineRowView.swift:375 | Partial | TimelineRow.kt:315 (2dp/8dp) | 8 not 12 (judgement) | UI |
| T3 | Reactions on corner, receipt faces | TimelineRowView.swift:319-323,362-363 | Partial | TimelineRow.kt:408-424 | Reactions below; "Read by …" | UI |
| T4 | Swipe to reveal times | TimelineCollectionView.swift:1216-1245 | Missing | — | — | UI |
| T5 | Quote jump and highlight | TimelineCollectionView.swift:592,608,921-950 | Missing | TimelineRow.kt:451-489 | Quote inert | UI |
| T6 | What I did and turn record | WhatIDidFooter.swift:17,44; TurnRecordLabel.swift:10; LiveStore.swift:247 | Partial | Timeline.kt:392-403; LiveTurn.kt:121 | No record label; lost on room switch (kit/stores/LiveStore.kt:178-181) | Kit |
| D5 | Churn across verbs | TimelineGrouping.swift:98-220 | Partial | kit/TimelineGrouping.kt:80-146 | Consecutive runs only | Kit |
| D6 | Hide redundant quote | TimelineGrouping.swift:245 | Missing | TimelineRow.kt:345 | — | Kit |
| D7 | Quiet metadata | TimelineRowView.swift:348-370 | Partial | TimelineRow.kt:408-414; DecisionCard.kt:175 (raw event type) | — | UI |
| — | New-messages line | TimelineRowView.swift:136-151 | Partial | TimelineRow.kt:161-169 | Unlabelled | UI |
| — | Day separators / jump to newest / turn error / voice / notice | — | Full | TimelineRow.kt:149,156,252; Timeline.kt:440-459; TurnErrorCard.kt; VoiceNote.kt; VoiceTranscript.kt | — | — |
| — | Streaming text | StreamingText.swift; LiveTurnView.swift:112,399 | Partial | kit/StreamingText.kt; LiveTurn.kt:75-103,159 | No markdown while streaming | FFI `richBlocksFromMarkdown` |
| — | Images | TimelineRowView.swift:702-760 | Partial (bug) | TimelineRow.kt:655-687 | Never loads the image | `mediaFetch` wrapped |
| — | Undecryptable | TimelineRowView.swift:158,637-680 | Partial | TimelineRow.kt:151 | No sender or recovery hint | UI |
| — | Message menu | TimelineCollectionView.swift:318,974,1039-1150 | Partial | Timeline.kt:326-330; MainActivity.kt:407-420; TimelineRow.kt:573-577 | Long-press silently edits or replies; no copy, delete, report or swipe; "+" needs a long-press | Delete in Kit; report needs a wrapper |
| — | Typing | TimelineView.swift:128,229 | Full | Timeline.kt:424-434 | — | — |
| — | Sender faces | TimelineRowView.swift (SenderFace) | Missing | Timeline.kt:318-334; TimelineRow.kt:616 | Initials only | `memberAvatar` wrapped |
| — | Follow / scroll | TimelineFollow.swift | Partial (judgement) | Timeline.kt:211-285 | kit TimelineFollow unused | — |

## Agents live and decisions
| ID | Feature | iOS | Android | Evidence | Gap | Core/FFI |
|---|---|---|---|---|---|---|
| A2 | Live activity card | LiveTurnView.swift:28-196,399 | Partial | LiveTurn.kt:61-165 | No current step, elapsed, step count or markdown | Elapsed time in Swift LiveStore.swift:93 |
| A3 | Sent to / is on it | SupermessageKit/Acknowledgement.swift:17-84; TimelineView.swift:124,162-226 | Missing | — | Silent gap | Swift Kit `Acknowledgement`, `RoomCast` |
| A4 | Stop | blocked | Blocked | — | — | AgentPod |
| G1 | Decision card | DecisionCard.swift:175,277-349,481-640; DecisionReceipt.swift | Partial | DecisionCard.kt:124-494 (FlowRow :402-456, feedback :462, receipt :248) | Flat layout, no destructive Reject, every field shown, no failure feedback | UI |
| — | Permission requests | DecisionCard.swift:502-508; TimelineCollectionView.swift:1022-1031 | **Missing (bug B1)** | DecisionCard.kt:350; kit/Session.kt:399-423 | Buttons never enable | FFI `sendPermissionAnswer`; needs a wrapper |

## Composer
| ID | Feature | iOS | Android | Evidence | Gap | Core/FFI |
|---|---|---|---|---|---|---|
| C1 | Capsule | FloatingSurface.swift:44; ComposerView.swift:184-201 | Missing | Composer.kt:239-285 | Stock row | UI |
| C2 | Voice recording | VoiceRecorder.swift:30-97 | Missing | AndroidManifest.xml:13 (INTERNET only) | — | `attachmentMarkVoice` unwrapped |
| C3 | Thumbnail, error on chip | ComposerView.swift:627-735 | Partial | Composer.kt:227,381-400 | Filename only | UI |
| C4 | @ picker | ComposerView.swift:253,512; MentionComposing.swift | Missing | kit/Session.kt:312 (`members = emptyList()`) | Mentions never resolved | FFI `collectMentions`, `peopleMatching`, `knownPeople` |
| D12 | Draft and attachment safety | Outbox.swift:39-71 | Partial (bug B2) | kit/Session.kt:291-324; Composer.kt:179 | Attachment can't send without text | UI |
| — | Captions, drafts, edit, reply | — | Full | kit/Session.kt:297; MainActivity.kt:362-364,437-439; Composer.kt:302-370 | — | — |
| — | Attachment sources | ComposerView.swift:129,171 | Partial | Composer.kt:196-249 | Images and video only | UI |

## Motion, notifications, safety, settings
| ID | Feature | iOS | Android | Evidence | Gap | Core/FFI |
|---|---|---|---|---|---|---|
| M1 | Motion | RoomListView.swift:168; TimelineView.swift:117,136 | Partial | Timeline.kt:232-246,398; kit/TimelineAnimation.kt | List items only | UI |
| M2 | Haptics | ComposerView.swift:113; DecisionCard.swift:175,561; TimelineRowView.swift:578 | Missing | 0 haptic calls | — | UI |
| P1 | Push | PushConfiguration.swift; AppDelegate.swift:72; NotificationService.swift:37-109 | Missing | app/build.gradle.kts:141-172 (no FCM or UnifiedPush) | — | `registerPusher` platform-neutral (core push.rs:28); hub FCM unknown |
| P2 | Local notifications with actions | LocalNotifier.swift:15-60; NotificationAnswerer.swift:25 | Missing | No POST_NOTIFICATIONS | — | FFI `notificationForRow`, `notificationGateAnswers`, `notificationPermissionAnswers`, `sendGateDecisionTo` |
| P3 | Live Activity analogue | FleetLiveActivity.swift:9 | Missing | — | — | `registerLiveActivityToken` is APNs-only |
| P4 | Widgets | NeedsYouWidget.swift:5; AgentsWidget.swift:6 | Missing | — | — | core `widget*` platform-neutral |
| — | Block / unblock | SafetyStore.swift:58-109; RoomInfoPanel.swift:242-261; AccountPanel.swift:115-130 | Missing | kit/Session.kt:805-809 ("iOS-only for now") | — | FFI; needs wrappers |
| — | Report | ReportSheet.swift:23-226 | Missing | — | — | FFI `report*`, `reportReasonRemaining` |
| — | Recovery and new key | RecoveryView.swift:75-173; NewRecoveryKeyView.swift | Full | Recovery.kt:58-207; NewRecoveryKey.kt; kit/Session.kt:713 | — | — |
| — | Search | SearchPanel.swift | Full | Search.kt:108-343 | Room scope never used | — |
| — | Account panel | AccountPanel.swift:55-163 | Partial | Account.kt:70-221 | No appearance, chats, blocked or help links | UI |
| — | Themes | Appearance.swift:11-116 | Partial | Theme.kt:117-121 | No chooser; Black and accent tokens unused (GeneratedThemeTokens.kt:121,146) | UI |
| — | Font scaling | TextScalePreviews.swift | Full (judgement) | sp typography; debug TextScalePreviews.kt | — | — |
| — | Connection status | RootView.swift:164-207 | Missing | kit/stores/ConnectionStore.kt:50 fed, never drawn | — | UI |
| — | Lifecycle | RootView.swift:44-46 | Missing (bug B4) | `scenePhaseChanged` has no caller | — | Kit exists |

**Also iOS-only:**
- pull to refresh;
- the who-reacted popover;
- the full-size room avatar;
- help, terms and privacy links;
- background widget refresh;
- readable "Runtime xxxxxx" space names (`SpaceNames.swift`).
