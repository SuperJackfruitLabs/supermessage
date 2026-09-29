#if DEBUG
import SupermessageKit
import SwiftUI

/// The fleet Live Activity's Lock Screen card, drawn in the app so the
/// preview gate renders it (`scripts/snapshot-previews.sh`). The card is the
/// widget extension's own view (`apple/SupermessageWidgets/Views`, compiled
/// into both); only the frame is a stand-in for the Lock Screen.
///
/// **Sized for the iPhone 13 mini**, the operator's phone, which has no
/// Dynamic Island — so this card is all of the Live Activity there. Its Lock
/// Screen gives a Live Activity the screen's width less the margins, 353
/// points, and at most 160 points of height; the frame proposes exactly that
/// and clips, so a card that does not fit shows as cropped here rather than
/// on the phone.
///
/// The clock is frozen (`FleetClock.frozen`), so "12 sec ago" and "2:04"
/// come out the same on every render.
private let now = Date(timeIntervalSince1970: 1_790_670_135)

private struct LockScreenCard: View {
    let state: FleetActivityAttributes.ContentState
    var isStale = false

    var body: some View {
        // Offered the Lock Screen's 160 points, it takes the height of the
        // rows that fit — as ActivityKit sizes it — and the rest of the slot
        // shows the wallpaper.
        FleetActivityCard(state: state, isStale: isStale, clock: .frozen(now))
            .frame(width: 353)
            .background(WidgetTheme.surface)
            .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
            .frame(height: 160, alignment: .top)
            .clipped()
    }
}

/// A Lock Screen's wallpaper, dark enough to read a card against in either
/// appearance.
private struct Wallpaper<Content: View>: View {
    @ViewBuilder let content: Content
    var body: some View {
        VStack(spacing: 12) { content }
            .padding(11)
            .background(Color(red: 0.16, green: 0.18, blue: 0.26))
    }
}

private enum Fleet {
    typealias State = FleetActivityAttributes.ContentState
    static let at = now.timeIntervalSince1970

    static let ray = State.Agent(
        roomId: "!ray:hs", name: "Research Ray", state: .needsYou, step: "Waiting for you",
        since: at - 135)
    static let lyra = State.Agent(
        roomId: "!lyra:hs", name: "Artistic Lyra", state: .working, step: "Running the tests",
        completed: 3, total: 7, since: at - 124)
    static let quill = State.Agent(
        roomId: "!quill:hs", name: "Writer Quill", state: .failed, step: "Failed at step 4 of 7",
        completed: 4, total: 7, since: at - 635)
    static let atlas = State.Agent(
        roomId: "!atlas:hs", name: "Atlas", state: .working,
        step: "Reading crates/supermessage-core/src/widget.rs", completed: 11, total: 12,
        since: at - 3_725)
    static let ganesha = State.Agent(
        roomId: "!ganesha:hs", name: "Ganesha", state: .done, step: "Done · 7 steps",
        completed: 7, total: 7, since: at - 300)

    static let decision = State.Decision(
        roomId: "!ray:hs", eventId: "$perm1", agent: "Research Ray", kind: .permission,
        question: "Run git push origin main?",
        options: [
            .init(id: "Allow once", label: "Allow once", declines: false),
            .init(id: "Reject", label: "Reject", declines: true),
        ])

    static let gate = State.Decision(
        roomId: "!board:hs", eventId: "$gate", agent: "Launch board", kind: .gate,
        question: "Approve \"Ship v2 to production\" — the release notes and the migration are ready?",
        options: [
            .init(id: "approve", label: "Approve", declines: false),
            .init(id: "reject", label: "Reject", declines: true),
        ])

    /// The shared fixture's state (`fixtures/fleet-content-state.json`).
    static let asked = State(
        agents: [ray, lyra, quill], more: 1, decision: decision, needsYou: 1, working: 1,
        updatedAt: at - 12)
    static let three = State(
        agents: [lyra, atlas, quill], more: 0, needsYou: 0, working: 2, updatedAt: at - 12)
    static let one = State(agents: [lyra], needsYou: 0, working: 1, updatedAt: at - 3)
    static let gated = State(
        agents: [atlas], decision: gate, needsYou: 1, working: 1, updatedAt: at - 40)
    static let done = State(
        agents: [ganesha, quill], needsYou: 0, working: 0, updatedAt: at - 95)
}

private struct Cards: View {
    var body: some View {
        Wallpaper {
            LockScreenCard(state: Fleet.asked)
            LockScreenCard(state: Fleet.three)
            LockScreenCard(state: Fleet.one)
        }
    }
}

#Preview("Fleet card, mini") {
    Cards()
}

#Preview("Fleet card, mini, dark") {
    Cards().preferredColorScheme(.dark)
}

#Preview("Fleet card, mini, larger text") {
    Cards().dynamicTypeSize(.xLarge)
}

#Preview("Fleet card, mini, larger text, dark") {
    Cards().dynamicTypeSize(.xLarge).preferredColorScheme(.dark)
}

#Preview("Fleet card, gate, stale, all done") {
    Wallpaper {
        LockScreenCard(state: Fleet.gated)
        LockScreenCard(state: Fleet.three, isStale: true)
        LockScreenCard(state: Fleet.done)
    }
    .dynamicTypeSize(.xLarge)
}

#Preview("Fleet card, gate, stale, all done, dark") {
    Wallpaper {
        LockScreenCard(state: Fleet.gated)
        LockScreenCard(state: Fleet.three, isStale: true)
        LockScreenCard(state: Fleet.done)
    }
    .preferredColorScheme(.dark)
}
#endif
