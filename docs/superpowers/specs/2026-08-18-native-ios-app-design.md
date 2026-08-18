# Native iOS app — design

**Status:** Decided (18 Aug 2026).
**Predecessor:** [`2026-08-18-native-ios-core-decoupling-design.md`](2026-08-18-native-ios-core-decoupling-design.md), which built the boundary this spec builds on and explicitly deferred the app itself: *"It does not design the SwiftUI app — that gets its own spec once the boundary exists and we know what it is like to work against."* It exists now. This is that spec.
**Companions:** [`2026-08-13-console-design.md`](2026-08-13-console-design.md) (visual authority), [`../../tech-stack.md`](../../tech-stack.md) (corrected by §9 below), [`../../positioning.md`](../../positioning.md).

---

## 1. What this builds

A native SwiftUI client for iPhone and iPad, at **full parity with the Svelte
desktop app**, driven by the existing Rust core over the UniFFI boundary. Svelte
remains the desktop UI. Android goes native later, and that fact shapes §3.

Parity means every capability the Svelte app has today: sign in, spaces, room
list, timeline with pagination, send / reply / react / edit-awareness,
attachments, typing, live agent turns, search, room info, room creation,
invitations, and decisions. It does **not** mean push notifications — the
desktop app has none, so neither does this. The APNs/Sygnal path stays a
separate programme.

### 1.1 Decisions taken, and by whom

| Decision | Choice |
|---|---|
| Scope | Full parity in one design, not a spine-first slice |
| Typography | System faces — SF Pro, New York, SF Mono — not the desktop's Plex/Source Serif |
| Devices | iPhone **and** iPad |
| State architecture | Ported stores + a serial event pump (§5) |
| Shared logic | Split by risk: wire contracts move to Rust, presentation is written natively (§3) |
| Deployment target | iOS 18.0 |

The typography choice deserves its reason recorded: the identity that matters is
**structural** — serif for what agents write, sans for what the operator writes,
mono for data and sigils, and amber for nothing but a pending decision. That
structure survives a typeface swap intact. New York reads better than Source
Serif at phone sizes, Dynamic Type comes free rather than as a hand-built ramp
over a custom face, and three variable font families stay out of the binary.
Desktop keeps Plex. The two apps are siblings, not twins.

---

## 2. Two parts, one dependency

This spec covers two bodies of work with a hard ordering between them.

**Part A — the shared view-model migration.** ~1,900 lines of TypeScript move
into the Rust core and are exposed as resolved DTOs; the Svelte app is rewired
to consume them. The desktop test suite stays green throughout. Part A ships
independently and is valuable on its own.

**Part B — the SwiftUI app.** Consumes the widened boundary Part A produces.

Part A lands first. They get separate implementation plans; they share this
spec because the partition argument and the DTO shapes are one design, and a
Part B document that could not name concrete types would be useless.

---

## 3. Part A — the risk split

### 3.1 The principle

The Svelte app carries **~4,090 lines of `src/lib/components/*.ts`** that
contain no Svelte at all: pure functions over the DTOs the core emits. iOS
needs the same answers from every one of them, and so will Android.

The test that decides where each module lives:

> **If iOS and desktop disagreed about this, would it be a bug or a design
> difference?**

Disagreeing about whether an untrusted payload contains a valid decision is a
bug. Disagreeing about when to collapse a message header is a design
difference. The first kind moves to Rust; the second is written natively per
platform.

The forcing case is `customEvents.ts`. It turns a `kind: "customMessage"` item
into a rendered card, and it is where **a permission request becomes a
decision** — wedge #3 in `positioning.md`, the reason this product exists. It
parses arbitrary JSON from anyone who can send to the room. It encodes a
two-axis versioning contract co-designed with Kaambaan (major version in the
event type string, minor as a `schema_version` integer in `content`). It bounds
and validates decisions so a malformed one degrades to nothing rather than
rendering a bogus Allow button. Three hand-written implementations of that,
agreeing by convention, will drift — and the drift renders a wrong approval
prompt before anyone notices.

### 3.2 The partition

**Moves to Rust (~1,912 lines of TypeScript):**

| Module | LOC | Why it is a wire contract |
|---|---:|---|
| `customEvents.ts` | 682 | Untrusted JSON; versioning contract; decision validation |
| `timelineItemView.ts` | 425 | Semantic classification of a Matrix event into a render decision |
| `roomIdentity.ts` | 281 | Suite-wide agent sigil/role convention |
| `matrixLinks.ts` | 186 | `matrix.to` URI parsing — protocol |
| `mentions.ts` | 138 | Detecting a mention of the logged-in user |
| `roomPreview.ts` | 125 | Last-message preview; keys off decision-bearing event types |
| `invitationView.ts` | 75 | Membership → affordance |

**Written natively per platform (~2,178 lines):** `timelineGrouping.ts` (312),
`stagedAttachment.ts` (455, minus its validation rules which the core already
owns), `messageLinks.ts` (190), `emojiPicker.ts` (172), `spacesRailView.ts`
(158), `pacer.ts` (121), `roomInfoView.ts` (88), `typingTracker.ts` (88),
`timelineFollow.ts` (87), `timelineCache.ts` (79), `roomCreation.ts` (75),
`timelinePane.ts` (71), `draftTracker.ts` (65), `searchView.ts` (60),
`typingView.ts` (53), `readTracking.ts` (52), `animateList.ts` (52).

These are genuinely presentational: scroll thresholds, pacing intervals,
grouping windows, picker state. A phone *should* differ from a workstation
here.

### 3.3 New DTOs

All derive `uniffi::Record`/`uniffi::Enum` and are additions to
`supermessage-core::dto`. Field names are snake_case in Rust, as UniFFI
requires; the generated Swift is camelCase.

#### 3.3.1 Rich text

Both message rendering paths collapse into one tree. Today the timeline has two:
`formatted_body` (sanitised `org.matrix.custom.html`, sent by human clients on
Element) rendered with `{@html}`, and — for **every agent message**, because the
hub generates no `formatted_body` — raw markdown in `body`, tokenised by
`svelte-streamdown` and rendered to components with raw HTML dropped.

The core parses both into `Vec<RichBlock>`: `pulldown-cmark` (MIT) for markdown,
the existing ruma-html sanitiser pass for the HTML case. The guarantee that raw
HTML inside agent markdown is *dropped, not escaped and not shown* is then made
once, in Rust, instead of re-argued per platform. **iOS ships no markdown or
HTML parser.**

```rust
pub enum RichBlock {
    Paragraph { inlines: Vec<RichInline> },
    Heading { level: u8, inlines: Vec<RichInline> },
    CodeBlock { language: Option<String>, text: String },
    BlockQuote { blocks: Vec<RichBlock> },
    List { ordered: bool, start: u32, items: Vec<RichListItem> },
    ThematicBreak,
    Table { header: Vec<RichTableCell>, rows: Vec<RichTableRow> },
}

pub struct RichListItem { pub blocks: Vec<RichBlock> }
pub struct RichTableRow { pub cells: Vec<RichTableCell> }
pub struct RichTableCell { pub inlines: Vec<RichInline> }

pub enum RichInline {
    Text { text: String },
    Emphasis { inlines: Vec<RichInline> },
    Strong { inlines: Vec<RichInline> },
    Code { text: String },
    Link { href: String, inlines: Vec<RichInline> },
    Break,
}
```

The block vocabulary is deliberately small and matches what the desktop renders
today. **No syntax highlighting, no mermaid, no math.** `AgentProse.svelte`
refused all three on purpose — shiki's grammars alone are several megabytes, and
"a code block lit up in six competing hues would be the loudest thing in the
window" when the whole palette runs on one accent. That reasoning is stronger on
a phone, not weaker.

**Depth cap.** This parses untrusted input into a recursive structure, so nesting
is capped at **16 levels**; content deeper than that is flattened to its plain
text. Without the cap, a deeply nested markdown document is a stack overflow in
the parser or in either host's renderer.

**Known risk.** UniFFI must generate a recursive Swift enum (`indirect enum`)
for `RichBlock`/`RichInline`. If UniFFI 0.28 cannot, the fallback is a flat
pre-order token stream (`Vec<RichToken>` with explicit open/close markers) that
each host folds back into a tree. Proving this generates is the first task of
Part A, before anything is built on it.

#### 3.3.2 Custom events and decisions

Mirrors the TypeScript contract exactly, including the distinction its comments
insist on — this is a **UI contract, not a wire schema**; a renderer translates
whatever its event type carries into this shape and never passes a payload
object through.

```rust
pub struct CustomEventField { pub label: String, pub value: String }

pub struct CustomEventDecisionOption {
    /// Display text. Bounded like any other field.
    pub label: String,
    /// An identifier, never rendered; handed back verbatim on answer.
    /// Deliberately not truncated.
    pub id: String,
}

pub struct CustomEventDecision {
    pub prompt: String,
    pub options: Vec<CustomEventDecisionOption>,
}

pub enum CustomEventView {
    /// A registered renderer produced fields.
    Rendered {
        fields: Vec<CustomEventField>,
        /// True when `schema_version` exceeds the renderer's known maximum:
        /// rendered best-effort, flagged so the host can note it.
        newer_version: bool,
        /// Validated and bounded. `None` means no decision, and a malformed
        /// decision degrades to `None` rather than to a bogus prompt.
        decision: Option<CustomEventDecision>,
    },
    /// No renderer, but the event carried a plain-text `body` fallback.
    Fallback { body: String },
    /// Nothing usable.
    Placeholder { text: String },
}
```

Every `value`, `label`, `prompt` and `text` above is **text only**. No host may
route them into markup, an `href`, an `src`, or a style. The Rust renderers keep
the TypeScript discipline of reading named fields one level at a time rather
than walking the payload recursively — that single rule is what makes a huge or
deeply nested payload harmless without a runtime depth guard.

The three shipped renderers port across: `dev.supermessage.demo.note.v1`,
`dev.agentpod.turn.v1`, `dev.agentpod.permission.v1`.

#### 3.3.3 The item render decision

`timelineItemView.ts`'s `ItemView` becomes a Rust enum, variant for variant:

```rust
pub enum ItemView {
    Bubble { muted: bool, blocks: Vec<RichBlock> },
    Emote,
    System { text: String },
    UnreadMarker,
    Placeholder { text: String },
    Image { alt: String, width: Option<u32>, height: Option<u32> },
    MediaFile {
        label: MediaFileLabel,
        filename: String,
        size: Option<u64>,
        mimetype: Option<String>,
    },
    CustomEvent { view: CustomEventView },
    None,
}

pub enum MediaFileLabel { File, Audio, Video }
```

`Bubble` carries its parsed `blocks` so a host never sees `body` or
`formatted_body` on the rendering path. `alt` is always non-empty (falling back
through `media.filename`, then `body`, to a generic "Image"), and `width`/
`height` let a host reserve the thumbnail's box before its bytes are requested —
on iOS that is what stops the lazy stack reflowing when an image lands.

**`ItemView` is computed in the core as each item is built, and travels with
it.** The timeline's element type becomes:

```rust
pub struct TimelineRow {
    pub item: TimelineItemDto,
    pub view: ItemView,
}
```

so `DiffEnvelope<TimelineItemDto>` becomes `DiffEnvelope<TimelineRow>`, and
`TimelineSnapshot::items` follows. The FFI's `TimelineDiffOp` mirror and the
`CoreEvent::TimelineDiff` variant change type accordingly.

The alternative — a `timeline_item_view(item)` method a host calls per row — was
rejected: it is an FFI round trip **per visible row per scroll frame**, which is
exactly the cost profile a lazy list cannot absorb. Computing once, at the point
the item is constructed, also means the markdown for a message is parsed once in
its lifetime rather than on every re-render.

`TimelineItemDto` itself is unchanged, so hosts that want raw fields — search
results, room previews — keep getting them.

#### 3.3.4 The remaining four

`RoomIdentity { sigil: Option<String>, name: String, role: Option<String> }`,
`RoomPreview { text: String, is_own: bool, names_sender: bool, pending_decision: bool }`,
`InvitationAffordance` (an enum over the membership states), and
`MatrixLink` (the parsed `matrix.to` target) follow the same pattern: a pure
function on `Core`, a record on the wire.

### 3.4 Rewiring the Svelte app

Each migrated module's TypeScript is deleted and replaced by a thin call
through `$lib/ipc.ts` to the new command, with the DTO consumed directly. The
Svelte components' markup does not change shape — `Timeline.svelte` already
switches on `viewFor(item)` and never makes the decision itself, which is
exactly why this migration is possible without redesigning it.

`Timeline.svelte`'s two `{@html}` sites go away entirely: with `Bubble`
carrying `blocks`, there is no HTML string left to interpolate. That removes the
single most carefully-guarded escape hatch in the desktop app, and with it the
three-paragraph comment chain justifying it.

**The desktop suite is the safety net.** Every migrated module has an existing
TypeScript test file; those tests are ported to Rust as the migration's
specification, and the Svelte-side tests that exercise the components through
the new DTOs stay.

---

## 4. Part B — targets and modules

```
apple/
  Supermessage/       app target        Swift 6   SwiftUI views only
  SupermessageKit/    framework target  Swift 6   boundary + stores; imports no SwiftUI
  SupermessageFFI/    static target     Swift 5   Generated/*.swift + Supermessage.xcframework
  project.yml         xcodegen
```

Deployment target **iOS 18.0**, Xcode 16.4, iOS 18.5 SDK.

The Swift-5 island exists because UniFFI 0.28's generated code is not
`Sendable`-clean and would bury a Swift 6 build in concurrency diagnostics that
are not ours to fix. Quarantining it lets everything else compile under **Swift 6
strict concurrency**, which turns the app's central invariant — nothing blocking
on the main actor, no store mutated off it — from a comment into a compile
error.

`SupermessageKit` importing no SwiftUI is enforcement, not taste: it is what
lets the entire state layer be tested without booting a simulator.

`apple/Probe` is deleted. It answered its question.

---

## 5. Part B — the boundary

Three files carry the risk.

### 5.1 `CoreClient.swift`

An `actor` owning the `Core` object. Each of the 29 FFI methods becomes an
`async` wrapper that performs the call inside `Task.detached`. **Every `Core`
method blocks the calling thread** — it is a synchronous Rust function that
`block_on`s the tokio runtime — so this is the only thing standing between the
app and a frozen main thread. Nothing above this file holds a `Core` reference.

### 5.2 `EventPump.swift`

The highest-risk code in the app, and about forty lines of it.

A `nonisolated final class` conforming to the generated `EventSink` protocol.
Its `emit` does exactly one thing — `continuation.yield(event)` into an
**unbounded** `AsyncStream` — and returns. The core's contract is explicit:
*"Implementations must not block: this is called from inside sync and timeline
processing, and a slow sink stalls the client rather than the UI."*

Exactly one `@MainActor` task drains that stream with `for await` and dispatches
each event to its store.

**Why one stream and one consumer.** `DiffEnvelope` carries a `seq`, and the
timeline's recovery logic is built on those arriving in order. A
`Task { @MainActor in … }` per event does **not** preserve ordering, and
out-of-order diff application corrupts the reader's view in a way that presents
as a rendering bug rather than as a data fault. `event.rs` states the
requirement; this file is where it is met.

### 5.3 `DiffApply.swift` and `GapSync.swift`

Ports of `src/lib/stores/diff.ts` (140) and `gapSync.ts` (188) — transcriptions
of already-debugged logic, carried over **with their comments**, because the
hazards they encode are not visible from the code alone.

`applyOps` must agree with `dto::apply_ops` operation for operation, including
out-of-range handling: `set`/`remove` out of bounds are no-ops, `popFront`/
`popBack` on an empty list are no-ops, `insert` is a no-op when `index > length`
but a valid append when `index == length`. The core's own resync snapshot is
maintained by folding the same op stream through `apply_ops`, so a divergence
here corrupts state silently on every resync.

`DiffTracker` detects a dropped envelope by sequence number: ahead of expected
means gap (return without touching state — applying partial state is the
corruption the type exists to prevent), behind means duplicate (ignore).
Sequences start at 1.

`GapSync` adds the three hazards that took real incidents to find:

1. **Subject filtering.** A channel's sequence is monotonic per channel *and
   subject*. The timeline channel's subject is the focused room id, and it
   changes while a subscribe round trip is in flight. An envelope for a subject
   the store no longer shows is not a gap and not a duplicate — it is somebody
   else's data, and the only correct thing to do is drop it. Treating it as a
   gap resyncs off the previous room and installs its messages in this one.
2. **In-flight resync guard.** While a resync is in flight the core keeps
   emitting on the same channel. Applying those against the pre-reset tracker
   rediscovers the same gap forever.
3. **Generation counter.** A resync issued under one subscription context and
   landing after the context changed must be discarded, or a slow resync rolls
   the new room's state back to the old room's data.

Plus `seed()`, for a store built after the core has already spoken. The channel
only speaks when something *changes*; a store that starts empty stays empty
until the next change, which in a quiet account is minutes. On iOS this is not
an edge case — it is what happens on **every return from background** (§8).

Swift gets one generic `GapSync<T>`. UniFFI's monomorphised `RoomDiffOp` and
`TimelineDiffOp` are bridged into a shared generic `DiffOp<T>` at the edge,
undoing the flattening the FFI forced.

---

## 6. Part B — the stores

Eleven `@MainActor @Observable` classes in `SupermessageKit`, one per Svelte
counterpart:

`ConnectionStore`, `RoomsStore`, `TimelineStore`, `SpacesStore`, `TypingStore`,
`LiveStore`, `ReplyTarget`, `DraftStore`, `AvatarCache`, `MemberAvatarCache`,
`MediaCache`.

Each owns its slice and reaches into no other. The pump is the only writer of
event-driven state; `CoreClient` is the only path outward. `RoomsStore` and
`TimelineStore` each own a `GapSync`; the rest are plain event handlers.

**One deliberate divergence from the Svelte originals:** the three caches are
unbounded dictionaries of `data:` URIs on desktop, which is fine for a session
on a workstation and is not fine on a phone. They become `NSCache`-backed with
count limits, which also buys eviction under memory pressure.

---

## 7. Part B — navigation and screens

One container serves both size classes: `NavigationSplitView` collapses to a
push stack on iPhone by itself.

**iPad (regular width).** Two columns plus an inspector. The sidebar holds the
spaces rail as a fixed 52pt strip beside the room list — *not* a third
`NavigationSplitView` column, because SwiftUI enforces a ~200pt column minimum
that would turn a row of sigils into a mostly-empty panel. This matches the
desktop layout, where `SpacesRail` sits beside `RoomList`. Room info uses
`.inspector()`, sliding in beside the timeline instead of covering it.

**iPhone (compact).** Room list pushes to timeline. The spaces rail has no room,
so spaces become a **horizontal pill strip as a scroll-away list header** —
present on arrival, giving its ~40pt back as soon as the operator scrolls. The
current space name also sits in the navigation title, so scope stays legible
once the strip has scrolled off.

| Panel | iPad | iPhone |
|---|---|---|
| Room info | `.inspector()` beside the timeline | Sheet, `.medium` + `.large` detents |
| Search | Sheet | Full-height sheet |
| New room | Sheet | Sheet |
| Space invite | Sheet | Sheet |
| Invitation (join / decline) | Inline where the composer would be — not a panel, same as desktop | Same |

---

## 8. Part B — the reading surface, composer, session

### 8.1 Timeline

**`ScrollView` + `LazyVStack`, not `List`.** `List` imposes separators, insets
and selection behaviour that fight an editorial layout, and its cell reuse makes
precise scroll anchoring harder rather than easier. `.defaultScrollAnchor(.bottom)`
opens at the newest message.

**Back-pagination anchoring** is the hardest problem in the app.
`.scrollPosition(id:)` is bound to the topmost visible item's id; when
`timeline_paginate_back` prepends older items, the anchored id holds its screen
position and content grows upward off-screen. `onScrollGeometryChange` (iOS 18)
drives both the pagination trigger and the distance-from-bottom that follow-scroll
needs — it is the reason the deployment target is 18 rather than 17.

**Follow-scroll** ports `timelineFollow.ts` including `shouldSettleAtBottom`,
which handles a room opened mid-history where the entire backlog arrives as a
single batch and `shouldRepin`'s first-observation discard would otherwise leave
the view stranded.

**Typography.** Peer message bodies in New York; the operator's own in SF,
tinted and indented; sigils, roles and timestamps in SF Mono. Consecutive
messages from one sender drop the header (`timelineGrouping.ts`, ported
natively). Type comes from `Font.system(_:design:)` throughout — `.serif`
resolves to New York, `.monospaced` to SF Mono, the default to SF Pro — so
Dynamic Type scaling is inherited rather than hand-built. No `Font.custom`
anywhere; that was the cost of the Plex option, and the Plex option was not
taken.

**Amber appears in exactly one place: a pending decision.** Not on unread
badges, not on hover, not on the connection bar. The console spec calls any
other use a review defect, and that rule travels to iOS unchanged. The decision
card is built from `CustomEventView.Rendered.decision`, so both hosts agree on
whether a payload contains a valid decision at all.

**Live turns** render above the composer from `LiveStore`: reasoning collapsed
by default, tool calls as they fire, and the answer streaming with a caret —
one honest signal that text is still arriving.

### 8.2 Composer

`TextField(axis: .vertical)` inside `safeAreaInset(edge: .bottom)`. That deletes
the largest risk in `tech-stack.md` — *"iOS keyboard doesn't resize WKWebView —
#1 'web tell' in a chat app… ~200 lines objc2 Rust resizing the webview frame;
treat as core work, not polish."* SwiftUI does keyboard avoidance natively, and
the 16px focus-zoom rule that broke the Tauri build does not exist outside a
webview.

Around it: per-room drafts, typing notifications throttled through a ported
pacer, the reply chip, the staged-attachment chip, and an emoji picker for
reactions (ported — iOS exposes no system picker to insert from). Mention
*detection* is Rust (§3.2); the autocomplete popover is native.

Attachments use `PhotosPicker` for images and `.fileImporter` for everything
else; both write to a temp URL whose path goes to `attachment_stage_path`. This
is why `FilePicker` never needed to cross the FFI boundary — the host picks, the
core receives a path.

### 8.3 Session, lifecycle, errors

Launch calls `restore_session(sink)`. `true` goes to the room list; `false` to a
login screen (`m.login.password` remains the only flow `id.agentpod.dev`
advertises). Credentials land in the iOS Data Protection keychain, which
`supermessage-core` already configures.

**Lifecycle is the one thing iOS needs that desktop never did.** A suspended app
loses its sockets, and `sm://` channels only speak when something changes. On
`scenePhase → .active`, both gap syncs are `seed()`ed — precisely what `seed()`
was written for. On `→ .background`, `set_typing(false)`.

`FfiError`'s nine variants map through a single `ErrorPresenter` so no view
invents its own wording: `Auth` returns to login, `Network` raises the connection
bar, `RoomChanged` and `NotReady` are silent retries, and `AttachmentTooLarge`,
`UnknownAttachment` and `UnknownSpace` surface inline where they occurred.

---

## 9. Removals and corrections

**Deleted:** `src-tauri/gen/apple` and the Tauri iOS target; `apple/Probe`; the
mobile viewport workarounds and safe-area gymnastics in the Svelte app's CSS and
`app.html`.

**Kept:** the `apple-native-keyring-store/protected` dependency in
`supermessage-core` — the FFI build needs it.

**`docs/tech-stack.md` is corrected.** These entries describe a webview on the
phone and are now false:

- "Mobile skin: **Framework7 Svelte** (v9)" and its Konsta UI fallback row.
- Key decision 4, "Framework7 as mobile skin".
- Risk: "iOS keyboard doesn't resize WKWebView — #1 'web tell' in a chat app".
- Risk: "Webview ceiling ≈ 85–90% native-adjacent".
- Risk: "Framework7 single-maintainer risk".
- Risk: "IPC cost of streaming timelines to the webview" (iOS passes structs, not JSON).
- "UI skins are the *only* platform-branched layer (~20% of UI code)" — no longer true; iOS is a separate UI.

Hard requirement #2 ("UI must feel native… not a web page in a shell") and the
permissive-license bar are unchanged and still binding. `pulldown-cmark` (MIT)
and swift-markdown-free rendering both clear it.

---

## 10. Testing

`SupermessageKit` imports no SwiftUI, so its tests run without a simulator.

- **Ported specifications.** `gapSync.test.ts` (283), `diff.test.ts` (80), and
  the store tests — `rooms` (432), `timeline` (377), `spaces` (221), `live`
  (201), `typing` (162) — are ~1,750 lines of already-debugged behaviour, ported
  to Swift Testing. This is the strongest argument for the ported-stores
  architecture: the specification already exists and was paid for.
- **The ordering test.** Push 10,000 events into the pump from a background
  thread and assert the MainActor consumer observes strictly monotonic `seq` per
  subject. This is the invariant the probe never exercised.
- **Rust.** Each migrated module's TypeScript tests are ported alongside it,
  `customEvents.test.ts` above all — it is the security discipline.
- **XCUITest, deliberately thin.** Launch, sign in, open a room, send, background
  and foreground. Enough to catch broken wiring; not a UI regression suite.

---

## 11. Out of scope

Push notifications and the Notification Service Extension. Android. Message
editing and deletion (the desktop app surfaces `edited` but does not send
edits). Voice/video. Syntax highlighting, mermaid and math in code blocks.
Widgets, Shortcuts, Handoff. iPad-specific multitasking beyond what
`NavigationSplitView` gives for free.

---

## 12. Risks

| Risk | Mitigation |
|---|---|
| UniFFI 0.28 cannot generate recursive Swift enums for `RichBlock` | Proven or disproven as Part A's **first** task; fallback is a flat pre-order token stream (§3.3.1) |
| Part A touches a working desktop app | Migrate module by module, each with its ported tests, desktop suite green at every commit |
| Scroll anchoring during back-pagination is subtle and device-dependent | Built early against a real account with deep history, not last |
| Swift 6 strict concurrency friction at the Kit/app boundary | The Swift-5 island isolates the generated code; if friction persists, the app target can drop to Swift 5 mode without changing the design |
| Two rich-text parsers (markdown, HTML) on untrusted input | Depth cap at 16; raw HTML in markdown dropped, not escaped; existing ruma-html pass reused rather than replaced |
