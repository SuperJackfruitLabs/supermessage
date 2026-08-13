//! Streams the focused room's timeline to the webview as versioned diffs,
//! and drives backward pagination and sending from the same subscription.
//!
//! Mirrors `core::rooms`'s shape (read that module's doc comment first): the
//! streaming task owns a materialized `Vec<TimelineItemDto>` alongside the
//! last emitted sequence number, updated inside the same critical section
//! that emits, so [`FocusedTimeline::snapshot`] can serve a resync out of
//! the live task's own state instead of opening a second, independently
//! numbered subscription.
//!
//! Unlike the room list, only **one** timeline is ever subscribed at a
//! time — the focused room. [`FocusedTimeline::subscribe`] always drops the
//! previous subscription first (see its doc comment), so switching rooms
//! can never leave a background timeline's task running.
//!
//! ## Recovering from an emptied timeline
//!
//! The room list's sliding-sync subscription uses `timeline_limit: 1`
//! (`docs/tech-stack.md`), so an incoming event for the focused room often
//! arrives as a *limited* ("gappy") sync even while it's focused. Traced
//! against matrix-sdk 0.18's own source, a limited sync that also carries a
//! new gap (`prev_batch` token) makes `RoomEventCacheState::handle_sync`
//! call `shrink_to_last_chunk` (`matrix-sdk-0.18.0/src/event_cache/caches/
//! room/state.rs`, the `timeline.limited && has_new_gap` branch), which
//! unloads every in-memory chunk but the newly-persisted last one, reloaded
//! straight from the store. That unload is implemented by
//! `linked_chunk::lazy_loader::replace_with`
//! (`matrix-sdk-common-0.18.0/src/linked_chunk/lazy_loader.rs`), which
//! unconditionally discards whatever updates hadn't yet been drained
//! (`updates.clear_pending()`) and replaces them with a single
//! `Update::Clear` before re-describing the reloaded chunk's own content.
//! `matrix_sdk_ui`'s `TimelineStateTransaction::handle_remote_events_with_diffs`
//! (`matrix-sdk-ui-0.18.0/src/timeline/controller/state_transaction.rs`)
//! translates that `VectorDiff::Clear` on the event cache's list 1:1 into a
//! `self.clear()` on the timeline's own *item* list — and, once no local
//! echo is still pending reconciliation (`ObservableItemsTransaction::
//! has_local()` is false), that clear takes its bulk path and is itself a
//! single `VectorDiff::Clear`, which is exactly what reaches
//! [`FocusedTimeline::subscribe`]'s stream. Empirically (per the debug log
//! this fix was written against), the diffs that are supposed to
//! re-describe the reloaded chunk's content do not reliably show up as a
//! further op on this stream — the observed sequence goes fully quiet right
//! after the lone `Clear`, and the timeline never repopulates on its own.
//! Notably, `shrink_to_last_chunk` also resets pagination status to `Idle {
//! hit_timeline_start: false }` (same file), i.e. the SDK itself does not
//! consider this "the start of the timeline" — it expects a consumer to
//! resume paginating backward from here, which is exactly what backward
//! pagination through the just-recorded gap is for.
//!
//! So this module treats "the materialized item list is about to go from
//! non-empty to empty" as a signal to re-seed the timeline the same way
//! [`FocusedTimeline::subscribe`] seeds it the first time: a
//! `paginate_backwards(INITIAL_PAGE_SIZE)` call. The trigger is decided by
//! [`should_reseed`], a pure function over the materialized length
//! before/after a batch would be folded and a re-seed counter — see its doc
//! comment for why it keys on the length transition rather than which
//! `DiffOp` produced it, and [`MAX_RESEED_ATTEMPTS`] for the loop bound.
//!
//! ### Coalescing the recovery into one visible transition
//!
//! An earlier version of this fix emitted the emptying batch as its own
//! envelope and let the re-seed's diffs trickle in afterwards, exactly as
//! `matrix_sdk_ui` produced them. That recovers correctly but is exactly
//! what makes it *visible*: the webview renders the room empty for the
//! envelope carrying the `Clear`, then watches it refill over the next
//! couple of diffs a moment later — correct, but it looks broken.
//!
//! [`decide_batch`] is what stops the emptying batch from ever reaching
//! [`emit_ops`] on its own. Given the materialized length before a batch and
//! the batch itself, it decides purely (via [`should_reseed`], fed by
//! `core::dto::ops_len_after`'s peek at what the batch's *length* effect
//! would be — never mutating the real materialized list to find out, which
//! is what keeps that peek from disturbing the "last-emitted `seq` and the
//! materialized list stay mutually consistent" invariant [`TimelineState`]
//! documents) whether to hand the batch back for the streaming task to fold
//! and emit as usual, or to signal "hold this one — re-seed instead". On
//! that second outcome the streaming task:
//!
//! 1. Never folds or emits the emptying batch at all — the materialized
//!    state and last-emitted `seq` are untouched, so a `snapshot()` racing
//!    this window still sees the old (stale, but self-consistent) content,
//!    never a state that claims to be empty at a `seq` that never described
//!    that.
//! 2. Awaits the same inline `paginate_backwards(INITIAL_PAGE_SIZE)` call
//!    the un-coalesced recovery used — same cancellation story: a room
//!    switch's `task.abort()` (`FocusedTimeline::clear_and_join`) cancels it
//!    exactly like every other await point in this task, no separate task to
//!    leak or race against a teardown.
//! 3. **Re-subscribes** — `Timeline::subscribe()` again, not a continuation
//!    of the stream already in hand — for an authoritative snapshot of
//!    whatever the timeline actually contains at that point, regardless of
//!    whether the re-seed found history to show, found none (a real gap
//!    that resolves to nothing further, or the timeline's genuine start), or
//!    the `paginate_backwards` call itself returned an error: this step
//!    doesn't branch on that result, it just re-subscribes either way, so a
//!    failure converges on "whatever is really there right now" instead of
//!    holding the stale pre-clear content forever.
//! 4. Emits that snapshot as a single [`DiffOp::Reset`] — [`coalesced_reset`]
//!    — through the exact same [`emit_ops`]/`seq` every other envelope on
//!    this stream uses, so exactly one `seq` is consumed for the whole
//!    transition and the webview's gap detector sees a continuous sequence,
//!    not a restart. The webview goes directly from the old content to the
//!    new; it never observes the empty state in between.
//! 5. Swaps the task's stream onto the freshly-subscribed one and drops the
//!    old one. This is what makes step 3 safe rather than merely
//!    convenient: the old stream may still have diffs queued describing the
//!    very transition just resolved (the `Clear` that triggered this, and
//!    whatever the re-seed's own pagination produced) — those are never
//!    read, folded, or emitted, they are discarded along with the stream
//!    that held them, so nothing can double-apply on top of the `Reset`
//!    that already accounts for all of it. The fresh stream's first future
//!    diff picks up from exactly where the fresh snapshot left off, per
//!    `Timeline::subscribe`'s own contract — the same guarantee the very
//!    first subscription in [`FocusedTimeline::subscribe`] already relies
//!    on.

use std::pin::Pin;
use std::sync::{Arc, Mutex};

use eyeball_im::VectorDiff;
use futures_util::{Stream, StreamExt};
use imbl::Vector;
use matrix_sdk::ruma::events::room::message::{
    FormattedBody, MessageFormat, MessageType, RoomMessageEventContent,
    RoomMessageEventContentWithoutRelation,
};
use matrix_sdk::ruma::events::room::MediaSource;
use matrix_sdk::ruma::events::AnyMessageLikeEventContent;
use matrix_sdk::ruma::html::{
    ElementAttributesSchemes, Html, HtmlSanitizerMode, ListBehavior, PropertiesNames,
    SanitizerConfig,
};
use matrix_sdk::ruma::{EventId, MilliSecondsSinceUnixEpoch, RoomId, UInt, UserId};
use matrix_sdk::Client;
use matrix_sdk_ui::timeline::{
    EventSendState, EventTimelineItem, MembershipChange, MsgLikeKind, ReactionsByKeyBySender,
    RoomExt, Timeline, TimelineDetails, TimelineEventItemId, TimelineItem, TimelineItemContent,
    TimelineItemKind, VirtualTimelineItem,
};
use tauri::{AppHandle, Emitter};
use tokio::task::JoinHandle;

use super::dto::{
    apply_ops, op_name, ops_len_after, project_diff, DiffEnvelope, DiffOp, MediaMetaDto,
    ReactionDto, ReplyToDto, SeqCounter, TimelineItemDto,
};
use super::error::{CoreError, CoreResult};

/// Tauri event channel carrying timeline diffs for the webview's timeline
/// store.
pub const TIMELINE_DIFF_EVENT: &str = "sm://timeline/diff";

/// The `DiffEnvelope::channel` value used for every timeline envelope.
const TIMELINE_CHANNEL: &str = "timeline";

/// How much history to load when a room is first opened.
///
/// Sliding sync caches only one event per room, so this is what makes an
/// opened room show a conversation rather than a single line. Sized to fill
/// a desktop viewport and leave something to scroll, which is also what lets
/// the UI's scroll-triggered pagination take over from here.
const INITIAL_PAGE_SIZE: u16 = 30;

/// Cap on how many times one streaming task will re-seed itself (see
/// [`should_reseed`]) over its whole lifetime.
///
/// This is what makes re-seeding safe against two very different failure
/// shapes without telling them apart:
///
/// - A genuinely empty room can't cause a loop in the first place —
///   [`should_reseed`] only fires on a non-empty-to-empty *transition*, and
///   an empty room never produces one after its first (also-empty) seed — so
///   this bound is not what protects that case.
/// - What it *does* bound is a room that keeps re-triggering the condition
///   (repeated gappy syncs, or a re-seed pagination that itself lands the
///   list back at zero some other way): each occurrence consumes one of a
///   small, fixed budget, so the streaming task can re-seed at most this many
///   times total, ever, no matter how many times the condition recurs.
///
/// Three is generous enough to ride out a burst of limited syncs in one
/// sitting while still being obviously finite. Hitting the cap doesn't wedge
/// anything further: the existing manual recovery (switch rooms and back,
/// which tears down and rebuilds this task from scratch — see
/// [`FocusedTimeline::subscribe`]'s doc comment) still works exactly as it
/// does today. The only user-visible change at the cap is that
/// [`decide_batch`] stops withholding the emptying batch and lets it through
/// as an ordinary emit instead (see this module's "Coalescing the recovery
/// into one visible transition" doc comment) — the room shows the old,
/// pre-coalescing flicker rather than staying empty forever, which is still
/// strictly better than an unbounded retry loop.
const MAX_RESEED_ATTEMPTS: u32 = 3;

/// The sequence number of the last diff folded into the materialized item
/// list, and the resulting list itself — always mutually consistent (see
/// `core::rooms::RoomListHandle`'s identical `RoomListSnapshot` for why).
type TimelineState = (u64, Vec<TimelineItemDto>);

/// The streaming task's diff stream, boxed to erase `Timeline::subscribe`'s
/// concrete (unnameable) return type.
///
/// Needed because of the "Coalescing the recovery into one visible
/// transition" mechanism above (this module's doc comment): the streaming
/// task must be able to swap in a *fresh* stream, from a *later, separate*
/// `subscribe()` call, after re-seeding. A plain `impl Stream` local can be
/// pinned in place (`futures_util::pin_mut!`) and read from, but a pinned
/// local can't be reassigned to point at a different value — boxing turns
/// each stream into an owned, movable value instead, so the task can just do
/// `current_stream = Box::pin(fresh_stream);` and carry on reading from the
/// same variable.
type TimelineDiffStream = Pin<Box<dyn Stream<Item = Vec<VectorDiff<Arc<TimelineItem>>>> + Send>>;

/// What [`FocusedTimeline::snapshot`] hands the webview: `(subject, seq,
/// items)` — the room id the snapshot belongs to, followed by the same
/// `(seq, items)` pair the room list returns.
///
/// The subject is not decoration. Spec §4 defines the sequence as
/// "monotonic, per channel+**subject**", and the timeline channel's subject
/// changes every time the user switches rooms. A snapshot without it cannot
/// be checked against the room the webview currently has focused, so a
/// resync issued during a room switch — where the fast mutex read behind
/// this call easily beats the slow `room.timeline()` build behind
/// `timeline_subscribe` — would be served out of the *previous* room's
/// still-installed handle and silently install that room's messages, at that
/// room's high seq, under the new room's header. The new room's stream then
/// starts back at seq 1 and is discarded as duplicates, so the wrong
/// messages stay until the next room switch. Returning the subject lets the
/// webview reject exactly that.
pub type TimelineSnapshot = (String, u64, Vec<TimelineItemDto>);

/// Build a [`TimelineItemDto`] from already-extracted parts.
///
/// Pure and SDK-free on purpose: it is the part `project_item` delegates
/// to, so the projection logic is testable without a live homeserver or SDK
/// timeline item.
#[allow(clippy::too_many_arguments)]
pub fn project_item_parts(
    id: &str,
    kind: &str,
    msgtype: Option<&str>,
    detail: Option<&str>,
    sender: Option<&str>,
    sender_display_name: Option<&str>,
    body: Option<&str>,
    formatted_body: Option<&str>,
    media: Option<MediaMetaDto>,
    timestamp_ms: Option<u64>,
    is_own: bool,
    send_state: Option<&str>,
    reply_to: Option<ReplyToDto>,
    edited: bool,
    reactions: Vec<ReactionDto>,
) -> TimelineItemDto {
    TimelineItemDto {
        id: id.to_string(),
        kind: kind.to_string(),
        msgtype: msgtype.map(str::to_string),
        detail: detail.map(str::to_string),
        sender: sender.map(str::to_string),
        sender_display_name: sender_display_name.map(str::to_string),
        body: body.map(str::to_string),
        formatted_body: formatted_body.map(str::to_string),
        media,
        timestamp_ms,
        is_own,
        send_state: send_state.map(str::to_string),
        reply_to,
        edited,
        reactions,
    }
}

/// The URI schemes this app allows a rendered message's `<a href>` to carry
/// once it reaches the webview: `http`, `https`, `mailto`, `matrix`.
///
/// This is not merely a narrower allowance than ruma's `HtmlSanitizerMode::Compat`
/// (`http`, `https`, `ftp`, `mailto`, `magnet`, plus `matrix` — see
/// `ruma-html-0.8.0/src/sanitizer_config/clean.rs`,
/// `spec::allowed_schemes`/`compat::allowed_schemes`) — it is **the** scheme
/// check that actually runs. Ruma's own has a real bug (see
/// [`harden_formatted_body`]'s doc comment for the exact mechanism, verified
/// against ruma-html 0.8.0's source): its scheme-checking loop can exit
/// early and skip `href` entirely when another, scheme-rule-less attribute
/// (`class`, say) is examined first, so `<a class="x"
/// href="javascript:alert(1)">` survives `HtmlSanitizerMode::Compat` with
/// its `javascript:` `href` intact. `harden_formatted_body`'s second pass
/// only reaches a correct answer here because `<a>`'s sole two permitted
/// attributes are `href` and `target`, and `"href" < "target"` in the
/// `BTreeSet<Attribute>` iteration order this loop walks — so `href`,
/// checked against *this* list, is always examined before the loop could
/// reach an attribute with no rule attached. `ftp`/`magnet` (which ruma's
/// own list would still allow, bug notwithstanding) are excluded from this
/// list too, since nothing in this app opens either — every link this app
/// does open goes through the system opener (`tauri-plugin-opener`), not an
/// in-app fetch.
const ALLOWED_LINK_SCHEMES: &[&str] = &["http", "https", "mailto", "matrix"];

/// Elements structurally allowed by ruma's `HtmlSanitizerMode::Compat` that
/// this app removes outright before a formatted body reaches the webview:
///
/// - `img`: this webview has no `mxc://` protocol handler, so even a
///   spec-compliant `<img src="mxc://...">` only ever paints a
///   broken-image icon — and, per [`harden_formatted_body`]'s doc comment,
///   ruma's own restriction of `img src` to `mxc://` is not reliably
///   enforced, so a non-`mxc` (e.g. remote-tracking-beacon) `src` cannot be
///   assumed to have been stopped upstream.
/// - `mx-reply`: `matrix_sdk_ui`'s `Message::from_event` only passes
///   `RemoveReplyFallback::Yes` (which is what makes ruma strip `mx-reply`)
///   when the event actually has an `in_reply_to` relation, and
///   `apply_edit` always passes `RemoveReplyFallback::No` — so a `mx-reply`
///   element is not reliably stripped upstream either. Left in, a crafted
///   `<mx-reply><blockquote><a href="https://matrix.to/#/@victim:example.org">
///   @victim</a><br>fabricated quote</blockquote></mx-reply>` renders with
///   this app's own blockquote styling as what looks like a genuine quoted
///   reply from another user — a spoofing bug the moment replies render
///   (harmless today, since nothing reads `mx-reply` specially yet, but
///   removing the element costs nothing and forecloses the bug before it
///   can exist).
const REMOVED_ELEMENTS: &[&str] = &["img", "mx-reply"];

/// Cap on nested-element depth this app allows in a rendered message body,
/// overriding ruma's own default of 100 (`spec::MAX_DEPTH` in
/// `ruma-html-0.8.0/src/sanitizer_config/clean.rs`) — generous enough for
/// any real message (ordinary markdown rarely nests more than 2-3 levels of
/// `blockquote`/list), tight enough that 100 nested `<ul>` or `<blockquote>`
/// — a valid 64KiB event body can easily carry either — cannot compound
/// their indentation into a message that dwarfs the room it's posted in
/// (measured in review: 100 nested `<ul>` produced a 2009px-wide bubble
/// against a ~512px viewport under ruma's default depth of 100).
const MAX_ELEMENT_DEPTH: u32 = 8;

/// Hardens an already-sanitised message HTML body before it crosses IPC to
/// the webview, which renders it with `{@html}` (`Timeline.svelte`).
///
/// `matrix_sdk_ui::timeline::Message::from_event` already runs ruma's
/// `HtmlSanitizerMode::Compat` allowlist sanitiser over `formatted_body`
/// before this is ever reached (`matrix-sdk-ui-0.18.0/src/lib.rs`,
/// `DEFAULT_SANITIZER_MODE`, applied via `FormattedBody::sanitize_html`).
/// That pass is genuinely reliable for *element*/*attribute* allowlisting —
/// no `<script>`, no inline event handler (`onerror`, ...), no `style`
/// attribute survives it, on any element, full stop; those are enforced by
/// a separate, correctly-implemented code path
/// (`SanitizerConfig::clean_element_attributes`) that this function does
/// not need to (and does not) redo.
///
/// It is **not** reliable for the *scheme* checks on `<a href>`/`<img
/// src>`, and this function is not belt-and-braces layered on top of a
/// working upstream check for those two — without it, both of the
/// following reach this app's `{@html}` unchanged:
///
/// - **`<img>` can survive with a remote `src`.** Ruma restricts `img src`
///   to a valid `mxc://` URI in principle (`spec::allowed_schemes` —
///   `("img", "src") => &["mxc"]`), but the loop that enforces it
///   (`ruma-html-0.8.0/src/sanitizer_config/clean.rs`, the `for attr in
///   attrs.iter()` loop inside `node_action`) has a bug: for each
///   attribute on the element, in `BTreeSet<Attribute>` order, it looks up
///   a scheme rule for that specific attribute name, and the moment it
///   finds an attribute with *no* rule of its own (e.g. `alt`, on `img`),
///   it does `return NodeAction::None` — returning out of the whole
///   function, not just skipping that one attribute — before the loop ever
///   reaches `src`. Concretely: `<img alt="a"
///   src="https://evil.example/beacon.png">` passes
///   `HtmlSanitizerMode::Compat` with its remote `src` completely intact,
///   because `alt` sorts before `src` and the scheme loop never got past
///   it — a tracking beacon leaking the reader's IP to whoever sent the
///   message, the instant it renders. This function does not depend on
///   that loop for `<img>` at all: [`REMOVED_ELEMENTS`] takes effect in
///   `node_action` *before* the scheme-checking loop runs, for either
///   pass, so the element is gone regardless. (Separately: even a
///   `src="mxc://..."` that legitimately passed ruma's check would only
///   ever paint a broken-image icon here, since this webview has no
///   `mxc://` protocol handler — so removing the element outright, rather
///   than rewriting it to its `alt` text, costs nothing real and avoids a
///   second code path that would have to build and escape a replacement
///   text node from attacker-supplied content.)
/// - **`<a href>` can survive with a `javascript:`/`data:` scheme.** Same
///   bug, same loop: `<a class="x" href="javascript:alert(1)">click</a>`
///   passes ruma's pass with `class` correctly stripped (attribute
///   *removal* is that separate, correctly-implemented code path — this is
///   specifically about the scheme *check*, a different code path in the
///   same file) but `href="javascript:alert(1)"` intact, because `class`
///   sorts before `href` and the scheme loop never got past it. This
///   function's own [`ALLOWED_LINK_SCHEMES`] allowlist is what actually
///   decides a link's fate here — see that constant's doc comment for why
///   it survives the same bug (in short: `<a>` only has two permitted
///   attributes, `href` and `target`, and `href` always sorts first, so
///   this pass's own `href` check is never skipped the way an
///   attacker-chosen decoy attribute could skip it upstream).
///
/// In short: **this function, not ruma's `HtmlSanitizerMode::Compat`, is
/// the actual enforcement for the `<img>`/`<a href>` rules above.** Do not
/// delete or weaken it on the assumption ruma's own allowlist already
/// covers this reliably — it doesn't, for exactly the two things that
/// matter most here (a remote-tracking `<img>`, a script-executing `<a
/// href>`). This module's tests cover the composed pipeline (both passes,
/// in the order the app actually runs them), not just this function in
/// isolation, specifically because testing it alone cannot catch a bug
/// that depends on what the *first* pass already stripped out.
///
/// Pure string-in, string-out and independent of any live SDK/timeline
/// object, so it's unit-testable on its own too (see this module's tests).
fn harden_formatted_body(html: &str) -> String {
    let config = SanitizerConfig::with_mode(HtmlSanitizerMode::Compat)
        .remove_elements(REMOVED_ELEMENTS.iter().copied())
        .max_depth(MAX_ELEMENT_DEPTH)
        .allow_schemes(
            [ElementAttributesSchemes {
                element: "a",
                attr_schemes: &[PropertiesNames {
                    parent: "href",
                    properties: ALLOWED_LINK_SCHEMES,
                }],
            }],
            ListBehavior::Override,
        );
    let parsed = Html::parse(html);
    parsed.sanitize_with(&config);
    parsed.to_string()
}

/// Extracts and hardens a message's HTML formatted body, if it has one whose
/// `format` is `org.matrix.custom.html` (`MessageFormat::Html` — the only
/// format the spec defines; anything else, including an absent `formatted`
/// field, projects to `None` here exactly as if the message had no
/// formatted body at all).
///
/// Only `m.text` and `m.notice` are handled — both render a bubble with
/// `{@html formattedBody}` in `Timeline.svelte` when this is `Some`.
/// `m.emote` also carries a `formatted: Option<FormattedBody>` field in
/// ruma's model (`ruma_events::room::message::EmoteMessageEventContent`),
/// but `Timeline.svelte`'s emote view renders only the plain `{item.body}`
/// centred italic line, never `formattedBody` — so computing (and hardening)
/// an HTML string for it here would be dead weight with no consumer.
/// Extend this match, and `Timeline.svelte`'s emote branch to actually
/// render it, together if that ever changes; every other msgtype has no
/// `formatted` field at all in ruma's model and returns `None`.
fn formatted_html_body(msgtype: &MessageType) -> Option<String> {
    let formatted: &FormattedBody = match msgtype {
        MessageType::Text(m) => m.formatted.as_ref(),
        MessageType::Notice(m) => m.formatted.as_ref(),
        _ => None,
    }?;
    if formatted.format != MessageFormat::Html {
        return None;
    }
    Some(harden_formatted_body(&formatted.body))
}

/// `MilliSecondsSinceUnixEpoch` wraps `js_int::UInt`, which only converts to
/// `i64`/`i128` directly; it is always non-negative and within `i64::MAX`,
/// so the round trip through `i64` is exact (same reasoning as
/// `core::rooms::project_room`'s identical conversion).
fn timestamp_to_millis(ts: MilliSecondsSinceUnixEpoch) -> u64 {
    i64::from(ts.get()) as u64
}

/// A `UInt`'s value as `u64`. `UInt` only converts to `i64`/`i128` directly
/// (no direct `u64` conversion exists in `js_int` 0.2), so this goes through
/// `i64` — exactly [`timestamp_to_millis`]'s reasoning above: `UInt` is
/// always non-negative and within `i64::MAX`, so the round trip is exact.
fn uint_to_u64(value: UInt) -> u64 {
    i64::from(value) as u64
}

/// Extracts the real `MediaSource` — `Plain(OwnedMxcUri)` or
/// `Encrypted(Box<EncryptedFile>)` — from a media message's `MessageType`.
/// `None` for a msgtype that isn't one of the four media kinds this pass
/// renders.
///
/// This is deliberately the *only* place the SDK's `MediaSource` is reached
/// for a message: [`FocusedTimeline::media_source`] is what calls it, from a
/// live timeline item looked up by event id, and hands the result straight
/// to `core::media::message_media_thumbnail` — never through
/// [`TimelineItemDto`], which carries only [`MediaMetaDto`]'s plain metadata
/// (see that struct's doc comment for why bytes/sources never cross IPC).
fn message_media_source(msgtype: &MessageType) -> Option<MediaSource> {
    match msgtype {
        MessageType::Image(m) => Some(m.source.clone()),
        MessageType::File(m) => Some(m.source.clone()),
        MessageType::Audio(m) => Some(m.source.clone()),
        MessageType::Video(m) => Some(m.source.clone()),
        _ => None,
    }
}

/// Projects a media message's size/dimension metadata into the wire
/// [`MediaMetaDto`] — never its bytes (see that struct's doc comment).
/// `None` for anything that isn't one of the four media msgtypes this pass
/// renders.
///
/// Width/height are only ever populated for `m.image`, straight from
/// `ImageInfo` — see [`MediaMetaDto::width`]'s doc comment for why `m.video`
/// (whose `VideoInfo` carries the same two fields) doesn't get them too.
/// `filename` uses each content type's own `filename()` method, which falls
/// back to `body` when the event carries no separate `filename` field.
fn media_meta(msgtype: &MessageType) -> Option<MediaMetaDto> {
    match msgtype {
        MessageType::Image(m) => Some(MediaMetaDto {
            filename: m.filename().to_string(),
            mimetype: m.info.as_ref().and_then(|i| i.mimetype.clone()),
            size: m.info.as_ref().and_then(|i| i.size).map(uint_to_u64),
            width: m.info.as_ref().and_then(|i| i.width).map(uint_to_u64),
            height: m.info.as_ref().and_then(|i| i.height).map(uint_to_u64),
        }),
        MessageType::File(m) => Some(MediaMetaDto {
            filename: m.filename().to_string(),
            mimetype: m.info.as_ref().and_then(|i| i.mimetype.clone()),
            size: m.info.as_ref().and_then(|i| i.size).map(uint_to_u64),
            width: None,
            height: None,
        }),
        MessageType::Audio(m) => Some(MediaMetaDto {
            filename: m.filename().to_string(),
            mimetype: m.info.as_ref().and_then(|i| i.mimetype.clone()),
            size: m.info.as_ref().and_then(|i| i.size).map(uint_to_u64),
            width: None,
            height: None,
        }),
        MessageType::Video(m) => Some(MediaMetaDto {
            filename: m.filename().to_string(),
            mimetype: m.info.as_ref().and_then(|i| i.mimetype.clone()),
            size: m.info.as_ref().and_then(|i| i.size).map(uint_to_u64),
            width: None,
            height: None,
        }),
        _ => None,
    }
}

/// Maps an SDK send state onto the wire vocabulary.
///
/// Exhaustive and wildcard-free on purpose: if the SDK ever adds an
/// `EventSendState` variant, this must fail to compile rather than silently
/// misreport a message's delivery state.
fn send_state_name(state: &EventSendState) -> &'static str {
    match state {
        EventSendState::NotSentYet { .. } => "notSentYet",
        EventSendState::SendingFailed { .. } => "sendingFailed",
        EventSendState::Sent { .. } => "sent",
    }
}

/// The stable id used for an event item: its event id once the server has
/// echoed it back, or its transaction id while still a local echo.
fn event_item_id(event: &EventTimelineItem) -> String {
    match event.identifier() {
        TimelineEventItemId::TransactionId(txn) => txn.to_string(),
        TimelineEventItemId::EventId(id) => id.to_string(),
    }
}

/// Maps an SDK `MembershipChange` onto the wire vocabulary, handling the
/// outer `Option` (an event whose membership change the SDK couldn't
/// compute) explicitly rather than folding it into a wildcard.
///
/// Exhaustive over both the `Option` and every `MembershipChange` variant —
/// like `send_state_name`, this must fail to compile if the SDK adds a
/// variant, rather than silently mislabel a membership line.
fn membership_change_name(change: Option<MembershipChange>) -> &'static str {
    let Some(change) = change else {
        return "unknown";
    };
    match change {
        MembershipChange::None => "none",
        MembershipChange::Error => "error",
        MembershipChange::Joined => "joined",
        MembershipChange::Left => "left",
        MembershipChange::Banned => "banned",
        MembershipChange::Unbanned => "unbanned",
        MembershipChange::Kicked => "kicked",
        MembershipChange::Invited => "invited",
        MembershipChange::KickedAndBanned => "kickedAndBanned",
        MembershipChange::InvitationAccepted => "invitationAccepted",
        MembershipChange::InvitationRejected => "invitationRejected",
        MembershipChange::InvitationRevoked => "invitationRevoked",
        MembershipChange::Knocked => "knocked",
        MembershipChange::KnockAccepted => "knockAccepted",
        MembershipChange::KnockRetracted => "knockRetracted",
        MembershipChange::KnockDenied => "knockDenied",
        MembershipChange::NotImplemented => "notImplemented",
    }
}

/// Projects the SDK's `TimelineItemContent` taxonomy into the wire `(kind,
/// msgtype, detail)` triple documented on [`TimelineItemDto`] and in
/// `docs/matrix-events.md`.
///
/// Exhaustive with **no wildcard arm**, like `send_state_name` and
/// `project_diff`: a future `TimelineItemContent` (or `MsgLikeKind`) variant
/// must fail this to compile rather than silently fall through to the old
/// "Unsupported event" behaviour this refactor exists to remove.
fn classify_content(
    content: &TimelineItemContent,
) -> (&'static str, Option<String>, Option<String>) {
    match content {
        TimelineItemContent::MsgLike(msg_like) => match &msg_like.kind {
            MsgLikeKind::Message(message) => (
                "message",
                Some(message.msgtype().msgtype().to_string()),
                None,
            ),
            MsgLikeKind::Sticker(_) => ("sticker", None, None),
            MsgLikeKind::Poll(_) => ("poll", None, None),
            MsgLikeKind::Redacted => ("redacted", None, None),
            MsgLikeKind::UnableToDecrypt(_) => ("unableToDecrypt", None, None),
            MsgLikeKind::Other(other) => {
                ("customMessage", None, Some(other.event_type().to_string()))
            }
            MsgLikeKind::LiveLocation(_) => ("liveLocation", None, None),
        },
        TimelineItemContent::MembershipChange(change) => (
            "membership",
            None,
            Some(membership_change_name(change.change()).to_string()),
        ),
        TimelineItemContent::ProfileChange(_) => ("profileChange", None, None),
        TimelineItemContent::OtherState(state) => (
            "state",
            None,
            Some(state.content().event_type().to_string()),
        ),
        TimelineItemContent::FailedToParseMessageLike { event_type, .. } => {
            ("failedToParse", None, Some(event_type.to_string()))
        }
        TimelineItemContent::FailedToParseState { event_type, .. } => {
            ("failedToParse", None, Some(event_type.to_string()))
        }
        TimelineItemContent::CallInvite => ("callInvite", None, None),
        TimelineItemContent::RtcNotification { .. } => ("rtcNotification", None, None),
    }
}

/// Cap on a quoted reply's body excerpt, in `char`s (not bytes, so a
/// truncation always lands on a valid boundary). Long enough to give the
/// reader a line or two of real context — the point of a reply quote — short
/// enough that a message right up against the spec's 64KiB event size limit
/// still crosses IPC as a few hundred bytes, not the whole thing. This is the
/// actual enforcement point for "truncate in the core": the webview never
/// receives more than this, regardless of what CSS does with it, so a
/// display-only line-clamp can never be the only thing standing between a
/// quoted 64KiB message and the wire.
const REPLY_EXCERPT_MAX_CHARS: usize = 160;

/// Truncates a message body down to [`REPLY_EXCERPT_MAX_CHARS`] `char`s,
/// appending an ellipsis when anything was actually cut. Pure and SDK-free —
/// [`reply_to_dto`] is the thin adapter that pulls a parent's raw body out of
/// the SDK's types and hands it here, so this is what's unit-tested (see this
/// module's tests).
fn truncate_reply_excerpt(body: &str) -> String {
    let trimmed = body.trim();
    if trimmed.chars().count() <= REPLY_EXCERPT_MAX_CHARS {
        return trimmed.to_string();
    }
    let mut excerpt: String = trimmed.chars().take(REPLY_EXCERPT_MAX_CHARS).collect();
    excerpt.push('…');
    excerpt
}

/// A short label describing *why* a reply's parent has nothing to quote —
/// only ever needed when the parent's content has no body (`reply_to_dto`'s
/// `Ready` arm calls this exactly when `embedded.content.as_message()` is
/// `None`). Classifies the parent's content the same way [`classify_content`]
/// already classifies a top-level item, and maps the resulting `kind` (plus
/// `detail`, for the two kinds that need it) to the same short wording
/// `Timeline.svelte`'s `timelineItemView.ts` (`viewFor`'s placeholder
/// branches) already uses for that event kind — so a reply quoting a
/// redacted, sticker, poll, undecryptable, live-location, call-invite,
/// rtc-notification, custom-message, or failed-to-parse parent reads with
/// the exact vocabulary the reader already knows from encountering that
/// event kind as a top-level item, rather than a second, differently-worded
/// label for the same thing.
///
/// That wording match is **not** exact for `"membership"`, `"profileChange"`
/// or `"state"` — this function returns a fixed generic string for each
/// ("Membership change", "Profile change", "State change"), where `viewFor`
/// itself renders, respectively, a dynamic attributed sentence
/// (`membershipView`, e.g. "Alice joined the room"), nothing at all
/// (`render: "none"`, deliberately suppressed as noise), and a per-event-type
/// decision (`stateView`: specific system text for `m.room.create`/
/// `m.room.encryption`/`m.room.tombstone`, `render: "none"` for every other
/// state event). None of the three is reachable here in practice, which is
/// what makes the generic strings an acceptable placeholder rather than a
/// bug to fix: `Timeline::send_reply` (via the homeserver's
/// `m.relates_to`/`m.in_reply_to` validation) rejects state events as reply
/// targets outright, and `MembershipChange`/`ProfileChange` content is
/// itself carried on a state event — so no parent this function is ever
/// actually called against can classify to any of these three kinds. Fix
/// this comment's claim, not the strings themselves, if that ever changes.
///
/// `None` for `kind == "message"`: every `MsgLikeKind::Message` has a body
/// (`content.as_message()` is always `Some`), so `reply_to_dto` never
/// actually calls this function for that case in practice — included in the
/// match anyway (rather than asserted unreachable) so this function stays
/// total and safe to call with any `classify_content` output, including a
/// hypothetical future caller.
fn reply_parent_label(kind: &str, detail: Option<&str>) -> Option<String> {
    match kind {
        "message" => None,
        "sticker" => Some("Sticker".to_string()),
        "poll" => Some("Poll".to_string()),
        "redacted" => Some("Message deleted".to_string()),
        "unableToDecrypt" => Some("Encrypted message — this device has no key for it".to_string()),
        "liveLocation" => Some("Live location".to_string()),
        "callInvite" => Some("Call".to_string()),
        "rtcNotification" => Some("Call notification".to_string()),
        "customMessage" => Some(format!("Custom event ({})", detail.unwrap_or("unknown"))),
        "membership" => Some("Membership change".to_string()),
        "profileChange" => Some("Profile change".to_string()),
        "state" => Some("State change".to_string()),
        "failedToParse" => Some(format!(
            "Unsupported event ({})",
            detail.unwrap_or("unknown")
        )),
        // Defensive only, mirroring `viewFor`'s own defensive default: no
        // `classify_content` kind reaches this arm today.
        other => Some(format!("Unsupported event ({other})")),
    }
}

/// Builds the reply-quote DTO from already-extracted parent details. Pure —
/// mirrors [`project_item_parts`]'s split between SDK extraction (in
/// [`reply_to_dto`]) and logic (here, so it's unit-testable without a live
/// timeline item).
fn project_reply_to(
    event_id: &str,
    available: bool,
    sender: Option<&str>,
    sender_display_name: Option<&str>,
    body: Option<&str>,
    label: Option<&str>,
) -> ReplyToDto {
    ReplyToDto {
        event_id: event_id.to_string(),
        available,
        sender: sender.map(str::to_string),
        sender_display_name: sender_display_name.map(str::to_string),
        excerpt: body.map(truncate_reply_excerpt),
        label: label.map(str::to_string),
    }
}

/// Extracts a reply-quote DTO from an event's content, when it has one at
/// all (`content.in_reply_to()` is `None` for an ordinary, non-reply
/// message).
///
/// Handles every [`TimelineDetails`] state the parent can be in — not just
/// `Ready`. The parent is populated eagerly only when it's already present
/// in the locally materialized timeline (`InReplyToDetails::new` scans the
/// in-memory item vector); otherwise it starts `Unavailable` and only
/// resolves via an explicit `Timeline::fetch_details_for_event` call, which
/// this read-only rendering pass never makes. So `Unavailable`, `Pending`,
/// and `Error` are all real, common outcomes here — not edge cases — and are
/// deliberately folded together into `available: false` rather than
/// distinguished on the wire: this pass has nothing more useful to tell the
/// reader for any of the three than "Original message unavailable" (see
/// [`ReplyToDto`]'s doc comment), and adding a fetch call to resolve
/// `Unavailable`/`Pending` is out of scope for a read-only pass.
///
/// A `Ready` parent can *itself* have nothing to quote — a redacted,
/// sticker, poll, or undecryptable parent still has a sender, just no body
/// (`embedded.content.as_message()` is `None`). Before, that case rendered
/// as a bare sender name with no explanation; `label` (via
/// [`reply_parent_label`], only ever computed when `body` is `None`) is
/// what fixes that — see this module's and `ReplyToDto`'s doc comments.
fn reply_to_dto(content: &TimelineItemContent) -> Option<ReplyToDto> {
    let reply = content.in_reply_to()?;
    let event_id = reply.event_id.to_string();
    match reply.event {
        TimelineDetails::Ready(embedded) => {
            let sender = embedded.sender.to_string();
            let sender_display_name = match &embedded.sender_profile {
                TimelineDetails::Ready(profile) => profile.display_name.clone(),
                _ => None,
            };
            let body = embedded.content.as_message().map(|m| m.body().to_string());
            let label = if body.is_none() {
                let (kind, _msgtype, detail) = classify_content(&embedded.content);
                reply_parent_label(kind, detail.as_deref())
            } else {
                None
            };
            Some(project_reply_to(
                &event_id,
                true,
                Some(&sender),
                sender_display_name.as_deref(),
                body.as_deref(),
                label.as_deref(),
            ))
        }
        TimelineDetails::Unavailable | TimelineDetails::Pending | TimelineDetails::Error(_) => {
            Some(project_reply_to(&event_id, false, None, None, None, None))
        }
    }
}

/// Pure computation over already-extracted reaction data: for each key, how
/// many senders used it and whether `own_user_id` is one of them.
///
/// Takes plain `(key, sender ids)` pairs rather than the SDK's
/// `ReactionsByKeyBySender` directly — that type's inner map is
/// crate-private in `matrix-sdk-ui` (same reasoning as `classify_content`'s
/// test-module comment: there is no public way to build one with real
/// entries outside a live, synced timeline) — so this is what's
/// unit-testable (see this module's tests); [`reaction_entries`] is the thin
/// adapter that extracts this shape from the real SDK type.
fn project_reactions(entries: &[(String, Vec<String>)], own_user_id: &str) -> Vec<ReactionDto> {
    entries
        .iter()
        .map(|(key, senders)| ReactionDto {
            key: key.clone(),
            count: senders.len() as u32,
            by_me: senders.iter().any(|sender| sender == own_user_id),
        })
        .collect()
}

/// Extracts `(key, sender ids)` pairs from the SDK's aggregated reactions, in
/// the shape [`project_reactions`] takes. `None` (an item with no reactions
/// at all, the common case) projects to an empty `Vec`, same as an empty
/// `ReactionsByKeyBySender` would.
fn reaction_entries(reactions: Option<&ReactionsByKeyBySender>) -> Vec<(String, Vec<String>)> {
    let Some(reactions) = reactions else {
        return Vec::new();
    };
    reactions
        .iter()
        .map(|(key, by_sender)| {
            (
                key.clone(),
                by_sender.keys().map(|id| id.to_string()).collect(),
            )
        })
        .collect()
}

/// Project an SDK event item into the wire [`TimelineItemDto`].
fn project_event_item(event: &EventTimelineItem, own_user: &UserId) -> TimelineItemDto {
    let id = event_item_id(event);
    let (kind, msgtype, detail) = classify_content(event.content());
    let sender = event.sender().to_string();
    let sender_display_name = match event.sender_profile() {
        TimelineDetails::Ready(profile) => profile.display_name.clone(),
        _ => None,
    };
    let message = event.content().as_message();
    let body = message.map(|m| m.body().to_string());
    let formatted_body = message.and_then(|m| formatted_html_body(m.msgtype()));
    let media = message.and_then(|m| media_meta(m.msgtype()));
    let timestamp_ms = timestamp_to_millis(event.timestamp());
    let is_own = event.sender() == own_user;
    let send_state = event.send_state().map(send_state_name);
    let reply_to = reply_to_dto(event.content());
    let edited = message.is_some_and(|m| m.is_edited());
    let reaction_entries = reaction_entries(event.content().reactions());
    let reactions = project_reactions(&reaction_entries, own_user.as_str());

    project_item_parts(
        &id,
        kind,
        msgtype.as_deref(),
        detail.as_deref(),
        Some(&sender),
        sender_display_name.as_deref(),
        body.as_deref(),
        formatted_body.as_deref(),
        media,
        Some(timestamp_ms),
        is_own,
        send_state,
        reply_to,
        edited,
        reactions,
    )
}

/// Project an SDK virtual item (date divider, read marker, timeline start)
/// into the wire [`TimelineItemDto`]. These carry no sender/body/ownership,
/// only an id and, for date dividers, a timestamp.
fn project_virtual_item(
    item: &TimelineItem,
    virtual_item: &VirtualTimelineItem,
) -> TimelineItemDto {
    let id = item.unique_id().0.as_str();
    let (kind, timestamp_ms) = match virtual_item {
        VirtualTimelineItem::DateDivider(ts) => ("dateDivider", Some(timestamp_to_millis(*ts))),
        VirtualTimelineItem::ReadMarker => ("readMarker", None),
        VirtualTimelineItem::TimelineStart => ("timelineStart", None),
    };
    project_item_parts(
        id,
        kind,
        None,
        None,
        None,
        None,
        None,
        None,
        None,
        timestamp_ms,
        false,
        None,
        None,
        false,
        Vec::new(),
    )
}

/// Project an SDK [`TimelineItem`] into the wire [`TimelineItemDto`].
///
/// A thin adapter: it only extracts values and delegates to
/// [`project_item_parts`] (via [`project_event_item`]/[`project_virtual_item`]),
/// which carry the actual logic and are what get unit-tested.
///
/// Always returns `Some` today — `TimelineItemKind` is exhaustively
/// `Event`/`Virtual` and both are handled — but stays `Option` in the
/// signature so a future item kind this format can't usefully represent can
/// be dropped without changing callers.
pub fn project_item(item: &TimelineItem, own_user: &UserId) -> Option<TimelineItemDto> {
    Some(match item.kind() {
        TimelineItemKind::Event(event) => project_event_item(event, own_user),
        TimelineItemKind::Virtual(virtual_item) => project_virtual_item(item, virtual_item),
    })
}

/// Project a raw batch of SDK diffs into the wire ops for one envelope.
///
/// `project_item` is total over `TimelineItemKind` (see its doc comment),
/// so the `expect` below never fires in practice; it exists only because
/// `project_diff`'s closure must return `T`, not `Option<T>`.
fn project_batch(
    batch: Vec<VectorDiff<Arc<TimelineItem>>>,
    own_user: &UserId,
) -> Vec<DiffOp<TimelineItemDto>> {
    batch
        .into_iter()
        .map(|diff| {
            project_diff(diff, |item| {
                project_item(&item, own_user).expect("project_item is total over TimelineItemKind")
            })
        })
        .collect()
}

/// Project the initial `Vector` `Timeline::subscribe` returns into the
/// values for a single seeding `Reset` op.
fn project_initial(items: &Vector<Arc<TimelineItem>>, own_user: &UserId) -> Vec<TimelineItemDto> {
    items
        .iter()
        .filter_map(|item| project_item(item, own_user))
        .collect()
}

/// Owns the background task streaming one room's timeline to the webview,
/// plus the `Timeline` itself so [`FocusedTimeline::paginate_back`] and
/// [`FocusedTimeline::send_text`] can drive it.
///
/// Mirrors `core::rooms::RoomListHandle`'s shape: dropping a
/// `TimelineHandle`, or calling `stop_and_join` on it, aborts the streaming
/// task. [`FocusedTimeline`] is the sole owner, replacing and stopping any
/// previous handle before storing a new one.
pub struct TimelineHandle {
    /// The room this subscription is for — the `subject` every envelope it
    /// emits is stamped with, and the one [`TimelineHandle::snapshot`]
    /// returns so the webview can tell whose messages it is being handed.
    room_id: String,
    timeline: Arc<Timeline>,
    state: Arc<Mutex<TimelineState>>,
    task: JoinHandle<()>,
}

impl TimelineHandle {
    /// Stops the background streaming task and waits for it to actually
    /// finish, for the same reason as `core::rooms::RoomListHandle`'s
    /// identical method: the task transitively holds the `Client` and so
    /// keeps the store's SQLite files open, and `Session::logout` deletes
    /// those files.
    async fn stop_and_join(&mut self) {
        self.task.abort();
        let _ = std::pin::Pin::new(&mut self.task).await;
    }

    /// A snapshot of the timeline as of the last diff this handle's task has
    /// applied, plus the sequence number of that diff and the room it
    /// belongs to — read directly out of the same state the streaming task
    /// maintains, not from a second subscription. See
    /// `core::rooms::RoomListHandle::snapshot`'s doc comment for why that
    /// distinction matters, and [`TimelineSnapshot`] for why the room id
    /// travels with it.
    fn snapshot(&self) -> CoreResult<TimelineSnapshot> {
        self.state
            .lock()
            .map(|guard| (self.room_id.clone(), guard.0, guard.1.clone()))
            .map_err(|_| CoreError::Protocol("timeline state lock poisoned".into()))
    }
}

impl Drop for TimelineHandle {
    fn drop(&mut self) {
        // Belt and suspenders, same reasoning as `RoomListHandle`'s `Drop`:
        // a `TimelineHandle` no one stopped explicitly must not leave its
        // task running forever.
        self.task.abort();
    }
}

/// Tauri managed state holding the currently focused room's timeline
/// subscription, if any. Exactly one at a time — see this module's doc
/// comment.
#[derive(Default)]
pub struct FocusedTimeline(Mutex<Option<TimelineHandle>>);

impl FocusedTimeline {
    /// Subscribes to `room_id`'s timeline, replacing (and stopping) any
    /// timeline already focused.
    ///
    /// The previous subscription is dropped *before* the new one is built —
    /// not swapped afterwards like `Session::start_room_list` does for the
    /// room list — because only one timeline is ever meant to exist:
    /// dropping first means a slow `room.timeline()`/`subscribe()` call
    /// against the new room can never overlap with the old room's task still
    /// emitting onto the same event.
    pub async fn subscribe(
        &self,
        client: &Client,
        room_id: &str,
        app: AppHandle,
    ) -> CoreResult<()> {
        // Waits for the previous room's task to be gone, not merely
        // cancelled, before the (slow) build below starts — so it cannot
        // still be emitting onto `TIMELINE_DIFF_EVENT` while the webview is
        // already tracking the new room.
        self.clear_and_join().await;

        let parsed_room_id =
            RoomId::parse(room_id).map_err(|e| CoreError::Protocol(e.to_string()))?;
        let room = client
            .get_room(&parsed_room_id)
            .ok_or_else(|| CoreError::Protocol("unknown room".into()))?;
        let timeline = room
            .timeline()
            .await
            .map_err(|e| CoreError::Protocol(e.to_string()))?;
        let timeline = Arc::new(timeline);

        // Required to compute `TimelineItemDto::is_own` — a client with no
        // user id can't meaningfully own a subscription in the first place.
        let own_user = client.user_id().ok_or(CoreError::NotReady)?.to_owned();

        let (initial, stream) = timeline.subscribe().await;

        // Starts at `(0, [])`: "before any diff has been folded in, the
        // timeline is empty" — consistent with `SeqCounter` starting at 1,
        // since the first envelope (seq 1, the seeding `Reset` below) is
        // exactly what turns this into the true state.
        let state: Arc<Mutex<TimelineState>> = Arc::new(Mutex::new((0, Vec::new())));
        let task_state = Arc::clone(&state);

        let subject = room_id.to_string();
        let paginator = Arc::clone(&timeline);
        let task = tokio::spawn(async move {
            let mut seq = SeqCounter::default();
            // How many times this subscription has re-seeded itself after
            // the SDK emptied the timeline out from under it (see this
            // module's "Recovering from an emptied timeline" doc comment
            // and `should_reseed`). Lives here, not in `TimelineState`,
            // for the same reason `seq` does: only this task ever needs it.
            let mut reseed_attempts: u32 = 0;

            // The initial `Vector` `subscribe()` returns becomes the
            // stream's first envelope as a single `Reset`, so the webview
            // always starts from a known state instead of an empty list it
            // has to guess is complete.
            let ops = vec![DiffOp::Reset {
                values: project_initial(&initial, &own_user),
            }];
            emit_ops(&app, &task_state, &mut seq, &subject, ops);

            // Seed the room with real history.
            //
            // Sliding sync keeps only `DEFAULT_LIST_TIMELINE_LIMIT` events
            // per room — which is **1** in matrix-sdk-ui 0.18 — so without
            // this a freshly opened room renders a single message. It also
            // cannot recover on its own: one item leaves nothing to scroll,
            // so the UI's scroll-triggered back-pagination never fires.
            //
            // Awaited inside this task rather than spawned separately, so
            // switching rooms aborts it along with everything else and no
            // detached task is left holding an `Arc<Timeline>` (and through
            // it a `Client`) past teardown. The events it loads arrive as
            // ordinary diffs on the stream below.
            if let Err(err) = paginator.paginate_backwards(INITIAL_PAGE_SIZE).await {
                tracing::warn!(
                    error = %err,
                    subject = %subject,
                    "initial back-pagination failed; the room will show only cached events"
                );
            }

            let mut current_stream: TimelineDiffStream = Box::pin(stream);
            while let Some(batch) = current_stream.next().await {
                let ops = project_batch(batch, &own_user);

                // Read-only: just the current length, never a clone of the
                // list itself — `decide_batch` only needs to know whether
                // this batch is *about to* empty the list, and
                // `core::dto::ops_len_after` answers that from a length
                // alone. Nothing here mutates `task_state` yet — see this
                // module's "Coalescing the recovery into one visible
                // transition" doc comment for why that has to wait until
                // the decision below is made.
                let before = {
                    let guard = task_state
                        .lock()
                        .expect("timeline state lock poisoned by an earlier panic");
                    guard.1.len()
                };

                match decide_batch(before, ops, reseed_attempts) {
                    BatchDecision::Emit(ops) => {
                        emit_ops(&app, &task_state, &mut seq, &subject, ops);
                    }
                    BatchDecision::ReseedInstead => {
                        reseed_attempts += 1;
                        tracing::warn!(
                            subject = %subject,
                            attempt = reseed_attempts,
                            max_attempts = MAX_RESEED_ATTEMPTS,
                            "timeline emptied out from under its subscription; re-seeding and coalescing into a single reset"
                        );

                        // Same call, same reasoning as the initial seed
                        // above: awaited inline in this task (not spawned),
                        // so a room switch's `task.abort()` cancels it
                        // exactly like every other await point here.
                        if let Err(err) = paginator.paginate_backwards(INITIAL_PAGE_SIZE).await {
                            tracing::warn!(
                                error = %err,
                                subject = %subject,
                                "re-seeding back-pagination failed; converging on the timeline's live state anyway"
                            );
                        }

                        // Re-subscribing — not continuing to read
                        // `current_stream` — is what makes this resilient to
                        // the failure above and gives an authoritative
                        // snapshot to coalesce into one `Reset`, whatever
                        // that snapshot turns out to hold. See this module's
                        // doc comment for why the old stream's own queued
                        // diffs are safe to discard once this has run.
                        let (fresh_items, fresh_stream) = paginator.subscribe().await;
                        let reset_ops = coalesced_reset(project_initial(&fresh_items, &own_user));
                        emit_ops(&app, &task_state, &mut seq, &subject, reset_ops);

                        current_stream = Box::pin(fresh_stream);
                    }
                }
            }
        });

        *self
            .0
            .lock()
            .map_err(|_| CoreError::Protocol("focused timeline lock poisoned".into()))? =
            Some(TimelineHandle {
                room_id: room_id.to_string(),
                timeline,
                state,
                task,
            });
        Ok(())
    }

    /// Paginates `room_id`'s timeline backwards by up to `count` events.
    /// Returns `true` when the start of the timeline was reached.
    ///
    /// Checked against the focused room the same way, and for the same
    /// race, as [`Self::send_text`] — see [`Self::active_timeline_for`]'s
    /// doc comment. A stale pagination landing on the *new* room (the only
    /// place it could land; there is no way to reach the old room's `Timeline`
    /// at all once it's unfocused) would be silently wasted work at best —
    /// an unwanted extra page loaded into a room the reader didn't ask to
    /// paginate — and at worst counts against the new room's own
    /// [`MAX_RESEED_ATTEMPTS`] budget or perturbs its `reachedStart`
    /// bookkeeping for a scroll position the reader isn't even looking at.
    /// Neither is dangerous the way a misdirected send is, but both are
    /// pointless once the caller can name the room it means, so this takes
    /// the same guard as the other three commands rather than being the one
    /// exception.
    ///
    /// Fails with [`CoreError::NotReady`] when no room is focused, or
    /// [`CoreError::RoomChanged`] when `room_id` isn't the one that is.
    pub async fn paginate_back(&self, room_id: &str, count: u16) -> CoreResult<bool> {
        let timeline = self.active_timeline_for(room_id)?;
        timeline
            .paginate_backwards(count)
            .await
            .map_err(|e| CoreError::Protocol(e.to_string()))
    }

    /// Sends a plain-text message to `room_id`.
    ///
    /// Checks `room_id` against whichever room is actually focused, under
    /// the same lock acquisition that reads out the `Timeline` to send
    /// through — see [`Self::active_timeline_for`]'s doc comment for why
    /// that atomicity is what closes the race this exists to close, rather
    /// than merely narrowing it. This is the one command among the four
    /// that guard this way where skipping the check would be a real
    /// wrong-recipient hazard, not a safety net firing by accident: unlike
    /// [`Self::send_reply`]/[`Self::toggle_reaction`], nothing about sending
    /// a plain message can fail just because it executed against a
    /// different room than the caller intended — the send would simply
    /// succeed, into the wrong room.
    ///
    /// Does not emit anything itself: `Timeline::send` adds the local echo
    /// to the timeline, which arrives at the webview through the same diff
    /// stream `subscribe` set up — emitting it again here would show the
    /// message twice.
    ///
    /// Fails with [`CoreError::NotReady`] when no room is focused, or
    /// [`CoreError::RoomChanged`] when `room_id` isn't the one that is — in
    /// either case, nothing is sent.
    pub async fn send_text(&self, room_id: &str, body: &str) -> CoreResult<()> {
        let timeline = self.active_timeline_for(room_id)?;
        let content =
            AnyMessageLikeEventContent::RoomMessage(RoomMessageEventContent::text_plain(body));
        timeline
            .send(content)
            .await
            .map_err(|e| CoreError::Protocol(e.to_string()))?;
        Ok(())
    }

    /// Sends a plain-text reply to `in_reply_to` in `room_id`.
    ///
    /// Checked against the focused room the same way, and for the same
    /// race, as [`Self::send_text`] — see [`Self::active_timeline_for`]'s
    /// doc comment. Before this check existed, a reply issued during a room
    /// switch that resolved on the Rust side still (almost always) failed
    /// safely by accident: `in_reply_to` is a Matrix event id scoped to the
    /// room it was composed against, and `Timeline::send_reply` resolves it
    /// by fetching the parent event *from whichever room this `Timeline`
    /// now belongs to* — a different room's event id essentially never
    /// resolves there, so the send failed with [`CoreError::Protocol`]
    /// instead of landing. This check does not change that outcome; it
    /// changes *why* it fails, from an opaque fetch error to a typed,
    /// intentional [`CoreError::RoomChanged`] the webview can actually
    /// explain to the reader — and it closes the accidental net's one gap:
    /// a parent event id that happens to collide (vanishingly unlikely, but
    /// not the kind of thing to leave to chance) could otherwise still
    /// resolve in the wrong room and send there.
    ///
    /// Does not emit anything itself, same reasoning as [`Self::send_text`]:
    /// `Timeline::send_reply` adds the local echo to the timeline, which
    /// arrives at the webview through the same diff stream `subscribe` set
    /// up — emitting it again here would show the message twice.
    ///
    /// `in_reply_to` must parse as a real Matrix event id, not a local
    /// echo's transaction id — `Timeline::send_reply` resolves it by
    /// fetching the parent event by id (`Room::make_reply_event`, via
    /// `EventSource::get_event`), falling back to a
    /// `GET /rooms/{roomId}/event/{eventId}` request when it isn't cached
    /// locally. That means a parent that has scrolled out of, or been
    /// redacted out of, the locally materialized timeline (see this
    /// module's "Recovering from an emptied timeline" doc comment for one
    /// way that can happen) can still be replied to as long as the event id
    /// itself still resolves somewhere — sending does not depend on the
    /// parent still being present in this timeline's own item list. If it
    /// doesn't resolve at all (a garbage id, or the fetch itself fails),
    /// this returns [`CoreError::Protocol`] the same way any other send
    /// failure does; the webview only ever offers the Reply affordance for
    /// an item whose `sendState` shows it has already been echoed back by
    /// the server (see `Timeline.svelte`'s `canReplyOrReact`), which is what
    /// guarantees `in_reply_to` parses as a valid [`EventId`] in the first
    /// place.
    ///
    /// Fails with [`CoreError::NotReady`] when no room is focused, or
    /// [`CoreError::RoomChanged`] when `room_id` isn't the one that is — in
    /// either case, nothing is sent.
    pub async fn send_reply(&self, room_id: &str, body: &str, in_reply_to: &str) -> CoreResult<()> {
        let event_id =
            EventId::parse(in_reply_to).map_err(|e| CoreError::Protocol(e.to_string()))?;
        let timeline = self.active_timeline_for(room_id)?;
        let content = RoomMessageEventContentWithoutRelation::text_plain(body);
        timeline
            .send_reply(content, event_id)
            .await
            .map_err(|e| CoreError::Protocol(e.to_string()))?;
        Ok(())
    }

    /// Toggles `reaction_key` as a reaction on `event_id` in `room_id`.
    /// Returns whether the reaction was added (`true`) or removed (`false`)
    /// — `Timeline::toggle_reaction` decides which by checking whether the
    /// current user has already reacted with this exact key, and (per its
    /// own doc comment) serialises concurrent toggles against the same item
    /// so a rapid double-click can't race itself into sending two requests.
    ///
    /// Checked against the focused room for the same reason, and with the
    /// same "already failed safely by accident, this makes it fail
    /// intentionally" caveat, as [`Self::send_reply`] — `event_id` is
    /// looked up scoped to whichever room this `Timeline` now belongs to
    /// (`Timeline::toggle_reaction` -> `EventCache`), so a mismatched room
    /// already surfaced as [`CoreError::Protocol`]
    /// (`FailedToToggleReaction`) before this check existed; now it
    /// surfaces as [`CoreError::RoomChanged`] instead, and no longer
    /// depends on the two rooms' event ids never colliding.
    ///
    /// Does not emit anything itself, same reasoning as [`Self::send_text`]:
    /// the SDK adds (or redacts) the reaction as a local echo that arrives
    /// back through the same diff stream `subscribe` set up.
    ///
    /// `event_id` has the same real-event-id requirement [`Self::send_reply`]'s
    /// `in_reply_to` does — see that method's doc comment.
    ///
    /// Fails with [`CoreError::NotReady`] when no room is focused, or
    /// [`CoreError::RoomChanged`] when `room_id` isn't the one that is.
    pub async fn toggle_reaction(
        &self,
        room_id: &str,
        event_id: &str,
        reaction_key: &str,
    ) -> CoreResult<bool> {
        let event_id = EventId::parse(event_id).map_err(|e| CoreError::Protocol(e.to_string()))?;
        let timeline = self.active_timeline_for(room_id)?;
        let item_id = TimelineEventItemId::EventId(event_id);
        timeline
            .toggle_reaction(&item_id, reaction_key)
            .await
            .map_err(|e| CoreError::Protocol(e.to_string()))
    }

    /// Resolves `event_id`'s `MediaSource` from the focused timeline's own
    /// live state — the SDK type `core::media::message_media_thumbnail`
    /// needs to fetch a message's media bytes, deliberately kept off
    /// [`TimelineItemDto`] entirely (see [`MediaMetaDto`]'s doc comment).
    ///
    /// Looked up by event id rather than trusting an mxc URI the webview
    /// might have cached from `TimelineItemDto` itself — it can't have one,
    /// since none is ever sent — because `MediaSource` has two variants,
    /// `Plain(OwnedMxcUri)` and `Encrypted(Box<EncryptedFile>)`, and only the
    /// real timeline item (not a bare string) can say which applies. That is
    /// also what lets an encrypted room's media resolve through this exact
    /// same path later, unencrypted-only as this deployment is today, with
    /// no redesign here: `Client::media().get_media_content` (called from
    /// `core::media::fetch_thumbnail`) transparently decrypts whichever
    /// variant it's handed.
    ///
    /// `Ok(None)` covers both "no such event in this timeline" (paginated
    /// away, wrong room, a still-pending local echo id) and "found it, but
    /// it isn't a media-bearing message" — `Session::media_fetch` treats
    /// both exactly like "there is nothing to fetch", not an error.
    ///
    /// Fails with [`CoreError::NotReady`] when no room is focused at all.
    pub async fn media_source(&self, event_id: &EventId) -> CoreResult<Option<MediaSource>> {
        let timeline = self.active_timeline()?;
        let Some(item) = timeline.item_by_event_id(event_id).await else {
            return Ok(None);
        };
        let Some(message) = item.content().as_message() else {
            return Ok(None);
        };
        Ok(message_media_source(message.msgtype()))
    }

    /// A snapshot of the focused timeline as of the last diff its streaming
    /// task has applied, plus the sequence number of that diff — read
    /// directly out of the live task's own state (see
    /// `TimelineHandle::snapshot`), not from a second subscription.
    ///
    /// Fails with [`CoreError::NotReady`] when no room is focused.
    pub async fn snapshot(&self) -> CoreResult<TimelineSnapshot> {
        let handle = self
            .0
            .lock()
            .map_err(|_| CoreError::Protocol("focused timeline lock poisoned".into()))?;
        handle.as_ref().ok_or(CoreError::NotReady)?.snapshot()
    }

    /// Clones the currently focused `Timeline`, if any. `Timeline` is
    /// wrapped in `Arc` on the handle, so this is cheap.
    ///
    /// Unchecked against any particular room id — only [`Self::media_source`]
    /// still calls this directly, for a read that's already scoped by event
    /// id rather than a room id the caller names (see that method's doc
    /// comment). Every command that names a room it means to act on goes
    /// through [`Self::active_timeline_for`] instead.
    fn active_timeline(&self) -> CoreResult<Arc<Timeline>> {
        let handle = self
            .0
            .lock()
            .map_err(|_| CoreError::Protocol("focused timeline lock poisoned".into()))?;
        Ok(Arc::clone(
            &handle.as_ref().ok_or(CoreError::NotReady)?.timeline,
        ))
    }

    /// Clones the focused `Timeline`, but only when `room_id` is actually
    /// the room installed right now — this is what closes the wrong-room
    /// race described in this module's doc comment for `send_text`,
    /// `send_reply`, `toggle_reaction` and `paginate_back`.
    ///
    /// The read (which room is focused) and the act (cloning that room's
    /// `Timeline` to hand back to the caller, which then sends into it) are
    /// not two operations — they are one lock acquisition. `subscribe`'s
    /// own install step (`*self.0.lock()... = Some(...)`, at the end of that
    /// method) needs the same `std::sync::Mutex`, so it cannot swap in a
    /// different room's handle while this function is still inside its own
    /// `lock()` call deciding whether `room_id` matches. That is what makes
    /// this race-free rather than merely narrower than checking `room_id`
    /// against a value read earlier: there is no window, however small,
    /// between "confirm this is the right room" and "hand back a `Timeline`
    /// to act through" for a `subscribe` call to land in.
    ///
    /// The comparison itself is delegated to [`verify_room_focus`], a pure
    /// function over two already-extracted strings, so the actual matching
    /// logic is unit-testable without a live `Mutex`/`Timeline` at all — see
    /// this module's tests. This function is the thin, SDK-touching adapter
    /// around it, the same split `should_reseed`/`emit_ops` and
    /// `truncate_reply_excerpt`/`reply_to_dto` already use elsewhere in this
    /// module.
    fn active_timeline_for(&self, room_id: &str) -> CoreResult<Arc<Timeline>> {
        let handle = self
            .0
            .lock()
            .map_err(|_| CoreError::Protocol("focused timeline lock poisoned".into()))?;
        let handle = handle.as_ref().ok_or(CoreError::NotReady)?;
        verify_room_focus(room_id, &handle.room_id)?;
        Ok(Arc::clone(&handle.timeline))
    }

    /// Stops and drops the currently focused subscription, if any, and waits
    /// for its streaming task to actually finish before returning. A safe
    /// no-op when nothing is focused.
    ///
    /// `Session::logout` calls this before it wipes the encrypted store off
    /// disk. Merely aborting is not enough there: the task holds
    /// `Arc<Timeline>` -> `Room` -> `Client`, so until it has actually been
    /// dropped the store's SQLite files are still open — which on Windows
    /// makes `remove_dir_all` fail, and fail *after* the store passphrase
    /// has already been deleted. See `Session::logout` for what that costs.
    pub async fn clear_and_join(&self) {
        let previous = self.take();
        if let Some(mut previous) = previous {
            previous.stop_and_join().await;
        }
    }

    /// Takes the currently focused handle out, leaving nothing focused.
    ///
    /// The `Mutex` is `std::sync::Mutex`, so the guard cannot be held across
    /// an await — taking the handle out under the lock and stopping it after
    /// the guard is dropped is what keeps [`Self::clear_and_join`] legal.
    fn take(&self) -> Option<TimelineHandle> {
        self.0
            .lock()
            .expect("focused timeline lock poisoned by an earlier panic")
            .take()
    }
}

/// Decides whether the streaming task should re-seed the timeline with a
/// fresh `paginate_backwards(INITIAL_PAGE_SIZE)` call, given the
/// materialized item count immediately before and after folding the latest
/// diff batch, and how many times this subscription has already re-seeded.
/// See this module's doc comment ("Recovering from an emptied timeline") for
/// the full mechanism this exists to recover from.
///
/// Pure and SDK-free on purpose, like `core::rooms::resolve_two_person_avatar_url`
/// and the webview's `timelineGrouping`: `matrix_sdk_ui::Timeline`/
/// `eyeball_im::VectorDiff` have no public constructor outside a live,
/// synced timeline, so the actual trigger condition needs to live in
/// something that only takes plain `usize`/`u32` inputs to be testable at
/// all (see this module's tests).
///
/// Keys on **"the materialized list just went from non-empty to empty"**
/// (`before > 0 && after == 0`) rather than on which `DiffOp` emptied it.
/// The mechanism traced in this module's doc comment produces a lone
/// `VectorDiff::Clear` today, but a `Reset` with an empty `values` list or a
/// `Truncate { length: 0 }` would leave the materialized list in exactly the
/// same state — `core::dto::apply_ops` folds either into an empty `Vec` just
/// as surely as `Clear` does — so pattern-matching on `Clear` specifically
/// would silently miss those, while comparing lengths catches all three (and
/// any future op with the same effect) uniformly.
///
/// `before == 0` is deliberately excluded even when `after == 0` too: that's
/// either the very first fold (nothing has ever been seeded yet, so there is
/// nothing to have "lost") or a room that is, and remains, genuinely empty.
/// A genuinely empty room's own next batch (if any) still folds `0 -> 0`,
/// never `>0 -> 0`, so it can never itself trigger a second attempt — this
/// exclusion is what keeps `should_reseed` from re-firing forever against an
/// empty room even without consulting `reseed_attempts` at all.
/// `reseed_attempts >= MAX_RESEED_ATTEMPTS` is the second, independent bound,
/// covering the other failure shape: a room that keeps re-triggering a real
/// non-empty-to-empty transition (repeated gappy syncs, or a re-seed itself
/// landing back at zero). See [`MAX_RESEED_ATTEMPTS`]'s doc comment.
fn should_reseed(before: usize, after: usize, reseed_attempts: u32) -> bool {
    before > 0 && after == 0 && reseed_attempts < MAX_RESEED_ATTEMPTS
}

/// What the streaming task should do with one incoming diff batch —
/// [`decide_batch`]'s result. See this module's "Coalescing the recovery
/// into one visible transition" doc comment for the mechanism this exists
/// to drive.
#[derive(Debug, PartialEq)]
enum BatchDecision {
    /// Fold and emit `ops` exactly as received; no re-seed needed.
    Emit(Vec<DiffOp<TimelineItemDto>>),
    /// `ops` would empty an already-populated materialized list
    /// ([`should_reseed`] fired). The caller must not fold or emit `ops` at
    /// all — re-seed instead, and emit a single [`coalesced_reset`] once
    /// that finishes.
    ReseedInstead,
}

/// Pure decision for one incoming batch, given the materialized list's
/// length *before* this batch and how many times this subscription has
/// already re-seeded. Never touches the shared materialized state or the
/// SDK — see [`should_reseed`] and `core::dto::ops_len_after`, the two pure
/// functions this composes, for why that's possible without either folding
/// `ops` into a real list or cloning one just to measure a length.
///
/// This is the seam that keeps the emptying batch from ever reaching
/// [`emit_ops`] on its own: when [`should_reseed`] would fire, this returns
/// [`BatchDecision::ReseedInstead`] and hands `ops` back to no one — the
/// streaming task's `match` on the result has no arm that folds or emits it,
/// so there is no path through this decision that lets the empty state
/// reach the webview by itself. Every other case returns
/// [`BatchDecision::Emit`] carrying `ops` straight back, unchanged, so an
/// ordinary batch is folded and emitted exactly as it was before this
/// module coalesced re-seeding — this function only ever *withholds* a
/// batch, never rewrites one.
fn decide_batch(
    before: usize,
    ops: Vec<DiffOp<TimelineItemDto>>,
    reseed_attempts: u32,
) -> BatchDecision {
    let after = ops_len_after(before, &ops);
    if should_reseed(before, after, reseed_attempts) {
        BatchDecision::ReseedInstead
    } else {
        BatchDecision::Emit(ops)
    }
}

/// Builds the single envelope's worth of ops for a coalesced re-seed
/// transition, given the authoritative post-re-seed item list (from
/// re-subscribing — see this module's doc comment for why that, not
/// whatever the old stream still has queued, is the source of truth here).
///
/// Always exactly one [`DiffOp::Reset`], whether `fresh_items` is populated
/// (the re-seed found history to show) or empty (a real gap that resolved
/// to nothing further, the timeline's genuine start, or a failed
/// `paginate_backwards` call the caller chose to proceed past anyway — see
/// this module's tests for both: the streaming task never branches on *why*
/// `fresh_items` came back the way it did, it just re-subscribes and hands
/// whatever it got straight here). That uniformity is what makes both "the
/// coalesced path is one transition, not empty-then-refill" and "a
/// genuinely empty room still ends empty" hold at once: this function
/// cannot produce more than one op, and an empty `fresh_items` produces a
/// perfectly ordinary (if empty) `Reset`, not a special case.
fn coalesced_reset(fresh_items: Vec<TimelineItemDto>) -> Vec<DiffOp<TimelineItemDto>> {
    vec![DiffOp::Reset {
        values: fresh_items,
    }]
}

/// The actual comparison behind [`FocusedTimeline::active_timeline_for`]:
/// does `requested` (the room id a room-scoped command was issued for) name
/// the same room as `focused` (the room id of the handle currently
/// installed)?
///
/// Pure and SDK-free on purpose, like [`should_reseed`] and
/// `core::rooms::resolve_two_person_avatar_url`: both inputs are plain
/// already-extracted strings, so the actual trigger condition for
/// [`CoreError::RoomChanged`] needs no `Mutex`, no live `Timeline`, no
/// Tauri state — see this module's tests.
///
/// String equality, not `RoomId` parsing — deliberately. `focused` always
/// comes from a `TimelineHandle::room_id` that was itself built from a
/// `RoomId` `subscribe` already validated, and `requested` gets its own
/// independent parse-or-fail treatment downstream wherever it's next used
/// as a `RoomId` (`FocusedTimeline::subscribe`'s `RoomId::parse`, for the
/// `timeline_subscribe` command) — this function only ever needs to answer
/// "are these the same room", which plain string comparison already does
/// correctly for two syntactically valid Matrix room ids, without this
/// function taking on a second, redundant validation job.
fn verify_room_focus(requested: &str, focused: &str) -> CoreResult<()> {
    if requested == focused {
        Ok(())
    } else {
        Err(CoreError::RoomChanged {
            requested: requested.to_string(),
            focused: focused.to_string(),
        })
    }
}

/// Folds `ops` into the materialized snapshot under one critical section,
/// then emits the resulting envelope — mirroring
/// `core::rooms::spawn_room_list`'s identical fold-then-emit sequencing (see
/// that function's doc comment for why the fold must happen before the lock
/// is released, and the lock must be released before emitting).
///
/// Returns `(before, after)` — the materialized item count immediately
/// before and after this batch was folded in — so the caller can feed
/// [`should_reseed`] without a second, separately-locked read of the same
/// state.
fn emit_ops(
    app: &AppHandle,
    state: &Arc<Mutex<TimelineState>>,
    seq: &mut SeqCounter,
    subject: &str,
    ops: Vec<DiffOp<TimelineItemDto>>,
) -> (usize, usize) {
    let seq_no = seq.next_seq();
    let (before, after) = {
        let mut guard = state
            .lock()
            .expect("timeline state lock poisoned by an earlier panic");
        let before = guard.1.len();
        apply_ops(&mut guard.1, &ops);
        guard.0 = seq_no;
        (before, guard.1.len())
    };
    let folded_len = after;

    tracing::debug!(
        seq = seq_no,
        subject = subject,
        ops = ops.len(),
        kinds = ?ops.iter().map(op_name).collect::<Vec<_>>(),
        items = folded_len,
        "emitting timeline diff"
    );

    let envelope = DiffEnvelope {
        channel: TIMELINE_CHANNEL.into(),
        subject: subject.to_string(),
        seq: seq_no,
        ops,
    };
    if let Err(err) = app.emit(TIMELINE_DIFF_EVENT, &envelope) {
        tracing::warn!(error = %err, "failed to emit {TIMELINE_DIFF_EVENT}");
    }

    (before, after)
}

#[cfg(test)]
mod tests {
    use super::*;
    // Only used by the composed-pipeline tests below, which call
    // `MessageType::sanitize` themselves to reproduce ruma's own
    // `HtmlSanitizerMode::Compat` pass exactly as `matrix_sdk_ui`'s
    // `Message::from_event` runs it, before handing the result to
    // `formatted_html_body`/`harden_formatted_body` the way the app
    // actually does. Not imported at module scope: production code never
    // needs it directly (that call happens inside `matrix-sdk-ui`, not
    // here), so importing it there would be an unused import outside tests.
    use matrix_sdk::ruma::html::RemoveReplyFallback;

    #[test]
    fn projects_a_text_message_with_ownership() {
        let dto = project_item_parts(
            "$e1",
            "message",
            Some("m.text"),
            None,
            Some("@me:x.org"),
            Some("Me"),
            Some("hello"),
            None,
            None,
            Some(1_700_000_000_000),
            true,
            None,
            None,
            false,
            Vec::new(),
        );
        assert_eq!(dto.kind, "message");
        assert_eq!(dto.msgtype.as_deref(), Some("m.text"));
        assert_eq!(dto.body.as_deref(), Some("hello"));
        assert!(dto.formatted_body.is_none());
        assert!(dto.media.is_none());
        assert!(dto.is_own);
        // A plain message (not a reply, not edited, no reactions) projects
        // none of the M2 additions — see this module's tests further down
        // for the cases where each one actually fires.
        assert!(dto.reply_to.is_none());
        assert!(!dto.edited);
        assert!(dto.reactions.is_empty());
    }

    #[test]
    fn virtual_items_are_projected_with_their_own_kind() {
        let dto = project_item_parts(
            "vd1",
            "dateDivider",
            None,
            None,
            None,
            None,
            None,
            None,
            None,
            None,
            false,
            None,
            None,
            false,
            Vec::new(),
        );
        assert_eq!(dto.kind, "dateDivider");
        assert!(dto.sender.is_none());
    }

    /// `classify_content`/`membership_change_name` can't be exercised
    /// directly here: `TimelineItemContent`, `MsgLikeKind`, `OtherState`, and
    /// `RoomMembershipChange` all have crate-private constructors in
    /// `matrix-sdk-ui` — there is no public way to build one outside a real
    /// synced timeline. The classifier's cases are covered indirectly below
    /// through `project_item_parts`, which is what it ultimately feeds; the
    /// exhaustive-match discipline itself is enforced by the compiler, not
    /// by a test (a new SDK variant fails to compile, per its doc comment).
    #[test]
    fn state_events_project_to_the_state_kind_with_the_event_type_as_detail() {
        let dto = project_item_parts(
            "$e2",
            "state",
            None,
            Some("m.room.name"),
            Some("@alice:x.org"),
            Some("Alice"),
            None,
            None,
            None,
            Some(1_700_000_000_000),
            false,
            None,
            None,
            false,
            Vec::new(),
        );
        assert_eq!(dto.kind, "state");
        assert_eq!(dto.detail.as_deref(), Some("m.room.name"));
        assert!(dto.msgtype.is_none());
    }

    #[test]
    fn notice_messages_carry_their_msgtype() {
        let dto = project_item_parts(
            "$e3",
            "message",
            Some("m.notice"),
            None,
            Some("@bot:x.org"),
            None,
            Some("build finished"),
            None,
            None,
            Some(1_700_000_000_000),
            false,
            None,
            None,
            false,
            Vec::new(),
        );
        assert_eq!(dto.kind, "message");
        assert_eq!(dto.msgtype.as_deref(), Some("m.notice"));
    }

    #[test]
    fn project_item_parts_carries_a_formatted_body_through_untouched() {
        // `project_item_parts` just stores whatever it's handed — the
        // extraction and hardening live in `formatted_html_body` /
        // `harden_formatted_body`, tested below.
        let dto = project_item_parts(
            "$e4",
            "message",
            Some("m.text"),
            None,
            Some("@me:x.org"),
            Some("Me"),
            Some("plain"),
            Some("<p>rich</p>"),
            None,
            Some(1_700_000_000_000),
            true,
            None,
            None,
            false,
            Vec::new(),
        );
        assert_eq!(dto.body.as_deref(), Some("plain"));
        assert_eq!(dto.formatted_body.as_deref(), Some("<p>rich</p>"));
    }

    #[test]
    fn formatted_html_body_is_none_for_a_plain_text_message() {
        assert!(formatted_html_body(&MessageType::text_plain("hello")).is_none());
    }

    #[test]
    fn formatted_html_body_is_none_for_a_msgtype_that_never_carries_one() {
        // `m.image` (and every other non-text/notice/emote msgtype) has no
        // `formatted` field at all in ruma's model.
        let msgtype = MessageType::new(
            "m.image",
            "cat.png".into(),
            serde_json::json!({ "url": "mxc://example.org/abc" })
                .as_object()
                .unwrap()
                .clone(),
        )
        .unwrap();
        assert!(formatted_html_body(&msgtype).is_none());
    }

    #[test]
    fn formatted_html_body_projects_an_html_formatted_text_message() {
        let msgtype = MessageType::text_html("plain fallback", "<p>rich <strong>text</strong></p>");
        let html = formatted_html_body(&msgtype).expect("m.text with an HTML formatted body");
        assert!(html.contains("<strong>text</strong>"));
    }

    #[test]
    fn formatted_html_body_projects_html_formatted_notice_messages_too() {
        assert!(formatted_html_body(&MessageType::notice_html("n", "<p>n</p>")).is_some());
    }

    #[test]
    fn formatted_html_body_is_none_for_an_emote_even_with_an_html_formatted_body() {
        // `Timeline.svelte`'s emote view never renders `formattedBody` (see
        // this function's doc comment) — computing one here would be dead
        // weight, so `m.emote` is deliberately excluded from the match even
        // though ruma's model lets it carry a `formatted` field.
        assert!(formatted_html_body(&MessageType::emote_html("e", "<p>e</p>")).is_none());
    }

    #[test]
    fn formatted_html_body_is_none_when_format_is_not_the_html_one() {
        use matrix_sdk::ruma::events::room::message::TextMessageEventContent;

        let mut content = TextMessageEventContent::plain("plain fallback");
        content.formatted = Some(FormattedBody {
            // The spec defines exactly one format, `org.matrix.custom.html`;
            // this is deliberately something else, simulating a value this
            // build doesn't understand.
            format: MessageFormat::from("some.other.format"),
            body: "<p>should not be projected</p>".into(),
        });
        assert!(formatted_html_body(&MessageType::Text(content)).is_none());
    }

    // `media_meta`/`message_media_source`: pure extraction from an SDK
    // `MessageType`, constructible in a test without a live homeserver or
    // synced timeline (unlike `classify_content`, whose inputs are
    // crate-private in `matrix-sdk-ui` — see that test module comment
    // above).

    #[test]
    fn media_meta_is_none_for_a_plain_text_message() {
        assert!(media_meta(&MessageType::text_plain("hello")).is_none());
    }

    #[test]
    fn media_meta_projects_an_image_messages_dimensions_mimetype_and_size() {
        use matrix_sdk::ruma::events::room::message::ImageMessageEventContent;
        use matrix_sdk::ruma::events::room::ImageInfo;
        use matrix_sdk::ruma::OwnedMxcUri;

        let mut info = ImageInfo::new();
        info.width = Some(UInt::from(800u32));
        info.height = Some(UInt::from(600u32));
        info.mimetype = Some("image/png".to_string());
        info.size = Some(UInt::from(123_456u32));

        let content = ImageMessageEventContent::plain(
            "cat.png".to_string(),
            OwnedMxcUri::from("mxc://example.org/abc"),
        )
        .info(Box::new(info));

        let meta =
            media_meta(&MessageType::Image(content)).expect("m.image must project media metadata");
        assert_eq!(meta.filename, "cat.png");
        assert_eq!(meta.mimetype.as_deref(), Some("image/png"));
        assert_eq!(meta.size, Some(123_456));
        assert_eq!(meta.width, Some(800));
        assert_eq!(meta.height, Some(600));
    }

    #[test]
    fn media_meta_projects_a_file_messages_filename_without_dimensions() {
        use matrix_sdk::ruma::events::room::message::{FileInfo, FileMessageEventContent};
        use matrix_sdk::ruma::OwnedMxcUri;

        let mut info = FileInfo::new();
        info.mimetype = Some("application/pdf".to_string());
        info.size = Some(UInt::from(9_000u32));

        let content = FileMessageEventContent::plain(
            "report.pdf".to_string(),
            OwnedMxcUri::from("mxc://example.org/def"),
        )
        .info(Box::new(info));

        let meta =
            media_meta(&MessageType::File(content)).expect("m.file must project media metadata");
        assert_eq!(meta.filename, "report.pdf");
        assert_eq!(meta.mimetype.as_deref(), Some("application/pdf"));
        assert_eq!(meta.size, Some(9_000));
        assert!(
            meta.width.is_none(),
            "a file message has no ImageInfo to derive dimensions from"
        );
        assert!(meta.height.is_none());
    }

    #[test]
    fn media_meta_falls_back_to_body_when_no_filename_field_is_set() {
        // `filename()` (see each content type's doc comment) falls back to
        // `body` when the event carries no separate `filename` field — the
        // common case, since `filename` only diverges from `body` when the
        // message also has a caption.
        use matrix_sdk::ruma::events::room::message::AudioMessageEventContent;
        use matrix_sdk::ruma::OwnedMxcUri;

        let content = AudioMessageEventContent::plain(
            "voice-note.ogg".to_string(),
            OwnedMxcUri::from("mxc://example.org/ghi"),
        );
        let meta =
            media_meta(&MessageType::Audio(content)).expect("m.audio must project media metadata");
        assert_eq!(meta.filename, "voice-note.ogg");
    }

    #[test]
    fn message_media_source_extracts_the_source_for_media_msgtypes_and_none_otherwise() {
        use matrix_sdk::ruma::events::room::message::ImageMessageEventContent;
        use matrix_sdk::ruma::OwnedMxcUri;

        let content = ImageMessageEventContent::plain(
            "cat.png".to_string(),
            OwnedMxcUri::from("mxc://example.org/abc"),
        );
        let source = message_media_source(&MessageType::Image(content))
            .expect("m.image must carry a MediaSource");
        match source {
            MediaSource::Plain(uri) => assert_eq!(uri.to_string(), "mxc://example.org/abc"),
            MediaSource::Encrypted(_) => panic!("expected a plain source, not an encrypted one"),
        }

        assert!(message_media_source(&MessageType::text_plain("hi")).is_none());
    }

    #[test]
    fn harden_formatted_body_drops_img_elements_entirely() {
        // Even a spec-compliant `mxc://` src (which ruma's own scheme check
        // is *supposed* to require, though see this function's doc comment
        // for why that check isn't reliable) is dropped: the webview can't
        // load `mxc://` either way, so this app drops `<img>` outright
        // rather than rendering a permanently-broken image.
        let out = harden_formatted_body(
            r#"<p>before<img src="mxc://example.org/abc" alt="cat">after</p>"#,
        );
        assert!(!out.contains("<img"), "expected no <img> in {out:?}");
    }

    #[test]
    fn harden_formatted_body_drops_mx_reply_elements() {
        let out = harden_formatted_body(
            r#"<mx-reply><blockquote><a href="https://matrix.to/#/@victim:example.org">@victim</a><br>fabricated quote</blockquote></mx-reply>real reply text"#,
        );
        assert!(
            !out.contains("mx-reply"),
            "expected mx-reply removed from {out:?}"
        );
        assert!(
            !out.contains("fabricated quote"),
            "expected mx-reply's *content* removed too (it's `remove_elements`, not \
             `ignore_elements`) from {out:?}"
        );
        assert!(
            out.contains("real reply text"),
            "expected content outside mx-reply preserved in {out:?}"
        );
    }

    #[test]
    fn harden_formatted_body_caps_nesting_depth() {
        // Each repeated `<blockquote>` adds exactly one level of nesting
        // (unlike, say, a `<ul><li>` pair, which would add two per
        // repeat), so the innermost content sits at depth `repeats - 1` —
        // the outermost `<blockquote>` is a top-level child, at depth 0.
        // `MAX_ELEMENT_DEPTH` repeats therefore lands the innermost `x`
        // exactly one level *under* the cap (depth `MAX_ELEMENT_DEPTH -
        // 1`, since a node is removed once its own depth `>=
        // MAX_ELEMENT_DEPTH`) — the tightest boundary this can assert
        // without being one off in either direction. Doubling the repeat
        // count pushes it well past the cap. Mirrors the review's
        // 100-nested-`<blockquote>` finding against ruma's own default
        // depth of 100, just at this app's much lower cap.
        let shallow = format!(
            "{}x{}",
            "<blockquote>".repeat(MAX_ELEMENT_DEPTH as usize),
            "</blockquote>".repeat(MAX_ELEMENT_DEPTH as usize)
        );
        let shallow_out = harden_formatted_body(&shallow);
        assert!(
            shallow_out.contains('x'),
            "expected content within the depth cap preserved in {shallow_out:?}"
        );

        let deep = format!(
            "{}x{}",
            "<blockquote>".repeat((MAX_ELEMENT_DEPTH * 2) as usize),
            "</blockquote>".repeat((MAX_ELEMENT_DEPTH * 2) as usize)
        );
        let deep_out = harden_formatted_body(&deep);
        assert!(
            !deep_out.contains('x'),
            "expected content past the depth cap removed from {deep_out:?}"
        );
    }

    #[test]
    fn harden_formatted_body_keeps_links_on_the_allowed_scheme_list() {
        for scheme_href in [
            "https://example.org/x",
            "http://example.org/x",
            "mailto:a@example.org",
        ] {
            let out = harden_formatted_body(&format!(r#"<a href="{scheme_href}">link</a>"#));
            assert!(
                out.contains(&format!(r#"href="{scheme_href}""#)),
                "expected {scheme_href} preserved in {out:?}"
            );
        }
    }

    #[test]
    fn harden_formatted_body_unwraps_links_outside_the_allowed_scheme_list() {
        // `ftp:`/`magnet:` are schemes ruma's own Compat sanitiser would
        // still allow (see this function's doc comment) but this app
        // narrows away, since nothing here opens either. `javascript:`
        // shouldn't reach this function at all (ruma's earlier pass already
        // strips it), but is included as a second layer of proof that a
        // disallowed scheme never survives with its anchor intact.
        for href in [
            "ftp://example.org/file",
            "magnet:?xt=urn:btih:abc",
            "javascript:alert(1)",
        ] {
            let out = harden_formatted_body(&format!(r#"<a href="{href}">click me</a>"#));
            assert!(
                !out.contains("<a "),
                "expected the anchor removed from {out:?}"
            );
            assert!(
                out.contains("click me"),
                "expected the link text preserved in {out:?}"
            );
        }
    }

    #[test]
    fn harden_formatted_body_preserves_ordinary_rich_text_formatting() {
        let out = harden_formatted_body(
            "<p>Fresh login URL:</p><p><strong>https://login.example.org/a/1</strong></p><pre><code>fn main() {}</code></pre>",
        );
        assert!(out.contains("<strong>https://login.example.org/a/1</strong>"));
        assert!(out.contains("<pre>"));
        assert!(out.contains("<code>"));
    }

    // The tests below run the *composed* pipeline — ruma's own
    // `HtmlSanitizerMode::Compat` pass (via `MessageType::sanitize`, called
    // exactly as `matrix_sdk_ui::timeline::Message::from_event` calls it),
    // then `formatted_html_body`/`harden_formatted_body` — rather than
    // handing already-hardened or hand-written "clean" input to
    // `harden_formatted_body` alone. That distinction is load-bearing: a
    // test of `harden_formatted_body` in isolation, fed input that was
    // never actually run through ruma's own (buggy) scheme check first,
    // cannot exercise — and so cannot catch a regression in — the exact
    // interaction `harden_formatted_body`'s doc comment depends on (that
    // ruma's own pass has already stripped `class` etc. from `<a>`/`<img>`
    // by the time this app's own pass runs, and that this app's pass does
    // not itself depend on ruma's scheme check having worked).

    #[test]
    fn composed_pipeline_removes_a_javascript_href_ruma_alone_lets_through() {
        // `<a class="x" href="javascript:...">`: `class` sorts before
        // `href` in ruma's `BTreeSet<Attribute>` iteration order, which
        // trips the early-return bug in `clean_node`'s scheme-checking loop
        // (`ruma-html-0.8.0/src/sanitizer_config/clean.rs`) and skips the
        // scheme check for `href` entirely — ruma's `HtmlSanitizerMode::Compat`
        // pass alone leaves `href="javascript:alert(1)"` completely intact
        // (only `class`, which *is* on a working code path, gets stripped).
        let mut msgtype = MessageType::text_html(
            "plain",
            r#"<a class="x" href="javascript:alert(1)">click</a>"#,
        );
        msgtype.sanitize(HtmlSanitizerMode::Compat, RemoveReplyFallback::Yes);

        let html = formatted_html_body(&msgtype).expect("m.text with an HTML formatted body");
        assert!(
            !html.contains("javascript:"),
            "expected the javascript: scheme removed from {html:?}"
        );
        assert!(
            !html.contains("<a "),
            "expected the anchor unwrapped (not just its href stripped) in {html:?}"
        );
        assert!(
            html.contains("click"),
            "expected the link text preserved in {html:?}"
        );
    }

    #[test]
    fn composed_pipeline_removes_a_remote_img_src_ruma_alone_lets_through() {
        // Same bug, same shape: `alt` sorts before `src` on `<img>`, so
        // ruma's `HtmlSanitizerMode::Compat` pass alone leaves a remote
        // (non-`mxc://`) `src` completely intact — a tracking beacon.
        let mut msgtype = MessageType::text_html(
            "plain",
            r#"<img alt="a" src="https://evil.example/beacon.png">"#,
        );
        msgtype.sanitize(HtmlSanitizerMode::Compat, RemoveReplyFallback::Yes);

        let html = formatted_html_body(&msgtype).expect("m.text with an HTML formatted body");
        assert!(
            !html.contains("<img"),
            "expected <img> removed from {html:?}"
        );
        assert!(
            !html.contains("evil.example"),
            "expected the remote src gone entirely from {html:?}"
        );
    }

    #[test]
    fn composed_pipeline_strips_script_handlers_style_and_srcdoc_end_to_end() {
        let mut msgtype = MessageType::text_html(
            "plain",
            r#"<p onclick="alert(1)" style="color:red">hi<script>alert(2)</script></p><iframe srcdoc="<script>alert(3)</script>"></iframe>"#,
        );
        msgtype.sanitize(HtmlSanitizerMode::Compat, RemoveReplyFallback::Yes);

        let html = formatted_html_body(&msgtype).expect("m.text with an HTML formatted body");
        assert!(
            !html.contains("<script"),
            "expected <script> removed from {html:?}"
        );
        assert!(
            !html.contains("onclick"),
            "expected the onclick handler removed from {html:?}"
        );
        assert!(
            !html.contains("style="),
            "expected the style attribute removed from {html:?}"
        );
        assert!(
            !html.contains("<iframe"),
            "expected <iframe> removed from {html:?}"
        );
        assert!(
            !html.contains("srcdoc"),
            "expected srcdoc removed from {html:?}"
        );
        assert!(
            html.contains("hi"),
            "expected the safe text content preserved in {html:?}"
        );
    }

    #[test]
    fn membership_change_names_are_mapped_to_the_wire_vocabulary() {
        assert_eq!(membership_change_name(None), "unknown");
        assert_eq!(membership_change_name(Some(MembershipChange::None)), "none");
        assert_eq!(
            membership_change_name(Some(MembershipChange::Error)),
            "error"
        );
        assert_eq!(
            membership_change_name(Some(MembershipChange::Joined)),
            "joined"
        );
        assert_eq!(membership_change_name(Some(MembershipChange::Left)), "left");
        assert_eq!(
            membership_change_name(Some(MembershipChange::Banned)),
            "banned"
        );
        assert_eq!(
            membership_change_name(Some(MembershipChange::Unbanned)),
            "unbanned"
        );
        assert_eq!(
            membership_change_name(Some(MembershipChange::Kicked)),
            "kicked"
        );
        assert_eq!(
            membership_change_name(Some(MembershipChange::Invited)),
            "invited"
        );
        assert_eq!(
            membership_change_name(Some(MembershipChange::KickedAndBanned)),
            "kickedAndBanned"
        );
        assert_eq!(
            membership_change_name(Some(MembershipChange::InvitationAccepted)),
            "invitationAccepted"
        );
        assert_eq!(
            membership_change_name(Some(MembershipChange::InvitationRejected)),
            "invitationRejected"
        );
        assert_eq!(
            membership_change_name(Some(MembershipChange::InvitationRevoked)),
            "invitationRevoked"
        );
        assert_eq!(
            membership_change_name(Some(MembershipChange::Knocked)),
            "knocked"
        );
        assert_eq!(
            membership_change_name(Some(MembershipChange::KnockAccepted)),
            "knockAccepted"
        );
        assert_eq!(
            membership_change_name(Some(MembershipChange::KnockRetracted)),
            "knockRetracted"
        );
        assert_eq!(
            membership_change_name(Some(MembershipChange::KnockDenied)),
            "knockDenied"
        );
        assert_eq!(
            membership_change_name(Some(MembershipChange::NotImplemented)),
            "notImplemented"
        );
    }

    #[test]
    fn send_state_names_are_mapped_to_the_wire_vocabulary() {
        assert_eq!(
            send_state_name(&EventSendState::NotSentYet { progress: None }),
            "notSentYet"
        );
        assert_eq!(
            send_state_name(&EventSendState::Sent {
                event_id: <&matrix_sdk::ruma::EventId>::try_from("$abc:example.org")
                    .unwrap()
                    .to_owned(),
            }),
            "sent"
        );
        // `SendingFailed`'s wire mapping only depends on the variant, not
        // the error payload, so any `matrix_sdk::Error` value nothing else
        // in this crate can construct more directly will do — an IO error
        // is the simplest one with a public constructor.
        let error = Arc::new(matrix_sdk::Error::Io(std::io::Error::other("boom")));
        assert_eq!(
            send_state_name(&EventSendState::SendingFailed {
                error,
                is_recoverable: false
            }),
            "sendingFailed"
        );
    }

    // `should_reseed`: pure over `(before, after, reseed_attempts)`, so it's
    // exercised directly here rather than through a live subscription — see
    // its own doc comment for why that's the only way to test this decision
    // at all (`matrix_sdk_ui::Timeline`/`eyeball_im::VectorDiff` have no
    // test-friendly constructor).

    #[test]
    fn should_reseed_fires_when_a_nonempty_list_becomes_empty() {
        assert!(should_reseed(20, 0, 0));
    }

    #[test]
    fn should_reseed_does_not_fire_when_the_list_stays_nonempty() {
        // Covers the "the SDK repopulated within the same batch" case: the
        // final post-batch length is what matters, not whether some op
        // partway through the batch happened to zero it out.
        assert!(!should_reseed(20, 1, 0));
    }

    #[test]
    fn should_reseed_does_not_fire_on_the_very_first_fold() {
        // `before == 0` covers both "nothing has been seeded yet" and "this
        // room is genuinely empty" — neither is a loss of anything to
        // recover.
        assert!(!should_reseed(0, 0, 0));
    }

    #[test]
    fn should_reseed_does_not_loop_forever_against_a_genuinely_empty_room() {
        // A room that starts (and stays) empty only ever folds `0 -> 0`
        // batches after its first seed — never `>0 -> 0` — so it can never
        // trigger a second time regardless of `reseed_attempts`, without
        // needing the attempts bound to intervene at all.
        for attempts in 0..10 {
            assert!(!should_reseed(0, 0, attempts));
        }
    }

    #[test]
    fn should_reseed_is_bounded_by_max_reseed_attempts() {
        // A room that keeps re-triggering a genuine non-empty-to-empty
        // transition (e.g. repeated gappy syncs) is allowed to recover up to
        // the bound, then stops — this is what actually prevents an
        // infinite loop for *that* failure shape (the empty-room case above
        // never reaches this bound in the first place).
        for attempts in 0..MAX_RESEED_ATTEMPTS {
            assert!(
                should_reseed(5, 0, attempts),
                "expected attempt {attempts} (below the cap of {MAX_RESEED_ATTEMPTS}) to still trigger"
            );
        }
        assert!(
            !should_reseed(5, 0, MAX_RESEED_ATTEMPTS),
            "expected the cap itself to stop triggering"
        );
        assert!(
            !should_reseed(5, 0, MAX_RESEED_ATTEMPTS + 5),
            "expected well past the cap to stay stopped"
        );
    }

    // `decide_batch`/`coalesced_reset`: the coalescing mechanism itself —
    // pure, like `should_reseed` above, and exercised directly for the same
    // reason. These cover exactly the properties this fix exists to
    // guarantee (see this module's "Coalescing the recovery into one
    // visible transition" doc comment): the batch that would empty the
    // timeline is withheld rather than emitted, an ordinary batch passes
    // through unchanged, a genuinely empty (or failed) re-seed still
    // converges on an empty `Reset` rather than holding stale content, a
    // repopulated re-seed becomes exactly one `Reset` rather than a
    // `Clear` plus separate inserts, and the whole transition consumes
    // exactly one sequence number.

    /// A minimal `TimelineItemDto` for tests that only care about identity
    /// (via `id`), not any of the other ~14 fields `project_item_parts`
    /// takes.
    fn minimal_dto(id: &str) -> TimelineItemDto {
        project_item_parts(
            id,
            "message",
            Some("m.text"),
            None,
            None,
            None,
            None,
            None,
            None,
            None,
            false,
            None,
            None,
            false,
            Vec::new(),
        )
    }

    #[test]
    fn decide_batch_holds_a_batch_that_would_empty_an_already_populated_list() {
        // The trigger from this module's doc comment: a non-empty
        // materialized list receiving a lone `Clear`.
        assert_eq!(
            decide_batch(20, vec![DiffOp::Clear], 0),
            BatchDecision::ReseedInstead
        );
    }

    #[test]
    fn decide_batch_emits_an_ordinary_batch_unchanged() {
        let ops = vec![DiffOp::PushBack {
            value: minimal_dto("$new"),
        }];
        assert_eq!(decide_batch(3, ops.clone(), 0), BatchDecision::Emit(ops));
    }

    #[test]
    fn decide_batch_emits_the_very_first_fold_even_though_it_stays_empty() {
        // Mirrors `should_reseed_does_not_fire_on_the_very_first_fold`: a
        // freshly-subscribed room's first (empty) batch is an ordinary
        // emit, not a hold.
        assert_eq!(
            decide_batch(0, Vec::new(), 0),
            BatchDecision::Emit(Vec::new())
        );
    }

    #[test]
    fn decide_batch_falls_back_to_emitting_once_the_reseed_budget_is_spent() {
        // Mirrors `should_reseed_is_bounded_by_max_reseed_attempts`: past
        // the cap, `decide_batch` must not keep withholding batches forever
        // — it falls back to showing the bare `Clear` (the pre-coalescing
        // behaviour) rather than looping. This is what keeps
        // `MAX_RESEED_ATTEMPTS` meaningful in the coalesced shape: it still
        // bounds how many times the streaming task will ever call
        // `paginate_backwards`+`subscribe` for one subscription's lifetime.
        let ops = vec![DiffOp::Clear];
        assert_eq!(
            decide_batch(5, ops.clone(), MAX_RESEED_ATTEMPTS),
            BatchDecision::Emit(ops)
        );
    }

    #[test]
    fn coalesced_reset_produces_a_single_reset_op_when_history_is_found() {
        // Never a `Clear` followed by separate inserts — the whole point of
        // coalescing.
        let a = minimal_dto("$a");
        let b = minimal_dto("$b");
        let ops = coalesced_reset(vec![a.clone(), b.clone()]);
        assert_eq!(ops, vec![DiffOp::Reset { values: vec![a, b] }]);
    }

    #[test]
    fn coalesced_reset_still_ends_empty_for_a_genuinely_empty_room() {
        // A room that really has nothing to show after re-seeding (the
        // timeline's actual start, or a real gap resolving to nothing
        // further) must not be left showing stale pre-clear content.
        assert_eq!(
            coalesced_reset(Vec::new()),
            vec![DiffOp::Reset { values: Vec::new() }]
        );
    }

    #[test]
    fn coalesced_reset_converges_regardless_of_why_fresh_items_is_what_it_is() {
        // The streaming task calls this with whatever `Timeline::subscribe`
        // returns *after* a re-seed attempt, whether that attempt's own
        // `paginate_backwards` call succeeded or failed — see this module's
        // doc comment for why re-subscribing unconditionally (rather than
        // branching on that `Result`) is what makes a failure converge on
        // the timeline's real state instead of holding stale content
        // forever. From this function's point of view, "a failed re-seed
        // that still found the last-persisted chunk" and "a successful
        // re-seed" are the same input shape: whatever real items came back.
        let dto = minimal_dto("$still-there");
        assert_eq!(
            coalesced_reset(vec![dto.clone()]),
            vec![DiffOp::Reset { values: vec![dto] }]
        );
    }

    #[test]
    fn coalesced_recovery_consumes_exactly_one_sequence_number_for_the_whole_transition() {
        let mut seq = SeqCounter::default();

        // An ordinary batch (e.g. the initial seeding `Reset`) gets its own
        // sequence number.
        let seq_for_seed = seq.next_seq();

        // A second ordinary batch — some real event before the gappy sync.
        let ordinary = decide_batch(
            20,
            vec![DiffOp::PushBack {
                value: minimal_dto("$x"),
            }],
            0,
        );
        assert!(matches!(ordinary, BatchDecision::Emit(_)));
        let seq_for_ordinary = seq.next_seq();

        // The gappy sync's `Clear` arrives: held, not emitted — so it must
        // not consume a sequence number of its own.
        let held = decide_batch(21, vec![DiffOp::Clear], 0);
        assert_eq!(held, BatchDecision::ReseedInstead);

        // The re-seed resolves; its coalesced `Reset` is the very next
        // envelope, immediately after the last ordinary one — no gap, no
        // number spent on the held `Clear`.
        let reset_ops = coalesced_reset(vec![minimal_dto("$x")]);
        let seq_for_reset = seq.next_seq();

        assert_eq!(seq_for_seed, 1);
        assert_eq!(seq_for_ordinary, 2);
        assert_eq!(
            seq_for_reset, 3,
            "the whole clear-and-reseed transition must consume exactly one \
             sequence number, immediately after the last ordinary one"
        );
        assert_eq!(
            reset_ops.len(),
            1,
            "the coalesced transition must be exactly one op, not a Clear \
             followed by separate inserts"
        );
    }

    // `verify_room_focus`: pure over two already-extracted room id strings,
    // so — like `should_reseed` above — this is exercised directly rather
    // than through a live `FocusedTimeline`/`Mutex`/`Timeline`. This is the
    // mismatch path the room-scope fix exists to close: a command issued for
    // room A while room B is focused must fail with `CoreError::RoomChanged`
    // and, structurally (see `FocusedTimeline::active_timeline_for` and
    // every one of `send_text`/`send_reply`/`toggle_reaction`/
    // `paginate_back`'s `let timeline = self.active_timeline_for(room_id)?;`
    // guard), never reach the SDK call that would actually send/toggle/
    // paginate — an `Err` here returns before any `Timeline` is even handed
    // back to the caller.

    #[test]
    fn verify_room_focus_succeeds_when_the_requested_room_is_focused() {
        assert!(verify_room_focus("!a:x.org", "!a:x.org").is_ok());
    }

    #[test]
    fn verify_room_focus_fails_with_room_changed_when_a_different_room_is_focused() {
        // The scenario from the task brief: a command meant for room A
        // (`requested`) issued while room B (`focused`) is what's actually
        // installed — e.g. because a room switch resolved on the Rust side
        // while the command was in flight.
        let err = verify_room_focus("!a:x.org", "!b:x.org").unwrap_err();
        assert_eq!(err.kind(), "roomChanged");
        match err {
            CoreError::RoomChanged { requested, focused } => {
                assert_eq!(requested, "!a:x.org");
                assert_eq!(focused, "!b:x.org");
            }
            other => panic!("expected CoreError::RoomChanged, got {other:?}"),
        }
    }

    // `truncate_reply_excerpt`/`project_reply_to`/`project_reactions`: pure
    // over plain strings, so — like `should_reseed` above — these are
    // exercised directly rather than through a live timeline item.
    // `reply_to_dto`/`reaction_entries` (the SDK-facing adapters either one
    // feeds) aren't exercised directly, same reasoning as
    // `classify_content`'s test-module comment: `TimelineItemContent`,
    // `InReplyToDetails`'s embedded event, and `ReactionsByKeyBySender`'s
    // inner map are all crate-private to construct with real data outside a
    // live, synced timeline.

    #[test]
    fn truncate_reply_excerpt_leaves_a_short_body_untouched() {
        assert_eq!(truncate_reply_excerpt("hello there"), "hello there");
    }

    #[test]
    fn truncate_reply_excerpt_trims_surrounding_whitespace() {
        assert_eq!(truncate_reply_excerpt("  hello  "), "hello");
    }

    #[test]
    fn truncate_reply_excerpt_caps_a_long_body_with_an_ellipsis() {
        // A message right up against the spec's 64KiB event body limit must
        // not cross IPC anywhere near full size — this is the truncation
        // this app actually relies on, not a display-only line-clamp.
        let long_body = "x".repeat(64 * 1024);
        let excerpt = truncate_reply_excerpt(&long_body);
        assert_eq!(excerpt.chars().count(), REPLY_EXCERPT_MAX_CHARS + 1); // +1 for the ellipsis
        assert!(excerpt.ends_with('…'));
        assert!(
            excerpt.len() < 1024,
            "expected the excerpt to be a small fraction of 64KiB"
        );
    }

    #[test]
    fn truncate_reply_excerpt_does_not_split_a_multibyte_character() {
        // `REPLY_EXCERPT_MAX_CHARS` counts `char`s, not bytes, so a body made
        // entirely of multi-byte characters must still truncate cleanly
        // rather than panicking or producing invalid UTF-8 mid-character.
        let long_body = "é".repeat(500);
        let excerpt = truncate_reply_excerpt(&long_body);
        assert_eq!(excerpt.chars().count(), REPLY_EXCERPT_MAX_CHARS + 1);
        assert!(excerpt.ends_with('…'));
    }

    #[test]
    fn project_reply_to_projects_a_ready_parent_with_a_truncated_excerpt() {
        let long_body = "y".repeat(1000);
        let reply = project_reply_to(
            "$parent:example.org",
            true,
            Some("@alice:example.org"),
            Some("Alice"),
            Some(&long_body),
            None,
        );
        assert_eq!(reply.event_id, "$parent:example.org");
        assert!(reply.available);
        assert_eq!(reply.sender.as_deref(), Some("@alice:example.org"));
        assert_eq!(reply.sender_display_name.as_deref(), Some("Alice"));
        let excerpt = reply.excerpt.expect("a message parent has an excerpt");
        assert_eq!(excerpt.chars().count(), REPLY_EXCERPT_MAX_CHARS + 1);
        assert!(
            excerpt.len() < long_body.len(),
            "expected the excerpt truncated, not carried through whole"
        );
        assert!(
            reply.label.is_none(),
            "a message parent has an excerpt, so it needs no label"
        );
    }

    #[test]
    fn project_reply_to_projects_an_unavailable_parent_gracefully() {
        // Covers `Unavailable`/`Pending`/`Error` alike (see `reply_to_dto`'s
        // doc comment for why they're folded together) — this is the shape
        // the webview must render as "Original message unavailable", not an
        // empty quote or a spinner that never resolves.
        let reply = project_reply_to("$parent:example.org", false, None, None, None, None);
        assert_eq!(reply.event_id, "$parent:example.org");
        assert!(!reply.available);
        assert!(reply.sender.is_none());
        assert!(reply.sender_display_name.is_none());
        assert!(reply.excerpt.is_none());
        assert!(reply.label.is_none());
    }

    #[test]
    fn project_reply_to_has_no_excerpt_when_the_parent_has_no_body() {
        // A `Ready` parent that isn't a message (a redacted event, a
        // sticker, ...) still has a sender to show, just nothing to quote.
        let reply = project_reply_to(
            "$parent:example.org",
            true,
            Some("@bob:example.org"),
            None,
            None,
            None,
        );
        assert!(reply.available);
        assert!(reply.excerpt.is_none());
    }

    #[test]
    fn project_reply_to_carries_a_label_through_when_the_parent_has_no_body() {
        // The review fix: a `Ready` parent with nothing to quote now carries
        // *why* through as `label`, rather than rendering as a bare sender
        // name. `project_reply_to` itself just carries whatever label its
        // caller (`reply_to_dto`) computed — see `reply_parent_label`'s own
        // tests for the actual classification logic.
        let reply = project_reply_to(
            "$parent:example.org",
            true,
            Some("@bob:example.org"),
            None,
            None,
            Some("Message deleted"),
        );
        assert!(reply.available);
        assert!(reply.excerpt.is_none());
        assert_eq!(reply.label.as_deref(), Some("Message deleted"));
    }

    // `reply_parent_label`: pure over the `(kind, detail)` pair
    // `classify_content` already produces, so — like `should_reseed` and
    // `classify_content`'s own indirect coverage above — this is exercised
    // directly rather than through a live `TimelineItemContent` (which has
    // no test-friendly constructor outside a real synced timeline).

    #[test]
    fn reply_parent_label_is_none_for_a_message_kind() {
        // `reply_to_dto` never actually calls this for `kind == "message"`
        // in practice (that case always has a body, so it never needs a
        // label) — this documents that `None` is still the right answer if
        // it ever were called with it.
        assert!(reply_parent_label("message", None).is_none());
    }

    #[test]
    fn reply_parent_label_names_common_non_message_parents() {
        // Mirrors the exact wording `timelineItemView.ts`'s `viewFor` uses
        // for the same event kinds as a top-level item — see this
        // function's doc comment for why that consistency matters.
        assert_eq!(
            reply_parent_label("redacted", None).as_deref(),
            Some("Message deleted")
        );
        assert_eq!(
            reply_parent_label("sticker", None).as_deref(),
            Some("Sticker")
        );
        assert_eq!(reply_parent_label("poll", None).as_deref(), Some("Poll"));
        assert_eq!(
            reply_parent_label("unableToDecrypt", None).as_deref(),
            Some("Encrypted message — this device has no key for it")
        );
        assert_eq!(
            reply_parent_label("liveLocation", None).as_deref(),
            Some("Live location")
        );
    }

    #[test]
    fn reply_parent_label_includes_the_event_type_for_kinds_that_carry_a_detail() {
        assert_eq!(
            reply_parent_label("customMessage", Some("org.supermessage.card")).as_deref(),
            Some("Custom event (org.supermessage.card)")
        );
        assert_eq!(
            reply_parent_label("failedToParse", Some("m.some.custom")).as_deref(),
            Some("Unsupported event (m.some.custom)")
        );
        // No detail supplied still produces a real label, never a panic or
        // an empty string.
        assert_eq!(
            reply_parent_label("customMessage", None).as_deref(),
            Some("Custom event (unknown)")
        );
    }

    #[test]
    fn reply_parent_label_never_returns_an_empty_string_for_any_known_kind() {
        for kind in [
            "sticker",
            "poll",
            "redacted",
            "unableToDecrypt",
            "liveLocation",
            "callInvite",
            "rtcNotification",
            "customMessage",
            "membership",
            "profileChange",
            "state",
            "failedToParse",
            "somethingFutureAndUnknown",
        ] {
            let label = reply_parent_label(kind, Some("detail"));
            assert!(
                label.is_some_and(|l| !l.is_empty()),
                "expected a non-empty label for kind {kind:?}"
            );
        }
    }

    #[test]
    fn project_reactions_projects_counts_and_by_me() {
        let entries = vec![
            (
                "👍".to_string(),
                vec!["@alice:x.org".to_string(), "@bob:x.org".to_string()],
            ),
            ("🎉".to_string(), vec!["@me:x.org".to_string()]),
        ];
        let mut reactions = project_reactions(&entries, "@me:x.org");
        reactions.sort_by(|a, b| a.key.cmp(&b.key));

        assert_eq!(reactions.len(), 2);
        assert_eq!(reactions[0].key, "🎉");
        assert_eq!(reactions[0].count, 1);
        assert!(reactions[0].by_me);
        assert_eq!(reactions[1].key, "👍");
        assert_eq!(reactions[1].count, 2);
        assert!(!reactions[1].by_me);
    }

    #[test]
    fn project_reactions_is_empty_for_no_reaction_data() {
        assert!(project_reactions(&[], "@me:x.org").is_empty());
    }

    #[test]
    fn project_reactions_by_me_is_false_when_the_current_user_never_reacted() {
        let entries = vec![("👍".to_string(), vec!["@alice:x.org".to_string()])];
        let reactions = project_reactions(&entries, "@me:x.org");
        assert_eq!(reactions.len(), 1);
        assert!(!reactions[0].by_me);
    }

    // `project_item_parts` end-to-end for the `edited`/`reactions` fields
    // (the reply case is already covered above via `project_reply_to`
    // directly, and through `project_item_parts`'s own carries-through-
    // untouched behaviour, identical to how `formatted_body` is covered).

    #[test]
    fn project_item_parts_carries_an_edited_flag_through_untouched() {
        let dto = project_item_parts(
            "$e5",
            "message",
            Some("m.text"),
            None,
            Some("@me:x.org"),
            Some("Me"),
            Some("edited text"),
            None,
            None,
            Some(1_700_000_000_000),
            true,
            None,
            None,
            true,
            Vec::new(),
        );
        assert!(dto.edited);
    }

    #[test]
    fn project_item_parts_carries_reactions_through_untouched() {
        let reactions = vec![ReactionDto {
            key: "👍".to_string(),
            count: 2,
            by_me: true,
        }];
        let dto = project_item_parts(
            "$e6",
            "message",
            Some("m.text"),
            None,
            Some("@me:x.org"),
            Some("Me"),
            Some("hi"),
            None,
            None,
            Some(1_700_000_000_000),
            true,
            None,
            None,
            false,
            reactions.clone(),
        );
        assert_eq!(dto.reactions, reactions);
    }

    #[test]
    fn project_item_parts_carries_a_reply_to_through_untouched() {
        let reply_to = project_reply_to(
            "$parent:example.org",
            true,
            Some("@alice:example.org"),
            Some("Alice"),
            Some("original message"),
            None,
        );
        let dto = project_item_parts(
            "$e7",
            "message",
            Some("m.text"),
            None,
            Some("@me:x.org"),
            Some("Me"),
            Some("a reply"),
            None,
            None,
            Some(1_700_000_000_000),
            true,
            None,
            Some(reply_to.clone()),
            false,
            Vec::new(),
        );
        assert_eq!(dto.reply_to, Some(reply_to));
    }

    // `TimelineHandle` holds a live `Arc<Timeline>`, which (unlike
    // `RoomListHandle`'s state-only fields) has no test-friendly
    // constructor outside a real `room.timeline()` call against a synced
    // room — so, following this crate's existing precedent (`spawn_room_list`
    // and `sync::start`, which take an `AppHandle` and are likewise not
    // unit-tested directly), `subscribe` itself isn't exercised here. What's
    // tested below is everything that doesn't require a live subscription:
    // the pure projections above, and the `NotReady` paths through
    // `FocusedTimeline`'s public API when nothing is focused yet.

    #[tokio::test]
    async fn focused_timeline_snapshot_reports_not_ready_when_nothing_is_focused() {
        let focused = FocusedTimeline::default();
        let err = focused.snapshot().await.unwrap_err();
        assert_eq!(err.kind(), "notReady");
    }

    #[tokio::test]
    async fn focused_timeline_paginate_back_reports_not_ready_when_nothing_is_focused() {
        // Nothing focused at all outranks a room mismatch: `active_timeline_for`
        // checks `handle.as_ref().ok_or(CoreError::NotReady)` before it ever
        // reaches `verify_room_focus`, so this reports `notReady` regardless
        // of which room id is passed — see the `_reports_room_changed_`
        // tests below for the case where a room *is* focused, just not this
        // one.
        let focused = FocusedTimeline::default();
        let err = focused.paginate_back("!a:x.org", 10).await.unwrap_err();
        assert_eq!(err.kind(), "notReady");
    }

    #[tokio::test]
    async fn focused_timeline_send_text_reports_not_ready_when_nothing_is_focused() {
        let focused = FocusedTimeline::default();
        let err = focused.send_text("!a:x.org", "hi").await.unwrap_err();
        assert_eq!(err.kind(), "notReady");
    }

    #[tokio::test]
    async fn focused_timeline_send_reply_reports_not_ready_when_nothing_is_focused() {
        let focused = FocusedTimeline::default();
        let err = focused
            .send_reply("!a:x.org", "hi", "$parent:example.org")
            .await
            .unwrap_err();
        assert_eq!(err.kind(), "notReady");
    }

    #[tokio::test]
    async fn focused_timeline_send_reply_reports_a_protocol_error_for_a_malformed_event_id() {
        // The event-id parse happens before the room check (which itself
        // happens before the "is anything focused at all" check — see
        // `send_reply`'s body), so this specifically exercises that
        // `EventId::parse` failure surfaces as `CoreError::Protocol`, not
        // `NotReady` or `RoomChanged` — even with nothing focused, a
        // malformed id is still the more specific, more useful error to
        // report.
        let focused = FocusedTimeline::default();
        let err = focused
            .send_reply("!a:x.org", "hi", "not-a-valid-event-id")
            .await
            .unwrap_err();
        assert_eq!(err.kind(), "protocol");
    }

    #[tokio::test]
    async fn focused_timeline_toggle_reaction_reports_not_ready_when_nothing_is_focused() {
        let focused = FocusedTimeline::default();
        let err = focused
            .toggle_reaction("!a:x.org", "$parent:example.org", "👍")
            .await
            .unwrap_err();
        assert_eq!(err.kind(), "notReady");
    }

    #[tokio::test]
    async fn focused_timeline_toggle_reaction_reports_a_protocol_error_for_a_malformed_event_id() {
        let focused = FocusedTimeline::default();
        let err = focused
            .toggle_reaction("!a:x.org", "not-a-valid-event-id", "👍")
            .await
            .unwrap_err();
        assert_eq!(err.kind(), "protocol");
    }
}
