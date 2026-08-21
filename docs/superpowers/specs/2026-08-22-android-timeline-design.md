# The Android timeline

**Status:** design, 22 Aug 2026. Written against `feat/android-roster` at the secret-store work.
**Audience:** whoever builds A2, and whoever later asks why the list is upside down.
**Companions:** `docs/superpowers/plans/2026-08-21-android-app-roadmap.md` places this as the second half of Phase A. `docs/superpowers/specs/2026-08-21-android-roster-design.md` is A1, which this completes.

## 1. What this builds

Open a room and read it. History paginates upward, new messages arrive at the bottom, an agent's live turn renders as it is written, and the room is marked read when you have actually read it.

A1 proved the plumbing: real `Core`, real session, real roster. **This is the part the app is judged on.** The roadmap says so, and `TimelineCollectionView.swift`'s 674 lines are the evidence — four of this app's shipped bugs lived in that file's problem space.

## 2. The scroll container, and why it is inverted

`LazyColumn(reverseLayout = true)`, fed **newest-first**.

iOS reached this the hard way. `TimelineCollectionView.swift`'s header records that a `ScrollView` + `LazyVStack` in natural order needs three separate mechanisms — `defaultScrollAnchor(.bottom)`, `scrollPosition(id:)`, and a `ScrollViewReader` — *and nothing arbitrates between them*. It dropped to an inverted `UICollectionView`. Element X iOS reached the same place.

Compose does not need the UIKit escape hatch, because `LazyColumn` already takes `reverseLayout`. What it inherits is the *reasoning*, and inversion buys exactly the same three things:

- **"Am I at the newest?"** becomes `firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0`. Exact, not a threshold with a tolerance to tune. (iOS's equivalent is `contentOffset.y <= 0`.)
- **A message arrives.** It goes in at index 0, off the far end of the scroll. Nothing on screen moves, so there is nothing to correct.
- **History prepends.** It appends to the tail, also off the far end. The reading position is untouched.

**And a room opens at its newest message by construction**, because that is where a fresh scroll already rests. No scroll-to-bottom on load, no anchor to reset on a room switch, nothing to land wrongly.

**Ruling: `reverseLayout`, not a manual scroll-to-bottom.** The alternative — natural order plus `scrollToItem(lastIndex)` on load and on arrival — is what iOS abandoned. It fights every one of the three cases above instead of dissolving them. Cost if wrong: `reverseLayout` composes items bottom-up, so any `Modifier` that assumes top-down ordering (sticky headers, some animations) needs checking; if it proves unworkable the fallback is the manual anchor, and we will have learned it the same way iOS did.

## 3. The four rules

Each was a shipped iOS bug. They are the acceptance criteria of this phase.

### Rule 1 — open at the newest message, fully visible

Satisfied by construction under §2. The thing that breaks it is a composer or inset overlapping the first row, so the test asserts the newest row's bounds sit **above** the bottom inset, not merely that it exists. *Assert geometry, not existence* — this project put a panel off the side of an iPad by asserting existence.

### Rule 2 — animate an arrival, and nothing else

Not a page of history, not a room's first fill, not more than a handful at once. iOS encodes it as a pure function (`TimelineCollectionView.swift:426`):

```swift
private func animates(arrived: Int, had: Int) -> Bool {
    guard hasApplied, !wasAway, had > 0 else { return false }
    return arrived > 0 && arrived <= 3
}
```

Four conditions, each with a reason the source states: a room's first fill *is not an arrival*, it is the room appearing; a reader who was scrolled away did not watch it happen; an empty room gaining rows is a fill; and "a conversation does not gain eight messages in one moment, so if it looks like it did, this is not an arrival."

**Ruling: this function moves to `:kit`, not into a composable.** It is a decision, and this project's central rule is that the view decides nothing. `:kit` is where decisions that are not the core's live (`StreamingText` is already there for the same reason), and it keeps the rule testable on the JVM instead of needing a device. Cost if wrong: it arguably belongs in `supermessage-core` so all three platforms share one copy — but that costs a Rust change plus a 15-minute binding rebuild, and iOS would need adopting separately. Moving it later is a rename; leaving it in a composable would not be.

### Rule 3 — do not rebuild every row on every streaming token

`TimelineStore.revision: StateFlow<ULong>` answers "did the history actually change" in constant time. The view consumes **that**, and never diffs rows itself. A live turn's text changes many times a second; if each token invalidates the list, the room stutters.

### Rule 4 — the keyboard dismisses on drag

It had no way down for weeks on iOS. In Compose this is a `nestedScroll` connection that hides the IME on downward drag, and it belongs to the **screen**, not to the timeline or the composer, so both agree about it. (B builds the composer; this phase installs the connection and proves it with the IME open.)

## 4. What a row is

`TimelineRow` carries `item: TimelineItemDto`, `view: ItemView`, and its attribution (`senderName`, plus a bridge-suffix-stripped variant). `ItemView` is a sealed class from the core with six shapes: `Bubble`, `System`, `Placeholder`, `Image`, `MediaFile`, `CustomEvent`.

**The view branches on `ItemView` and renders. It classifies nothing.** Which shape a row is, who to attribute it to, whether it is muted — all decided in the core, all already tested there.

**Row identity is `item.id`.** Compose's `key` in `items()` is the analogue of iOS's diffable identifier, and getting it wrong reproduces the exact flicker iOS had when a confirmed message replaced its local echo.

## 5. The live turn

`LiveStore` gives `answer`, `thought`, `tools`, `finished`. `StreamingText` paces the reveal.

**Where the turn card sits depends on whether it has finished** — from `TimelineCollectionView.swift:432`:

- A turn **in progress** belongs at the bottom (index 0, inverted): it is the newest thing in the room, still being written, and the message it becomes has not arrived.
- A turn that has **finished** belongs *above* the message it produced: the reasoning and tool calls happened before the answer, and drawing them under it says they happened after.

**Ruling: the view owns the reveal pacer**, matching iOS, where `LiveTurnView` holds `@State private var stream = StreamingText()` and feeds it from `.onChange(of: live.answer)`. A `:kit` task once proposed giving `LiveStore` ownership and it was reverted as speculative — no consumer existed. Now one does, and the consumer is the view, exactly as on iOS. In Compose that is `remember { StreamingText(scope) }` with a `LaunchedEffect(answer)` feeding it. Cost if wrong: if the pacer must survive recomposition in ways `remember` cannot provide, ownership moves to the store — a contained change, since `StreamingText` already takes a `CoroutineScope`.

## 6. Marking read

Two triggers, from `TimelineView.swift`:

1. **On room change** — `LaunchedEffect(roomId) { markRead() }`.
2. **On any history change while at the newest end** — keyed on `roomId`, `revision` and away-ness together.

The second exists because of a real bug: marking only on entry meant a message landing while the room was open on screen stayed unread — *"you read it, went back to the roster, and the room was still bold, which is the app disagreeing with what you just did."*

It is gated on being at the newest end: scrolled up in history, the newest message genuinely has not been read. `mark_as_read` is a no-op at the homeserver when the receipt already points at the latest event, so firing per arrival costs nothing when there is nothing to say.

## 7. Pagination, and the way back

`paginateBack(count = 20)` fires when the reader approaches the tail (which, inverted, is *older*). `isPaginating` and `canPaginate` are already `StateFlow`s on the store; the view shows a spinner and stops asking when the store says there is no more.

A **"jump to newest"** affordance appears only when away from the newest end — *"scrolling through history with no route home is the thing that makes a long room feel like a trap."* It must not sit over a reaction chip; iOS put it at 12pt trailing and 20pt bottom after it covered one, which is a control covering another control.

## 8. Structure

```
app/src/main/kotlin/dev/supermessage/
  Timeline.kt        the container: LazyColumn(reverseLayout), anchoring,
                     pagination, markRead, jump-to-newest, typing line
  TimelineRow.kt     one row, branching on ItemView   ← TimelineRowView.swift (475)
  RichText.kt        RichBlock rendering              ← RichTextView.swift (113)
  StreamingText.kt   the paced reveal, drawn          ← StreamingTextView.swift (84)
  LiveTurn.kt        the in-progress turn card        ← LiveTurnView.swift (182)
  DecisionCard.kt    custom event cards               ← DecisionCard.swift (158)
kit/src/main/kotlin/dev/supermessage/kit/
  TimelineAnimation.kt   `animates(...)`, per §3 rule 2
```

## 9. What A2 must prove

**By test, on the JVM:** `animates` — every one of its four conditions, each mutated until it fails.

**By instrumented test:** rule 1's geometry (newest row above the bottom inset, not merely present); row identity stability across a local-echo→confirmed transition; that a `revision` bump re-renders while an unrelated recomposition does not rebuild rows.

**On a device:** open a real room, read it, scroll back through history, watch an agent's live turn render, and confirm the room stops being bold in the roster afterwards.

**The standard:** a test that has never failed is not yet a regression test. Every test above is mutated until it fails before it is kept. This project has found eight tests that could not fail for their stated reason, five of them in shipping iOS code.

## 10. What this does not cover

- **Sending anything.** The composer is Phase B. This phase reads.
- **Swipe to reply**, which iOS has (`swipeToReply(at:)`). It needs the composer's reply target to be worth anything.
- **Room info, search, invitations** — Phase C.
- **Theme.** `Theme.swift`'s structural typography — serif for what agents write, sans for the operator, mono for data, amber for a pending decision — lands in Phase D. A2 uses `MaterialTheme`, and D will revisit every surface built here.
- **The decode budget for message images.** `MediaCache` bounds by encoded `data:` URI length, not decoded bitmaps, and reuses iOS's 64 MiB constant against a much smaller unit. A2 renders images; the budget question is called out here and settled when it bites.
