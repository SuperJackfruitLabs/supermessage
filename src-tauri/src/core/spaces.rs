//! Enumerates the account's joined spaces and flattens each one's subtree,
//! for the spaces rail
//! (`docs/superpowers/specs/2026-08-14-spaces-rail-design.md`).
//!
//! **Downward only.** A space's membership is read from the space's own
//! `m.space.child` state (§2). That state lives *on the space*, so a space
//! naming its children is authoritative by construction. The upward direction
//! — `Room::parent_spaces()`, a room claiming membership of a space — is the
//! untrustworthy one: any room can assert `m.space.parent` pointing at a
//! space that has never heard of it, which is why the SDK hands back
//! `Reciprocal`/`WithPowerlevel`/`Unverifiable`/`Illegitimate` rather than a
//! plain list. **This module deliberately never calls it.** Nothing here
//! needs the answer to "which spaces is this room in?", so asking the
//! question would import a trust problem this cut does not have. A later
//! feature that genuinely needs the upward direction must respect those
//! verification levels rather than trusting the claim.
//!
//! **Cycles are legal** (§3). `A → B → A` is a spec-permitted space graph, so
//! [`SpaceGraph::flatten`] carries a visited set — mandatory, not defensive:
//! without it the walk never terminates, and it would fail to terminate
//! *inside the core*, taking the app with it. It is bounded by depth as well,
//! for a different reason: the visited set prevents non-termination, the
//! depth bound prevents a pathological graph from costing a long walk before
//! it returns.
//!
//! Mirrors the split every other projection in this crate makes (see
//! `core::rooms::project_room_parts`): [`SpaceGraph`] and its traversal are
//! pure and SDK-free — `OwnedRoomId` is a validated string, not a live
//! handle — and are what the tests actually exercise; [`build_space_index`]
//! is the thin adapter that extracts real state events and feeds them in.

use std::collections::{HashMap, HashSet};

use matrix_sdk::deserialized_responses::SyncOrStrippedState;
use matrix_sdk::ruma::events::space::child::SpaceChildEventContent;
use matrix_sdk::ruma::events::SyncStateEvent;
use matrix_sdk::ruma::{OwnedRoomId, OwnedServerName, RoomId};
use matrix_sdk::{Client, Room};
use matrix_sdk_ui::room_list_service::filters::new_filter_space;
use matrix_sdk_ui::room_list_service::RoomListItem;
use serde::Serialize;

use super::error::{CoreError, CoreResult};

/// How many `m.space.child` hops [`SpaceGraph::flatten`] will follow before it
/// stops descending.
///
/// Not a correctness device — the visited set is what guarantees termination.
/// This is a cost bound: a maliciously or accidentally deep chain of
/// subspaces would otherwise be walked in full, once per space, every time
/// the rail is fetched. Eight levels is far past any hierarchy a human
/// organises by hand; rooms deeper than that simply do not appear under the
/// space, which is a visibly incomplete list rather than a hung app.
pub const MAX_SPACE_DEPTH: usize = 8;

/// One joined space, as the rail renders it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SpaceSummary {
    pub id: String,
    /// The space's display name, falling back to its room id — the same
    /// convention `core::rooms::project_room_parts` uses, so the rail never
    /// has to render an empty label.
    pub name: String,
    /// The space's own `m.room.avatar` as a raw `mxc://` URI, never fetched
    /// bytes — resolved through the existing `room_avatar` command like every
    /// other avatar in this app.
    ///
    /// No hero / two-person fallback, unlike
    /// `core::rooms::resolve_room_avatar_mxc`: those rules exist to infer a
    /// *conversation's* picture from the person on the other side of it, and
    /// a space is not a conversation. A space with no avatar falls back to
    /// its parsed initial in the rail (design §6).
    pub avatar_url: Option<String>,
    /// How many **joined** rooms the reader will see when they select this
    /// space: the size of [`SpaceGraph::flatten`]'s result, which is the very
    /// same list that becomes the roster filter's identifier clause.
    ///
    /// Joined-only, and spaces-excluded, on purpose (§5). `m.space.child` can
    /// name rooms we are not in and subspaces the roster hides, and counting
    /// either would advertise "12" and then reveal four. The count and the
    /// filter come from one function so they cannot drift.
    pub child_count: u64,
}

/// Whether an `m.space.child` event actually declares a child.
///
/// The `via` list is what makes the declaration real: it names the servers a
/// client would route a join through. The spec requires it, and the
/// established convention for *removing* a child without redacting the event
/// is to set `via` to an empty list — Synapse's own room-hierarchy walk skips
/// those, and so does Element. Treating an empty `via` as a live child would
/// resurrect children a space admin has already taken down.
///
/// Deliberately stricter than ruma's own
/// `PossiblyRedactedSpaceChildEventContent::is_valid`, which only checks that
/// the field is present.
fn declares_a_child(via: &[OwnedServerName]) -> bool {
    !via.is_empty()
}

/// The `m.space.child` edges of every **joined** space, plus every joined
/// non-space room — enough to answer "what would the roster show under this
/// space?" without touching the SDK again.
///
/// Pure and SDK-free (`OwnedRoomId` is a validated string, not a live room
/// handle), so the traversal below is unit-testable against hand-built
/// graphs — including the cyclic and pathological ones a real homeserver
/// will not politely produce on demand.
#[derive(Debug, Default, Clone)]
pub struct SpaceGraph {
    /// Joined space id -> the child room ids its own `m.space.child` state
    /// declares, in no particular order.
    ///
    /// **Every joined space has an entry**, even one with no children, and
    /// nothing else does — so `children.contains_key(id)` is precisely "`id`
    /// is a space we are joined to, and can therefore descend into". A child
    /// that is a space we have *not* joined has no entry, which is correct:
    /// we hold none of its state and cannot see through it.
    children: HashMap<OwnedRoomId, Vec<OwnedRoomId>>,
    /// Every joined room that is *not* a space — the only rooms the roster
    /// can ever show, since its filter carries `not(space)`.
    joined_rooms: HashSet<OwnedRoomId>,
}

impl SpaceGraph {
    /// Records a joined space and the children its own state declares.
    fn add_space(&mut self, space_id: OwnedRoomId, children: Vec<OwnedRoomId>) {
        self.children.insert(space_id, children);
    }

    /// Records a joined, non-space room.
    fn add_room(&mut self, room_id: OwnedRoomId) {
        self.joined_rooms.insert(room_id);
    }

    /// Whether `space_id` is a space this account has joined — the check
    /// `Session::select_space` makes before it will scope the roster to it.
    pub fn is_joined_space(&self, space_id: &RoomId) -> bool {
        self.children.contains_key(space_id)
    }

    /// Every **joined, non-space** room anywhere beneath `root`, each exactly
    /// once, following `m.space.child` edges down to at most `max_depth`
    /// hops.
    ///
    /// Full-subtree, not just the top level (§3): selecting a mission shows
    /// the mission's rooms, not only the ones someone happened to attach
    /// directly. That is what makes the visited set mandatory rather than
    /// optional.
    ///
    /// Three properties the tests pin down, because each of them is a real
    /// graph a homeserver will hand us:
    ///
    /// - **A cycle terminates.** `root` is seeded into `visited`, so an edge
    ///   pointing back at an ancestor is skipped rather than followed.
    /// - **A diamond yields one entry.** A room reachable by two paths is
    ///   inserted into `visited` by whichever path reaches it first; the
    ///   second finds it already there.
    /// - **Depth exhaustion truncates rather than hangs.** Rooms past
    ///   `max_depth` hops are absent from the result; nothing spins.
    ///
    /// `root` itself is never in the result — it is a space, and the roster
    /// filter's `not(space)` clause would drop it anyway. Subspaces are
    /// likewise descended *through* but never returned, for the same reason:
    /// they are not rooms the reader would see, so counting them would make
    /// `childCount` a promise the roster does not keep.
    ///
    /// Breadth-first so that `max_depth` means hops from the root rather
    /// than "steps taken", which is the bound a reader could actually
    /// reason about.
    pub fn flatten(&self, root: &RoomId, max_depth: usize) -> Vec<OwnedRoomId> {
        // Seeded with `root`: without it a `root -> child -> root` cycle
        // would re-expand the root on the second hop and walk the whole
        // subtree again for every lap the depth bound allows.
        let mut visited: HashSet<&RoomId> = HashSet::from([root]);
        let mut found: Vec<OwnedRoomId> = Vec::new();
        let mut frontier: Vec<&RoomId> = vec![root];

        for _ in 0..max_depth {
            if frontier.is_empty() {
                break;
            }
            let mut next: Vec<&RoomId> = Vec::new();
            for node in frontier {
                let Some(children) = self.children.get(node) else {
                    continue;
                };
                for child in children {
                    if !visited.insert(child) {
                        continue;
                    }
                    if self.joined_rooms.contains(child) {
                        found.push(child.clone());
                    }
                    // A child that is itself a joined space is descended
                    // into; one we have not joined is a dead end, because we
                    // hold none of its `m.space.child` state.
                    if self.children.contains_key(child) {
                        next.push(child);
                    }
                }
            }
            frontier = next;
        }

        found
    }
}

/// The joined spaces and the graph beneath them, resolved together in one
/// pass so a space's `childCount` and the roster filter it produces can never
/// disagree.
#[derive(Debug, Default, Clone)]
pub struct SpaceIndex {
    graph: SpaceGraph,
    /// The joined spaces' identity fields, in the order the rail renders
    /// them (see [`Self::summaries`]).
    spaces: Vec<SpaceIdentity>,
}

/// What the rail needs to draw one space, before its subtree has been counted.
#[derive(Debug, Clone, PartialEq, Eq)]
struct SpaceIdentity {
    id: OwnedRoomId,
    name: String,
    avatar_url: Option<String>,
}

impl SpaceIndex {
    /// The rail's entries, each carrying the count of joined rooms its
    /// subtree flattens to.
    ///
    /// Sorted by name, then by room id as a tiebreak. `Client::joined_rooms`
    /// returns rooms in whatever order the state store iterates, which is not
    /// stable across calls — an unsorted rail would reshuffle itself on every
    /// re-fetch, moving the entry out from under a reader's cursor.
    pub fn summaries(&self) -> Vec<SpaceSummary> {
        let mut summaries: Vec<SpaceSummary> = self
            .spaces
            .iter()
            .map(|space| SpaceSummary {
                id: space.id.to_string(),
                name: space.name.clone(),
                avatar_url: space.avatar_url.clone(),
                child_count: self.graph.flatten(&space.id, MAX_SPACE_DEPTH).len() as u64,
            })
            .collect();
        summaries.sort_by(|a, b| a.name.cmp(&b.name).then_with(|| a.id.cmp(&b.id)));
        summaries
    }

    /// The joined rooms the roster should show for `space_id`, or
    /// [`CoreError::UnknownSpace`] when it is not a space this account has
    /// joined.
    ///
    /// An error rather than a silent fall back to "All rooms": a space that
    /// has vanished (left, or never joined in the first place) leaves the
    /// rail highlighting an entry that no longer exists, and widening the
    /// roster underneath that highlight would show *everything* while the UI
    /// still claims to be scoped. Failing tells the frontend to re-fetch
    /// `spaces_list` and move its own selection back to "All rooms", so the
    /// highlight and the roster agree again.
    ///
    /// An **empty** result is not an error: a space whose last joined child
    /// is gone is still a space, and an empty roster under it is the honest
    /// answer.
    pub fn rooms_in(&self, space_id: &RoomId) -> CoreResult<Vec<OwnedRoomId>> {
        if !self.graph.is_joined_space(space_id) {
            return Err(CoreError::UnknownSpace {
                space_id: space_id.to_string(),
            });
        }
        Ok(self.graph.flatten(space_id, MAX_SPACE_DEPTH))
    }
}

/// Reads `space`'s own `m.space.child` state and returns the room ids it
/// declares as children.
///
/// `get_state_events_static::<SpaceChildEventContent>()` reads the local
/// state store — no network round trip, and no `/hierarchy` call: the
/// children of a space we have joined are already in the state we sync.
///
/// Events that fail to deserialize are skipped, and that is load-bearing
/// rather than incidental: a child removed by clearing the event's content
/// (rather than redacting it) leaves `{}` behind, which has no `via` and so
/// cannot be a `SpaceChildEventContent` at all. Skipping it is exactly
/// right — it is not a child any more.
async fn space_children(space: &Room) -> CoreResult<Vec<OwnedRoomId>> {
    let events = space
        .get_state_events_static::<SpaceChildEventContent>()
        .await
        .map_err(|e| CoreError::Protocol(e.to_string()))?;

    let mut children = Vec::new();
    for raw in events {
        let event = match raw.deserialize() {
            Ok(event) => event,
            Err(err) => {
                tracing::debug!(
                    space = %space.room_id(),
                    error = %err,
                    "skipping an m.space.child event that no longer declares a child"
                );
                continue;
            }
        };
        match event {
            SyncOrStrippedState::Sync(SyncStateEvent::Original(event)) => {
                if declares_a_child(&event.content.via) {
                    children.push(event.state_key);
                }
            }
            // A redacted `m.space.child` has had its `via` stripped by
            // definition: the child was taken down.
            SyncOrStrippedState::Sync(SyncStateEvent::Redacted(_)) => {}
            // Stripped state belongs to rooms we are only *invited* to, and
            // this only ever runs over joined spaces — handled rather than
            // wildcarded so a future caller that does walk an invited space
            // gets the same `via` rule instead of silently getting nothing.
            SyncOrStrippedState::Stripped(event) => {
                if event.content.via.as_deref().is_some_and(declares_a_child) {
                    children.push(event.state_key);
                }
            }
        }
    }
    Ok(children)
}

/// Walks the account's joined rooms once, sorting them into spaces (with
/// their declared children) and ordinary rooms.
///
/// **Uses `new_filter_space()` unwrapped**, which is the constructor
/// `core::rooms::spawn_room_list` wraps in `not` to keep spaces *out* of the
/// roster. Its doc comment reads "filter out rooms that are spaces" but its
/// body is `|room| room.cached_is_space` returned directly — it is an
/// include-filter with an exclude-sounding description. Here that is exactly
/// what is wanted, so it is used bare; the two call sites are deliberately
/// the same constructor so a future change to what counts as a space cannot
/// make the rail and the roster disagree about which rooms are hidden from
/// one and listed in the other.
pub async fn build_space_index(client: &Client) -> CoreResult<SpaceIndex> {
    let is_space = new_filter_space();
    let mut index = SpaceIndex::default();

    for room in client.joined_rooms() {
        // `RoomListItem` is the type the filter takes: a facade over `Room`
        // that snapshots the handful of `RoomInfo` fields filters read, so
        // they don't contend on its lock. Building one here is what lets the
        // rail reuse the roster's own definition of "is a space".
        let item = RoomListItem::from(room.clone());
        if is_space(&item) {
            let children = space_children(&room).await?;
            index.spaces.push(SpaceIdentity {
                id: room.room_id().to_owned(),
                name: space_display_name(&item, &room),
                avatar_url: room.avatar_url().map(|url| url.to_string()),
            });
            index.graph.add_space(room.room_id().to_owned(), children);
        } else {
            index.graph.add_room(room.room_id().to_owned());
        }
    }

    Ok(index)
}

/// A space's label for the rail: its computed display name, then its
/// `m.room.name`, then its room id.
///
/// The same three-step fallback `core::rooms::project_room` ends in, for the
/// same reason: a space is a room, and the rail parses its name through the
/// roster's own `roomIdentity` path (design §6), which needs *something* to
/// parse.
fn space_display_name(item: &RoomListItem, room: &Room) -> String {
    item.cached_display_name()
        .map(|name| name.to_string())
        .or_else(|| room.name())
        .unwrap_or_else(|| room.room_id().to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn id(local: &str) -> OwnedRoomId {
        RoomId::parse(format!("!{local}:x.org")).unwrap()
    }

    fn server(name: &str) -> OwnedServerName {
        matrix_sdk::ruma::ServerName::parse(name).unwrap()
    }

    /// Builds a graph from `(space, [children])` pairs plus a list of joined
    /// non-space rooms — the same two facts `build_space_index` extracts from
    /// the SDK, hand-written so the awkward shapes are reachable.
    fn graph(spaces: &[(&str, &[&str])], rooms: &[&str]) -> SpaceGraph {
        let mut g = SpaceGraph::default();
        for (space, children) in spaces {
            g.add_space(id(space), children.iter().map(|c| id(c)).collect());
        }
        for room in rooms {
            g.add_room(id(room));
        }
        g
    }

    fn flattened(g: &SpaceGraph, root: &str, max_depth: usize) -> Vec<String> {
        let mut ids: Vec<String> = g
            .flatten(&id(root), max_depth)
            .iter()
            .map(|room| room.to_string())
            .collect();
        ids.sort();
        ids
    }

    #[test]
    fn declares_a_child_needs_at_least_one_via_server() {
        assert!(declares_a_child(&[server("x.org")]));
    }

    #[test]
    fn an_empty_via_is_a_removed_child_not_a_live_one() {
        // The convention for taking a child down without redacting the
        // event. Accepting it would resurrect children an admin removed.
        assert!(!declares_a_child(&[]));
    }

    #[test]
    fn flatten_returns_the_joined_rooms_directly_under_a_space() {
        let g = graph(&[("space", &["a", "b"])], &["a", "b"]);
        assert_eq!(
            flattened(&g, "space", MAX_SPACE_DEPTH),
            vec!["!a:x.org", "!b:x.org"]
        );
    }

    #[test]
    fn flatten_descends_through_subspaces_to_the_whole_subtree() {
        // §3: selecting a mission shows the mission's rooms, not only the
        // ones someone happened to attach at the top level.
        let g = graph(
            &[
                ("top", &["mid", "a"]),
                ("mid", &["deep", "b"]),
                ("deep", &["c"]),
            ],
            &["a", "b", "c"],
        );
        assert_eq!(
            flattened(&g, "top", MAX_SPACE_DEPTH),
            vec!["!a:x.org", "!b:x.org", "!c:x.org"]
        );
    }

    #[test]
    fn flatten_never_returns_the_root_or_any_subspace() {
        // The roster filter carries `not(space)`, so a subspace in the
        // result would be counted and then never shown — exactly the "12
        // then four" mismatch §5 forbids.
        let g = graph(&[("top", &["mid", "a"]), ("mid", &["b"])], &["a", "b"]);
        let found = flattened(&g, "top", MAX_SPACE_DEPTH);
        assert!(!found.contains(&"!top:x.org".to_string()));
        assert!(!found.contains(&"!mid:x.org".to_string()));
    }

    #[test]
    fn flatten_terminates_on_a_direct_cycle() {
        // `A -> B -> A`, which is a legal space graph. Without the visited
        // set this re-expands A on every lap the depth bound allows and
        // returns each room `MAX_SPACE_DEPTH / 2` times over.
        let g = graph(&[("a", &["b", "ra"]), ("b", &["a", "rb"])], &["ra", "rb"]);
        assert_eq!(
            flattened(&g, "a", MAX_SPACE_DEPTH),
            vec!["!ra:x.org", "!rb:x.org"]
        );
    }

    #[test]
    fn flatten_terminates_on_a_longer_cycle() {
        // `A -> B -> C -> A`. A visited set that only remembered the
        // immediate parent would still loop here.
        let g = graph(
            &[
                ("a", &["b", "ra"]),
                ("b", &["c", "rb"]),
                ("c", &["a", "rc"]),
            ],
            &["ra", "rb", "rc"],
        );
        assert_eq!(
            flattened(&g, "a", MAX_SPACE_DEPTH),
            vec!["!ra:x.org", "!rb:x.org", "!rc:x.org"]
        );
    }

    #[test]
    fn flatten_returns_a_diamond_reachable_room_exactly_once() {
        // `shared` hangs off both `left` and `right`. It is one room and the
        // reader sees one row, so it must be counted once — a duplicate
        // would inflate `childCount` above what the roster shows.
        let g = graph(
            &[
                ("top", &["left", "right"]),
                ("left", &["shared"]),
                ("right", &["shared"]),
            ],
            &["shared"],
        );
        assert_eq!(flattened(&g, "top", MAX_SPACE_DEPTH), vec!["!shared:x.org"]);
        assert_eq!(g.flatten(&id("top"), MAX_SPACE_DEPTH).len(), 1);
    }

    #[test]
    fn flatten_stops_at_the_depth_bound_instead_of_walking_forever() {
        // Four hops of subspace with a room at each level; a bound of two
        // reaches the rooms one and two hops down and no further.
        let g = graph(
            &[
                ("s0", &["s1", "r1"]),
                ("s1", &["s2", "r2"]),
                ("s2", &["s3", "r3"]),
                ("s3", &["r4"]),
            ],
            &["r1", "r2", "r3", "r4"],
        );
        assert_eq!(flattened(&g, "s0", 2), vec!["!r1:x.org", "!r2:x.org"]);
        assert_eq!(
            flattened(&g, "s0", MAX_SPACE_DEPTH),
            vec!["!r1:x.org", "!r2:x.org", "!r3:x.org", "!r4:x.org"]
        );
    }

    #[test]
    fn flatten_at_depth_zero_returns_nothing() {
        let g = graph(&[("space", &["a"])], &["a"]);
        assert!(flattened(&g, "space", 0).is_empty());
    }

    #[test]
    fn flatten_skips_children_we_have_not_joined() {
        // §4: `m.space.child` can name rooms we are not in. They are not in
        // the room list, so the identifier filter would never match them —
        // and counting them would advertise rooms the reader cannot see.
        let g = graph(&[("space", &["joined", "stranger"])], &["joined"]);
        assert_eq!(
            flattened(&g, "space", MAX_SPACE_DEPTH),
            vec!["!joined:x.org"]
        );
    }

    #[test]
    fn flatten_cannot_see_through_a_subspace_we_have_not_joined() {
        // We hold none of its `m.space.child` state, so it is a dead end
        // rather than a hole in the walk.
        let g = graph(&[("top", &["unjoined-space"])], &["hidden"]);
        assert!(flattened(&g, "top", MAX_SPACE_DEPTH).is_empty());
    }

    #[test]
    fn flatten_of_a_childless_space_is_empty_rather_than_an_error() {
        let g = graph(&[("space", &[])], &["elsewhere"]);
        assert!(flattened(&g, "space", MAX_SPACE_DEPTH).is_empty());
    }

    #[test]
    fn is_joined_space_is_true_only_for_joined_spaces() {
        let g = graph(&[("space", &["a"])], &["a"]);
        assert!(g.is_joined_space(&id("space")));
        assert!(!g.is_joined_space(&id("a")));
        assert!(!g.is_joined_space(&id("never-heard-of-it")));
    }

    fn index(spaces: &[(&str, &str, &[&str])], rooms: &[&str]) -> SpaceIndex {
        let mut idx = SpaceIndex::default();
        for (space, name, children) in spaces {
            idx.graph
                .add_space(id(space), children.iter().map(|c| id(c)).collect());
            idx.spaces.push(SpaceIdentity {
                id: id(space),
                name: (*name).to_string(),
                avatar_url: None,
            });
        }
        for room in rooms {
            idx.graph.add_room(id(room));
        }
        idx
    }

    #[test]
    fn child_count_matches_the_rooms_the_roster_will_actually_show() {
        // The property §5 is about: the number on the rail and the length of
        // the filter's identifier list are the same call.
        let idx = index(
            &[
                ("mission", "Mission", &["sub", "a", "stranger"]),
                ("sub", "Sub", &["b"]),
            ],
            &["a", "b"],
        );
        let summaries = idx.summaries();
        let mission = summaries.iter().find(|s| s.name == "Mission").unwrap();
        assert_eq!(mission.child_count, 2);
        assert_eq!(
            mission.child_count as usize,
            idx.rooms_in(&id("mission")).unwrap().len()
        );
    }

    #[test]
    fn child_count_excludes_rooms_we_have_not_joined() {
        // A space advertising 12 and then showing 4 is worse than showing no
        // number at all.
        let idx = index(&[("space", "Space", &["joined", "stranger"])], &["joined"]);
        assert_eq!(idx.summaries()[0].child_count, 1);
    }

    #[test]
    fn child_count_excludes_subspaces_themselves() {
        let idx = index(&[("top", "Top", &["sub"]), ("sub", "Sub", &[])], &[]);
        let summaries = idx.summaries();
        assert_eq!(
            summaries
                .iter()
                .find(|s| s.name == "Top")
                .unwrap()
                .child_count,
            0
        );
    }

    #[test]
    fn summaries_are_sorted_by_name_so_the_rail_does_not_reshuffle() {
        let idx = index(
            &[("z", "Alpha", &[]), ("a", "Zulu", &[]), ("m", "Mike", &[])],
            &[],
        );
        let names: Vec<String> = idx.summaries().into_iter().map(|s| s.name).collect();
        assert_eq!(names, vec!["Alpha", "Mike", "Zulu"]);
    }

    #[test]
    fn summaries_serialize_camel_case_on_the_wire() {
        let idx = index(&[("space", "Ops", &["a"])], &["a"]);
        let json = serde_json::to_value(&idx.summaries()[0]).unwrap();
        assert_eq!(json["id"], "!space:x.org");
        assert_eq!(json["name"], "Ops");
        assert_eq!(json["avatarUrl"], serde_json::Value::Null);
        assert_eq!(json["childCount"], 1);
    }

    #[test]
    fn rooms_in_reports_unknown_space_rather_than_falling_back_to_all_rooms() {
        // A silent fall back would widen the roster to everything while the
        // rail still highlighted the vanished space.
        let idx = index(&[("space", "Space", &[])], &["a"]);
        let err = idx.rooms_in(&id("gone")).unwrap_err();
        assert_eq!(err.kind(), "unknownSpace");
    }

    #[test]
    fn rooms_in_refuses_an_ordinary_room_id() {
        let idx = index(&[("space", "Space", &["a"])], &["a"]);
        assert_eq!(idx.rooms_in(&id("a")).unwrap_err().kind(), "unknownSpace");
    }

    #[test]
    fn rooms_in_an_emptied_space_is_an_empty_list_not_an_error() {
        // Still a space; the honest answer is an empty roster.
        let idx = index(&[("space", "Space", &[])], &["elsewhere"]);
        assert!(idx.rooms_in(&id("space")).unwrap().is_empty());
    }
}
