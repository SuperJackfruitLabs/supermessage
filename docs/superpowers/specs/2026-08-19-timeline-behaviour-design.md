# Timeline behaviour: identity and the reading surface

**Status:** design, approved 2026-08-19
**Supersedes nothing.** Extends `2026-08-18-native-ios-app-design.md` §6.3.

## Why this exists

Between 17 and 19 August a reader using the desktop and the iOS app reported,
in their own words: no messages older than yesterday; the typing line never
going away; the whole timeline disappearing when the composer grew a line;
the whole timeline disappearing on send and coming back only on a manual
scroll; no room under the last message; and, generally, that it did not feel
like a messaging app.

Seven faults were found. Five were shallow and are fixed. Two are structural,
and this document is about those two.

**None of them were in `matrix-sdk-ui`.** That was checked directly, against
the 0.18.0 source rather than its docs, because it was the natural suspicion.
The SDK's diff stream is sound, our projection of it is exhaustive with no
wildcard arm, and Element X iOS — SwiftUI, on the same matrix-rust-sdk — has
a timeline that behaves. Every fault has been in our own adaptation.

## Finding A: a message changes identity when it is confirmed

### What we do now

`core::timeline::event_item_id` keys an event row by
`EventTimelineItem::identifier()`:

- a **transaction id** while the message is a local echo, and
- an **event id** once the server echoes it back.

So the identity of a message changes at the instant it is confirmed.
Virtual items (date dividers, the read marker) are keyed by
`TimelineItem::unique_id()` instead, so the timeline has two identity spaces
in one list.

### What the SDK actually offers

`TimelineItem::unique_id()` is stable across exactly this transition, and the
SDK works to keep it so:

- `algorithms.rs` — `with_inner_kind` rebuilds the item with
  `self.internal_id.clone()`, so a send-state change preserves the id.
- `controller/mod.rs` — the local echo is updated with
  `txn.items.replace(idx, new_item)`, an in-place replace, which reaches a
  subscriber as `VectorDiff::Set`.
- `state_transaction.rs` — when an item is genuinely removed and re-added,
  `recycled_timeline_id` carries its id across so it comes back with the same
  identity rather than a new one.

`unique_id()`'s own doc warns it is best-effort *for virtual items* — a date
divider you perceive as "the same" may get a new id. It says nothing of the
kind about events, and the recycling machinery exists precisely to make event
identity hold.

### What it costs us

1. A keyed list sees **delete + insert** where the SDK is saying **update**.
   On the desktop that is a row vanishing and returning — logged on
   2026-08-17 as "Buddy? disappeared and then reappeared".
2. `core::dto::collapse_reinsertion`, the workaround written for that report,
   **cannot fire in the case it was written for.** It only collapses a batch
   that leaves the id sequence unchanged, and here the sequence changes.
3. On iOS, `.scrollPosition(id:)` can be left anchored to an id that no
   longer exists. This is the most likely mechanism behind "the whole
   timeline disappeared when I sent a message".

Element X keys its diffable data source on
`TimelineItemIdentifier.UniqueID` — the same id — and never on the event id.

### The change

`TimelineItemDto` gains a distinction it should always have had:

- **`id`** — the SDK's `unique_id()`. Identity. What a list keys on, what a
  scroll anchor holds. Never used to address an event over the wire.
- **`event_id`** — `Some` once the server has echoed the message back,
  `None` while it is a local echo. What reply, react and redact address.

Today `id` silently serves both roles, which is why this is a contract change
across `packages/contract`, the core, the desktop and iOS rather than a local
edit.

**A local echo has no `event_id`, and that is the point.** It is already the
real rule — `can_reply_or_react` exists because you cannot react to a message
the server has not acknowledged — but it is currently inferred rather than
represented. After this change the type says it.

## Finding B: the reading surface fights itself

### What we do now

`TimelineView` is a `ScrollView` + `LazyVStack` in natural order, driven by
**three** mechanisms that all write scroll position:

- `.defaultScrollAnchor(.bottom)`,
- `.scrollPosition(id: $anchorId, anchor: .top)`,
- `ScrollViewReader.scrollTo` inside `scrollToBottom`.

Nothing arbitrates between them. The observed result is a view parked at an
offset where no rows are realised — blank until a manual scroll forces
layout. It reproduces when the composer's height changes (a newline) and on
send, both of which are just "the safe-area inset changed while the scroll
position was already ambiguous".

### What Element X does

The timeline is a `UITableView` behind `UIViewControllerRepresentable`, and
it is **inverted**:

```swift
tableView.transform = CGAffineTransform(scaleX: 1, y: -1)
cell.contentView.transform = CGAffineTransform(scaleX: 1, y: -1)
```

Inversion is not a trick for its own sake. It makes three separate problems
disappear rather than be managed:

| Question | Natural order | Inverted |
|---|---|---|
| Am I at the bottom? | offset vs content height vs viewport, with a tolerance | `contentOffset.y <= 0` |
| A new message arrives | content grows below; correct the offset or the view jumps | lands off the far end; **nothing moves** |
| Older history is prepended | everything shifts; snapshot and restore the offset | lands off the far end; **nothing moves** |

Their back-pagination trigger is
`contentOffset.y > contentSize.height - visibleSize.height * 2` — two
viewports of lookahead — throttled, and they still snapshot and restore
layout for the non-live case.

### The change

Replace the iOS timeline's scroll container with an **inverted
`UICollectionView`** using `UICollectionViewDiffableDataSource`, wrapped in
`UIViewControllerRepresentable`, keyed on the identity from Finding A. Rows
stay SwiftUI, hosted per cell, so `TimelineRowView` and `RichTextView` are
untouched.

This is scoped to the timeline. Nothing else in the app moves to UIKit.

**The desktop does not change.** virtua's `VList` already preserves offset on
prepend and the desktop's pagination trigger is a proper threshold. The
desktop gets Finding A only.

## Behaviour this must produce

These are the acceptance rules. Each names the layer that can falsify it.

**Opening a room.** Lands on the newest message with it fully visible, not
under the composer. No visible scrolling-into-place. *(UI test: the last
row's frame is above the composer's top edge.)*

**Receiving, while at the bottom.** The new message appears and stays
visible. No jump. *(UI test.)*

**Receiving, while reading history.** Nothing moves. *(Kit: `shouldRepin`,
already covered.)*

**Sending.** The local echo appears immediately at the bottom, and when the
server confirms it the row **does not move, flicker, or change identity**.
*(Core: the confirmation projects to a single `Set` at the same index, with
the same id. Kit: applying that batch leaves the id sequence unchanged.)*

**Growing the composer.** A newline changes the composer's height and moves
nothing else. The timeline stays on screen. *(UI test: row count and last
row's id are unchanged across a newline.)*

**Scrolling toward history.** Pages load ahead of the reader, the reading
position is preserved, and the request is not made while one is in flight or
after the start is reached. *(Kit: `wantsOlderHistory`, already covered.)*

**Reaching the start.** "Beginning of the room" once, and no further
requests. *(Core, already covered.)*

**Typing.** The line appears on a notice and goes away on that sender's
message rather than on the server's timeout. *(Kit, already covered.)*

## Testing doctrine for this work

The project's rule — *a test that has never failed is not yet a regression
test* — applies with one addition learned today.

A UI test asserted the room-info panel's member list **existed** and passed
while the panel was laid out at x=850.5 on an 834-point screen: present in
the accessibility tree, invisible to the reader. Asserting it had area on
screen is what caught the real fault.

**So: every UI assertion about the timeline asserts geometry, not existence.**
On screen, with area, and where it belongs relative to the composer.

## Sequencing

1. **Finding A in the core**, with the wire-form test. Contract first.
2. **Both hosts onto the new field.** The desktop's `collapse_reinsertion`
   should now fire for the confirmation case; that is a test.
3. **Finding B on iOS.** The inverted collection view, behind the same
   `TimelineView` type so nothing above it changes.
4. **Geometry assertions** for each rule above.

Steps 1 and 2 are worth doing on their own: they fix the flicker on both
platforms and they are the precondition for step 3 keying correctly.
