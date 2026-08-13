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

use std::sync::{Arc, Mutex};

use eyeball_im::VectorDiff;
use futures_util::{pin_mut, StreamExt};
use imbl::Vector;
use matrix_sdk::ruma::events::room::message::{
    FormattedBody, MessageFormat, MessageType, RoomMessageEventContent,
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
    EventSendState, EventTimelineItem, MembershipChange, MsgLikeKind, RoomExt, Timeline,
    TimelineDetails, TimelineItem, TimelineItemContent, TimelineItemKind, VirtualTimelineItem,
};
use tauri::{AppHandle, Emitter};
use tokio::task::JoinHandle;

use super::dto::{
    apply_ops, op_name, project_diff, DiffEnvelope, DiffOp, MediaMetaDto, SeqCounter,
    TimelineItemDto,
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

/// The sequence number of the last diff folded into the materialized item
/// list, and the resulting list itself — always mutually consistent (see
/// `core::rooms::RoomListHandle`'s identical `RoomListSnapshot` for why).
type TimelineState = (u64, Vec<TimelineItemDto>);

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
    use matrix_sdk_ui::timeline::TimelineEventItemId;
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
            pin_mut!(stream);
            let mut seq = SeqCounter::default();

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

            while let Some(batch) = stream.next().await {
                let ops = project_batch(batch, &own_user);
                emit_ops(&app, &task_state, &mut seq, &subject, ops);
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

    /// Paginates the focused timeline backwards by up to `count` events.
    /// Returns `true` when the start of the timeline was reached.
    ///
    /// Fails with [`CoreError::NotReady`] when no room is focused.
    pub async fn paginate_back(&self, count: u16) -> CoreResult<bool> {
        let timeline = self.active_timeline()?;
        timeline
            .paginate_backwards(count)
            .await
            .map_err(|e| CoreError::Protocol(e.to_string()))
    }

    /// Sends a plain-text message to the focused room.
    ///
    /// Does not emit anything itself: `Timeline::send` adds the local echo
    /// to the timeline, which arrives at the webview through the same diff
    /// stream `subscribe` set up — emitting it again here would show the
    /// message twice.
    ///
    /// Fails with [`CoreError::NotReady`] when no room is focused.
    pub async fn send_text(&self, body: &str) -> CoreResult<()> {
        let timeline = self.active_timeline()?;
        let content =
            AnyMessageLikeEventContent::RoomMessage(RoomMessageEventContent::text_plain(body));
        timeline
            .send(content)
            .await
            .map_err(|e| CoreError::Protocol(e.to_string()))?;
        Ok(())
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
    fn active_timeline(&self) -> CoreResult<Arc<Timeline>> {
        let handle = self
            .0
            .lock()
            .map_err(|_| CoreError::Protocol("focused timeline lock poisoned".into()))?;
        Ok(Arc::clone(
            &handle.as_ref().ok_or(CoreError::NotReady)?.timeline,
        ))
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

/// Folds `ops` into the materialized snapshot under one critical section,
/// then emits the resulting envelope — mirroring
/// `core::rooms::spawn_room_list`'s identical fold-then-emit sequencing (see
/// that function's doc comment for why the fold must happen before the lock
/// is released, and the lock must be released before emitting).
fn emit_ops(
    app: &AppHandle,
    state: &Arc<Mutex<TimelineState>>,
    seq: &mut SeqCounter,
    subject: &str,
    ops: Vec<DiffOp<TimelineItemDto>>,
) {
    let seq_no = seq.next();
    let folded_len = {
        let mut guard = state
            .lock()
            .expect("timeline state lock poisoned by an earlier panic");
        apply_ops(&mut guard.1, &ops);
        guard.0 = seq_no;
        guard.1.len()
    };

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
        );
        assert_eq!(dto.kind, "message");
        assert_eq!(dto.msgtype.as_deref(), Some("m.text"));
        assert_eq!(dto.body.as_deref(), Some("hello"));
        assert!(dto.formatted_body.is_none());
        assert!(dto.media.is_none());
        assert!(dto.is_own);
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
        let focused = FocusedTimeline::default();
        let err = focused.paginate_back(10).await.unwrap_err();
        assert_eq!(err.kind(), "notReady");
    }

    #[tokio::test]
    async fn focused_timeline_send_text_reports_not_ready_when_nothing_is_focused() {
        let focused = FocusedTimeline::default();
        let err = focused.send_text("hi").await.unwrap_err();
        assert_eq!(err.kind(), "notReady");
    }
}
