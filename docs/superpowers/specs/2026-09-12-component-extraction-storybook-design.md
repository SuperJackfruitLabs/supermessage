# Components that take props, and a place to look at them

**Status:** design approved 2026-09-12, not yet planned or implemented.
**Scope:** P2a. P2b (native previews, parity inventory) is specced separately.
**Follows:** `2026-09-12-design-language-tokens-design.md` (P1, landed).

## 1. The problem

The desktop UI is roughly 7,100 lines. Six files carry most of it, and the
two largest are doing far too much:

| File | Lines | Shape |
|---|---|---|
| `Timeline.svelte` | 2,618 | 948 script · 1,121 markup · 547 style |
| `+page.svelte` | 1,070 | 565 script · 505 markup |
| `Composer.svelte` | 882 | |
| `RoomList.svelte` | 475 | |
| `RoomInfoPanel.svelte` | 352 | |
| `LiveTurn.svelte` | 315 | |

Three consequences.

**The signature element has no boundary.** The dispatch card — the thing
`2026-08-13-console-design.md` §7 calls the signature element, and the one
differentiator of an agent-aware client — is roughly 215 lines of markup
inside `Timeline.svelte`. It is a separate file on iOS (`DecisionCard.swift`)
and on Android (`DecisionCard.kt`). On the platform where it is easiest to
iterate, it is the hardest to find.

**Nothing can be looked at in isolation.** There is no component catalogue, so
the UI's states cannot be enumerated. That is the mechanism behind the drift
P1 documented: nobody could see that web had `AgentReasoning`, `Shimmer` and
`LiveActivity` while native had none, or that native had a real
`DecisionCard` while web did not.

**Components read stores, so they cannot be given fixtures.** *Fifteen*
components import from `$lib/stores`. `AGENTS.md` rule 1 says the webview "parses
nothing and decides nothing" and receives `TimelineRow`/`RoomRow` values the
core has already computed — but the components reach for the store rather
than receiving the value, which is the one part of that rule the frontend
does not honour.

## 2. Decisions

1. **Props down, stores at the route.** Extracted leaves take view-models as
   props and read no stores. This is `AGENTS.md`'s own rule applied one level
   further in.
2. **All fifteen store-reading components**, not just `Timeline.svelte`.
   The six largest carry the structure; the nine small ones carry a third of
   the catalogue.
3. **Caches resolve in containers.** A leaf takes `avatarUrl: string | null`,
   never an `AvatarCache`.
4. **Storybook is a design surface**, plus `addon-a11y`. Assertions stay in
   vitest. No play functions, no second test surface.
5. **One fixtures module**, shared by the stories and the existing tests.
6. **vitest stays node-only.** No component render tests; see §8.
7. **The token generator gains explicit appearance selectors** so a catalogue
   can switch between them; see §7.2.

## 3. Scope

P2 as originally decomposed was ~7,100 lines of refactor plus Storybook plus
previews across 44 native files plus a parity table. That is more than one
spec can carry, so it splits:

- **P2a — this document.** Web extraction, fixtures, Storybook, stories.
  Verifiable end to end on a macOS development machine.
- **P2b — separate.** `#Preview` on 21 SwiftUI views, `@Preview` on 24
  Compose files, and the parity inventory that compares all three platforms
  against the story list this project produces.

P2b is deliberately second. It touches the two platforms that cannot be
verified locally — there is no Android SDK on the development machine, and
Xcode 16.4's iOS 18.5 SDK cannot deploy to the available iOS 26.6.1 device.
P1 lost three CI round-trips to that gap. P2a has no such exposure.

## 4. The component breakdown

Containers keep state; leaves are pure functions of props.

| From | Container keeps | Leaves |
|---|---|---|
| `Timeline.svelte` | scroll, follow, pagination, gap-sync, store reads | `TimelineRow`, `MessageBubble`, `ReplyQuote`, `ReactionsRow`, `MessageActions`, `SeenMarker`, `LogLine`, **`DispatchCard`**, `ImageAttachment`, `FileAttachment`, `UnreadMarker` |
| `+page.svelte` | pane `matchMedia`, store wiring, selection | `PaneShell`, `RoomHeader`, `EmptyRoomState` |
| `Composer.svelte` | draft, IME, send, upload | `MentionMenu`, `StagedAttachmentChip`, `ReplyBanner` |
| `RoomList.svelte` | roster arrangement, store reads | `RoomRow`, `RosterSection`, `ArrangementMenu` |
| `RoomInfoPanel.svelte` | member loading | `MemberRow`, `RoomIdentityHeader` |
| `LiveTurn.svelte` | stream subscription | `LiveTurnBubble` |

And nine smaller components — 984 lines in total — that read stores and so
cannot currently be given fixtures. They keep their own files and simply stop
reading stores, taking view-models and callbacks instead:

`AgentReasoning` (135) · `SpacesRail` (156) · `NewRoomPanel` (186) ·
`SearchPanel` (146) · `SpaceInvitePanel` (112) · `InvitationPanel` (79) ·
`LiveActivity` (75) · `TypingIndicator` (52) · `ConnectionBanner` (43)

They are included because leaving them out would leave roughly a third of the
catalogue without stories, which undercuts the reason for building one. Four
of them — the modal panels — are also the components that use the `scrim` and
`elevation-overlay` tokens P1 changed, so they are among the most worth
looking at. Their small size is not incidental: they are where the pattern is
cheapest to establish before it is applied to `Timeline.svelte`.

**The seams are not invented.** `Timeline.svelte`'s markup is already seven
top-level Svelte 5 `{#snippet}` blocks — `replyQuote`, `reactionsRow`,
`messageActions`, `seenMarker`, `logLine`, `messageBlock`, plus nested
`bubbleContent`, `imageContent` and `mediaFileContent`. A snippet is already
a reusable markup unit; this promotes each to a component with a typed
interface.

### 4.1 What does not move

The scroll and pagination machinery stays in the container, untouched.
`timelineFollow.ts`, `gapSync.ts` and `timelinePane.ts` are the subtlest
code in the frontend, they have their own tests, and they are what produced
the "opening a room shows one message" bug that code-reading missed and the
WebDriver harness caught. Moving them buys nothing and risks the one part of
this file that has already bitten.

### 4.2 Styles

Five scoped classes, each travelling with its markup: `.dispatch-card` and
`.dispatch-card-pending` to `DispatchCard`, `.reaction-chip-mine` to
`ReactionsRow`, `.fade-in` to `TimelineRow`.

The 23 `:global()` rules all descend from `.message-html` and style rendered
rich text. They move as one block to the component that renders message HTML.
Because they are already global-scoped, nothing about their behaviour changes
— which is the point: a `:global()` rule that gets re-scoped silently stops
applying, and rendered message content is exactly where that would be least
visible.

## 5. What crosses the boundary

Three kinds of thing, three treatments.

**Data → view-model props, unchanged.** `TimelineRow`, `RoomRow`,
`RosterSection` and `RoomIdentity` arrive from the core already decided
(`src/lib/ipc.ts`). Leaves take those types directly. No mapping layer,
because a mapping layer is where two hosts begin to disagree — the reason the
render classification moved into Rust in the first place.

**Actions → callback props, explicitly typed.** `onReact`, `onReply`,
`onResolveDecision`, `onSelectRoom`. `RoomsStoreDeps` is already entirely
commands (`joinRoom`, `leaveRoom`, `logout`, `roomsResync`), so this is the
same shape one level in. A story passes a spy and nothing reaches Tauri.

**Caches → resolved values, not the cache.** `RoomRow` takes
`avatarUrl: string | null`; the container calls `avatarCache.get(room.id)`
and passes the result.

The alternative — passing the cache down, or reaching for Svelte context —
would make every leaf depend on a service, and then every story would need a
decorator to supply one. That is the same invisible coupling as mocking the
stores, relocated. Resolving in the container keeps each leaf a pure function
of plain data, so a story is a literal.

The prop-drilling cost is two levels at its deepest (`+page` → `RoomList` →
`RoomRow`; `Timeline` → `TimelineRow` → `MessageBubble`). Two explicit props
read more easily than a context nobody can see.

**Each leaf exports its `Props` interface**, so the component, its story and
any future test share one type instead of three drifting copies.

### 5.1 The stores are already ready for this

Worth recording, because it makes the work smaller than it looks: the stores
are factory functions with injectable dependencies —
`createAvatarCache(deps: AvatarCacheDeps)`,
`createRoomsStore(deps: RoomsStoreDeps)` — and those interfaces exist so the
386 existing tests can stub them. Only six call sites construct a store
themselves; the rest import a singleton. The coupling to remove is narrow.

## 6. Fixtures

`src/lib/fixtures/` — `timeline.ts`, `rooms.ts`, `customEvents.ts`,
`members.ts` — becomes the single definition of what a view-model looks like.

**Builders**, in the pattern the tests already use: `message({ body })`,
`membership({ detail })`, `roomRow({ pendingDecision: true })`, each taking
`Partial<T>` overrides. The 11 builders currently private to four test files
(`timelineGrouping.test.ts` has eight) move here, so there is one answer to
"what shape is a `TimelineRow`" rather than five.

**Named scenarios** on top, which are the actual content of a design surface
— the states worth looking at:

```
dispatchCardPending      dispatchCardAnswered     dispatchCardPlaceholder
encryptedPlaceholder     senderRun                dateDivider
ownMessageSending        ownMessageFailed         longUnbrokenToken
reactionsMine            reactionsMany            replyToDeleted
rosterQuiet              rosterApprovalNeeded     rosterAgentWorking
```

That list answers a question the P1 audit raised and could not settle: **what
states does this UI have?** They are currently uncountable, which is why
drift between platforms was invisible. A named scenario per state makes the
set enumerable, and it is what P2b's parity table will compare iOS and
Android against.

**Sharing with the existing tests is a deliberate tradeoff.** It buys one
source of truth and costs a blast radius — a fixture change can now fail many
tests at once. That is the correct direction: if the shape of a `TimelineRow`
changes, everything depending on it should notice.

**Fixtures must never reach the production bundle.** Nothing in app code
imports `$lib/fixtures`, so Vite tree-shakes it — but "so it should" is not
verification. §8 adds a check.

## 7. Storybook

### 7.1 Setup

`storybook@10.6`, `@storybook/sveltekit`, `@storybook/addon-svelte-csf`,
`@storybook/addon-a11y`. All devDependencies, all MIT, all clearing the
repository's dependency-licence rule. Peers satisfied: the framework wants
`svelte ^5` and `vite ^5–^8`; this repository is on Svelte 5 and Vite 6.

`.storybook/preview.ts` imports `src/app.css`, so stories render through the
**real generated tokens** rather than a copy. A palette change changes the
catalogue; there is no second set of values to drift.

Stories are co-located: `DispatchCard.svelte` and
`DispatchCard.stories.svelte` in one directory, Svelte CSF with `defineMeta`.
A story that drifts from its component is then a one-directory problem
rather than a search.

### 7.2 The change P1's generator needs

`src/lib/tokens.css` currently reaches dark **only** through
`@media (prefers-color-scheme: dark)`. Paper is attribute-selectable; dark is
not. So a catalogue could switch to paper but would need the operating
system's theme changed to show dark — and on a dark-set machine, light would
be unreachable. A design surface whose job includes comparing appearances
cannot work that way.

The app CSS emitter therefore emits every appearance as an explicit selector
as well:

```css
@theme { /* light — the default */ }
@media (prefers-color-scheme: dark) {
  :root:not([data-appearance]) { /* dark, when the OS asks and nothing overrides */ }
}
[data-appearance="light"] { … }
[data-appearance="dark"]  { … }
[data-appearance="paper"] { … }
```

The `:not([data-appearance])` guard makes an explicit choice beat the OS in
both directions. One emitter change and a golden refresh, for three returns:
Storybook gets a working light/dark/paper toolbar; the application gains the
user-facing appearance chooser P1's §6.1 explicitly deferred as "available
later"; and forcing light on a dark machine becomes possible, which it is
not today.

The alternative — having Storybook fake the theme with its own CSS — would
mean the catalogue showing something the application cannot produce, which
defeats the purpose of importing the real tokens.

### 7.3 A11y addon

`addon-a11y` runs axe per story. With contrast already derived and asserted
by P1, it is expected to pass on colour — which makes it useful for what it
will actually catch: ARIA and focus-order problems in the newly
extracted and converted components.

## 8. Verification

**The 386 existing tests do not cover what this project changes.** They are
pure-module tests in a node environment, and the repository documents the
reason in `timelineActionAnchor.test.ts`: *"asserted on the source rather
than a rendered DOM because `vite.config.js` pins `environment: "node"` — the
timeline is tested through extracted pure modules, not by mounting
components."* There are zero component render tests. Moving markup out of six
files therefore has no automated safety net, and pretending otherwise would
be the most dangerous assumption available here.

**The primary gate is a before/after DOM comparison of the real
application.** A pure extraction should produce identical rendered DOM. Drive
the running app through the Tauri MCP bridge (`pnpm tauri:mcp`,
`@hypothesi/tauri-plugin-mcp-bridge`), serialise the roster and timeline DOM
before the refactor and again after each batch, and diff. That answers the
actual question — did moving this markup change the output? — more directly
than any test written for the purpose.

`scripts/e2e-drive.py`, the harness that caught the one-message bug, is
Linux and Windows only because macOS has no WKWebView driver. The MCP bridge
is the macOS path.

**Supporting checks, all of which already exist:**

- `pnpm check` — svelte-check. A real gate here, not a formality: thirty-two
  components gain or change a `Props` interface, and a dropped or
  mistyped prop is exactly what it catches.
- `pnpm test` — the 386 must stay green. They cover the logic the containers
  keep, so a failure means state moved that should not have.
- `storybook build` in CI — a story referencing a prop that no longer exists
  should be a red build, not a surprise next time someone opens the
  catalogue. Same reasoning as P1's token drift gate.
- A fixture-leak check: grep the production bundle for a marker string
  exported from `$lib/fixtures`.

**vitest stays node-only.** The alternative — a second vitest project with
jsdom and testing-library for the leaves — is genuinely attractive once
components are pure props-in, and it would make them permanently
regression-protected rather than checked only at extraction time. It is
declined here to avoid a second test surface and to leave a documented
decision standing. §9 records it as open.

## 9. Open questions

- **Should the leaves get render tests now that they are mountable?** The
  node-environment decision was made because components could not be mounted
  in isolation, and this project removes that premise. Recorded rather than
  decided; worth revisiting once there is something concrete to test.
- **`+page.svelte` and `Composer.svelte` hold the subtlest remaining
  behaviour** — pane `matchMedia` geometry and draft/IME/mention handling.
  Both are in scope for extraction of their *leaves*, but if the DOM diff
  shows movement there, the right response is to narrow rather than push
  through.
- P1's open question stands: the three platforms disagree about when panes
  split (web 1238, `RootView.swift` 1000, Android measured). P2a does not
  touch it.

## 10. Non-goals

- **No redesign.** Every component renders what it renders today. The DOM
  diff in §8 is the definition of done, and a visual change is a defect.
- Native previews and the parity inventory — P2b.
- Icons — P3. Native accessibility — P4. i18n — P5. Native surface
  adoption — P6.
- No new runtime dependency. Storybook and its addons are devDependencies.
- The scroll, follow and pagination logic is not refactored (§4.1).
