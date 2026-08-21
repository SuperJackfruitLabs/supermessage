# The Android roster, end to end

**Status:** design, 21 Aug 2026. Written against `main` at `779ff58`, the commit that merged the `:kit` port.
**Audience:** whoever builds A1, and whoever later wonders why the `Session` lives in a `ViewModel`.
**Companions:** `docs/superpowers/plans/2026-08-21-android-app-roadmap.md` splits the app into phases; this is the first half of its Phase A. `docs/superpowers/specs/2026-08-20-android-app-design.md` decides what the app is. `docs/superpowers/specs/2026-08-20-android-scaffold-design.md` owns the pane rule this must not disturb.

## What this builds

Sign in on a device against a real homeserver, see your actual rooms in the right sections, tap one and the detail pane opens.

That sentence is the whole scope, and it is larger than it sounds: **nothing has ever constructed a real `Core` on Android.** The `:kit` port's 198 tests all run against a fake. This is the first time Rust, UniFFI, `:kit` and Compose run together against anything live, and that — not the roster's appearance — is what A1 is for.

### Decisions taken, and by whom

| Decision | Choice |
|---|---|
| Scope | Phase A split: roster now, timeline as A2 |
| Sign-in | Included — without it there is no way to reach a roster on a device |
| `Session` lifetime | A `ViewModel`, on `viewModelScope` |
| Avatar decoding | In the composable; the decode budget is A2's, with message images |
| Preferences | DataStore Preferences |
| Spaces rail | Out — the scaffold spec §7 defers it |

The roadmap listed nine files for Phase A and did not mention `LoginView.swift`. That was an omission rather than a decision: a roster you cannot reach proves nothing.

---

## 1. Structure

Five new files in `:app`, two edited.

```
app/src/main/kotlin/dev/supermessage/
  SessionViewModel.kt      builds the Core from filesDir; owns Session on viewModelScope
  RosterPreferences.kt     DataStore: homeserver, view, showsInvitations, showsState
  LoginScreen.kt           ← apple/Supermessage/LoginView.swift (73)
  Roster.kt                ← apple/Supermessage/Rooms/RoomListView.swift (270)
  RoomRow.kt               ← apple/Supermessage/Rooms/RoomRowView.swift (167)
  MainActivity.kt          [edit] hoist the ViewModel
  RootScaffold.kt          [edit] gate on Session.phase; real roster in the list pane
```

`RoomRow.kt` is a separate file from `Roster.kt` for the reason the Swift splits them: a row is the thing most likely to be read on its own when something looks wrong, and 167 lines of row inside 270 lines of list is where that stops being possible.

### 1.1 The pane rule does not change

`paneCountFor(maxWidth)` and the custom `PaneScaffoldDirective` stay exactly as they are. The roster becomes the list pane's *content*; nothing about how many panes there are, or how that is decided, is in scope.

**`RootScaffoldTest`'s three geometry tests must keep passing without modification.** They are the contract for "the roster did not break the pane rule". If one goes red, the roster changed something the rule had fixed, and that is a finding rather than a test to adjust.

---

## 2. Where the `Session` lives

A `ViewModel`:

```kotlin
class SessionViewModel(app: Application) : AndroidViewModel(app) {
    val session = Session(
        client = CoreClient(Core(dataDir = app.filesDir.path)),
        scope = viewModelScope,
    )
}
```

**Why not the shape iOS uses.** `RootView.swift:12` holds `@State private var session = Session()` for the app's lifetime. That is also the shape that made issue #28 reachable — a `Session` outliving sign-out, with a pump closed for good. Our `Session` handles re-sign-in correctly now, so the bug does not reproduce here; but a `ViewModel` is the better home regardless, because it has a defined end.

**Why not `remember { }`.** The manifest suppresses recreation for `orientation|screenSize|screenLayout|keyboardHidden`. It does not suppress a locale change, a dark-mode toggle, a font-scale change, or process death. Under `remember`, each of those rebuilds the Rust core and reopens SQLite, and the previous `Session`'s drain may outlive it. A `ViewModel` survives exactly the set the manifest does not.

**Why not an `Application` singleton.** It survives everything, including the teardown it should get. `viewModelScope` cancels when the owner goes away, which is what `Session`'s drain wants; a process-scoped `Session` never gets that signal.

**The data directory.** `Session` takes a built `CoreClient` and no path — Task 7 of the `:kit` port pushed that decision out deliberately, because `:kit` depends on no Android types and that is what keeps its 198 tests on the JVM. `:app` supplies `app.filesDir.path`. This ViewModel is the only place in the Android app that names a filesystem location.

---

## 3. Data flow

One direction, and no decisions along it:

```
Session.rooms: RoomsStore
    └── rooms: StateFlow<List<RoomRow>>
            │
            ├── RosterArrangement.sections(rows, view, showsInvitations, now)
            │       └── RosterSection(id, title, detail, rows, attention)
            │               └── RosterRow(row, state)   ← state arrives here
            └── RosterArrangement.hiddenInvitations(rows, showsInvitations)
                    │
                    ▼
              Roster ──► RoomRow(row, avatarUri, state, when, showsState, hidesHost)
                            ├── row.identity.name, row.identity sigil
                            ├── row.preview
                            ├── row.affordance == RespondToInvitation
                            ├── AvatarCache.uri(roomId) ──► remember { decode }
                            └── RelativeTime.label(ms, now, locale)
```

**Read `state` off the section's row, never per row.** `RosterArrangement.state(row, now)` exists but its own KDoc warns against it, and `RoomListView.swift:62-64` says why: *"The state arrives on the row. Asking per row would be a boundary crossing per visible room per re-render."* `sections` already carries it on each `RosterRow`.

**Every ordering, grouping, section title and attention rule already has an answer** in `crates/supermessage-core/src/roster.rs`, which carries 13 tests of its own. `RosterArrangement` is a thin call into it, verified as such during the port.

If the Compose grows a comparator, a sort, or a rule about which section a room belongs to, that is the defect this architecture exists to prevent — and the reason a third client is worth having at all.

### 3.1 The clock

`RosterArrangement.state` and `RelativeTime.label` both take `now`. The roster refreshes it on a timer, as iOS does, so "3m" becomes "4m" without a new event. Hold it in the composable and drive it from a `LaunchedEffect` with a delay; do not reach for `System.currentTimeMillis()` inside a row, or the row becomes untestable for the same reason `RelativeTime` takes an injected clock.

---

## 4. Preferences

Four keys, in DataStore:

| Key | Type | Default | Why it persists |
|---|---|---|---|
| `login.homeserver` | String | `https://id.agentpod.dev` | See below |
| `roster.view` | `RosterChoice` | `Waiting` | The reader's chosen arrangement |
| `roster.showsInvitations` | Boolean | `false` | Invitations are noise until they are not |
| `roster.showsState` | Boolean | `true` | The state dot can be turned off |

**`login.homeserver` earns its place with a story.** From `LoginView.swift:15-18`: it *was* `@State`, so a failed sign-in — a typo in the password, a homeserver briefly down — threw the address away and made the reader type it again to try the thing that was nearly right. Persisting it is a fix, not a convenience.

**The flicker DataStore costs.** Reads are a `Flow`, so the first composition renders on defaults and the stored values arrive after. For three booleans and an enum this is a single frame, and only visible when the stored choice differs from the default. Accept it; the alternative is a disk read on the composing thread.

---

## 5. What A1 must prove

### On a device, by hand

Sign in against a real homeserver. See your actual rooms, in the right sections, with the right previews. Tap one; the detail pane opens. Rotate; the roster survives and the pane count follows the width.

**This is the acceptance criterion**, and it cannot be automated here — it needs a homeserver and an account. State that plainly in the report rather than implying coverage that does not exist.

### By test, on the JVM

Three things. Two of them guard failures this project has actually had; the third is cheap and is the whole navigation model:

1. **The phase gate.** `STARTING` renders progress, `SIGNED_OUT` renders `LoginScreen`, `SIGNED_IN` renders the scaffold. Nothing has broken here yet — it is three cases and a few lines, and it decides what the app shows at every moment.

2. **The roster re-renders when `rooms` emits** — asserting an observer was *notified*, not that the value reads back afterwards. This is `AvatarCache`'s lesson one layer up: on iOS, `@Observable` could not see through an `NSCache` and avatars appeared only on the second scroll, because the data was there and nothing told the view. A test that reads state back succeeds against exactly that bug.

3. **`RootScaffoldTest`'s three geometry tests, unmodified.** The contract from §1.1.

### The standard that applies

**A test that has never failed is not yet a regression test.** Every test above is mutated until it fails before it is kept. The `:kit` port applied this literally and found five places where a shipping iOS test did not cover the rule its name claimed, plus two live defects in the shared design (issue #28). The rule is not ceremony.

---

## 6. Sequence

Each step ends somewhere you could stop.

1. **`SessionViewModel` and the phase gate.** Done when the app launches, builds a real `Core`, and shows `LoginScreen` because it is signed out.
2. **`LoginScreen`.** Done when a real sign-in succeeds on a device and `phase` becomes `SIGNED_IN`.
3. **`RosterPreferences`.** Done when the four keys round-trip and the homeserver survives a failed attempt.
4. **`RoomRow`.** Done when one row draws correctly from a fixture, including the invitation badge and the absent-avatar case.
5. **`Roster`.** Done when the sections, the arrangement menu and the hidden-invitation count all come from `RosterArrangement` and the list renders them.
6. **Wire it into the list pane.** Done when tapping a room opens the detail pane and the three geometry tests still pass.

Steps 1–2 carry the risk: they are the first real `Core` on Android. Step 5 is the largest but the least uncertain, because every decision in it belongs to the core.

---

## 7. What this does not cover

- **The timeline** — A2, and the four rules that make it hard.
- **The spaces rail.** Deferred by the scaffold spec §7; `ListDetailPaneScaffold` will need negotiating with when it arrives.
- **Room info, search, new room, invitations, account** — Phase C.
- **Theme.** `Theme.swift`'s 140 lines are structural typography — serif for what agents write, sans for the operator, mono for data, amber for a pending decision. None of that distinction is visible in a room list, so it lands with A2 where it carries meaning. A1 uses `MaterialTheme` as the scaffold already does.
- **The decode budget.** Avatars are small circles bounded by what `LazyColumn` composes. The real question — how many decoded bitmaps may be in flight once message images exist — is A2's, and the roadmap records why.
- **Push, release, signing.** Unbuilt on every platform.
