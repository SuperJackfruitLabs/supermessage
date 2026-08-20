# iOS polish: everything found, in the order it will be fixed

> **For agentic workers:** each item is small enough to finish, test and commit
> on its own. Tick as you go. Items reference the surfaces they touch.

**Source:** screenshots of the running app on an iPhone 13 mini and an iPad Pro
11-inch, 19–20 August 2026, plus a read of the core against both hosts. Nothing
here is speculative — every item was seen on screen or found by comparing what
the core sends against what a host draws.

**Deferred, not an app issue:** agent text carries small corruptions —
`Hell o!`, `runningg`, `actuallly`, `woorks`, `automation,,`. Inserted spaces
and doubled letters, in different places each time, on both hosts. Judged to be
generation rather than rendering. Revisit if it persists after the agent side is
looked at.

---

## A · Naming, said differently on every surface — **done**

The rules exist in `core::display_name` and are tested. These are surfaces that
do not call them.

- [x] **A1.** Room info renders the room topic raw: `openclaw on ashram —
      openclaw:ganesha`. An internal address, on the panel's most prominent
      line. Route through `parse_runtime`.
- [x] **A2.** Room info member names are raw: `ganesha (openclaw @ ashram)`.
      Route through `sender_label`, as the timeline already does.
- [x] **A3.** Room info shows full member ids
      (`@agent_ashram_openclaw-ganesha:id.agentpod.dev`), wrapping two lines.
      Truncate in the middle so both ends survive, or hide behind a disclosure.
- [x] **A4.** The timeline repeats `(OpenClaw on Ashram)` on every turn. Inside
      a room it never changes. Drop it when the room has one agent; keep it
      where more than one speaks.

## B · The timeline

- [x] **B1.** Membership runs are not grouped. The desktop's
      `groupTimelineItems` collapses them into "Alice, Bob and 3 others joined
      the room"; iOS draws every one — ten consecutive lines in Ganesha's
      history. Port it.
- [x] **B2.** **No send state on own messages.** `sendState` is on every item
      and nothing draws it: sending, sent and *failed* look identical. The one
      place a chat app must not be ambiguous.
- [x] **B3.** No timestamps on own messages. The peer header carries one; your
      side carries nothing, so three identical messages are indistinguishable.
- [ ] **B4.** Read receipts unused. `readBy` is on the DTO and nothing reads it.
- [x] **B5.** No jump-to-latest and no new-message badge. Nearly free in an
      inverted list, where being at the bottom is exactly `contentOffset.y <= 0`.
- [ ] **B6.** Turn cards are headed `dev.agentpod.turn.v1` — a Matrix event type
      shown to a reader.
- [ ] **B7.** Turn card bodies are raw argv, truncated mid-path. What it did and
      whether it worked matters more than the exact command.
- [ ] **B8.** No sender avatars. Fine with one agent, weak in a room with
      several.
- [ ] **B9.** Reaction chips sit louder than the prose they attach to.
- [ ] **B10.** No way to see *who* reacted, only how many.
- [ ] **B11.** No message editing or deletion. The core has neither, so this is
      core work before it is UI work.

## C · Room info

- [x] **C1.** Says nothing about the runtime — harness, host, last seen, room id
      are what you open this panel to find.
- [ ] **C2.** "Leave room" sits below the fold at the medium detent: the only
      destructive action is the one you have to hunt for.
- [ ] **C3.** No mute, no notification setting, no pin. Mute is the one people
      reach for first.
- [ ] **C4.** A member list of two, one of whom is always you — in an agent room
      the useful question is what it is and where it runs.

## D · Search

- [ ] **D1.** **Looks broken while working.** Typing a query leaves the *empty
      state* on screen — magnifying glass and "Find a message across your
      rooms."
- [ ] **D2.** No searching state between submit and results.
- [ ] **D3.** No "no matches for …" state.
- [ ] **D4.** Results carry no date.
- [ ] **D5.** Results carry no room avatar, so a hit is hard to place.
- [ ] **D6.** No scope — no way to search within one room.

## E · New conversation

- [ ] **E1.** Create and Join read as labels rather than buttons — grey list
      rows, and grey is also how iOS draws disabled.
- [ ] **E2.** "Invite (user id)" takes a raw `@someone:server` with no
      completion, no validation, and no way to pick from people already in your
      rooms.
- [ ] **E3.** The join placeholder is the wire format: `#room:server or
      !id:server`.
- [ ] **E4.** No busy state on either action; a slow homeserver looks like a
      dead button.
- [ ] **E5.** Nothing here starts a conversation *with an agent*, which is what
      the app is for.

## F · Account and toolbar

- [x] **F1.** **No way to sign out.** `Session.signOut` is implemented, tested,
      and called from nowhere. A missing exit, not a missing feature.
- [x] **F2.** No account or settings entry point anywhere in the app.
- [x] **F3.** Nothing shows who you are signed in as — worth knowing on a
      console that acts on your behalf.
- [x] **F4.** Sign-in does not remember the homeserver between attempts, so a
      typo costs the whole field.
- [ ] **F5.** The roster's arrangement filter sits *inside the list* rather than
      in the toolbar, inconsistent with search and compose.
- [ ] **F6.** The room header shows only the name. On a console, whether the
      agent is alive belongs at the top of the screen — and a two-line header
      could retire the ⓘ button.
- [ ] **F7.** The search sheet's Done vanishes once the field has focus:
      `.searchable` replaces it with its own Cancel. Two dismissals for one
      sheet, depending where you tapped.

## G · Roster

- [ ] **G1.** Space pills run wide for long names; only the hex ids are
      shortened. Truncate in the middle so `Rakesh's MacBook Pro` keeps both
      ends.
- [ ] **G2.** Only the selected pill shows how many rooms it holds. All of them
      could.

## H · Invitation

- [ ] **H1.** Does not say who invited you — the thing you want before
      accepting.

## I · Consistency across hosts

- [ ] **I1.** The desktop has not received the roster work: three arrangements,
      state dots, unread and time. The two clients now disagree about what a
      roster is.
- [ ] **I2.** The timeline spec's acceptance rules
      (`docs/superpowers/specs/2026-08-19-timeline-behaviour-design.md`) still
      lack the geometry assertions it calls for.

---

## Order

**A** first: it is the cheapest, it is the most visible, and the rules are
already written and tested — these are call sites, not decisions.

Then **B2 and B5**, because a failed message that looks sent is the only item
here that can cost someone something real.

Then **F1–F3**, because an app you cannot sign out of is not finished.

Then the rest, roughly in the order above, with **B11** and **E5** last since
both need core work before any UI exists to write.
