# What each platform has, and what it does not

**Written 2026-09-12**, at the end of P2b. Counts are from the tree at that
date; the commands that produce them are given so a stale number can be
caught rather than trusted.

## 1. How much of this is measured

Two of the three columns below are mechanical. The third is not, and the
distinction matters more than any row in the table.

| | Mechanical? |
|---|---|
| Which components exist on which platform | **yes** — the files are there or they are not |
| How many catalogued states each has | **yes** — `<Story>` and `#Preview`/`@Preview` are countable |
| Whether two platforms catalogue *the same* states | **no** |

The third is not mechanical because of a decision taken in this project's
spec: the native previews cover **per-platform states**, not mirrors of the
web's 83 named scenarios. So nothing can report "web has a story for this
state and iOS does not" — the names do not line up, by design, because
re-pointing native states at web names would be speculative work on
platforms where nobody can look at the result. Every comparison in §4 is
therefore a judgement, and is labelled as one.

```bash
# The three counts, verbatim.
grep -rc "<Story" --include='*.stories.svelte' src | awk -F: '{s+=$2} END {print s}'
grep -rho "#Preview(\|#Preview {" apple/Supermessage --include='*.swift' | wc -l
grep -rho "@Preview" android/app/src/debug | wc -l
```

## 2. The shape of each platform

| | Web | iOS | Android |
|---|---|---|---|
| UI units | 22 components with catalogues | 18 views | 14 composables |
| Catalogued states | **83** stories | **52** previews | **48** previews |
| Catalogue tool | Storybook 10.6 | Xcode previews | Compose previews |
| Where the catalogue lives | beside the component | beside the view | **a separate source set** |
| Anything renders it here | yes, in a browser | no | no |
| Anything renders it in CI | no | **yes** — 40 PNGs per run | **yes** — 24 test files, plus **all 48** as PNGs |
| Injection seam | view-models as props (P2a) | 11 protocols (P2b) | **never needed one** |

Three rows in that table are the substance of this document.

**Android never needed a seam.** Its composables were already props-down
before any of this started: `AccountPanel` takes
`loadAccount: suspend () -> AccountDto?`, `Timeline` takes rows and
callbacks, and only `AppRoot` takes a `Session` at all. The eleven protocols
in `apple/SupermessageKit/CoreSeam.swift` exist to give iOS what Android
already had, and P2a's component extraction did the same for the web. This is
the largest structural difference between the two native halves and it
favours Android.

**Android is the only platform whose UI is rendered by CI.** Twenty-four
instrumented Compose test files run on an emulator on every Android CI run,
asserting real layout and visibility. Web has 386 frontend unit tests but no
rendered assertion; iOS's `SupermessageUITests` drive a signed-in app against
a real homeserver and therefore cannot run in CI at all — they are
`build-for-testing` only, which compiles them and runs nothing. So the
platform with the fewest previews has the most evidence that its UI draws.

**Android's previews are not beside their views**, and that is forced rather
than chosen: `compose-ui-tooling-preview` could only move out of release
scope because the `@Preview` functions live in `src/debug/kotlin`. A preview
in `src/main` would no longer compile. iOS keeps its previews in the view's
own file under `#if DEBUG`, which is better and is not available here.

## 3. Component by component

`—` means the platform has no such unit. A count is that unit's catalogued
states.

| Concern | Web | iOS | Android |
|---|---|---|---|
| Roster row | `roster/RoomRow` 8 | `RoomRowView` 3 | `RoomRow` 3 |
| Roster section heading | `roster/RosterSectionHeading` 4 | folded into `RoomListView` | folded into `Roster` |
| Roster arrangement menu | `roster/ArrangementMenu` 3 | folded into `RoomListView` | folded into `Roster` |
| Whole roster | `RoomList` (no catalogue) | `RoomListView` 3 | `Roster` 4 |
| Spaces | `SpacesRail` 4 | `SpacePillStrip` 2 | — |
| Space invitation | `SpaceInvitePanel` 3 | folded into `SpacePillStrip` | — |
| Timeline row | `timeline/*` (6 components) | `TimelineRowView` 9 | `TimelineRow` 8 |
| Whole timeline | `Timeline` (no catalogue) | `TimelineView` 2, `TimelineCollectionView` 1 | `Timeline` 3 |
| Decision card | `timeline/DispatchCard` 5 | `CustomEventCard` 6 | `DecisionCard` 6 |
| Log line / placeholder | `timeline/LogLine` 5 | folded into `TimelineRowView` | folded into `TimelineRow` |
| Unread marker | `timeline/UnreadMarker` 1 | folded into `TimelineRowView` | folded into `TimelineRow` |
| Rich text | `RichText` (no catalogue) | `RichTextView` 2 | `RichText` 2 |
| Live turn | `live/LiveTurnBubble` 4, `LiveActivity` 6, `AgentReasoning` 3 | `LiveTurnView` 3 | `LiveTurn` 3 |
| Streaming text | `ai/Shimmer` (no catalogue) | `StreamingTextView` 2 | folded into `LiveTurn` |
| Typing line | `TypingIndicator` 5 | folded into `TimelineView` | folded into `Timeline` |
| Composer | `Composer` (no catalogue) | `ComposerView` 3 | `Composer` 6 |
| Reply banner | `composer/ReplyBanner` 3 | folded into `ComposerView` | folded into `Composer` |
| Staged attachment | `composer/StagedAttachmentChip` 3 | folded into `ComposerView` | folded into `Composer` |
| Mention menu | `composer/MentionMenu` 4 | — | — |
| Connection state | `ConnectionBanner` 5 | `ConnectionBar`, folded into `RootView` 1 | folded into `RootScaffold` |
| Login | (no component) | `LoginView` 2 | `LoginScreen` 2 |
| Account | (no component) | `AccountPanel` 1 | `AccountPanel` 1 |
| Room info | `RoomInfoPanel` (no catalogue) | `RoomInfoPanel` 1 | `RoomInfoPanel` 1 |
| Members | `roster/MemberRow` 4 | folded into `RoomInfoPanel` | folded into `RoomInfoPanel` |
| Room header | `RoomIdentityHeader` 3 | `PaneShell`/`RoomHeader` (not extracted) | folded into `RootScaffold` |
| Search | `SearchPanel` 4 | `SearchPanel` 2 | `SearchPanel` 2 |
| New room | `NewRoomPanel` 2 | `NewRoomPanel` 2 | `NewRoomPanel` 2 |
| Invitation | `InvitationPanel` 3 | `InvitationView` 2 | `InvitationView` 2 |
| Empty room | `layout/EmptyRoomState` 1 | folded into `TimelineView` | folded into `Timeline` |
| Shell | (routes) | `RootView`/`SignedInView` 6 | `RootScaffold` 3 |

### What only one platform has

- **A mention menu** (`composer/MentionMenu`, 4 states). Neither native
  platform has one at all. `Mentionable` crosses the FFI boundary and both
  native composers ignore it.
- **A spaces surface.** Web has a rail, iOS has a pill strip, **Android has
  neither** — no space filter exists on Android, so an account with spaces
  cannot narrow by one there.
- **`Shimmer`** is web-only as a component; iOS reimplements the idea in
  `StreamingTextView` at glyph granularity via `TextRenderer`, and Android
  folds pacing into `LiveTurn` through `:kit`'s `StreamingText`. Three
  implementations of one idea, and no shared statement of what it should look
  like.

## 4. The design language, platform by platform — judgement, not detection

Each row is a rule from `docs/design-language.md`. "Measured" means a number
was produced; everything else is a reading of the code.

| Rule | Web | iOS | Android |
|---|---|---|---|
| Amber means a pending decision and nothing else | **measured**: 3 of 83 stories | asserted by review; 3 previews are pending | asserted by `DebugSourceSetTest`: exactly 1 pending roster fixture |
| Serif for the agent, sans for the operator, mono for data | in `app.css` | `Theme.body`/`own`/`meta` | `SupermessageThemeFonts` |
| Paper is what light means on a phone | n/a (desktop) | `Theme.dynamic` resolves `paper` for light | `SupermessageTheme` resolves `paper` for light |
| The three-level depth ramp | `surface`/`sunken`/`raised` in use | **defined, zero call sites** | reached only through the Material bridge |
| Controls take the palette's accent | yes | **no** — `RoomInfoPanel`'s `Done` renders system blue, seen in its snapshot | through the Material bridge |
| One generated palette | `src/lib/tokens.css` | `Generated/ThemeTokens.swift` | `GeneratedThemeTokens.kt` |

The fourth row is the open one, and it is **P6** rather than this project:
iOS declares `ground`, `sunken` and `hairline` and uses none of them, and
Android reaches the palette only by mapping it onto Material's colour scheme —
so a Material component drawn on Android takes its colours through a
translation layer that no rule in the design language describes. Both were
found by P1 and neither is fixed.

`amber` is the one rule with a real measurement, and only on the web. Nothing
equivalent exists for either native platform: "3 previews are pending" is a
statement about fixtures, not about pixels, and on iOS not one of the 52
previews has been rendered by anyone.

## 5. What this document cannot tell you

- **Whether most native previews render.** No longer unknown, and the answer
  favours the platform that had nothing a week ago. **Android renders all 48**
  — Roborazzi and Robolectric on the JVM, no emulator — and Robolectric drives
  pending work to completion before the frame is taken, so panels that load
  asynchronously show their content. **iOS renders 40 of 52**, and rendering
  is not verifying there: any panel loading through a `.task` captures its
  *loading* frame, and `RoomInfoPanel` is a bare spinner in its own snapshot.

  So the more trustworthy set is Android's, which inverts the assumption this
  arc began with — that iOS was the platform worth investing in because
  Android already had instrumented tests.
- **Whether two platforms' versions of a state look alike.** §3 says both have
  a decision card with six previews. It does not say the two cards agree, and
  no tool here can.
- **Whether the counts mean coverage.** `Composer` has 6 previews on Android
  and 3 on iOS. That is not a claim that Android's composer is better
  catalogued — it reflects that Android's composer takes its reply, edit and
  attachment states as parameters, so each is one line, while iOS's reaches
  them through stores.

## 6. Where to look first, if you can look

For anyone with a working Xcode canvas or Android Studio, in this order,
because these carry a rule rather than an illustration:

1. **Roster rows, all states, both appearances** — the amber rule, and the
   only frame where "exactly one row is amber" is checkable by eye.
2. **The decision card, pending then answered** — the difference between those
   two frames is the whole visual grammar of "this needs you".
3. **Every wrap guard** — the unbreakable body, the unbreakable card value,
   the wide code block. The web equivalent of one of these rendered 1147px
   wide on its first attempt *while appearing to show the guard holding*.
4. **The hostile event type** — a sender-controlled string carrying U+202E.
   If it reorders itself on screen, that is a real defect reachable by anyone
   who can send to a room.
5. **Dark, anywhere faint text sits on a raised surface** — `content-faint`'s
   contract names all three grounds because in dark the ground it fails on
   flips to `surface-raised`.

## 7. Icons, and two gaps the icon audit found

The audit was meant to compare icon *styles* and found something blunter:
iOS draws 17 SF Symbols, Android drew **none**, and the web draws one
disclosure chevron. Where iOS puts a glyph, Android and the web put a word —
"Cancel", "Done", "Copy", "Search".

That divergence is mostly correct and is left alone. A text button is the
Material and web idiom for a dialog action exactly as a toolbar symbol is the
iOS one, and every affordance checked — attach, reply, delete, copy, edit,
new room, search — exists on all three. The rule for when a mark may be text
at all is in `docs/design-language.md` §7.

Two things are **not** correct, and one of them is still open.

### 7.1 Jump-to-newest: an arrow and a chevron (closed, with a note)

iOS shows `arrow.down`; Android shows `Icons.Filled.KeyboardArrowDown`,
a chevron. They differ because `material-icons-core` ships 49 icons and a
plain down-arrow is not among them — it lives in `-extended`, the artifact
known for what it adds to a release build.

Giving iOS `chevron.down` would have matched them in one word, and is the
wrong trade: it lets a library's contents decide the glyph on a platform that
has the right one. The affordance is identical, the position is identical,
and the accessible name is identical on both — "Jump to newest".

### 7.2 The web has no jump-to-newest at all (open)

`Timeline.svelte` scrolls to the newest item when the tail grows, but there
is no control to get back there once a reader has scrolled up. Both mobile
platforms have one, and the iOS source says why in a comment that applies
just as well to the browser: *scrolling through history with no route home is
the thing that makes a long room feel like a trap.*

This is a missing control rather than a mis-drawn one, so it is recorded here
rather than fixed under an icon audit. What it needs is the scroll-position
tracking the virtual list already has, an `isAwayFromNewest` derived from it,
and a button — the shape of it is `TimelineView.swift:66`.
