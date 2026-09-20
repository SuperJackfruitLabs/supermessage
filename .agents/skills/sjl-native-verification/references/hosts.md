# Supermessage host verification map

Resolve these paths in the selected product checkout. The skill does not ship
native SDKs, generated bindings, application credentials or device state.

| Surface | Source and evidence |
|---|---|
| Shared behavior | crates/supermessage-core; workspace tests exercise protocol/view-model ownership |
| Native API | crates/supermessage-ffi; both checked-in Swift and Kotlin bindings must match |
| Desktop | src-tauri commands plus src routes/stores; Tauri must have a running dev server or embedded frontend |
| iOS | apple: generated FFI, SupermessageKit state and Supermessage SwiftUI targets; actual Xcode scheme/destination |
| Android | android/core bindings and native libraries; kit domain state; app Compose UI and measured pane width |
| Design | design/tokens.toml plus the token/mark generators described in AGENTS.md; generated outputs are not the source |

Useful commands, selected according to the change and host:

- `cargo test --workspace` includes dependency members; running only from the
  Tauri shell does not exercise every core test.
- `pnpm check`, `pnpm test`, and `pnpm build` cover the frontend, not native IPC.
- `pnpm tauri dev` runs the development shell with its frontend; a bare debug
  binary without its dev server is not a valid Tauri UI check.
- Android Gradle commands run from android/. Check native libraries and the
  current ABI/NDK requirements first. Host JVM/core tests may also need a host
  Rust library, separate from Android cross-compiled libraries.
- Use the current Xcode project and installed simulator identifiers; do not
  assume a named historical simulator exists locally.

Current repository constraints include one ordered event consumer, blocking Core
calls off cooperative Swift executors, and shared Rust decisions instead of
per-host copies. Verify those boundaries for relevant concurrency fixes.

Tauri WebDriver is supported on Linux and Windows; macOS has no WKWebView driver
for that route. Native UI verification needs the actual platform mechanism.
A browser-only pass does not exercise Tauri commands. A simulator pass does not
prove physical-device behavior or public mobile distribution.
