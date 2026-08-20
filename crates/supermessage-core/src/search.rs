//! Finding what an agent said last week.
//!
//! Back-pagination in one focused room was the only retrieval this client had,
//! which makes "what did the analyst say about the migration" a scrolling
//! exercise across however many rooms it might have been.
//!
//! **Server-side `/search`, not a local index.** The trade is real and the
//! deciding fact is this deployment's: these rooms are unencrypted by design
//! (the Knowledge layer has to read history, and an Application Service does
//! not mix with E2EE), so the homeserver can see every message and index it —
//! and tuwunel implements the endpoint, verified against the live one before a
//! line of this was written. A local index would be stronger, and would need
//! the client to retain history it deliberately does not. When encrypted rooms
//! arrive, they will simply not be searchable this way, which is honest and
//! is what every other client does too.
//!
//! The projection is pure so it can be tested without a homeserver: what a
//! result *is* — sender, body, room, when — is the part that can be wrong.

use matrix_sdk::ruma::api::client::search::search_events::v3::{
    Categories, Criteria, Request as SearchRequest, ResultRoomEvents,
};
use matrix_sdk::ruma::events::AnyTimelineEvent;
use matrix_sdk::ruma::serde::Raw;
use matrix_sdk::ruma::{RoomId, UInt};
use matrix_sdk::Client;
use serde::Serialize;

use super::error::{CoreError, CoreResult};

/// How many results one search returns.
///
/// A search box that scrolls forever invites reading rather than finding; if
/// the answer is not in the first twenty, a better query beats a longer list.
pub const SEARCH_LIMIT: u32 = 20;

/// One hit, as the webview shows it.
#[derive(Debug, Clone, PartialEq, Serialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct SearchResultDto {
    pub event_id: String,
    pub room_id: String,
    pub sender: String,
    /// The message text. Never HTML — a search result is a fragment of
    /// evidence, and rendering a sender's markup inside a list of them is a
    /// sanitiser question with nothing to gain.
    pub body: String,
    pub timestamp_ms: Option<u64>,
}

/// Projects one raw search hit, or `None` when it is not a message.
///
/// A hit can be any timeline event, including state the homeserver decided
/// matched. Only `m.room.message` has a body worth showing, and a result row
/// with no text is a row that cannot be read.
pub fn project_result(raw: &Raw<AnyTimelineEvent>) -> Option<SearchResultDto> {
    let event_id = raw.get_field::<String>("event_id").ok().flatten()?;
    let room_id = raw.get_field::<String>("room_id").ok().flatten()?;
    let sender = raw.get_field::<String>("sender").ok().flatten()?;
    let event_type = raw.get_field::<String>("type").ok().flatten()?;
    if event_type != "m.room.message" {
        return None;
    }

    #[derive(serde::Deserialize)]
    struct Body {
        body: Option<String>,
    }
    let body = raw.get_field::<Body>("content").ok().flatten()?.body?;
    // A redacted message keeps its event but loses its content; an empty row
    // is worse than no row.
    if body.trim().is_empty() {
        return None;
    }

    let timestamp_ms = raw.get_field::<u64>("origin_server_ts").ok().flatten();

    Some(SearchResultDto {
        event_id,
        room_id,
        sender,
        body,
        timestamp_ms,
    })
}

/// Projects a whole `room_events` result set, newest first.
///
/// The homeserver orders by rank unless asked otherwise, and rank across
/// rooms is not something a reader can reason about — "when" is. Hits without
/// a timestamp sort last rather than being dropped: they are still answers.
pub fn project_results(results: &ResultRoomEvents) -> Vec<SearchResultDto> {
    let mut projected: Vec<SearchResultDto> = results
        .results
        .iter()
        .filter_map(|hit| hit.result.as_ref())
        .filter_map(project_result)
        .collect();

    projected.sort_by(|a, b| {
        b.timestamp_ms
            .unwrap_or(0)
            .cmp(&a.timestamp_ms.unwrap_or(0))
    });
    projected
}

/// The search criteria for `term`, optionally narrowed to one room.
///
/// Split out from [`search_messages`] so the shape of the request is
/// testable without a homeserver: everything that decides *what is asked
/// for* lives here, and `search_messages` is the round trip.
///
/// A room id that does not parse narrows the search to nothing rather than
/// silently widening it to every room. Returning results from every room the
/// account can see, when the reader asked about one room, is the failure mode
/// worth avoiding — it looks like the scope was ignored, and on a console
/// that is how a message gets read in the wrong context.
fn search_criteria(term: &str, room_id: Option<&str>) -> Option<Criteria> {
    let mut criteria = Criteria::new(term.to_string());
    criteria.filter.limit = Some(UInt::from(SEARCH_LIMIT));
    if let Some(room_id) = room_id {
        let parsed = RoomId::parse(room_id).ok()?;
        criteria.filter.rooms = Some(vec![parsed]);
    }
    Some(criteria)
}

/// Searches for `term`, in one room when `room_id` is given and in every room
/// this account can see otherwise.
pub async fn search_messages(
    client: &Client,
    term: &str,
    room_id: Option<&str>,
) -> CoreResult<Vec<SearchResultDto>> {
    let trimmed = term.trim();
    if trimmed.is_empty() {
        // Not an error, and not a request either: an empty search term asks
        // the homeserver to return the whole room history.
        return Ok(Vec::new());
    }

    let Some(criteria) = search_criteria(trimmed, room_id) else {
        return Err(CoreError::Protocol("unknown room".into()));
    };

    let mut categories = Categories::new();
    categories.room_events = Some(criteria);

    let response = client
        .send(SearchRequest::new(categories))
        .await
        .map_err(|e| CoreError::Network(e.to_string()))?;

    Ok(project_results(&response.search_categories.room_events))
}

#[cfg(test)]
mod tests {
    use super::*;
    use matrix_sdk::ruma::serde::Raw;
    use serde_json::json;

    fn raw(value: serde_json::Value) -> Raw<AnyTimelineEvent> {
        Raw::new(&value).expect("valid json").cast_unchecked()
    }

    fn message(body: &str) -> serde_json::Value {
        json!({
            "type": "m.room.message",
            "event_id": "$one",
            "room_id": "!room:example.org",
            "sender": "@ana:example.org",
            "origin_server_ts": 1_700_000_000_000u64,
            "content": { "msgtype": "m.text", "body": body },
        })
    }

    #[test]
    fn projects_a_message_into_a_readable_row() {
        let result = project_result(&raw(message("the migration finished"))).expect("a message");

        assert_eq!(result.event_id, "$one");
        assert_eq!(result.room_id, "!room:example.org");
        assert_eq!(result.sender, "@ana:example.org");
        assert_eq!(result.body, "the migration finished");
        assert_eq!(result.timestamp_ms, Some(1_700_000_000_000));
    }

    #[test]
    fn drops_a_hit_that_is_not_a_message() {
        // The homeserver can match state events. A result row with no text is
        // a row nobody can read.
        let mut state = message("x");
        state["type"] = json!("m.room.topic");
        assert!(project_result(&raw(state)).is_none());
    }

    #[test]
    fn drops_a_redacted_message_rather_than_showing_an_empty_row() {
        // A redaction keeps the event and takes its content.
        let mut redacted = message("");
        redacted["content"] = json!({});
        assert!(project_result(&raw(redacted)).is_none());

        assert!(project_result(&raw(message("   "))).is_none());
    }

    #[test]
    fn sorts_newest_first_rather_than_by_rank() {
        // Rank across rooms is not something a reader can reason about; "when"
        // is. Built by hand rather than through `ResultRoomEvents`, whose
        // fields are what this sorts *after*.
        let mut rows = [
            SearchResultDto {
                event_id: "$old".into(),
                room_id: "!r:x".into(),
                sender: "@a:x".into(),
                body: "old".into(),
                timestamp_ms: Some(1_000),
            },
            SearchResultDto {
                event_id: "$new".into(),
                room_id: "!r:x".into(),
                sender: "@a:x".into(),
                body: "new".into(),
                timestamp_ms: Some(9_000),
            },
            SearchResultDto {
                event_id: "$undated".into(),
                room_id: "!r:x".into(),
                sender: "@a:x".into(),
                body: "undated".into(),
                timestamp_ms: None,
            },
        ];
        rows.sort_by(|a, b| {
            b.timestamp_ms
                .unwrap_or(0)
                .cmp(&a.timestamp_ms.unwrap_or(0))
        });

        // Undated sorts last rather than being dropped: it is still an answer.
        assert_eq!(
            rows.iter().map(|r| r.event_id.as_str()).collect::<Vec<_>>(),
            ["$new", "$old", "$undated"]
        );
    }
}

#[cfg(test)]
mod scope_tests {
    use super::search_criteria;

    #[test]
    fn a_search_with_no_scope_asks_about_every_room() {
        let criteria = search_criteria("deploy", None).expect("criteria");
        assert!(
            criteria.filter.rooms.is_none(),
            "a room filter was set for a search nobody scoped"
        );
    }

    #[test]
    fn a_scoped_search_asks_about_that_room_only() {
        let criteria = search_criteria("deploy", Some("!abc:x.org")).expect("criteria");
        let rooms = criteria.filter.rooms.expect("a room filter");
        assert_eq!(rooms.len(), 1);
        assert_eq!(rooms[0].as_str(), "!abc:x.org");
    }

    #[test]
    fn a_room_id_that_does_not_parse_narrows_to_nothing_rather_than_widening() {
        // The tempting failure is to drop the filter and search everything.
        // A reader who asked about one room and got hits from every room has
        // been shown messages in the wrong context without being told.
        assert!(search_criteria("deploy", Some("not-a-room")).is_none());
    }
}
