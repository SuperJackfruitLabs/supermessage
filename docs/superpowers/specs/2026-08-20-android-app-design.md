# The Android app

**Status:** design, 20 Aug 2026. Written the day `v0.0.9` shipped the native
iOS client, against `main` at `66cca10`.
**Audience:** whoever builds this, on a machine that is not the one it was
written on.
**Companion:** `docs/superpowers/specs/2026-08-18-native-ios-app-design.md`.
Android is the second host over a boundary built for two; that document is the
first, and most of its reasoning is not repeated here.

## The claim this rests on

Everything that is a *decision* already lives in Rust. Android inherits it.

`supermessage-core` is 21,491 lines with 621 tests: timeline projection and
render classification, markdown and sanitised HTML into `RichBlock`, room
identity, previews, affordances, mentions, `matrix.to` parsing, custom-event
rendering, display naming, roster arrangement and grouping, search scoping,
people directory. None of it is re-derived per host, and the reason is written
into `AGENTS.md` as a rule: *the app parses nothing, and decides nothing.*

That rule was worth following for iOS. It is what makes Android tractable.

**Verified, not assumed** — the Kotlin bindings were generated from the shipped
library on 20 Aug before this was written:

```
cargo run -q -p supermessage-ffi --bin uniffi-bindgen -- generate \
    --library target/debug/libsupermessage_ffi.dylib --language kotlin --out-dir …
→ 9,449 lines, clean: the full `Core` interface, 38 records and enums
```

Same `#[uniffi::export]` definitions, second language, no new Rust.

## What already exists

| | State |
|---|---|
| `crate-type` | Already lists `cdylib` — the manifest comment says "for Android's `.so`" |
| 16 KB pages | `core::tls` installs ring explicitly because aws-lc-rs crashes on 16 KB-page devices, which Play now requires. Half the fix; the other half is a linker flag, in `scripts/build-android-libs.sh` |
| Build pipeline | `scripts/build-android-libs.sh` — four ABIs plus Kotlin bindings, mirroring `build-xcframework.sh`. **Run green on the Linux box, 20 Aug**, unmodified: four `.so` in ~15 min and 9,449 lines of Kotlin, the count this spec predicted. Every `LOAD` segment aligns `0x4000`, so the 16 KB fix is real and not just a flag that was passed |
| Toolchain | Verified 20 Aug: SDK at `~/Android/Sdk`, NDK `29.0.14206865`, JDK 21, all four Rust targets. `AGENTS.md` was right about the machine and wrong about the NDK — it said there was none, having been written on the Mac. Only `cargo-ndk` was missing |

## The decision: native Compose, not the Tauri webview

`gen/android/` exists and `pnpm tauri android dev` is documented, so the
webview path is *closer*. It is still the wrong one, for the reason iOS went
native: a chat app is judged on the timeline, and a webview timeline cannot
have an inverted list whose "am I at the newest message" is exactly
`contentOffset.y <= 0`. Every timeline bug fixed on iOS this month —
pagination inversion, the scroll landing, the composer overlap, the reconfigure
storm — was a question about a native list's geometry.

Shipping the webview build would also make a third client that can disagree
with the other two, which is the failure the core exists to prevent.

**If the goal changes** — an Android build in a week, correctness later — the
webview path is legitimate and mostly done. It is a different product, and the
choice should be deliberate rather than made by whichever gets started first.

## Structure

Three Gradle modules, mirroring the three Xcode targets and for the same
reasons:

```
android/
  core/     the generated bindings + the .so files. The Kotlin is checked in;
            the .so are not — 362MB across four ABIs, and x86 alone is over
            GitHub's 100MB per-file limit. Run the build script for those.
  kit/      the boundary and the stores. Imports no Compose.
  app/      the views.
```

`kit` importing no Compose is not tidiness. It is what keeps the state layer
testable on the JVM without an emulator, and what stops view code leaking into
it — the same rule `SupermessageKit` follows, and the reason its 2,892 lines
port rather than needing redesign.

## What to write

| Piece | Source | Size | Nature |
|---|---|---|---|
| `kit` | `apple/SupermessageKit` | ~2,900 lines | Mechanical port |
| `app` | `apple/Supermessage` | ~4,100 lines | New. Compose is not SwiftUI |
| Build script | done | ~110 lines | Debug once against a real NDK |

### The port, file by file

Every one of these has a Swift original to read and, where it carries a rule,
tests to port with it. The tests matter more than the code: they are where the
rules are written down.

- `CoreClient` — every `Core` call off the main thread. On iOS that is a
  `DispatchQueue`; here it is `Dispatchers.IO`. **The rule is identical and
  the reason is identical**: every call blocks, and a cooperative pool assumes
  tasks yield. `Task.detached` was the wrong answer on iOS; `GlobalScope` is
  the wrong answer here.
- `EventPump` → a single `Channel` drained by one collector. Order is the
  whole point: out-of-order diffs corrupt the reader's view in a way that
  looks like a rendering bug. `EventPumpTests` port directly.
- `DiffApply` / `DiffTracker` / `GapSync` — pure, and the most valuable tests
  in the Kit. `DiffApplyTests` includes the rule that a confirmed message
  keeps its place and identity, which is the flicker `TimelineItemDto.id`
  exists to prevent.
- The eleven stores — `RoomsStore`, `TimelineStore`, `LiveStore`,
  `TypingStore`, `AvatarCache`, `MediaCache`, `DraftStore`, `ReplyTarget`,
  `EditTarget`, `SpacesStore`, `StagedAttachment`, `ConnectionStore`.
  `@Observable` becomes `StateFlow`. Two carry hard-won rules: `AvatarCache`
  is a hand-bounded map because `@Observable` could not see through an
  `NSCache` (avatars appeared only on the second scroll) — Compose has the
  same hazard with a mutable cache behind a `State`; and `TypingStore` matches
  on **user id**, never on a display name, because the two hosts name people
  differently and the indicator stuck for minutes.
- `TimelineGrouping`, `TimelineFollow`, `RelativeTime`, `SearchState`,
  `SendState`, `RichTextFolding`, `StreamingText` — pure, tested, port as-is.
  `StreamingText` is the reveal pacer: the network must not decide the
  animation speed.
- `RosterArrangement` — now a thin call into `core::roster`. Keep it thin.
  The rules moved into Rust precisely so a second host could not re-invent
  them.

### The views

No shortcut, but the decisions are made. Read the iOS files for *what* is
drawn and why; the layout is Compose's problem.

The one that needs real thought is the timeline. On iOS it is an inverted
`UICollectionView` with a diffable data source, and the inversion is what makes
"at the newest message" exact rather than approximate. The Compose equivalent
is `LazyColumn(reverseLayout = true)`, where the same property is
`listState.firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0`.

Carry these across, because each was a bug:

1. **Open at the newest message, fully visible, not under the composer.**
2. **Animate an arrival, and nothing else** — not a page of history, not a
   room's first fill, not more than a handful of rows at once.
3. **Do not rebuild every row on every streaming token.** iOS reconfigured
   every visible cell several times a second and it read as jitter. The fix
   was a `revision` counter on the store answering "did the history actually
   change" in constant time. `TimelineStore.revision` is already there and
   already carries that doc comment.
4. **Keyboard dismisses on drag.** It had no way down for weeks.

## Testing

`kit` tests run on the JVM with no emulator, which is the point of it importing
no Compose. Port the Swift tests with the code — most are pure-function tests
and translate almost line for line.

The doctrine holds and is not optional: **a test that has never failed is not
yet a regression test.** Every test on the iOS branch was mutated until it
failed before it was kept, and that caught two tests that asserted nothing —
one comparing `""` to `""` because cells carried no identifier, one passing the
same name to both sides of the bug it was meant to catch.

For UI tests, assert **geometry, not existence**. A test once asserted the
room-info panel existed while it was laid out off the side of an iPad.

## Sequence

Each step ends somewhere you could stop.

1. **Prove the pipeline.** ~~Run `scripts/build-android-libs.sh` on a machine
   with the NDK.~~ Done 20 Aug on the Linux box — the script needed no fixing,
   only `cargo install cargo-ndk`. Four `.so` files and the Kotlin exist. What
   remains of this step is a scratch Android test calling `peopleLabel`.
2. **`core` module.** Gradle consumes the `.so` and the bindings. Done when an
   instrumented test on a device calls into Rust and gets an answer.
3. **`kit`: client and pump.** `CoreClient` off the main thread, `EventPump`
   in order, with their tests. Done when a login and a room list arrive.
4. **`kit`: stores.** All eleven, tests ported. Done on the JVM, no emulator.
5. **`app`: roster and timeline.** The reading surface, and the four rules
   above. Done when a room reads correctly on a device.
6. **`app`: composer and the rest.** Sending, replies, reactions, images,
   search, room info.

Steps 1–4 are the ones where being wrong is expensive and the tests are already
written. Step 5 is where the judgement is.

## What this does not cover

- **Push.** `AGENTS.md` describes `event_id_only` via Sygnal → FCM. Nothing
  here is built, on either platform.
- **The desktop's remaining gaps.** Mute, edit/delete and scoped search have
  core support and Tauri commands but no Svelte. Android should not wait for
  them; it should not re-derive them either.
- **Distribution.** The release workflow builds macOS, Linux and Windows.
  Neither mobile platform is in it.
