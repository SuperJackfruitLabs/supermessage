---
name: sjl-native-verification
description: Verify a Supermessage behavior or change across its shared Rust core, UniFFI bindings, SwiftUI, Compose, and Tauri consumers. Use for native parity, FFI drift, platform regressions, or device-evidence claims, not unrelated prose edits.
license: MIT
---

Establish the requested behavior, product revision, affected hosts and available
test environment. Read AGENTS.md and inspect both the UI caller and its core
implementation. Registration, generated declarations and a dated parity report
do not prove a reachable or working interaction.

## Trace the boundary

Use [the host verification map](references/hosts.md) to connect the Rust decision
or state to the FFI/Tauri boundary, native adapter and visible interaction. Keep
shared protocol and view-model decisions in the core. Inspect both Swift and
Kotlin generated bindings when the FFI surface changes. Inspect canonical tokens
and regenerate affected targets when visual assets or styles change.

Check actual host tools and architecture rather than assuming the machine named
in older repository instructions is the current one. A Linux-generated Swift
binding can be inspected without claiming an iOS build, signing or device run.

## Verify the changed behavior

Select checks for the affected boundary and run the repository's required checks.
For a regression, first show the test fails for the original defect or a targeted
mutation, then restore the correct implementation and record the pass. Repeat
ordering/concurrency mutations enough to expose scheduler-sensitive false passes,
as required by AGENTS.md. Preserve unrelated edits throughout.

For Android layout tests, use the repository's CI parity setup and both required
heights; record device/emulator identity, timezone and geometry. For iOS, name the
scheme and actual destination. Check required Rust native libraries before
claiming that generated bindings or a Gradle build establish runtime readiness.

Classify each result as source review, generated-file consistency, unit/contract
test, platform build, simulator/emulator interaction, physical-device interaction,
or live integration. One category cannot silently stand in for another. A tool
that is unavailable yields an explicit missing check, not a skipped success.

## Report the result

Summarize behavior and evidence per affected host, with revision/artifact,
commands, exit status, device or engine, observed interaction and remaining gaps.
Do not infer background push, encrypted-history recovery, gate resolution or
message delivery from a registered handler. Live room posting follows explicit
user authorization and the existing repository rule; preparing tests does not
send a message. Distribution and signing need their own artifact evidence.
