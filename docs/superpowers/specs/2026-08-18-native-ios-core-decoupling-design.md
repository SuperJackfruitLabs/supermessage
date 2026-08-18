# Decoupling the core, and a native iOS client

## Why

supermessage runs its whole brain in Rust — Matrix session, sync, timeline
recovery, room projection, secrets — and paints it through a SvelteKit webview
hosted by Tauri. That works well on desktop. On a phone it does not, and the
reasons are structural rather than cosmetic.

Running the existing UI on an iPhone surfaced, in one afternoon: a webview
whose frame is 759pt of an 852pt screen and cannot be reached by any CSS; page
zoom on every field focus; a keyboard whose geometry the layout cannot see;
hover-revealed message actions that a finger cannot reveal. The first is a host
bug we do not control. The rest are the ordinary friction of asking a desktop
document to behave like an app.

The decision is therefore: **the logic stays shared and in Rust; the UI goes
native per platform.** SwiftUI on iOS now, Kotlin on Android later, SvelteKit
remaining the desktop client.

The encouraging part is how little stands in the way. The core is 13,754 lines
and its entire coupling to Tauri is 30 `#[tauri::command]` attributes, two
`AppHandle` values and eight `.emit()` calls. Nothing about Matrix, timelines or
credentials knows what a webview is.

## What this spec covers

The boundary: extracting the core, giving it a host-agnostic event channel, and
exposing it to Swift. **It does not design the SwiftUI app** — that gets its own
spec once the boundary exists and we know what it is like to work against.

## Decisions

| Decision | Choice | Why |
| --- | --- | --- |
| Binding technology | **UniFFI** | Generates Swift *and* Kotlin from one definition. Android is planned; swift-bridge would mean building the boundary twice. |
| Adapter topology | **Two adapters, one core** | Desktop's JSON wire format stays byte-identical. Mobile is free to shape types as UniFFI needs. |
| Crate layout | **Workspace split** | "The core does not know about Tauri" becomes a compiler rule rather than a convention. |
| DTO strategy | **Shared derives; mirror only generics** | See below — blanket mirroring is duplication with a silent-drift hazard. |
| Event channel | **Typed `CoreEvent` enum** | A closed, reviewable list of what the core can say. A stringly channel would let a typo compile. |
| Generated bindings | **Checked in** | Fast, hermetic Xcode builds, and boundary changes show up in review. |
| Repository | **This one** | While the API moves weekly, atomic cross-boundary commits matter more than tidiness. |
| iOS v1 scope | **Full parity** | All 30 commands. Built in slices so UniFFI friction is found early. |

### On mirrored DTOs

The first draft of this design mirrored every DTO into the FFI crate, to "keep
UniFFI's constraints out of core". That reasoning does not survive contact with
the code.

`serde` is already a core dependency and nobody calls it a host leak, because it
is a serialisation concern. `uniffi` derives are the same category. What we are
keeping out of core is *Tauri* — the app handle, the emit, the lifecycle — and
that stays out regardless.

Blanket mirroring also carries a specific hazard: adding a field to a core DTO
does **not** fail to compile in a hand-written mirror. It silently goes missing
on mobile. Struct literals catch the reverse direction, not that one.

Of the 10 types in `dto.rs`, **8 cross unchanged** — `Membership`,
`RoomSummary`, `MediaMetaDto`, `ReplyToDto`, `ReactionDto`, `TimelineItemDto`,
`TypingUserDto`, `SeqCounter`. Two cannot: `DiffOp<T>` and `DiffEnvelope<T>` are
generic, and UniFFI has no generics.

So: derive `Serialize` and `uniffi::Record` on the same eight types; mirror only
the two generics, monomorphised per concrete `T`, in the FFI crate. One shape
where the type systems agree; mirroring confined to where they genuinely do not.

## Architecture

```
crates/supermessage-core/     no tauri, no uniffi-host concerns
  session, sync, timeline, rooms, live, spaces, search,
  media, attachments, room_info, auth/, secrets, tls,
  dto, error
  event.rs                    NEW — CoreEvent + EventSink

crates/supermessage-ffi/      uniffi only
  Core object, monomorphised diff types, FfiError,
  EventSink implementation over a callback interface

src-tauri/                    tauri only
  lib.rs                      app setup
  commands.rs                 the 30 #[tauri::command] wrappers
  events.rs                   EventSink implementation over AppHandle

apple/                        the SwiftUI app (its own spec)
apple/Generated/              uniffi-bindgen output, checked in
scripts/build-xcframework.sh
```

`commands.rs` splits rather than moves. Today the `#[tauri::command]` attribute
sits on functions that also hold logic; the logic goes to the core module it
belongs to, and the annotated wrapper stays in `src-tauri` doing nothing but
calling core and mapping the error. Thin enough to be obviously correct.

`secrets.rs` moves as-is, keeping the iOS Data Protection keychain handling that
already works — the native app inherits it.

## The event channel

```rust
// crates/supermessage-core/src/event.rs
pub enum CoreEvent {
    Connection(ConnectionPayload),
    RoomsDiff(DiffEnvelope<RoomSummary>),
    TimelineDiff(DiffEnvelope<TimelineItemDto>),
    LiveDelta(LivePayload),
    AttachmentProgress(AttachmentProgress),
    // one variant per existing channel
}

pub trait EventSink: Send + Sync + 'static {
    fn emit(&self, event: CoreEvent);
}
```

Core holds an `Arc<dyn EventSink>` where it currently holds `AppHandle`. Each of
the eight emit sites becomes `self.sink.emit(CoreEvent::TimelineDiff(env))`.

The Tauri adapter matches on the variant and calls `app.emit` with today's exact
channel name and payload — that is how the desktop wire format stays identical.
The FFI adapter converts to its monomorphised event type and hands it to a
UniFFI callback interface.

**Ordering is a correctness requirement, not a nicety.** The diff envelopes
carry `seq` counters and the timeline's recovery logic depends on them arriving
in order. UniFFI callbacks arrive on whatever thread invoked them, so the Swift
sink must deliver on a single serial queue. Events also fire from tokio worker
threads, so the Swift layer must hop to the main actor before touching UI.

## The FFI adapter

```rust
#[derive(uniffi::Object)]
pub struct Core { session: Arc<Session>, runtime: tokio::runtime::Runtime }

#[uniffi::export(async_runtime = "tokio")]
impl Core {
    #[uniffi::constructor]
    pub fn new(data_dir: String) -> Arc<Self>;
    pub fn set_sink(&self, sink: Arc<dyn EventSink>);
    pub async fn rooms_list(&self) -> Result<Vec<RoomSummary>, FfiError>;
    // one method per existing command, same name, same semantics
}
```

`Core` owns the tokio runtime. On desktop the Tauri app builds one and holds
`Session` as managed state; on iOS there is no Tauri app to do that, so the
object does. Swift constructs it once at launch and keeps it alive.

Method names mirror the Tauri commands exactly — two hosts, one vocabulary.

`CoreError` is a `thiserror` enum carrying source errors that cannot cross FFI,
so the FFI crate declares `FfiError` mirroring its variants with a `From`. This
mirroring *is* justified: the types genuinely differ.

## Build

`cargo build` for `aarch64-apple-ios` and `aarch64-apple-ios-sim` →
`uniffi-bindgen generate` → `xcodebuild -create-xcframework`. One script, run
when the Rust API changes. Xcode consumes the `.xcframework` as a binary
dependency and never invokes cargo.

Android costs nothing extra here: the same crate feeds `uniffi-bindgen-kotlin`
and an `.so` per ABI. Only the packaging is new.

`src-tauri/gen/apple` — the Tauri iOS target — stays until the native app
reaches parity, since it is currently the only thing that runs on a phone. It is
removed then.

## Testing

- **The core's 307 Rust tests move with it** and gain the ability to run without
  a Tauri app at all.
- **Golden-JSON tests are the safety net for the desktop promise.** Snapshot
  each DTO's serialised output *before* any change; assert byte-identical after
  the derives and the move. This turns "desktop is unchanged" into a check.
- **Conversion tests for the two mirrored generics**, where a field can silently
  go missing.
- **The 557 JS tests and the desktop e2e specs stay green throughout.** If they
  do not, the refactor is wrong.

## Sequencing

Four slices, each shippable, each leaving desktop working:

1. **Extract `supermessage-core`.** File movement and imports. No behaviour
   change, no new dependencies. Green tests are the whole acceptance criterion.
2. **`EventSink` + Tauri adapter.** The eight emit sites and two `AppHandle`
   uses go. Golden tests prove the wire format identical.
3. **FFI crate, one thin slice** — `login`, `rooms_list`, connection events —
   plus a deliberately throwaway SwiftUI screen listing room names. This is the
   step that reveals what UniFFI is like to live with, before 30 commands are
   re-typed against assumptions.
4. **Widen to parity**, command by command.

The SwiftUI app proper is designed after slice 3, when the boundary is real.

## Risks

**UniFFI async maturity.** `async_runtime = "tokio"` works, but the timeline
subscription is long-lived and event-driven rather than request/response. Slice
3 exists to find out whether the callback interface carries that load before we
commit the other 28 commands to it.

**Silent DTO drift on the two mirrored generics.** Mitigated by conversion
tests, but it is the known sharp edge.

**Two UIs to maintain, permanently.** This is the accepted cost of the decision,
not a risk to be mitigated. It is worth stating plainly: every user-visible
feature after this lands is built twice, and the desktop and mobile clients will
diverge in behaviour unless someone actively keeps them together.
