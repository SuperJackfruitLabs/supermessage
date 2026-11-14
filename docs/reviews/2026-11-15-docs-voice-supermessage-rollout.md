# Claims ledger — SuperMessage docs voice rollout

Status: verify-stage evidence for the nine-page voice rollout; live distribution and hub evidence
remain pending from the operator.

## Immutable revisions and scope

- PR base: `fad3340535c23c2106231ad28cb24f87931f4d59`.
- Voice guide: `SuperJackfruitLabs/sketchbook@29690e4397467bcf92ea7ef7e88b4e89cffba88f`, `ideas/2026-11-14-docs-voice-guide-v1/README.md`.
- Reference rollout: every published page in `SuperJackfruitLabs/superdispatch@77185fc39f3431a3ffeca6d05bf405561b58f1b0`, especially `start/first-placement.mdx`, `run/running-it.md`, `run/opting-a-board-in.md`, `placement/`, and `records/`.
- Scope: all nine hand-written, published pages under `docs-site/src/content/docs/`, the repository-owned docs claims test, and this ledger.
- Product code, site configuration, theme, package manifests, styles, components, generated files, and the home-page layout are unchanged.
- Product evidence below is pinned to the PR base. The docs-only CI commands at that base are
  `.github/workflows/ci.yml:231-249,1192-1206`. Because this rollout also changes
  `crates/supermessage-core/tests/docs_claims.rs`, `scripts/ci-plan.mjs:20-42` classifies the actual
  patch as non-docs-only; the operator's GitHub PR will therefore need the regular component plan
  to finish as well. The checks recorded below are the required docs checks, not a claim that the
  unpushed artifact has completed that remote plan.

## Page-by-page voice review

All pages pass narrator, exactness, vocabulary/privacy, originality, and claims. No page in this set is a tutorial or quick start, so the tutorial-only running-gag row is not applicable throughout; no running gag was added.

| Page | Kind | Narrator and momentum | Action payoff | Exact and safety material |
|---|---|---|---|---|
| `index.mdx` | home/reference | pass — the hero and cards now carry a consistent we/you narrator | pass — choosing the client, bringing in an agent, answering, and reading receipt state each end with the result | pass — version, release caveats, platform list, event behavior, links, and privacy caveat stay literal |
| `start/what-it-is.md` | concept | pass — “let’s start” carries the reader from Matrix familiarity to the product boundary | not applicable — explanatory page, with outcomes stated where it invites inspection | pass — event types, diagram, platform architecture, release status, and physical-device caveat stay literal |
| `start/concepts.md` | reference | pass — direct “we/you” framing makes the glossary one guided vocabulary | not applicable | pass — event identifiers and definitions are unchanged; the exact thirteen-kind claim is backed below |
| `use/messages.md` | task/reference | pass — each common message action addresses the reader directly | pass — sending, mentioning, editing, deleting, replying, reacting, attaching, typing, and marking state each end with an observable result or reason | pass — the corrected `msgtype` table is literal and mechanically checked |
| `use/encryption.md` | concept/task | pass — direct guidance makes the exception and recovery path explicit | pass — room creation, recovery-key use, and placeholder interpretation state what changes | pass — the agent exception, placeholder, backup/recovery behavior, and live-output warning remain plain |
| `use/rooms.md` | task/reference | pass — the narrator accompanies roster, space, membership, search, block/report, and read actions | pass — each action ends with the narrowed list, membership effect, search result, account-wide ignore, server report, or receipt boundary | pass — four states, 20-result cap, encryption search limitation, and safety behavior remain literal |
| `use/agents.md` | concept/task | pass — a real we/you narrator carries cards, answers, receipts, voice, live output, and boundaries | pass — answering, recording, and inspecting each state the record or presentation produced | pass — event tables, examples, fallback rules, safety constraints, and version behavior stay exact |
| `use/notifications.md` | task/concept | pass — the narrator takes the reader from action to privacy and background behavior | pass — choosing an action states what is sent and why no open room is required | pass — action table, privacy boundary, quiet-layer table, fallback strings, entitlement behavior, and the unverified-device caveat are literal |
| `use/widgets.md` | concept/task | pass — direct framing distinguishes recap from intended live behavior | pass — opening and answering state the recap or decision state obtained | pass — timings, counts, plaintext warning, receipt semantics, and unverified remote boundary stay literal |

Candor passes across the set: unsigned/debug-signed release caveats, app-store absence, unencrypted agent rooms, temporary and non-E2EE live output, server-side search limits, “answered is not resolved,” notification entitlement behavior, and physical-device verification gaps stay explicit. No real local host, agent, team, credential, customer, or private-message name was added. Product names, public service names, schema identifiers, and synthetic examples are the only identifiers used.

## Claims checked at the PR base

Each row covers every material command, route, setting, default, field, enum, limit, version, platform, and behavioral statement on that page. There are no shell commands, API routes, or JSON blocks in this nine-page set.

| Page | Material checked | Base evidence |
|---|---|---|
| `index.mdx` | v0.1.2; desktop/Android/iOS release caveats; five platforms and shared-core architecture; suite-card rendering and fallbacks; message-push identifier boundary; notification/widget implementation and verification limit; gate receipt semantics; default encryption and agent-room exception; generated LLM routes | `package.json:2-4`; `AGENTS.md:7-43,87-102`; `crates/supermessage-core/src/custom_events.rs:1-18,61-81`; `crates/supermessage-core/src/push.rs:1-21,43-59`; `crates/supermessage-core/src/widget.rs:23-31`; `crates/supermessage-core/src/session.rs:1898-1919`; `docs-site/astro.config.mjs` and `crates/supermessage-core/tests/docs_claims.rs:141-173` |
| `start/what-it-is.md` | Matrix-client boundary; three event types and labels; notification/widget/Live Activity roles; identifier-only message pushes and local decrypt design; fallback bodies; temporary to-device live output; Svelte/Tauri, SwiftUI, Compose, UniFFI and shared Rust core; AgentPod/SuperPipeline/Treaty boundaries | `AGENTS.md:7-29,104-116`; `crates/supermessage-core/src/item_view.rs:195-242,1667-1684`; `crates/supermessage-core/src/push.rs:1-21`; `crates/supermessage-core/src/widget.rs:1-58`; `crates/supermessage-core/src/live_activity.rs:1-25`; `crates/supermessage-core/src/turns.rs:146-153`; `crates/supermessage-core/src/treaty_corpus.rs:27-75` |
| `start/concepts.md` | homeserver/client boundary; default encryption and agent-invite exception; spaces and filtering; thirteen event-content kinds and visible state transitions; turn/permission/gate/outcome identifiers; receipt ordering and sender rule; voice-reply pairing; fallback bodies | `crates/supermessage-core/src/session.rs:1874-1926`; `crates/supermessage-core/src/spaces.rs:1-20,46-86`; `crates/supermessage-core/src/timeline.rs:1130-1172`; `crates/supermessage-core/src/item_view.rs:739-765,870-1023`; `crates/supermessage-core/src/gate_outcome.rs:34-88`; `crates/supermessage-core/src/voice_reply.rs:20-62` |
| `use/messages.md` | Markdown outbound body and mentions; edit/delete/reply/reaction behavior; attachment staging and on-demand media; all documented `msgtype` rendering branches; typing; visible state changes and membership grouping | `crates/supermessage-core/src/timeline.rs:2328-2350,2834-2904`; `crates/supermessage-core/src/item_view.rs:627-708,739-765,870-1023,1435-1510`; `crates/supermessage-core/src/media.rs:9-14,135-145`; `crates/supermessage-core/src/item_view.rs:457-488`; corrected rows mechanically pinned by `crates/supermessage-core/tests/docs_claims.rs:142-159` |
| `use/encryption.md` | default encrypted creation and `@agent_` exception; irreversible room encryption; AgentPod-created-room boundary; undecryptable placeholder; encrypted backup, one-time recovery key, new-device recovery and reset boundary; three visible state transitions; non-E2EE temporary live output and encrypted durable message | `crates/supermessage-core/src/session.rs:1898-1919,2110-2129,2143-2157`; `crates/supermessage-core/src/recovery.rs:1-17,40-69,97-129`; `crates/supermessage-core/src/item_view.rs:71-90,739-765,900-906`; `crates/supermessage-core/tests/docs_claims.rs:161-173` |
| `use/rooms.md` | three roster arrangements; four state names and 15-minute/24-hour thresholds; non-health boundary; spaces; invite/create/join/leave/hide behavior; server-side one/all-room search, plaintext-only results, newest-first order and 20 cap; account-wide ignore, cache refill, three report subjects; message-like read receipt | `crates/supermessage-core/src/roster.rs:20-69,96-128,130-182,201-220`; `crates/supermessage-core/src/spaces.rs:1-20,39-55`; `crates/supermessage-core/src/session.rs:1850-1950`; `crates/supermessage-core/src/search.rs:1-35,51-155`; `crates/supermessage-core/src/safety.rs:1-42,179-228,279-319`; `crates/supermessage-core/src/rooms.rs:963-971` |
| `use/agents.md` | v1 and v2 card surfaces; malformed fallback; gate answer/receipt/sender/order/removal semantics; turn-error shape and folded attempts; voice-note/transcript fields and fallback; voice-reply ordering/pairing/sender/edit/redaction cases; temporary paced live output; newer schema behavior; one-level bounded text-only renderer; product-plane boundaries | `crates/supermessage-core/src/custom_events.rs:20-89,95-109`; `crates/supermessage-core/src/gate_outcome.rs:34-88,162-189`; `crates/supermessage-core/src/turn_error.rs:1-45,49-66,132-171`; `crates/supermessage-core/src/voice_transcript.rs:1-65,82-164`; `crates/supermessage-core/src/voice_reply.rs:20-62,84-154,184-218`; `AGENTS.md:110-116` |
| `use/notifications.md` | category/action table; direct answer without focused timeline; time-sensitive decision handling; `event_id_only`; local decrypt/wording design; turn counts; shared core decision; three quiet layers; rule prefix/idempotence/disabled-rule behavior; fallback strings; filtering entitlement boundary; background sync/store lifecycle; physical-device verification gap | `crates/supermessage-core/src/notification.rs:31-41,110-180,716-800,848-992`; `crates/supermessage-core/src/notification_policy.rs:33-74,147-187,418-501`; `crates/supermessage-core/src/push.rs:1-21,94-214`; `apple/SupermessageNotificationService/NotificationService.swift:118-124`; `AGENTS.md:31-43,275-309` |
| `use/widgets.md` | recap ordering and omission; turn counts; inline answers and stale-answer check; answered-vs-resolved lifetimes; immediate decision reload request and 15-minute general interval; locked multi-process snapshot and frames; one fleet card, 15-minute active contract, hub-owned lifecycle, token relay/deletion; plaintext fields; device-verification boundary | `crates/supermessage-core/src/widget.rs:1-58,68-101,107-180,214-259`; `crates/supermessage-core/src/live_activity.rs:1-30,51-99,116-169`; `apple/Supermessage/Platform/FleetActivityController.swift:9-19`; `apple/SupermessageNotificationService/NotificationService.swift:118-124`; `AGENTS.md:311-363` |

## Corrections and evidence-bound changes

No factual change was made silently.

| Page | Old material | New material | Why and base evidence |
|---|---|---|---|
| `index.mdx` | The Lock Screen card presented the iOS push/decrypt/answer path as an unqualified working capability. | It now identifies the implemented path and says physical-device end-to-end verification is pending. | `AGENTS.md:31-43` explicitly says iOS background push is not yet verified on a device; client implementation is in `push.rs`, `notification.rs`, and `notification_policy.rs`. |
| `start/what-it-is.md` | Message push decryption and the hub-pushed Live Activity were stated as completed runtime behavior without the device boundary. | The page now distinguishes implemented client design from the pending physical-device end-to-end check. | Same boundary: `AGENTS.md:31-43`; client contract: `push.rs:1-21`, `live_activity.rs:1-25`. |
| `use/messages.md` | Markdown was said to “read the same in Element as it does here.” | The page now claims only that outbound formatting survives in Element. | Outbound messages use `RoomMessageEventContent::text_markdown` (`timeline.rs:2328-2348`), while the reader's own row deliberately renders the typed body verbatim (`item_view.rs:578-606`). |
| `use/messages.md` | Every `m.notice` was documented as a de-emphasised bubble. | A one-line notice is now a system notice; longer or formatted notices keep the de-emphasised bubble. | Exact branches: `item_view.rs:609-617,644-659`. |
| `use/messages.md` | `m.audio` was grouped with `m.file` and `m.video` as a generic file row. | `m.audio` is documented as an audio player; only file and video remain generic file rows. | Exact branches: `item_view.rs:672-700`; the new docs claim ratchet rejects the old row. |
| `use/encryption.md` | “Verifying the device brings history within reach,” and device verification was named as the way a new device earns old history. | The page now names the recovery key and encrypted backup as the recovery path and says verification alone does not provide room keys. | `recovery.rs:1-17,40-69`; backup settings at `session.rs:2119-2128`; mechanically checked by `docs_claims.rs`. |
| `use/agents.md` | An agent's spoken message was said to follow the text “seconds later.” | The unsupported latency was removed; the page claims only the ordering and pairing the client can verify. | `voice_reply.rs:20-62` proves ordering/pairing cases but defines no delivery-latency bound. |
| `use/notifications.md` | Notifications were guaranteed to keep working after force-quit. | The page says the architecture is designed for force-quit push handling and that physical-device verification remains pending. | `AGENTS.md:31-43`; client lifecycle implementation at `AGENTS.md:275-309`. |
| `use/widgets.md` | A WidgetKit reload request was described as an immediate reload, and the hub-driven Live Activity as verified live behavior. | The page says the code asks WidgetKit to reload immediately and labels the hub-to-device Live Activity path as implemented client design with pending physical-device verification. | Reload request: `NotificationService.swift:118-124`; remote/device boundary: `AGENTS.md:31-43,337-363`. |

## Deployment and infrastructure claims that remain UNVERIFIED

The repository proves intended client behavior but not the current state of private infrastructure or live distribution:

- **UNVERIFIED:** `index.mdx` and `start/what-it-is.md` say iOS is available through TestFlight by invitation. The repository has TestFlight automation, but it cannot prove current invitation availability.
- **UNVERIFIED:** the AgentPod hub currently emits the identifier-only message pushes, turn-count additions, and fleet Live Activity start/update/end payloads described by `start/what-it-is.md`, `use/notifications.md`, and `use/widgets.md`. This repository pins the client contract and explicitly marks the physical-device end-to-end path as pending; the server implementation and live deployment are outside this repository.

A request for current distribution and pinned private-estate/AgentPod evidence was posted on card `card_ab254acc77274e82`. These claims are not treated as false merely because their deployment evidence is outside this repository.

## Mechanical claims ratchet

The existing `crates/supermessage-core/tests/docs_claims.rs` already walks every published page, checks frontmatter, resolves internal links, verifies every named suite event against the renderer registry, and pins the four roster words. This rollout adds three regression checks:

- the `msgtype` table must keep `m.audio` on the core's audio-player branch and distinguish one-line from longer/formatted `m.notice` handling;
- the encryption page must name recovery key/backup, reject the old verification-only claim, and match the implemented backup and recovery functions;
- every page that advertises the iOS remote push or Live Activity path must retain the physical-device verification boundary, and the old force-quit guarantee is rejected.

## Exact-material inventory

- `index.mdx`: frontmatter, version, public release link, platform names, component structure, event behavior, internal routes, and LLM routes.
- `start/what-it-is.md`: release callout, event table, architecture diagram, event identifiers, product links, and next links.
- `start/concepts.md`: event identifiers, schema links, definitions, and next links.
- `use/messages.md`: `msgtype` table, state names, and internal links.
- `use/encryption.md`: `@agent_`, security warnings, recovery behavior, and state text.
- `use/rooms.md`: arrangement/state tables, timings, search cap, Matrix behavior, and links.
- `use/agents.md`: event table, identifiers, fallback strings, receipt rules, examples, and safety constraints.
- `use/notifications.md`: action and quiet-layer tables, literal fallback strings, warning/caveat prose, and privacy statements.
- `use/widgets.md`: literal status strings, timings, counts, plaintext warning, and links.

No humour was added to any exact region, warning, recovery step, table, literal value, identifier, or privacy statement.

## Real checks

- Rust formatting and docs claims: **pass** — `cargo fmt --all --check`; `cargo test -p supermessage-core --all-features --test docs_claims` ran 8 tests, 8 passed, 0 failed. Cargo emitted the existing unused `uniffi` build-dependency warning and a future-incompatibility note for `proc-macro-error2`; neither failed the target.
- Docs-only site command from `.github/workflows/ci.yml:231-249`: **pass** — `npm ci && npm run build` in `docs-site`. `npm ci` added 308 packages and audited 309; npm reported 9 dependency vulnerabilities (3 moderate, 6 high) and an unapproved `esbuild@0.28.2` install script. The build completed, generated the nine docs routes plus `/404.html`, built 10 pages, indexed 10 HTML files with Pagefind, generated the sitemap and LLM text routes, and retained the existing warnings that the optional `i18n` collection is empty and `docs → 404` is absent.
- CI routing probe: **non-docs-only** — with the base and artifact head supplied to
  `node scripts/ci-plan.mjs plan`, the classifier reported
  `reason=crates/supermessage-core/tests/docs_claims.rs is not a docs path`. This is an expected
  consequence of extending the repository-owned claims ratchet, but it means the eventual GitHub
  PR must not be described as having only the two docs jobs.
