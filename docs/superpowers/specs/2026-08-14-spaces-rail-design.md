# The spaces rail — design

**Status:** Decided (14 Aug 2026). Binding for the first cut.
**Prior art in-repo:** spaces are now excluded from the roster (`b27b6f0`).
This adds the surface that makes them useful instead of merely hidden.

---

## 1. What a space is, in one paragraph

A space **is a room**: same state, same timeline, marked only by
`m.room.type: "m.space"` on its creation event. Its membership is expressed as
`m.space.child` state events on the space, keyed by child room id, carrying
`via` (servers to route through), `order` (a string sort key) and `suggested`.
Children may point back with `m.space.parent`. It is a **DAG, not a tree** — a
room can belong to several spaces, spaces nest, and **cycles are legal**.

---

## 2. Direction matters, and only one direction needs verifying

We read **downward**: from a space, its `m.space.child` events. That state lives
on the space itself, so a space naming its children is authoritative by
construction.

The **upward** direction is the untrustworthy one — any room can assert
`m.space.parent` pointing at a space that has never heard of it. The SDK models
this properly, returning `Reciprocal`, `WithPowerlevel`, `Unverifiable` or
`Illegitimate` from `Room::parent_spaces()`. **We do not use the upward
direction in this cut**, which removes the whole question. If a later feature
needs it — "which spaces is this room in?" — it must respect those verification
levels rather than trusting the claim.

---

## 3. Cycles are legal and must not hang

`A → B → A` is a valid, spec-permitted space graph. Any traversal without a
visited set is an infinite loop, and it will be an infinite loop inside the
core, taking the app with it.

Flattening is **full-subtree with a visited set**: a space shows every joined
room beneath it, at any depth, each once. That matches what a reader expects —
selecting a mission shows the mission's rooms, not just the ones someone
happened to attach at the top level — and it makes the visited set mandatory
rather than optional.

Bound the traversal by depth as well as by the visited set. A visited set
prevents non-termination; a depth bound prevents a pathological graph from
costing a long walk before returning.

---

## 4. Filtering reuses what the room list already has

No new streaming machinery. The roster is already a filtered dynamic-adapter
stream; selecting a space changes the filter:

```
all([ non_left, not(space), identifiers(<flattened child ids>) ])
```

Selecting "All rooms" restores the current filter, which is the first two
clauses.

Two consequences worth stating:

- **Children we have not joined disappear for free.** `m.space.child` can name
  rooms we are not in; they are simply not in the room list, so the identifier
  filter never matches them. No extra handling, and no accidental exposure of
  rooms the reader cannot see.
- **Changing a filter re-emits the list as a `Reset`.** The frontend's
  `DiffTracker` already handles `Reset`; what it must not see is a sequence
  restart it was not armed for. The re-emission has to flow through the same
  sequence counter as every other batch, or the gap detector will treat it as
  corruption. This is the same hazard `rooms.svelte.ts`'s module comment
  documents at length for login and restore.

The filter is set inside the spawned stream task, so selection has to reach
that task through a channel rather than by calling the controller from a
command.

---

## 5. Interface

| Command | Purpose |
|---|---|
| `spaces_list()` | The joined spaces: `{ id, name, avatarUrl, childCount }`. |
| `space_select(spaceId \| null)` | Scopes the roster. `null` restores all rooms. |

Spaces change rarely — far less than the room list — so this is a one-shot
fetch like `room_info`, not a third diff-streamed channel. Re-fetch on session
start and after a resync. If that proves too static in practice, promoting it
to a stream is a contained change; starting there is machinery bought before
it is needed.

`childCount` is the count of **joined** rooms under the space after flattening,
because that is what the reader will see when they select it. A space showing
"12" and then revealing four rooms is worse than showing nothing.

---

## 6. The rail

A vertical strip left of the roster, on `--color-surface-sunken` like the
roster it borders.

- **"All rooms" sits at the top**, always, and is the default. Without it a
  reader who selects a space has no way back, and there is no other affordance
  that means "stop filtering".
- One entry per joined space: its avatar, or its parsed initial through the
  same `roomIdentity` path the roster uses — a space is a room and its name may
  carry the same `glyph — Name — Role` structure.
- **Selection uses the accent rail**, matching the roster's own selected state.
  Not the signal colour: choosing a space is navigation, not a decision.
- Each entry needs a real accessible name — an avatar alone is not a label —
  and the selected one carries `aria-current`.
- **Unread aggregation is out of this cut.** A badge on a space would have to
  sum its subtree's unread counts, which means keeping that sum current as
  rooms change. Worth doing; not worth blocking the rail on.

### When there are no spaces

The rail does not render at all. Most accounts have no spaces, and a permanent
empty 56px strip with one "All rooms" button that does nothing is worse than no
rail. It appears when the account has at least one.

---

## 7. What must not regress

- Selecting a room, and the timeline subscription, are untouched by space
  selection. **A space switch must not re-subscribe the timeline** — that is
  the sequence-counter hazard again.
- If the selected room is filtered out by a space switch, the room pane keeps
  showing it. The roster is a navigation surface; filtering it must not close
  what the reader is reading.
- The roster's own layout, the preview line, and the responsive collapse
  behaviour all stay as they are. The rail is a new column to their left, and
  at narrow widths it collapses with them.
- No new `{@html}`. A space name is server-controlled and gets bounded and
  escaped like every other such string.

---

## 8. Amendment, 2026-08-17: invitations

Written after the rail met a real fleet. Two things this design got wrong, both
in the same place — it assumed a space and its rooms are things you are already
*in*.

**An invitation to a space is a rail entry, not a roster row.** §4's filter
originally hid every space, which left an invitation with nowhere to appear:
the rail enumerated joined spaces, and the roster hid spaces, so it showed up in
neither while Element displayed it plainly. The first fix let invited spaces
through into the roster — and that put two node-space invitations among forty
agent-room ones, in a list of conversations, which is precisely the clutter §4
exists to prevent. A space is not a conversation. So the roster hides every
space again, `spaces_list` reports invited spaces alongside joined ones with a
`membership` field, and the rail draws them as pending entries — dashed ring,
`Invitation` in place of a room count, and a click that opens Accept / Decline
rather than a filter. There is nothing to filter by: we hold none of the
space's state, so `rooms_in` answers `UnknownSpace` for it, correctly.

**A space's children include rooms you have only been invited to.** §5 defined
`childCount` as the joined rooms in the subtree, and the filter agreed with it,
so the two could not drift — but both were wrong together. AgentPod provisions
one room per agent and invites the operator to each, so a freshly-built fleet is
a space whose children are *all* invitations: every space reported zero and
filtered to an empty roster while its rooms sat visibly in All rooms. The rule
is now "every room the roster can show", which is what §4's filter (`non_left`
and `not(space)`) already meant. Rooms we have no membership of at all are still
excluded, so a space still cannot advertise twelve and reveal four.

Declining now re-reads the rail, for the same reason accepting does: the roster
is diffed and heals itself, the rail is a one-shot fetch and does not.
