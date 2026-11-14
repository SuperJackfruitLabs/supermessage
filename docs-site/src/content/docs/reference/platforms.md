---
title: Platform support
description: What each of the five platforms runs, how it is packaged and signed, and which notification, widget and voice features it has.
---

One Rust core does the Matrix work on every platform. The desktop apps are a Svelte interface in
Tauri's webview; iPhone and iPad run a SwiftUI app; Android runs a Compose app. Both mobile apps
call the core through UniFFI.

This table is what the code implements. Which rows have been checked on a real device, and which
are pending, is recorded in the status table of the
[README](https://github.com/SuperJackfruitLabs/supermessage#status-early-with-active-desktop-and-native-clients).

## Builds

| | macOS | Windows | Linux | iOS and iPadOS | Android |
|---|---|---|---|---|---|
| Minimum | Not stated in the build | Not stated in the build | The glibc of Ubuntu 22.04 | iOS 18.0 | Android 12 (API 31) |
| Architectures | Apple silicon and Intel, in one universal app | x64 | x86_64 | arm64 | arm64-v8a, armeabi-v7a, x86_64, x86 |
| Packages | `.dmg`, `.app.tar.gz` | Setup `.exe`, `.msi` | `.deb`, `.rpm`, `.AppImage` | TestFlight, by invitation | One `.apk` per architecture |
| Signing | Unsigned, not notarised | Unsigned | Unsigned | App Store distribution | Debug-signed, for sideloading |
| Where | GitHub Releases | GitHub Releases | GitHub Releases | TestFlight | GitHub Releases |
| Interface | Svelte in Tauri | Svelte in Tauri | Svelte in Tauri | SwiftUI | Compose |

## Notifications

| | macOS | Windows | Linux | iOS and iPadOS | Android |
|---|---|---|---|---|---|
| While the app runs | Yes | Yes | Yes | Yes | Yes |
| While the app is closed | No | No | No | Push through the hub's gateway | Push through a UnifiedPush distributor |
| Answer from the notification | No, informational | No, informational | Yes, where the notification server offers actions | Yes | Yes |

## Beyond the window

| | macOS | Windows | Linux | iOS and iPadOS | Android |
|---|---|---|---|---|---|
| Tray, and keep running when closed | Built, not verified | Built, not verified | Yes, where a StatusNotifier tray is running | Not applicable | Not applicable |
| Home Screen widgets | No | No | No | Needs you and Agents | Needs you and Agents |
| A running turn, live | In the room | In the room | In the room, and the tray's working line | The fleet Live Activity on the Lock Screen | A quiet ongoing notification |
| `supermessage://open?room=` links | Not applicable | Not applicable | Not applicable | Yes | Yes |

## Voice

| | macOS | Windows | Linux | iOS and iPadOS | Android |
|---|---|---|---|---|---|
| Record voice messages | Expected to be hidden: the microphone shows only where the webview can record Ogg/Opus | The same | Yes, with GStreamer's base and good plugins | Yes | Yes |

A recorded voice message is sent as Ogg/Opus, with its length and a waveform.

## The same everywhere

These come from the core, so no platform differs: password sign-in, sliding sync, end-to-end
encryption with key backup and recovery, how every timeline row and suite card is drawn, the
roster's order and states, and how a notification is worded.
