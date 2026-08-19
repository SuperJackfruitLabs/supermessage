//! Projects a room's descriptive metadata and joined member list into the
//! wire [`RoomInfoDto`] — the surface `docs/matrix-events.md` promised
//! ("Suppress; surface in room info") for `m.room.topic` and
//! `m.room.canonical_alias`, but which had no surface to land on until now.
//! Room name, topic, canonical alias, alt aliases, room id and the joined
//! member list all live here; nothing else in this codebase projects them.
//!
//! Mirrors `core::rooms`/`core::timeline`'s shape: a pure, SDK-free
//! projection ([`project_room_info_parts`]/[`project_member_parts`]) that is
//! what's actually unit-tested, plus a thin SDK-touching adapter
//! ([`build_room_info`]) that extracts real `Room`/`RoomMember` values and
//! delegates to it — see `core::rooms::project_room_parts`'s doc comment for
//! why that split exists throughout this codebase.

use matrix_sdk::room::RoomMember;
use matrix_sdk::{Room, RoomMemberships};
use serde::Serialize;

use super::error::{CoreError, CoreResult};

/// One joined member of a room, as shown in the room-info panel's member
/// list.
#[derive(Debug, Clone, PartialEq, Serialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct RoomMemberDto {
    pub user_id: String,
    /// The member's own `m.room.member` display name, when set. `None`
    /// means the webview falls back to `user_id`, the same convention every
    /// other sender-name field in this codebase already uses (see
    /// `Timeline.svelte`'s `item.senderDisplayName ?? item.sender`).
    pub display_name: Option<String>,
    /// The member's avatar as a raw `mxc://` URI — never fetched bytes. The
    /// webview resolves this through the same authenticated-media path a
    /// room's own avatar already uses (`core::media::avatar_thumbnail`, via
    /// the new `member_avatar` command), not a second fetch path — see that
    /// command's doc comment in `core::commands`.
    pub avatar_url: Option<String>,
}

/// A room's descriptive metadata plus its joined member list, for the
/// room-info panel.
#[derive(Debug, Clone, PartialEq, Serialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct RoomInfoDto {
    pub room_id: String,
    pub name: Option<String>,
    /// The display name parsed into sigil / name / role.
    ///
    /// Resolved against the same fallback the panel used to apply by hand —
    /// the trimmed name, or the room id when there is none — so the header and
    /// the roster row cannot disagree about what a room is called.
    pub identity: crate::room_identity::RoomIdentity,
    /// The topic **as a person wrote it**, or `None`.
    ///
    /// `None` also when the topic was the bridge's runtime line rather than
    /// prose — everything worth saying from that line is in [`Self::runtime`],
    /// in structured form, and showing both would say it twice.
    pub topic: Option<String>,
    /// The harness and machine this room's agent runs on, read from the topic.
    pub runtime: Option<crate::dto::RuntimeDto>,
    pub canonical_alias: Option<String>,
    pub alt_aliases: Vec<String>,
    /// The room's active (joined + invited) member count
    /// (`Room::active_members_count`) — a cheap, always-current figure read
    /// straight from room state, independent of how many entries
    /// [`Self::members`] actually managed to list. May exceed
    /// `members.len()` when the room has pending invites, since `members`
    /// is joined-only; that's expected, not a mismatch to reconcile.
    pub active_member_count: u64,
    /// The room's joined members. See [`resolve_joined_members`]'s doc
    /// comment for why this can require a live fetch rather than always
    /// being served from the local cache.
    pub members: Vec<RoomMemberDto>,
}

/// Builds a [`RoomMemberDto`] from already-extracted parts. Pure, mirroring
/// `core::timeline::project_item_parts`'s split between SDK extraction and
/// logic — trivial here (no branching), but kept as its own function so a
/// future rule (e.g. blanking a member's display name) has one obvious place
/// to land, rather than being scattered across call sites, and so
/// [`project_member`] stays a thin, untested-on-its-own adapter like
/// `core::rooms::project_room`/`core::timeline::project_event_item`.
fn project_member_parts(
    user_id: &str,
    display_name: Option<String>,
    avatar_url: Option<String>,
) -> RoomMemberDto {
    RoomMemberDto {
        user_id: user_id.to_string(),
        // The same rule the timeline's attribution uses. Without it the same
        // agent was named two ways three centimetres apart on one screen.
        display_name: display_name
            .as_deref()
            .map(crate::display_name::sender_label),
        avatar_url,
    }
}

/// Builds a [`RoomInfoDto`] from already-extracted parts. Pure and SDK-free,
/// like [`project_member_parts`] and `core::rooms::project_room_parts` — the
/// part this module's tests exercise directly, without a live homeserver.
#[allow(clippy::too_many_arguments)]
pub fn project_room_info_parts(
    room_id: &str,
    name: Option<String>,
    topic: Option<String>,
    canonical_alias: Option<String>,
    alt_aliases: Vec<String>,
    active_member_count: u64,
    members: Vec<RoomMemberDto>,
) -> RoomInfoDto {
    // The bridge writes the runtime into the topic. Read as a runtime it is
    // worth showing; read as prose it is an internal address sitting on the
    // panel's most prominent line.
    let runtime = topic
        .as_deref()
        .and_then(crate::display_name::parse_runtime)
        .map(|(harness, host)| crate::dto::RuntimeDto { harness, host });

    RoomInfoDto {
        // The same fallback the panel used to apply by hand: the trimmed
        // name, or the room id when there is none.
        identity: crate::room_identity::parse_room_identity(
            name.as_deref()
                .map(str::trim)
                .filter(|n| !n.is_empty())
                .unwrap_or(room_id),
        ),
        room_id: room_id.to_string(),
        name,
        topic: if runtime.is_some() { None } else { topic },
        runtime,
        canonical_alias,
        alt_aliases,
        active_member_count,
        members,
    }
}

/// Whether `cached_len` (the length of whatever `Room::members_no_sync`
/// already returned) is short enough against `active_member_count`
/// (`Room::active_members_count`) that [`resolve_joined_members`] should pay
/// for a real `Room::members` fetch instead of trusting the cache.
///
/// Pure and SDK-free so the trigger condition is unit-testable without a
/// live room — same reasoning as `core::timeline::should_reseed`.
///
/// Inherits `core::rooms::resolve_room_avatar_mxc`'s own imprecision,
/// deliberately: `active_member_count` counts joined *and* invited members,
/// while `cached_len` (and the `members` fetch this gates) is joined-only —
/// see that function's doc comment for why sliding sync's `$LAZY` member
/// state can leave the joined-only cache short in the first place. A room
/// with pending invites can therefore trigger a real fetch here even when
/// its joined-member cache was already complete. That is an accepted,
/// already-shipped trade-off in this codebase, not a new one: the same
/// comparison (a joined-only count against `active_members_count`) is
/// exactly what that function's own two-person fallback already makes, and
/// the cost is the same — at most one extra round trip, which the SDK then
/// caches.
fn should_fetch_full_member_list(cached_len: usize, active_member_count: u64) -> bool {
    (cached_len as u64) < active_member_count
}

/// Resolves `room`'s full joined member list, falling back to a live
/// `Room::members` fetch when the locally-cached `Room::members_no_sync`
/// list looks short by [`should_fetch_full_member_list`].
///
/// Unlike `core::rooms::resolve_room_avatar_mxc`'s identically-shaped
/// fallback, this has no "only for a two-person room" narrowing: that
/// function only ever needs *one* member's avatar, so it deliberately bounds
/// its fetch to the one case where pulling a large room's full list would
/// otherwise be wasted just to find it. The room-info panel this function
/// backs *is* the full member list — there is no narrower thing to ask for
/// instead — so any room whose cache looks short gets a real fetch,
/// regardless of the room's size.
async fn resolve_joined_members(room: &Room) -> CoreResult<Vec<RoomMember>> {
    let cached = room
        .members_no_sync(RoomMemberships::JOIN)
        .await
        .map_err(|e| CoreError::Protocol(e.to_string()))?;
    if should_fetch_full_member_list(cached.len(), room.active_members_count()) {
        return room
            .members(RoomMemberships::JOIN)
            .await
            .map_err(|e| CoreError::Protocol(e.to_string()));
    }
    Ok(cached)
}

/// Projects one live [`RoomMember`] into the wire [`RoomMemberDto`]. A thin
/// adapter, like `core::rooms::project_room`/`core::timeline::project_event_item` —
/// it only extracts values and delegates to [`project_member_parts`].
fn project_member(member: &RoomMember) -> RoomMemberDto {
    project_member_parts(
        member.user_id().as_str(),
        member.display_name().map(str::to_string),
        member.avatar_url().map(|url| url.to_string()),
    )
}

/// Builds `room`'s [`RoomInfoDto`]: its descriptive metadata plus its
/// resolved joined member list (see [`resolve_joined_members`]).
///
/// A thin adapter over [`project_room_info_parts`], the same split every
/// other projection in this codebase makes between SDK extraction and pure
/// logic.
pub async fn build_room_info(room: &Room) -> CoreResult<RoomInfoDto> {
    let members = resolve_joined_members(room).await?;
    let member_dtos = members.iter().map(project_member).collect();

    Ok(project_room_info_parts(
        room.room_id().as_str(),
        room.name(),
        room.topic(),
        room.canonical_alias().map(|alias| alias.to_string()),
        room.alt_aliases()
            .iter()
            .map(|alias| alias.to_string())
            .collect(),
        room.active_members_count(),
        member_dtos,
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn member(
        user_id: &str,
        display_name: Option<&str>,
        avatar_url: Option<&str>,
    ) -> RoomMemberDto {
        project_member_parts(
            user_id,
            display_name.map(str::to_string),
            avatar_url.map(str::to_string),
        )
    }

    #[test]
    fn a_member_is_named_the_way_the_timeline_names_them() {
        // The same agent was called `Ganesha (OpenClaw on Ashram)` in the
        // timeline and `ganesha (openclaw @ ashram)` three centimetres away in
        // this panel, because only one of them went through the rules.
        let member = project_member_parts(
            "@agent_ashram_openclaw-ganesha:id.agentpod.dev",
            Some("ganesha (openclaw @ ashram)".into()),
            None,
        );
        assert_eq!(member.display_name.as_deref(), Some("Ganesha (OpenClaw on Ashram)"));
    }

    #[test]
    fn a_person_keeps_the_display_name_they_set() {
        let member = project_member_parts("@rakesh:id.agentpod.dev", Some("rakesh 💕".into()), None);
        assert_eq!(member.display_name.as_deref(), Some("rakesh 💕"));
    }

    #[test]
    fn a_bridge_topic_becomes_a_runtime_rather_than_a_line_of_prose() {
        // `openclaw on ashram — openclaw:ganesha` was rendered verbatim on the
        // panel's most prominent line. The half after the dash is an internal
        // address; the half before it is worth saying properly.
        let info = project_room_info_parts(
            "!r:example.org",
            Some("ganesha".into()),
            Some("openclaw on ashram \u{2014} openclaw:ganesha".into()),
            None,
            vec![],
            2,
            vec![],
        );
        assert_eq!(
            info.runtime,
            Some(crate::dto::RuntimeDto {
                harness: "OpenClaw".into(),
                host: "Ashram".into()
            })
        );
        // And the raw line is gone rather than shown twice: everything it said
        // that a reader wants is now in `runtime`.
        assert_eq!(info.topic, None);
    }

    #[test]
    fn a_topic_someone_wrote_survives_untouched() {
        let info = project_room_info_parts(
            "!r:example.org",
            Some("Release".into()),
            Some("Where we plan the release".into()),
            None,
            vec![],
            4,
            vec![],
        );
        assert_eq!(info.runtime, None);
        assert_eq!(info.topic.as_deref(), Some("Where we plan the release"));
    }

    #[test]
    fn project_room_info_parts_carries_every_field_through_untouched() {
        let info = project_room_info_parts(
            "!abc:example.org",
            Some("Ops".into()),
            Some("Where things happen".into()),
            Some("#ops:example.org".into()),
            vec!["#ops-old:example.org".into()],
            5,
            vec![member(
                "@alice:example.org",
                Some("Alice"),
                Some("mxc://x.org/a"),
            )],
        );
        assert_eq!(info.room_id, "!abc:example.org");
        assert_eq!(info.name.as_deref(), Some("Ops"));
        assert_eq!(info.topic.as_deref(), Some("Where things happen"));
        assert_eq!(info.canonical_alias.as_deref(), Some("#ops:example.org"));
        assert_eq!(info.alt_aliases, vec!["#ops-old:example.org".to_string()]);
        assert_eq!(info.active_member_count, 5);
        assert_eq!(info.members.len(), 1);
        assert_eq!(info.members[0].user_id, "@alice:example.org");
    }

    #[test]
    fn project_room_info_parts_handles_a_bare_unnamed_topicless_room() {
        let info = project_room_info_parts(
            "!abc:example.org",
            None,
            None,
            None,
            Vec::new(),
            1,
            Vec::new(),
        );
        assert_eq!(info.name, None);
        assert_eq!(info.topic, None);
        assert_eq!(info.canonical_alias, None);
        assert!(info.alt_aliases.is_empty());
        assert!(info.members.is_empty());
    }

    #[test]
    fn project_member_parts_falls_back_to_none_when_the_member_never_set_a_display_name_or_avatar()
    {
        let m = member("@bob:example.org", None, None);
        assert_eq!(m.user_id, "@bob:example.org");
        assert_eq!(m.display_name, None);
        assert_eq!(m.avatar_url, None);
    }

    #[test]
    fn should_fetch_full_member_list_when_the_cached_list_is_short() {
        assert!(should_fetch_full_member_list(1, 5));
    }

    #[test]
    fn should_fetch_full_member_list_is_false_when_the_cache_already_covers_the_active_count() {
        assert!(!should_fetch_full_member_list(5, 5));
        assert!(!should_fetch_full_member_list(6, 5));
    }

    #[test]
    fn should_fetch_full_member_list_is_false_for_an_empty_but_genuinely_empty_room() {
        assert!(!should_fetch_full_member_list(0, 0));
    }
}
