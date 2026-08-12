# supermessage — Tech Stack & Architecture

**Status:** Decided (Aug 2026), pre-implementation.
**Next input pending:** product vision & target audience (will refine requirements).

supermessage is a cross-platform Matrix chat client targeting **iOS, Android, Windows, macOS, and Linux from a single codebase**.

## Hard requirements that shaped the stack

1. Single codebase; mobile and desktop are equally weighted.
2. UI must **feel native** — platform-conformant chrome, behavior, and polish; not a web page in a shell.
3. **Dependency licenses:** all runtime dependencies must be permissively licensed (MIT / Apache-2.0 / BSD) **or MPL-2.0 used unmodified**. MPL-2.0 is file-level copyleft; it obliges publishing changes to those files and explicitly permits combination into a larger work under other terms. Thirteen MPL-2.0 crates arrive unavoidably with matrix-sdk and Tauri. Strong and network copyleft (GPL / AGPL / LGPL) remain banned — this eliminated matrix-dart-sdk/Flutter (AGPL-3.0) and the trixnity-messenger framework (AGPL-3.0).
4. Open source project; E2EE is non-negotiable (a plaintext-only Matrix client is not viable in 2026).

## The stack

| Layer | Choice | License | Rationale |
|---|---|---|---|
| App shell | **Tauri 2** | MIT/Apache-2.0 | One Rust + webview codebase for all 5 OS targets; native chrome APIs (overlay titlebars, native menus, vibrancy, dialogs); small binaries |
| Matrix SDK | **matrix-rust-sdk** (plain crate in the Tauri core) | Apache-2.0 | The reference SDK: Simplified Sliding Sync, native OIDC, vodozemac E2EE, Element X production pedigree. Used directly — no UniFFI/FFI bridge |
| Frontend | **Svelte 5** (SPA mode, no SSR) | MIT | Tauri-community fit (GitButler precedent), lean compiled output, Framework7's best flavor |
| Styling | **Tailwind CSS v4** design tokens | MIT | Foundation for the per-platform skin system |
| Headless primitives | **Bits UI** | MIT | Radix-equivalent for Svelte; accessibility for free |
| Mobile skin | **Framework7 Svelte** (v9) | MIT | Highest-fidelity iOS (Cupertino/iOS 26) & Material adaptive components; ships Messages + Photo Browser |
| Mobile skin (fallback) | **Konsta UI Svelte** | MIT | Tailwind presentation-only skin; less maintainer risk, all behavior DIY |
| Desktop skins | Per-OS token themes + Tauri native chrome | — | Fluent-inspired (Windows), hand-rolled HIG (macOS), libadwaita CSS vars (Linux) |
| Message list | **virtua** (Svelte virtualizer) | MIT | Virtualized, inverted chat timeline |
| JS ↔ Rust bridge | Tauri commands/events + Svelte stores | — | Reference: [tauri-plugin-matrix-svelte](https://github.com/IT-ess/tauri-plugin-matrix-svelte), [koushi-matrix](https://github.com/shinaoka/koushi-matrix) |
| Push infra | Self-hosted push gateway + FCM/APNs: unmodified **Sygnal**, or own minimal Rust gateway (M3 decision) | AGPL-3.0 if Sygnal — infrastructure only, not linked | Matrix requires app-vendor-owned gateway; AGPL doesn't reach the client (homeserver→gateway HTTP) |

## Architecture

```
┌─────────────────────────────────────────────────┐
│ Webview frontend (Svelte 5, SPA)                │
│  shared core: Tailwind tokens, Bits UI, stores, │
│  chat logic, virtua message list                │
│  ├─ mobile skin: Framework7 (iOS/MD adaptive)   │
│  └─ desktop skins: per-OS themes + Tauri chrome │
├────────── Tauri commands / events ──────────────┤
│ Rust core (tokio)                               │
│  matrix-sdk client · SyncService (sliding sync) │
│  vodozemac crypto · SQLite stores (sqlcipher)   │
├─────────────────────────────────────────────────┤
│ Platform: Tauri window chrome, native menus,    │
│ haptics, dialogs, tray · push (FCM/APNs)        │
└─────────────────────────────────────────────────┘
         │ Matrix Client-Server API (HTTPS)
   Homeserver · Sygnal push gateway (self-hosted)
```

Rules:

- The Matrix client lives **entirely in the Rust core**. The webview is a dumb renderer: Svelte stores mirror core state streamed over Tauri events; user intents go down as Tauri commands. Windowed/delta updates to bound IPC serialization cost.
- Exactly one `matrix_sdk::Client` per logged-in account, owned by the core.
- UI skins are the *only* platform-branched layer (~20% of UI code). All logic, state, and chat behavior is shared.

## Key decisions (and what was rejected)

1. **Tauri over Flutter / React Native / KMP.** The permissive-license requirement removed the two most production-proven mobile paths (matrix-dart-sdk, trixnity-messenger — both AGPL). RN has no viable E2EE path (matrix-js-sdk crypto is WASM; Hermes can't run it; official matrix-rn-sdk archived 2025). Tauri is the only stack pairing a top-tier permissively-licensed SDK with one codebase for 5 targets. Accepted risk: no production Tauri-*mobile* Matrix client exists yet.
2. **matrix-rust-sdk in the core, not a JS SDK in the webview.** matrix-js-sdk works in webviews but lacks sliding sync on mobile-grade quality and duplicates what the Rust SDK does better; direct crate use means zero FFI glue.
3. **Svelte over React.** Framework7 Svelte is first-class (F7 React is second-class; Ionic — F7's adaptive competitor — has no Svelte support at all); tauri-plugin-matrix-svelte is an existing reference for the exact bridge; GitButler proves Svelte scales on Tauri.
4. **Framework7 as mobile skin.** Highest mobile-native fidelity available in a webview (iOS 26 restyle in v9; Messages/Photo Browser components save weeks). Risks: single maintainer; desktop theme removed in v8 — irrelevant here since desktop gets its own skins. Fallback: Konsta UI.
5. **Per-OS desktop skins, not a "desktop UI kit".** No credible native-look web kits exist (Fluent UI React is React-only and Fluent≠WinUI; no maintained macOS HIG kit). Polished Tauri apps (GitButler, Cinny) hand-roll tokens over headless components + native chrome. Linux: import [libadwaita's documented CSS variables](https://github.com/GNOME/libadwaita/blob/main/doc/css-variables.md).

## Matrix protocol choices

- **Auth:** `m.login.password` is the only flow available today — `id.agentpod.dev` (Synapse 1.152.0) advertises no SSO or OIDC, and both `/_matrix/client/v1/auth_metadata` and the MSC2965 unstable path return 404. Native OIDC (MSC3861) remains the intended target but requires deploying matrix-authentication-service first. The client implements password login behind an `AuthProvider` trait so OIDC is additive. Guide: [areweoidcyet.com](https://areweoidcyet.com/).
- **Sync:** Simplified Sliding Sync (MSC4186) via the SDK's SyncService (native in Synapse ≥1.114; `org.matrix.simplified_msc3575` advertised on `id.agentpod.dev`); `/sync` v3 fallback for older servers.
- **E2EE:** SDK crypto (vodozemac). Cross-signing, SSSS key backup, emoji/SAS device verification. Never hand-roll crypto.
- **Media:** authenticated media endpoints (spec ≥1.11; available on `id.agentpod.dev`).
- **Push:** `event_id_only` pushes via own Sygnal → FCM/APNs; app fetches and decrypts content itself. iOS phase 2: Notification Service Extension in Swift linking the Rust SDK (Element X NSE as reference).

## Known risks & mitigations

| Risk | Mitigation |
|---|---|
| No shipped Tauri-mobile Matrix client — integration trailblazing | Prototype the push path *early* (M3 pulled forward in spirit: spike FCM/APNs before polishing UI) |
| iOS keyboard doesn't resize WKWebView — #1 "web tell" in a chat app | ~200 lines objc2 Rust resizing the webview frame ([tauri discussion #9368](https://github.com/orgs/tauri-apps/discussions/9368)); treat as core work, not polish |
| aws-lc-rs crashes on Android 16KB-page devices ([matrix-rust-sdk#6442](https://github.com/matrix-org/matrix-rust-sdk/issues/6442)) | Force `ring` TLS backend until upstream fix lands; Google Play requires 16KB support |
| IPC cost of streaming timelines to the webview | Windowed/delta event updates; reference IT-ess plugin's serialization approach |
| Framework7 single-maintainer risk | MIT-licensed and forkable; skin isolated to ~20% of UI; Konsta fallback |
| iOS push reliability without NSE (silent-push throttling) | Phase 1 accept; phase 2 native Swift NSE embedding matrix-rust-sdk |
| Webview ceiling ≈ 85–90% "native-adjacent" | Behavior budget below; per-engine QA (WebKit / WebView2 / WebKitGTK) |

## Native-feel behavior budget (non-negotiable checklist)

- iOS keyboard resize fix (Rust) and keyboard-aware composer
- Safe areas: `viewport-fit=cover` + `env(safe-area-inset-*)`
- Haptics via official `tauri-plugin-haptics` (long-press menus etc.)
- Native popup context menus (muda) with accelerators — no HTML dropdowns for OS-level actions
- Scrollbar discipline: untouched overlay scrollbars on macOS, `fluentOverlay` on Windows (Tauri ≥2.8)
- System font stack per platform; Dynamic Type via `-apple-system-body` hook on iOS
- Strict `user-select` discipline (off on chrome, on in message text)
- Per-engine visual QA incl. oldest supported distro WebKitGTK

## License compliance

All runtime dependencies are permissive: Tauri (MIT/Apache-2.0), matrix-rust-sdk (Apache-2.0), Svelte/Tailwind/Framework7/Konsta/Bits UI/virtua (MIT), or MPL-2.0 used unmodified. Thirteen MPL-2.0 crates arrive unavoidably with matrix-sdk (`eyeball`, `eyeball-im`, `imbl`, `imbl-sized-chunks`, `bitmaps`, `readlock`, `readlock-tokio`, `as_variant`) and Tauri (`cssparser`, `cssparser-macros`, `dtoa-short`, `selectors`, `option-ext`). MPL-2.0 is file-level copyleft and obliges publishing changes to those files; if you modify one, publish it. Strong and network copyleft (GPL / AGPL / LGPL) remain banned.
Caution: Element X apps and trixnity-messenger/Tammy are **AGPL-3.0 — read for reference, never copy code**.

**Push gateway (infrastructure, not a dependency):** the maintained [element-hq/sygnal](https://github.com/element-hq/sygnal) is **AGPL-3.0** (Element took over maintenance in 2025 and relicensed, as with Synapse); the original [matrix-org/sygnal](https://github.com/matrix-org/sygnal) is Apache-2.0 but archived/unmaintained. This does not contaminate supermessage: the client never talks to the gateway — the homeserver POSTs to it — and it runs as a separate service, not linked code. Stance, in order of preference:

1. Run Sygnal **unmodified** — no AGPL obligations arise without modification/distribution; if we ever modify it, publish the fork (trivial once this repo is public).
2. Fallback: fork the archived Apache-2.0 matrix-org/sygnal (permissive, but we own all patching).
3. M3 decision point: a **minimal own push gateway in Rust** — the Push Gateway API is a single endpoint (`POST /_matrix/push/v1/notify` → forward to FCM/APNs → report rejected pushkeys). A few hundred lines, fully license-clean; cost is owning FCM/APNs quirks.

## Milestones

- **M0 — spine:** Tauri scaffold; Rust core syncs a real account (OIDC + password); Svelte stores mirror room list/timeline; virtua message list renders; send/receive plaintext.
- **M1 — E2EE:** encrypted DMs, emoji verification, key backup bootstrap.
- **M2 — daily driver:** media send/receive, replies/reactions/edits, receipts/typing, iOS keyboard fix, Android 16KB/ring fix, F7 mobile skin + desktop skins in place.
- **M3 — push:** Sygnal deployment; FCM on Android; APNs phase 1; then iOS NSE.
- **M4 — breadth:** spaces, threads, multi-account, settings polish, store submissions.

## References

- [matrix-rust-sdk](https://github.com/matrix-org/matrix-rust-sdk) · [Tauri v2 docs](https://v2.tauri.app/) · [Framework7](https://framework7.io/) · [Bits UI](https://bits-ui.com/) · [virtua](https://github.com/inokawa/virtua)
- Bridge references: [tauri-plugin-matrix-svelte](https://github.com/IT-ess/tauri-plugin-matrix-svelte) · [koushi-matrix](https://github.com/shinaoka/koushi-matrix) · [Robrix (plain-crate mobile builds)](https://github.com/project-robius/robrix)
- Matrix: [Client-Server spec](https://spec.matrix.org/latest/client-server-api/) · [OIDC guide](https://areweoidcyet.com/) · [Sygnal](https://github.com/element-hq/sygnal) · [Element X iOS NSE](https://github.com/element-hq/element-x-ios) (AGPL — reference only)
- Tauri precedents: [GitButler](https://github.com/gitbutlerapp/gitbutler) · [Cinny desktop](https://github.com/cinnyapp/cinny-desktop)
