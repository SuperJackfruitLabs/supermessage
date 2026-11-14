---
title: Building from source
description: Build the desktop app, the iOS app and the Android app from a checkout, and the build-time settings that change what each one does.
---

The previews on GitHub Releases are built from `main` by CI. When you want something newer, or
want to change something, you build it yourself. All three apps share one Rust core, so every
path below starts by building that core for the platform; only the app around it differs.

## What every build needs

- [Rust](https://rustup.rs), stable.
- [Node](https://nodejs.org) 22 or later, and [pnpm](https://pnpm.io) at the version
  `package.json` pins in `packageManager`.
- A checkout:

```sh
git clone https://github.com/SuperJackfruitLabs/supermessage.git
cd supermessage
pnpm install --frozen-lockfile
```

## The desktop app

Install the [Tauri 2 prerequisites](https://v2.tauri.app/start/prerequisites/) for your system.
On Ubuntu, CI installs these:

```sh
sudo apt-get install -y --no-install-recommends \
  libwebkit2gtk-4.1-dev libappindicator3-dev librsvg2-dev libxdo-dev \
  libssl-dev patchelf build-essential file wget
```

Then run it:

```sh
pnpm tauri dev
```

That starts Vite on port 1420 and the app around it, and you have a working client signed in to
nothing yet. For installers, build a bundle:

```sh
pnpm tauri build
```

It ends by listing the installers it wrote, in the formats your system builds. CI builds the Mac
one with `--target universal-apple-darwin`, so one app runs on both kinds of Mac.

Do not judge the app by a bare `cargo build` binary. A debug build loads its interface from
`http://localhost:1420`, so without Vite running the window is empty and every call fails. It
looks broken, and it is only lonely.

## The iOS app

You need a Mac with Xcode and [XcodeGen](https://github.com/yonaskolb/XcodeGen). Build the core
as an XCFramework, generate the project, and run the tests on a simulator:

```sh
./scripts/build-xcframework.sh
cd apple && xcodegen generate && cd ..
xcodebuild test -project apple/Supermessage.xcodeproj -scheme Supermessage \
  -destination 'platform=iOS Simulator,name=iPhone 16 Pro'
```

Or open `apple/Supermessage.xcodeproj` and run the `Supermessage` scheme. Xcode never calls cargo,
so after a change to the Rust side, run `build-xcframework.sh` again; skip it and you get a link
error rather than an app quietly running old code.

A plain `xcodegen generate` leaves out push, the Notification Service Extension and the widgets,
because each changes what signing needs. The settings below turn them on.

## The Android app

You need the Android SDK, NDK `29.0.14206865`, JDK 21 and `cargo-ndk`:

```sh
cargo install cargo-ndk
rustup target add aarch64-linux-android armv7-linux-androideabi \
                  x86_64-linux-android i686-linux-android
export ANDROID_HOME="$HOME/Android/Sdk"
export NDK_HOME="$ANDROID_HOME/ndk/29.0.14206865"
```

Build the core's libraries once after cloning. They are not checked in (362 MB across four ABIs),
so a fresh checkout has the Kotlin bindings and nothing behind them:

```sh
./scripts/build-android-libs.sh
```

Then build the APKs:

```sh
cd android && ./gradlew :app:assembleDebug
```

One APK per ABI lands in `android/app/build/outputs/apk/debug/`. To build a single ABI, which is
much faster, set `ANDROID_ABIS="x86_64"` for both commands.

## Build-time settings

| Setting | Where | Default | What it does |
|---|---|---|---|
| `SM_PUSH_GATEWAY_URL` | `xcodegen generate` | empty | The push gateway's `/_matrix/push/v1/notify` URL. Empty, or anything but an absolute `https://` URL, means no pusher is registered. |
| `SM_PUSH` | `xcodegen generate` | off | The APNs entitlements, for push. |
| `SM_NSE` | `xcodegen generate` | off | The Notification Service Extension, which fetches and decrypts a pushed event. Useful only beside `SM_PUSH`. |
| `SM_EXTENSIONS` | `xcodegen generate` | off | The widgets, the Live Activity and the App Group they share. |
| `SM_NSE_FILTERING` | `xcodegen generate` | off | Apple's filtering entitlement, so the extension can drop a push instead of showing it quietly. Needs Apple's grant. |
| `ANDROID_ABIS` | `build-android-libs.sh` and Gradle | `arm64-v8a armeabi-v7a x86_64 x86` | Which ABIs to build. Both commands must agree. |
| `ANDROID_VERSION_CODE` | Gradle | the floor in `build.gradle.kts` | The `versionCode`, never below that floor. CI passes its run number. |

Each `SM_` flag is on when set to `YES`. The TestFlight workflow sets `SM_PUSH`, `SM_NSE` and
`SM_EXTENSIONS` to `YES` and `SM_PUSH_GATEWAY_URL` to the AgentPod hub's gateway.

## Before you open a pull request

`./scripts/ci-local.sh` runs CI's checks on your machine, job by job, so a push is not how you
find a failure. It takes `--only` with a list of jobs, such as `--only rust,frontend`. The Rust
part alone is:

```sh
cargo test --workspace
cargo fmt --all --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
```

## Next

- [Installing it](/run/install/): the builds CI makes, and signing in
- [Platform support](/reference/platforms/): what each app does today
