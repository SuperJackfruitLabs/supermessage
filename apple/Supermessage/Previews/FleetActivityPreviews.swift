#if DEBUG
import SupermessageKit
import SwiftUI
import UIKit

/// The fleet Live Activity, drawn in the app so the preview gate renders it
/// (`scripts/snapshot-previews.sh`): the Lock Screen card in every state of
/// the approved "A + C" mockups (docs/superpowers/specs/
/// 2026-09-30-fleet-card-mockups.html), and its small form on the watch.
/// The cards are the widget extension's own views
/// (`apple/SupermessageWidgets/Views`, compiled into both); only the frames
/// around them stand in for the Lock Screen and the Smart Stack.
///
/// **Sized for the iPhone 13 mini**, the operator's phone, which has no
/// Dynamic Island — so this card is all of the Live Activity there: 344
/// points wide, as the mockups measure it, and at most 160 tall. The frame
/// proposes exactly that and clips, and a dashed line marks the limit, so a
/// card that does not fit shows as cropped here rather than on the phone.
///
/// The clock is frozen (`FleetClock.frozen`), so "2:04" comes out the same on
/// every render, and the header's "9:41 PM" is a fixed moment in UTC (the
/// gate's zone). The avatars are stand-ins for the Guild's, drawn from the
/// palette, supplied through `fleetAvatar` as the App Group's files would be.
private let now = Date(timeIntervalSince1970: 1_790_718_135)  // 2026-09-29 21:42:15 UTC
private let width: CGFloat = 344
private let limit: CGFloat = 160

private struct LockScreenCard: View {
    let label: String
    let state: FleetActivityAttributes.ContentState
    var isStale = false

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label)
                .font(.caption2.weight(.medium))
                .foregroundStyle(.white.opacity(0.75))
            // Offered the Lock Screen's 160 points, the card takes the height
            // of the layout that fits — as ActivityKit sizes it — and the
            // rest of the slot shows the wallpaper and the limit.
            FleetActivityCard(state: state, isStale: isStale, clock: .frozen(now))
                .frame(width: width)
                .background(WidgetTheme.surface)
                .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
                .frame(height: limit, alignment: .top)
                .clipped()
                .overlay(alignment: .bottom) {
                    Rectangle()
                        .stroke(style: StrokeStyle(lineWidth: 1, dash: [4, 3]))
                        .foregroundStyle(.white.opacity(0.45))
                        .frame(height: 1)
                }
        }
    }
}

/// A Lock Screen's wallpaper, dark enough to read a card against in either
/// appearance.
private struct Wallpaper<Content: View>: View {
    @ViewBuilder let content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 12) { content }
            .padding(12)
            .background(Color(red: 0.10, green: 0.10, blue: 0.20))
            .environment(\.fleetAvatar, Faces.image)
    }
}

/// Stand-ins for the Guild's pictures: a diagonal wash between two palette
/// colours, one pair per agent, as the App Group's 64×64 files would be.
private enum Faces {
    static func pair(_ userId: String) -> (Color, Color)? {
        let p = ThemeTokens.dark
        switch userId {
        case "@agent_artistic-lyra:hs": return (p.accent, p.danger)
        case "@agent_research-ray:hs": return (p.accent, p.ok)
        case "@agent_coder-kai:hs": return (p.ok, p.contentMuted)
        default: return nil
        }
    }

    @Sendable static func image(_ userId: String) -> UIImage? {
        guard let pair = pair(userId) else { return nil }
        let size = CGSize(width: 64, height: 64)
        return UIGraphicsImageRenderer(size: size).image { context in
            let colors = [UIColor(pair.0).cgColor, UIColor(pair.1).cgColor] as CFArray
            let gradient = CGGradient(
                colorsSpace: CGColorSpace(name: CGColorSpace.sRGB), colors: colors, locations: [0, 1])!
            context.cgContext.drawLinearGradient(
                gradient, start: .zero, end: CGPoint(x: size.width, y: size.height), options: [])
        }
    }
}

private enum Fleet {
    typealias State = FleetActivityAttributes.ContentState
    static let at = now.timeIntervalSince1970

    static let lyra = State.Agent(
        roomId: "!lyra:hs", mxid: "@agent_artistic-lyra:hs", name: "Artistic Lyra",
        state: .working, phase: .tools, step: "Running the tests", completed: 3, total: 7,
        since: at - 124)
    static let kai = State.Agent(
        roomId: "!kai:hs", mxid: "@agent_coder-kai:hs", name: "Coder Kai", state: .working,
        phase: .tools, step: "Reading widget.rs", completed: 1, total: 4, since: at - 60)
    /// No cached picture: drawn as its initial.
    static let atlas = State.Agent(
        roomId: "!atlas:hs", mxid: "@agent_atlas:hs", name: "Atlas", state: .working,
        phase: .thinking, step: "Thinking", since: at - 20)
    static let ray = State.Agent(
        roomId: "!ray:hs", mxid: "@agent_research-ray:hs", name: "Research Ray", state: .needsYou,
        step: "Waiting for you", since: at - 12)
    static let doneLyra = State.Agent(
        roomId: "!lyra:hs", mxid: "@agent_artistic-lyra:hs", name: "Artistic Lyra", state: .done,
        step: "Done · 7 steps", completed: 7, total: 7, since: at - 400, endedAt: at - 163)
    static let failedQuill = State.Agent(
        roomId: "!quill:hs", mxid: "@agent_writer-quill:hs", name: "Writer Quill", state: .failed,
        step: "Failed at step 4 of 7", completed: 4, total: 7, since: at - 480, endedAt: at - 408)
    static let lastQuill = State.Agent(
        roomId: "!quill:hs", mxid: "@agent_writer-quill:hs", name: "Writer Quill", state: .failed,
        step: "Failed at step 4 of 7", completed: 4, total: 7, since: at - 72, endedAt: at)

    static let decision = State.Decision(
        roomId: "!ray:hs", eventId: "$perm1", agent: "Research Ray", kind: .permission,
        question: "Run git push origin main?",
        options: [
            .init(id: "Allow once", label: "Allow once", declines: false),
            .init(id: "Reject", label: "Reject", declines: true),
        ])

    static let one = State(agents: [lyra], working: 1, updatedAt: at - 60)
    static let three = State(agents: [lyra, kai, atlas], working: 3, updatedAt: at - 60)
    static let asked = State(
        agents: [ray, lyra, kai], decision: decision, needsYou: 1, working: 2, updatedAt: at)
    static let finished = State(agents: [doneLyra, failedQuill], updatedAt: at)
    static let failed = State(agents: [lastQuill], updatedAt: at)
    /// The hub's stale date has passed with an agent working: no clock
    /// moves, nothing claims progress — and a failed row keeps its red.
    static let stale = State(
        agents: [
            State.Agent(
                roomId: "!lyra:hs", mxid: "@agent_artistic-lyra:hs", name: "Artistic Lyra",
                state: .working, phase: .tools, step: "Running the tests", completed: 3, total: 7,
                since: at - 20 * 60 - 124),
            failedQuill,
        ], working: 1, updatedAt: at - 20 * 60)
}

private struct Cards: View {
    var body: some View {
        Wallpaper {
            LockScreenCard(label: "One agent working", state: Fleet.one)
            LockScreenCard(label: "Three agents working", state: Fleet.three)
            LockScreenCard(label: "Needs you", state: Fleet.asked)
            LockScreenCard(label: "Finished, with a failed row", state: Fleet.finished)
            LockScreenCard(label: "Failed", state: Fleet.failed)
            LockScreenCard(label: "Stale", state: Fleet.stale, isStale: true)
        }
    }
}

#Preview("Fleet card, A + C") {
    Cards()
}

#Preview("Fleet card, A + C, dark") {
    Cards().preferredColorScheme(.dark)
}

#Preview("Fleet card, A + C, larger text") {
    Cards().dynamicTypeSize(.xLarge)
}

#Preview("Fleet card, A + C, larger text, dark") {
    Cards().dynamicTypeSize(.xLarge).preferredColorScheme(.dark)
}

/// Past the sizes the Lock Screen is tested at: the card gives up the stage
/// labels, then the rows, then the hero's subtitle, and keeps the header and
/// the decision whole (spec 2026-09-30, B2).
#Preview("Fleet card, A + C, accessibility text") {
    Wallpaper {
        LockScreenCard(label: "One agent working", state: Fleet.one)
        LockScreenCard(label: "Three agents working", state: Fleet.three)
        LockScreenCard(label: "Needs you", state: Fleet.asked)
        LockScreenCard(label: "Finished, with a failed row", state: Fleet.finished)
    }
    .dynamicTypeSize(.accessibility1)
}

/// The watch's Smart Stack: a small Live Activity, about 170 points wide on
/// a 45 mm watch, on black.
private struct WatchStack: View {
    let state: FleetActivityAttributes.ContentState

    var body: some View {
        FleetWatchCard(state: state, isStale: false, clock: .frozen(now))
            .frame(width: 170)
            .background(WidgetTheme.surface)
            .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            .padding(12)
            .background(Color.black, in: RoundedRectangle(cornerRadius: 34, style: .continuous))
    }
}

#Preview("Fleet card, on the watch") {
    Wallpaper {
        WatchStack(state: Fleet.one)
        WatchStack(state: Fleet.asked)
        WatchStack(state: Fleet.finished)
    }
    .preferredColorScheme(.dark)
}
#endif
