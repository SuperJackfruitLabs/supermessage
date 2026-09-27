//! Blocking and reporting — the two safety actions App Store Review Guideline
//! 1.2 (and Google Play's User Generated Content policy) require of any app
//! whose content is written by other people (issue #60).
//!
//! Neither is invented here. Both are Matrix protocol:
//!
//! - **Block** is `m.ignored_user_list` account data, through the SDK's
//!   `Account::ignore_user`/`unignore_user`. The homeserver stops delivering
//!   the ignored user's events, to this account, on every device. It works on
//!   an agent exactly as on a person: the agent keeps running, its messages
//!   simply stop reaching this account.
//! - **Report** is one of three endpoints, all of which reach the homeserver's
//!   administrator rather than anyone in the room:
//!   `POST /rooms/{roomId}/report/{eventId}` for a message,
//!   `POST /rooms/{roomId}/report` for a room (spec 1.13), and
//!   `POST /users/{userId}/report` for a person (spec 1.14). The SDK wraps the
//!   first two; the third is sent as the raw ruma request.
//!
//! What this module owns is the part a host must not decide for itself: how
//! long a reason may be ([`REPORT_REASON_MAX_CHARS`], counted the one way every
//! host agrees on), what a refusal from the homeserver means
//! ([`report_failure`]), and the ignore list as a host should see it — a
//! stream of changes ([`spawn_ignore_watch`]) plus a snapshot
//! ([`blocked_users`]) with names already resolved.
//!
//! ## Ignoring empties every timeline, and that is expected
//!
//! When the ignore list changes, the SDK's event cache calls
//! `clear_all_rooms()` (`matrix-sdk-0.18.0/src/event_cache/tasks.rs`,
//! `ignore_user_list_update_task`), because events already cached may have
//! been sent by the person now ignored. For the focused room that arrives as
//! a lone `Clear` on the timeline stream — exactly the shape
//! `core::timeline`'s re-seed recovery exists for. The test
//! `the_focused_timeline_refills_after_an_ignore` in `core::timeline` pins
//! that the room refills instead of going blank.

use std::sync::Arc;

use matrix_sdk::ruma::api::client::reporting::report_user;
use matrix_sdk::ruma::api::error::ErrorKind;
use matrix_sdk::ruma::events::ignored_user_list::IgnoredUserListEventContent;
use matrix_sdk::ruma::{OwnedEventId, OwnedUserId, RoomId, UserId};
use matrix_sdk::Client;

use crate::error::{CoreError, CoreResult};
use crate::event::{CoreEvent, EventSink};
use crate::room_info::RoomMemberDto;

/// The channel [`CoreEvent::IgnoredUsers`] travels on to the desktop webview.
pub const IGNORED_USERS_EVENT: &str = "sm://ignored-users";

/// The longest reason a report may carry, in Unicode scalar values (Rust
/// `char`s).
///
/// 2000 because that is what the homeserver this app ships against (tuwunel)
/// accepts; a longer reason is refused by the server with a generic error, so
/// it is refused here first with one that says what to do about it.
///
/// Counted in `char`s, and only here: Swift counts grapheme clusters and
/// Kotlin UTF-16 units, so a host that counted for itself would disagree with
/// this check at exactly the boundary it exists for. Hosts ask
/// [`report_reason_remaining`] instead.
pub const REPORT_REASON_MAX_CHARS: usize = 2000;

/// What is being reported, for wording a failure.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ReportSubject {
    Message,
    Room,
    User,
}

impl ReportSubject {
    fn noun(self) -> &'static str {
        match self {
            Self::Message => "message",
            Self::Room => "room",
            Self::User => "person",
        }
    }
}

/// The reason as it will be sent: trimmed, and refused when too long.
///
/// Trimmed first, so trailing whitespace from a text field never counts
/// against the limit or reaches an administrator.
pub fn normalise_reason(reason: &str) -> CoreResult<String> {
    let trimmed = reason.trim();
    let length = trimmed.chars().count();
    if length > REPORT_REASON_MAX_CHARS {
        return Err(CoreError::ReasonTooLong {
            length: length as u64,
            limit: REPORT_REASON_MAX_CHARS as u64,
        });
    }
    Ok(trimmed.to_string())
}

/// How many more characters `reason` may grow by before a report refuses it.
/// Negative when it is already over.
///
/// The number a host's counter shows, so the counter and
/// [`normalise_reason`] can never disagree about where the limit is.
pub fn report_reason_remaining(reason: &str) -> i64 {
    REPORT_REASON_MAX_CHARS as i64 - reason.trim().chars().count() as i64
}

/// What a refused report means to the person who sent it.
///
/// Pure over the parts of the SDK error that matter, so every branch is
/// testable without a homeserver. `network` is whether the request never got
/// an answer at all.
pub fn report_failure(
    subject: ReportSubject,
    kind: Option<&ErrorKind>,
    network: bool,
    detail: String,
) -> CoreError {
    let noun = subject.noun();
    match kind {
        Some(ErrorKind::LimitExceeded(_)) => CoreError::Refused(format!(
            "Too many reports in a row. Wait a minute and send this {noun} report again."
        )),
        Some(ErrorKind::NotFound) => CoreError::Refused(format!(
            "The homeserver couldn't find that {noun}. It may already have been removed."
        )),
        // An older homeserver, or one that has switched reporting off. Worth
        // saying plainly: the report did not reach anyone.
        Some(ErrorKind::Unrecognized) => CoreError::Refused(format!(
            "This homeserver doesn't accept {noun} reports. Nothing was sent."
        )),
        Some(ErrorKind::TooLarge | ErrorKind::InvalidParam | ErrorKind::BadJson) => {
            CoreError::Refused(format!(
                "The homeserver refused this report's reason. Shorten it and try again. ({detail})"
            ))
        }
        _ if network => CoreError::Network(detail),
        _ => CoreError::Protocol(detail),
    }
}

fn map_sdk(subject: ReportSubject, error: matrix_sdk::Error) -> CoreError {
    let network = matches!(&error, matrix_sdk::Error::Http(http)
        if matches!(**http, matrix_sdk::HttpError::Reqwest(_)));
    report_failure(
        subject,
        error.client_api_error_kind(),
        network,
        error.to_string(),
    )
}

fn map_http(subject: ReportSubject, error: matrix_sdk::HttpError) -> CoreError {
    let network = matches!(error, matrix_sdk::HttpError::Reqwest(_));
    report_failure(
        subject,
        error.client_api_error_kind(),
        network,
        error.to_string(),
    )
}

/// An account-data write that failed: a network failure when no answer came
/// back, the homeserver's own words otherwise.
fn map_account(error: matrix_sdk::Error) -> CoreError {
    match &error {
        matrix_sdk::Error::Http(http) if matches!(**http, matrix_sdk::HttpError::Reqwest(_)) => {
            CoreError::Network(error.to_string())
        }
        _ => CoreError::Protocol(error.to_string()),
    }
}

fn parse_user(user_id: &str) -> CoreResult<OwnedUserId> {
    UserId::parse(user_id.trim()).map_err(|e| CoreError::Protocol(e.to_string()))
}

/// Blocks `user_id`: adds them to this account's `m.ignored_user_list`.
///
/// Refuses to block this account itself before asking the SDK, whose own
/// refusal (`CantIgnoreLoggedInUser`) would otherwise surface as a generic
/// protocol string.
pub async fn ignore_user(client: &Client, user_id: &str) -> CoreResult<()> {
    let user_id = parse_user(user_id)?;
    if client.user_id() == Some(user_id.as_ref()) {
        return Err(CoreError::Refused("You can't block yourself.".into()));
    }
    client
        .account()
        .ignore_user(&user_id)
        .await
        .map_err(map_account)
}

/// Unblocks `user_id`. A no-op when they were not blocked.
pub async fn unignore_user(client: &Client, user_id: &str) -> CoreResult<()> {
    let user_id = parse_user(user_id)?;
    client
        .account()
        .unignore_user(&user_id)
        .await
        .map_err(map_account)
}

/// The ignored user ids, read from the local store, sorted.
///
/// The store rather than the SDK's `subscribe_to_ignore_user_list_changes`
/// observable: that one starts empty on every launch and only moves when a
/// sync brings a list *different* from the stored one, so after a restore it
/// can say "nobody" for the whole session while the account has blocked
/// people.
pub async fn ignored_user_ids(client: &Client) -> CoreResult<Vec<String>> {
    let raw = client
        .account()
        .account_data::<IgnoredUserListEventContent>()
        .await
        .map_err(|e| CoreError::Store(e.to_string()))?;
    let Some(raw) = raw else {
        return Ok(Vec::new());
    };
    let content = raw
        .deserialize()
        .map_err(|e| CoreError::Protocol(e.to_string()))?;
    let mut ids: Vec<String> = content
        .ignored_users
        .keys()
        .map(|id| id.to_string())
        .collect();
    ids.sort();
    Ok(ids)
}

/// Everyone this account has blocked, named for a list.
///
/// Named the way the room-info panel names a member: the display name from
/// any joined room that still has their member event (read from the local
/// cache only), and otherwise the name an agent's id carries. The result is
/// a [`RoomMemberDto`] with `is_ignored` set, so a host draws a blocked row
/// with the same component it draws a member with.
pub async fn blocked_users(client: &Client) -> CoreResult<Vec<RoomMemberDto>> {
    let ids = ignored_user_ids(client).await?;
    let mut blocked = Vec::with_capacity(ids.len());
    for id in ids {
        let Ok(user_id) = UserId::parse(&id) else {
            continue;
        };
        let mut display_name = None;
        let mut avatar_url = None;
        for room in client.joined_rooms() {
            if let Ok(Some(member)) = room.get_member_no_sync(&user_id).await {
                display_name = member.display_name().map(str::to_string);
                avatar_url = member.avatar_url().map(|url| url.to_string());
                break;
            }
        }
        blocked.push(crate::room_info::project_member_parts(
            &id,
            display_name,
            avatar_url,
            true,
        ));
    }
    Ok(blocked)
}

/// Reports one message to the homeserver's administrator.
///
/// `reason` may be empty — the endpoint makes it optional — and is sent as
/// absent rather than as an empty string when it is.
pub async fn report_event(
    client: &Client,
    room_id: &str,
    event_id: &str,
    reason: &str,
) -> CoreResult<()> {
    let reason = normalise_reason(reason)?;
    let room_id = RoomId::parse(room_id).map_err(|e| CoreError::Protocol(e.to_string()))?;
    let event_id =
        OwnedEventId::try_from(event_id).map_err(|e| CoreError::Protocol(e.to_string()))?;
    let room = client
        .get_room(&room_id)
        .ok_or_else(|| CoreError::Protocol("unknown room".into()))?;
    room.report_content(event_id, (!reason.is_empty()).then_some(reason))
        .await
        .map(|_| ())
        .map_err(|e| map_sdk(ReportSubject::Message, e))
}

/// Reports a whole room. Works on an invitation too: the endpoint does not
/// require membership, which is what lets a reader report a room they were
/// pulled into without joining it first.
pub async fn report_room(client: &Client, room_id: &str, reason: &str) -> CoreResult<()> {
    let reason = normalise_reason(reason)?;
    let room_id = RoomId::parse(room_id).map_err(|e| CoreError::Protocol(e.to_string()))?;
    let room = client
        .get_room(&room_id)
        .ok_or_else(|| CoreError::Protocol("unknown room".into()))?;
    room.report_room(reason)
        .await
        .map(|_| ())
        .map_err(|e| map_sdk(ReportSubject::Room, e))
}

/// Reports a person (or an agent) rather than any one thing they said.
///
/// The SDK has no wrapper for this endpoint in 0.18, so the ruma request is
/// sent directly; the SDK picks the stable or MSC4260 path from the versions
/// the homeserver advertises.
pub async fn report_user(client: &Client, user_id: &str, reason: &str) -> CoreResult<()> {
    let reason = normalise_reason(reason)?;
    let user_id = parse_user(user_id)?;
    if client.user_id() == Some(user_id.as_ref()) {
        return Err(CoreError::Refused("You can't report yourself.".into()));
    }
    client
        .send(report_user::v3::Request::new(user_id, reason))
        .await
        .map(|_| ())
        .map_err(|e| map_http(ReportSubject::User, e))
}

/// Streams the ignore list to `sink` as [`CoreEvent::IgnoredUsers`]: once now,
/// then again whenever it changes.
///
/// The SDK's observable is used only as a trigger; each emission re-reads the
/// stored list (see [`ignored_user_ids`] for why the observable's own value
/// cannot be trusted after a restore). The observable is updated after the
/// sync's changes are saved (`response_processors::changes::save_and_apply`),
/// so the re-read sees the new list.
///
/// The task holds a `Client`, so the session must stop it before wiping the
/// store — see `Session::logout`.
pub fn spawn_ignore_watch(client: Client, sink: Arc<dyn EventSink>) -> tokio::task::JoinHandle<()> {
    let mut changes = client.subscribe_to_ignore_user_list_changes();
    tokio::spawn(async move {
        let mut last: Option<Vec<String>> = None;
        loop {
            match ignored_user_ids(&client).await {
                Ok(ids) if last.as_ref() != Some(&ids) => {
                    last = Some(ids.clone());
                    sink.emit(CoreEvent::IgnoredUsers(ids));
                }
                Ok(_) => {}
                Err(err) => tracing::warn!(error = %err, "could not read the ignore list"),
            }
            if changes.next().await.is_none() {
                break;
            }
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use matrix_sdk::ruma::api::error::LimitExceededErrorData;

    #[test]
    fn a_reason_at_the_limit_is_accepted_and_one_past_it_is_not() {
        let at = "a".repeat(REPORT_REASON_MAX_CHARS);
        assert_eq!(
            normalise_reason(&at).unwrap().len(),
            REPORT_REASON_MAX_CHARS
        );

        let over = "a".repeat(REPORT_REASON_MAX_CHARS + 1);
        assert!(matches!(
            normalise_reason(&over),
            Err(CoreError::ReasonTooLong { length, limit })
                if length == REPORT_REASON_MAX_CHARS as u64 + 1
                    && limit == REPORT_REASON_MAX_CHARS as u64
        ));
    }

    #[test]
    fn the_limit_counts_characters_not_bytes() {
        // Each `अ` is three bytes. Counting bytes would refuse this at a third
        // of the limit; a reader writing in Hindi would be told they had
        // written too much when they had written 700 characters.
        let hindi = "अ".repeat(REPORT_REASON_MAX_CHARS);
        assert!(hindi.len() > REPORT_REASON_MAX_CHARS);
        assert!(normalise_reason(&hindi).is_ok());
        assert_eq!(report_reason_remaining(&hindi), 0);
    }

    #[test]
    fn whitespace_around_a_reason_is_neither_sent_nor_counted() {
        let padded = format!("  {}  \n", "a".repeat(REPORT_REASON_MAX_CHARS));
        assert_eq!(
            normalise_reason(&padded).unwrap(),
            "a".repeat(REPORT_REASON_MAX_CHARS)
        );
        assert_eq!(report_reason_remaining(&padded), 0);
    }

    #[test]
    fn the_counter_goes_negative_once_the_reason_is_too_long() {
        assert_eq!(report_reason_remaining(""), REPORT_REASON_MAX_CHARS as i64);
        let over = "a".repeat(REPORT_REASON_MAX_CHARS + 5);
        assert_eq!(report_reason_remaining(&over), -5);
    }

    #[test]
    fn rate_limiting_reads_as_wait_and_retry() {
        let kind = ErrorKind::LimitExceeded(LimitExceededErrorData::new());
        let error = report_failure(ReportSubject::Message, Some(&kind), false, "x".into());
        assert!(matches!(&error, CoreError::Refused(m) if m.contains("Wait a minute")));
    }

    #[test]
    fn a_homeserver_without_reporting_says_nothing_was_sent() {
        let error = report_failure(
            ReportSubject::User,
            Some(&ErrorKind::Unrecognized),
            false,
            "x".into(),
        );
        assert!(
            matches!(&error, CoreError::Refused(m) if m.contains("Nothing was sent") && m.contains("person")),
            "{error:?}"
        );
    }

    #[test]
    fn a_vanished_subject_is_named_in_the_failure() {
        let error = report_failure(
            ReportSubject::Room,
            Some(&ErrorKind::NotFound),
            false,
            "x".into(),
        );
        assert!(matches!(&error, CoreError::Refused(m) if m.contains("room")));
    }

    #[test]
    fn no_answer_at_all_is_a_network_failure_and_keeps_its_detail() {
        let error = report_failure(ReportSubject::Message, None, true, "timed out".into());
        assert!(matches!(&error, CoreError::Network(m) if m == "timed out"));
        let error = report_failure(ReportSubject::Message, None, false, "odd".into());
        assert!(matches!(&error, CoreError::Protocol(m) if m == "odd"));
    }
}
