# AGENTS.md — supermessage

Guidance for AI coding agents working in this repository. Read this first; it summarizes the project's purpose, current state, and decided architecture. For full detail, read the two docs in `docs/` — they are the source of truth.

## Project overview

supermessage is a **cross-platform Matrix chat client** targeting **iOS, Android, Windows, macOS, and Linux from a single codebase**.

It is the **Communication layer (client)** of the Synthetic Organization suite (AgentPod + Kaambaan + Matrix + org control plane) — the human-facing, agent-aware Matrix client for a mixed human/AI-agent organization. Generic-client quality is the baseline; the differentiators are agent-aware rendering of suite events (Kaambaan cards/runs, permission requests, station status), approvals from chat (Kaambaan gate resolution), and fleet/mission awareness. See `docs/positioning.md`.

**Current status: M0 and most of M1 are on `main`, plus a full design pass; v0.0.1 is tagged.** Password login, encrypted session persistence, `SyncService`, room-list and timeline streaming, send/receive, replies, reactions, typing notices, read receipts, media rendering, the custom-event framework, and a responsive three-pane desktop UI (571 Rust tests, 379 frontend tests, clippy and svelte-check clean).

**Before planning work, read [docs/parity-gap-analysis.md](docs/parity-gap-analysis.md).** It is the only document here grounded in the command surface rather than in intent, and it is blunt about how far this is from a general-purpose client: no file sending, no edit or delete, no room creation or joining, no search, no notifications, and encrypted rooms rendering a placeholder. The 17 commands registered in `src-tauri/src/lib.rs`'s `generate_handler!` are the authoritative capability list — everything else in these docs describes intent and drifts.

Verified by driving the real app over WebDriver against `id.agentpod.dev` (see "Driving the real UI" below): 16 rooms render, rooms load history, encrypted events show placeholders, composer drafts stay scoped to their room, no connection banner while sync is live, and the session restores from the keyring with no password after a restart.

**What is still unverified:** sending a message end to end (not exercised, because it posts real text into real rooms), scroll-triggered back-pagination beyond the initial page, and anything on Windows, macOS or mobile.

Remaining follow-ups:

- **Hardening.** `start_sync`/`start_room_list` are `pub` and rely on every caller holding the lifecycle mutex — making them private would make that structural. `logout` holds that mutex across an untimed HTTP call, so a hung homeserver blocks the next login for its duration. `gapSync`'s `void doResync` has no `.catch`, so a rejected resync becomes an unhandled rejection.
- **Deferred minors** from the task reviews, notably: no `event.isComposing` guard in the composer (CJK IME Enter sends prematurely), and the login error slot's `min-h-10` is unproven at narrow widths.

## Repository layout

```
README.md            — one-paragraph summary and stack pointer
docs/tech-stack.md   — full stack decision record: choices, rationale, risks, protocol choices, milestones
docs/positioning.md  — suite context, boundaries (hard rules), near-term wedge, milestone adjustments
                       NOT IN THIS REPOSITORY. Internal strategy notes, git-ignored and unpublished.
                       References to it below are deliberate and will not resolve in a public clone;
                       every rule it sets that binds this codebase is restated here in full.
package.json         — frontend manifest (pnpm)
svelte.config.js     — SvelteKit, adapter-static, SPA mode
vite.config.js       — Vite + Tailwind v4 plugin; fixed port 1420 for Tauri
src/app.css          — Tailwind v4 design tokens + behavior-budget base rules
src/routes/          — Svelte 5 routes (currently the M0 placeholder screen)
src-tauri/           — the Rust core and Tauri config
  src/lib.rs         — app setup, tracing, command registration
  src/core/tls.rs    — rustls provider selection (ring); see the Android note below
  src/core/session.rs— ownership seam for the logged-in matrix_sdk::Client
  gen/android/       — generated Android Studio project (committed)
android/             — the native Android app (Gradle, over the Rust core via UniFFI)
  core/              — the FFI boundary module: generated Kotlin bindings + jniLibs
  kit/               — Kotlin-side domain/state layer, no Compose UI
  app/               — the Compose app: adaptive three-pane shell, views
```

## Decided technology stack

| Layer | Choice | License |
|---|---|---|
| App shell | Tauri 2 (one Rust + webview codebase for all 5 OS targets) | MIT/Apache-2.0 |
| Matrix SDK | matrix-rust-sdk, as a plain crate in the Tauri core (no UniFFI/FFI bridge) | Apache-2.0 |
| Frontend | Svelte 5, SPA mode, no SSR | MIT |
| Styling | Tailwind CSS v4 design tokens | MIT |
| Headless primitives | Bits UI (Radix-equivalent for Svelte) | MIT |
| Mobile skin | Framework7 Svelte v9 (fallback: Konsta UI Svelte) | MIT |
| Desktop skins | Per-OS token themes over Tauri native chrome (Fluent-inspired Windows, hand-rolled HIG macOS, libadwaita CSS vars Linux) | — |
| Message list | virtua (Svelte virtualizer) for the inverted chat timeline | MIT |
| JS ↔ Rust bridge | Tauri commands/events + Svelte stores | — |
| Push infra | Self-hosted push gateway (unmodified Sygnal, or own minimal Rust gateway at M3) + FCM/APNs | AGPL-3.0 if Sygnal — infrastructure only, not linked |

**Wired so far:** Tauri 2, matrix-sdk 0.18 (`markdown` + `bundled-sqlite`), Svelte 5 + SvelteKit (SPA), Tailwind v4, Bits UI, virtua. **Not yet added:** Framework7 (mobile skin, M2) and the desktop skins — add them when skin work starts, not before, so the Konsta fallback stays cheap.

## Architecture rules (from docs/tech-stack.md — treat as binding)

- The Matrix client lives **entirely in the Rust core** (tokio). The webview is a dumb renderer: Svelte stores mirror core state streamed over Tauri events; user intents go down as Tauri commands. Use windowed/delta updates to bound IPC serialization cost.
- Exactly one `matrix_sdk::Client` per logged-in account, owned by the core.
- UI skins are the **only** platform-branched layer (~20% of UI code). All logic, state, and chat behavior is shared.

## Product boundaries (hard rules from docs/positioning.md)

- Matrix conversation ≠ ACP execution transcript ≠ Kaambaan work activity. supermessage is **not** an ACP client and **not** a work-state board; it renders links and projections of those, never their truth.
- Correlate rooms to work via `missionId/cardId/taskId/runId` + `matrixRoomId/matrixEventId`; never attach a whole Matrix room to one run.
- Agent identity, Station, ACP Session, and Kaambaan Run are distinct linked objects — render them as such.
- Do not build a homeserver (Synapse stays) and do not own org membership (the P1 Organization layer will). The Application Service bridge (server half) lives outside this repo.
- Custom "rich card" event types must be **versioned, documented, suite-shared schemas** with plain-text fallback so Element/Cinny remain usable clients. Never client-private hacks.

## Matrix protocol choices

- **Auth:** `m.login.password` is the **only** flow available today —
  `id.agentpod.dev` (Synapse 1.152.0) advertises no SSO or OIDC, and both
  `/_matrix/client/v1/auth_metadata` and the MSC2965 unstable path return 404.
  Native OIDC (MSC3861) remains the intended target but requires deploying
  matrix-authentication-service first. The client implements password login
  behind an `AuthProvider` trait so OIDC is additive.
- **Sync:** Simplified Sliding Sync (MSC4186) via the SDK's SyncService; `/sync` v3 fallback for older servers.
- **E2EE:** SDK crypto (vodozemac): cross-signing, SSSS key backup, emoji/SAS device verification. Never hand-roll crypto.
- **Media:** authenticated media endpoints (spec ≥1.11).
- **Push:** `event_id_only` pushes via own Sygnal → FCM/APNs; the app fetches and decrypts content itself. iOS phase 2: Notification Service Extension in Swift linking the Rust SDK.

## Build, test, and development commands

Package manager is **pnpm**. All commands run from the repo root unless noted.

```bash
pnpm install                 # frontend dependencies
pnpm tauri dev               # run the desktop app (Vite on :1420 + Rust core)
pnpm tauri build             # production desktop bundle
pnpm check                   # svelte-check (TypeScript + Svelte diagnostics)
pnpm build                   # frontend only -> build/

cd src-tauri
cargo check                  # fast Rust typecheck
cargo test                   # Rust unit tests
cargo fmt && cargo clippy    # format and lint
```

Android (SDK at `~/Android/Sdk`, NDK 29.0.14206865 installed; all four Rust
Android targets added):

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export NDK_HOME="$HOME/Android/Sdk/ndk/29.0.14206865"
pnpm tauri android dev       # requires a device or emulator
```

**Do not verify the app with a bare `cargo run`/`cargo build` binary.** Tauri
debug builds load `build.devUrl` (`http://localhost:1420`), so without Vite
running the webview loads nothing and every `invoke` fails — which looks like a
broken app. Use `pnpm tauri dev`, or `pnpm tauri build` for a binary that has
the frontend embedded.

iOS/macOS builds need a Mac and are not possible on the current Linux machine;
WebKit visual QA has to happen elsewhere.

### Driving the real UI (end-to-end)

Per [Tauri's WebDriver docs](https://v2.tauri.app/develop/tests/webdriver/),
`tauri-driver` proxies to the platform's native WebDriver. Linux and Windows
only — macOS has no WKWebView driver.

```bash
sudo apt install webkit2gtk-driver     # must match the installed webkit2gtk (2.52.3)
cargo install tauri-driver --locked

pnpm tauri build --debug --no-bundle   # tauri-driver launches the BINARY, so the
                                       # frontend must be embedded; a plain debug
                                       # build loads devUrl and shows nothing
tauri-driver --port 4444 --native-port 4445 &
python3 scripts/e2e-drive.py src-tauri/target/debug/supermessage
```

`scripts/e2e-drive.py` talks raw W3C WebDriver over HTTP — no `webdriverio`
dependency. It asserts against the real DOM (room rows, `p.selectable`
message bodies, `span.italic` placeholders, the composer) using whatever
account the keyring currently holds, so it needs a logged-in session. It is a
diagnostic harness, not part of `pnpm test`. Note WebKitWebDriver returns lone
surrogates for astral-plane emoji; the script repairs them before printing.

This harness is what caught the "opening a room shows one message" bug — it
is worth reaching for before trusting a UI claim made from code reading alone.

## The iOS app

Native SwiftUI, not a webview. `apple/` holds three targets and `project.yml`
generates the Xcode project — regenerate after adding a file:

```bash
cd apple && xcodegen generate && cd ..
xcodebuild test -project apple/Supermessage.xcodeproj -scheme SupermessageKit -destination 'platform=iOS Simulator,name=iPhone 16 Pro'
xcodebuild test -project apple/Supermessage.xcodeproj -scheme Supermessage    -destination 'platform=iOS Simulator,name=iPhone 16 Pro'
```

- `SupermessageFFI` — the generated bindings, **Swift 5**. UniFFI 0.28's
  output is not `Sendable`-clean; quarantining it is what lets everything else
  compile under strict concurrency.
- `SupermessageKit` — the boundary and the stores. **Imports no SwiftUI**, so
  the state layer stays testable and view code cannot leak into it.
- `Supermessage` — the views.

Three rules that are not style preferences:

1. **The app parses nothing, and decides nothing.** No markdown, no HTML, no
   `matrix.to`, no room-name splitting. Those arrive already decided on
   `TimelineRow` and `RoomRow` as `RichBlock`, `ItemView`, `MatrixLinkTarget`
   and `RoomIdentity`. The same goes for product rules: how the roster is
   ordered and grouped, how long silence takes to read as quiet, what a
   section is called when it is the only one — all of that is `core::roster`,
   and a host asks for `RosterSection`s rather than computing them. Two hosts
   each holding a copy is how they start disagreeing, which is exactly what
   happened before it moved.
2. **Events are delivered in order, by one consumer.** `EventPump` yields into
   a single `AsyncStream` and `Session` drains it with one `for await`. A task
   per event does not preserve order, and out-of-order diffs corrupt the
   reader's view in a way that looks like a rendering bug.
3. **No `Core` call on a cooperative thread.** `CoreClient` puts every one on
   a `DispatchQueue`, because they all block and the cooperative pool assumes
   tasks yield. `Task.detached` looks right and is not.

Amber (`Theme.signal`) means a pending decision and nothing else. Only
`DecisionCard` may use it.

The app icon is generated, not hand-drawn: `assets/logo.svg` is the mark and
`assets/build-icon.py` renders the three variants iOS asks for (light, dark and
tinted) into `apple/Supermessage/Assets.xcassets`. Both need `librsvg`, and
regenerating the mark itself needs `potrace` (`brew install librsvg potrace`).
Read `assets/build-logo.py`'s doc comment before changing the mark — it records
why it is drawn rather than traced.

Design: `docs/superpowers/specs/2026-08-18-native-ios-app-design.md`.
The AgentPod event contract this client consumes: `docs/agentpod-events.md`.

## The Android app

Native Kotlin/Compose, not the Tauri webview path. `android/` is a three-module
Gradle build — `:core` (the FFI boundary: generated Kotlin bindings + jniLibs),
`:kit` (domain/state, no Compose dependency, enforced by two independent
checks), `:app` (Compose, the adaptive three-pane shell) — consuming the same
Rust core as iOS through the same UniFFI bindings. The shell picks pane count
from its own measured width, not from `WindowSizeClass`, for the reason
recorded at `directiveFor()` in `RootScaffold.kt`: the default directive ties
pane count to the real window instead of the width the shell was actually
given, which is the same substitution that put iOS's info panel off the side
of an iPad. Tests pass on both `supermessage-phone` and `supermessage-tablet`
AVDs, portrait and landscape.

`docs/superpowers/specs/2026-08-20-android-app-design.md` specs the module
layout and `docs/superpowers/specs/2026-08-20-android-scaffold-design.md`
specs the adaptive shell built on top of it; both are binding.
### Instrumented tests must be run under CI's conditions, not this machine's

Three device differences have each cost a CI round-trip on this repo, and all three were
findable locally:

| | local default | CI |
|---|---|---|
| timezone | Asia/Kolkata | UTC |
| screen | 1080x2400 | pinned `pixel_6`, previously unpinned |
| clipping | tall enough to hide the bug | not |

`scripts/android-ci-parity.sh` sets the emulator to CI's timezone and geometry. **Run it,
then run the suite twice — once at pixel_6 height and once at `wm size 1080x1280`.** A test
that only passes at one height is asserting on layout luck, and this repo has produced six
of them.

Two traps behind those failures, both worth knowing:

- **`onNodeWithText("2", substring = true)`** matched one node here and two on CI, because
  the timezone changed how a fixture timestamp rendered. A single-character substring
  matcher is almost always this bug waiting to happen.
- **`Box(Modifier.requiredSize(w, 800.dp))`** as a test shell is *taller than a short
  device*, so its content is clipped by the window and `performScrollTo` cannot reach it.
  Require the **width** — that is what `paneCountFor` reads — and let height follow the
  device.

And note the AVD cache key must contain every input that shapes the AVD. It did not contain
the profile, so pinning `profile: pixel_6` was silently ignored for a whole run: the cache
hit restored the old device and the create step was skipped.

### Changing the FFI surface changes **two** sets of checked-in bindings

Add or alter anything in `supermessage-ffi` and both the Kotlin **and** the Swift
bindings go stale. Both are committed; both are diffed by their own CI job. Regenerating
one and not the other is a mistake that has been made twice on this repo, and it does not
surface as a compile error — iOS fails its *runtime* checksum check
(`uniffi_..._checksum_constructor_core_new() != <n>`), which reads like a mystery.

**The Swift bindings can be regenerated on Linux**, even though the XCFramework cannot.
`uniffi-bindgen` reads crate metadata, which is platform-independent, so the host `.so`
works in place of the iOS library:

```bash
cargo build -p supermessage-ffi
STAGE=$(mktemp -d)
cargo run -q -p supermessage-ffi --bin uniffi-bindgen -- generate \
    --library target/debug/libsupermessage_ffi.so --language swift --no-format --out-dir "$STAGE"
mkdir -p "$STAGE/headers" && mv "$STAGE"/*.h "$STAGE/headers/"
cat "$STAGE"/*.modulemap > "$STAGE/headers/module.modulemap" && rm -f "$STAGE"/*.modulemap
rm -rf apple/Generated && cp -r "$STAGE" apple/Generated
```

Verified twice against a macOS CI run: the output is identical to what
`build-xcframework.sh` produces there. `--no-format` is what keeps it reproducible.

`scripts/build-android-libs.sh` builds the four ABIs and generates the
Kotlin — the same `#[uniffi::export]` definitions that produce the Swift, so
no new Rust is needed. The script runs green on this machine, unmodified:

```bash
cargo install cargo-ndk                                    # the one missing piece
export ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/29.0.14206865"
./scripts/build-android-libs.sh                            # ~15 min, four ABIs
```

**Run it in a fresh checkout before touching Gradle**, and again whenever the
FFI surface changes. The `.so` files are *not* in the repo — `.gitignore` drops
`android/core/src/main/jniLibs/`, because they are 362MB across four ABIs and
the x86 slice alone is over GitHub's 100MB per-file limit. A clone therefore
has the generated Kotlin but no libraries behind it, and Gradle never invokes
cargo, so the missing half shows up as a link error at runtime rather than as a
build failure. The generated Kotlin *is* checked in, exactly as the generated
Swift is, and for the same reason: it is the boundary's shape, and a moved API
should appear in review.

**Every Gradle command below runs from `android/`, not the repo root** — it is
its own Gradle project, separate from the pnpm/cargo one at the top of this
file:

```bash
cd android
./gradlew test                                    # :core and :kit unit tests (JVM, no emulator)
./gradlew :app:testDebugUnitTest                   # :app unit tests (JVM, no emulator)
./gradlew :core:connectedDebugAndroidTest          # :core instrumented tests (needs a device)
./gradlew :app:connectedDebugAndroidTest           # :app instrumented tests (needs a device)
```

`:kit`'s tests are JVM-only, but a handful (`RosterArrangementTest`) call the
real Core rather than just constructing its plain data classes — that is
what makes `RosterArrangement` thin instead of a second copy of the roster's
rules. Reaching Rust from a desktop JVM test needs a *host* build, distinct
from the four cross-compiled `.so`s above: `target/debug/libsupermessage_ffi.so`,
which `./gradlew test` does not build for you any more than it builds the
Android ABIs.

```bash
cargo build -p supermessage-ffi                    # once, or whenever the FFI surface changes
```

Skip this and run `:kit:test` anyway and it **fails**, not skips — same
posture as the missing-`.so` check above, for the same reason: a quiet skip
here would mean these tests silently stop running in CI and nobody notices.
The failure names the exact file and the exact command. If you deliberately
don't want to build Rust right now, `-Pkit.allowMissingHostCore=true` opts
out and skips those tests instead, saying so in the skip message.

The instrumented suites need a running device or emulator (`adb devices`).
Available AVDs: `supermessage-tablet` (800dp portrait / 1280dp landscape),
`supermessage-phone` (411/914dp), `supermessage-16k` (411/923dp) — the shell's
pane-count tests are written to be device-independent (`requiredSize`, not
`size`; bounds compared against the shell's own rect, not the device's), so
any of them should pass any of these tests, but the tablet is the one that
exercises three panes.

Two things there that are easy to get wrong and expensive to find later. Play
requires 16 KB page support, which needs *both* halves — ring as the active
provider (`core::tls`, done) and `-Wl,-z,max-page-size=16384` at link time (in
the script). And `gen/android/` is the Tauri webview path, which is closer to
working and is deliberately **not** the plan; the spec says why.

## Testing strategy

`cargo test` covers the Rust core (622 tests) and `pnpm test` the frontend
(380, vitest).

The frontend count went *down* as the Rust one went up, and that is the
shape of the shared view-model migration rather than lost coverage: the
render classification, rich-text parsing, the custom-event registry, room
identity, matrix-link parsing, mention collection, previews and affordances
all moved into the core, taking their tests with them, so iOS and Android
cannot disagree with this app about any of them. Both are clean, and CI runs them on every PR along with
`clippy -D warnings` and a dependency-licence gate.

### A test that has never failed is not yet a regression test

This is the single most repeated defect on this project. **Four separate
times** a test was written, shipped green, and later shown to pass against a
deliberately broken implementation. In every case the code happened to be
correct and the test was worthless — which is worse than no test, because it
buys unearned confidence and stops the next person looking.

The failures were not careless. Each was a plausible-looking assertion:

- An assertion that was a *tautology* — `[...s].join("") === s` holds for every
  string, including one containing the lone surrogate it was meant to catch.
- An assertion whose *fixture masked the clause under test* — a date-divider
  case that broke a sender-run on a mismatched sender, so the kind check it
  claimed to verify never ran.
- An assertion against a bound that could not be crossed — the naive cut landed
  exactly on a code-point boundary because the limit was even and the character
  two units wide.
- An assertion whose outcome depended on the *scheduler* — `tokio::select!`
  randomises branch order, so a sequence-continuity test sometimes exercised
  the path where a restarted counter still looks correct.

So: **mutate the implementation, watch the test fail, restore, and record what
you saw.** For anything touching ordering, concurrency or a boundary, run the
mutated version several times — the scheduler case passed on some runs and not
others, and one green run proves nothing. Falsification is the standard here,
not thoroughness.

Two quality requirements from `docs/tech-stack.md` shape the rest:

- **Per-engine visual QA** across WebKit (iOS/macOS), WebView2 (Windows), and WebKitGTK (Linux, including the oldest supported distro version).
- A **native-feel behavior budget** (non-negotiable checklist): iOS keyboard webview-resize fix, safe-area handling, haptics via `tauri-plugin-haptics`, native popup context menus (no HTML dropdowns for OS-level actions), platform scrollbar discipline, system font stacks/Dynamic Type, strict `user-select` discipline.

Already honored in `src/app.css` and `src/app.html`: `viewport-fit=cover` plus
`--inset-*` safe-area variables, per-platform system font stack, and
`user-select` off on chrome / on for `.selectable` content. The rest is open.

## Security and license considerations

- **Dependency licenses:** all runtime dependencies must be permissively
  licensed (MIT / Apache-2.0 / BSD) **or MPL-2.0 used unmodified**. MPL-2.0 is
  file-level copyleft: it obliges publishing changes to those files and
  explicitly permits combination into a larger work under other terms. Thirteen
  MPL-2.0 crates arrive unavoidably with matrix-sdk (`eyeball`, `eyeball-im`,
  `imbl`, `imbl-sized-chunks`, `bitmaps`, `readlock`, `readlock-tokio`,
  `as_variant`) and Tauri (`cssparser`, `cssparser-macros`, `dtoa-short`,
  `selectors`, `option-ext`). **Strong and network copyleft (GPL / AGPL /
  LGPL) remain banned** — that requirement is what eliminated
  Flutter/matrix-dart-sdk and trixnity. If you modify an MPL-2.0 file, publish
  the change.
- **AGPL projects are reference-only, never copy code:** Element X apps, trixnity-messenger/Tammy, mautrix. If an Application Service bridge is ever co-designed, prefer Ruma/ruma-appservice (MIT); avoid mautrix (AGPL).
- **Sygnal (push gateway) is AGPL-3.0** in its maintained element-hq form; the Apache-2.0 matrix-org original is archived. It is deployed infrastructure, not a dependency — the client never talks to it (the homeserver POSTs to it). Run it unmodified; a minimal own Rust push gateway is an M3 option (see docs/tech-stack.md license section).
- E2EE via vodozemac only; never hand-roll cryptography. Note the product call (docs/positioning.md): org rooms are unencrypted by design (knowledge extraction, AS-bridge incompatibility); E2EE stays available for external/DM contexts but is not on the critical path.
- Push content is fetched and decrypted by the app itself (`event_id_only` pushes) — do not route message content through the push gateway.

## Milestones (docs/positioning.md supersedes docs/tech-stack.md on ordering)

- **M0 — spine:** Tauri scaffold; Rust core syncs a real account on `id.agentpod.dev` (password login); Svelte stores mirror room list/timeline; virtua message list; send/receive plaintext. Dogfood immediately against real agent users.
- **M1 — agent-aware client:** custom event rendering framework + schema drafts (card/run/permission/station), deep links, graceful plain-text fallback. E2EE is "available, not blocking".
- **M2 — daily driver:** media, replies/reactions/edits, receipts/typing, iOS keyboard fix, Android 16KB/ring fix, Framework7 mobile skin + desktop skins.
- **M3 — push + approvals:** Sygnal deployment; FCM/APNs; Kaambaan gate notifications → Matrix → approve/reject end-to-end; then iOS NSE.
- **M4 — mission surfaces:** spaces/mission rooms, presence-from-org-state, fleet event rooms, multi-account, settings polish, store submissions.

## Known risks to keep in mind when writing code

- No production Tauri-**mobile** Matrix client exists yet — mobile integration is trailblazing; spike the push path early.
- iOS keyboard does not resize WKWebView (the #1 "web tell" in chat apps) — the planned fix is ~200 lines of objc2 Rust resizing the webview frame; treat as core work.
- aws-lc-rs crashes on Android 16KB-page devices ([matrix-rust-sdk#6442](https://github.com/matrix-org/matrix-rust-sdk/issues/6442), still open as of Aug 2026). **The `ring` backend cannot be selected purely by features:** matrix-sdk 0.18 depends on `reqwest` with its `rustls` feature, which resolves to `__rustls-aws-lc-rs` and turns on `rustls/aws_lc_rs`. Cargo features are additive, so aws-lc-rs is compiled in no matter what this crate declares. The mitigation is runtime, in `src-tauri/src/core/tls.rs`: we also enable `rustls/ring` and install ring as the process-wide provider at the top of `run()`. This is also load-bearing for correctness — with two providers compiled in, rustls has no implicit default and `ClientConfig::builder()` panics unless one is installed. **Anything that constructs TLS must run after `install_ring_provider()`.** Verify on a real 16KB-page device at M2; if it still crashes, the remaining lever is a `[patch.crates-io]` forcing reqwest's `rustls-no-provider` feature.
- IPC cost of streaming timelines to the webview — use windowed/delta updates.
- Framework7 is single-maintainer — keep the skin isolated (~20% of UI) so the Konsta fallback stays viable.

## Related repositories (not part of this workspace's code)

The suite's other surfaces live in sibling repos: **AgentPod** (fleet console/node-agent, agents already have Matrix accounts on `id.agentpod.dev`) and **Kaambaan** (cards/tasks/runs/gates, REST+MCP, approvals, notifications). supermessage integrates with them via links, projections, and the Matrix room/event IDs — it must not own their state.
