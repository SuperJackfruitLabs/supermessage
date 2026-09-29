// Compiled into the widget extension, which draws the Live Activity, and into
// the app so its previews render with the app's other previews.

import SwiftUI
import UIKit
import WidgetKit

#if !SM_WIDGET_EXTENSION
import SupermessageKit
#endif

/// Which clock the card's times read. `live` everywhere but a preview, which
/// freezes it so the preview gate renders the same frame twice.
enum FleetClock: Sendable {
    case live
    case frozen(Date)
}

/// An agent's cached picture, by Matrix user id. The store in the App Group
/// everywhere but a preview, which supplies its own.
extension EnvironmentValues {
    @Entry var fleetAvatar: @Sendable (String) -> UIImage? = { userId in
        AgentAvatarStore.imageData(for: userId).flatMap(UIImage.init(data:))
    }
}

/// The fleet on the Lock Screen (spec 2026-09-30, "A + C"): counts across the
/// top, one agent as the hero with its turn drawn as a track, and a compact
/// row for each of the others.
///
/// - **The header** counts ("3 working", "1 needs you", "1 done") beside a
///   fixed time — "Updated 9:41 PM", or "Finished 9:45 PM" — that never
///   ticks. Past the hub's stale date it says "No update since 9:41 PM"
///   rather than claiming anything is still moving.
/// - **The hero** is an owed decision, drawn by `WidgetDecisionCard` — the
///   one amber element — or else an agent: its face, what it is doing, whose
///   it is and how far, and a big clock that is the card's only moving part.
/// - **The track** runs Thinking → Tools → Writing → Done, filled to the
///   stage and marked where the turn is; red where a failed turn stopped.
/// - **Rows**, one line each, for everyone else listed.
///
/// The Lock Screen draws a Live Activity at most 160 points tall and crops the
/// rest without saying so. So the card is laid out through `ViewThatFits`,
/// giving up, in order, the stage labels, the rows, then the hero's subtitle —
/// never the header or the decision (spec 2026-09-30, B2).
///
/// Decides nothing: who is listed and in what order, their state, phase,
/// step and counts are the hub's, and which of them is the hero and how it
/// is worded is `FleetCard`'s.
struct FleetActivityCard: View {
    let state: FleetActivityAttributes.ContentState
    let isStale: Bool
    var clock: FleetClock = .live

    private var card: FleetCard { FleetCard(state) }

    /// Every way to lay the card out, most generous first.
    private struct Layout: Hashable {
        var rows: Int
        var stages: Bool
        var subtitle: Bool
        var questionLines = 2
        var tightDecision = false
    }

    private var layouts: [Layout] {
        switch card.hero {
        case .decision:
            return [
                Layout(rows: 0, stages: false, subtitle: true),
                Layout(rows: 0, stages: false, subtitle: true, questionLines: 1),
                Layout(rows: 0, stages: false, subtitle: true, questionLines: 1, tightDecision: true),
            ]
        case .agent, nil:
            let rows = min(3, card.rows.count)
            if rows == 0 {
                return [
                    Layout(rows: 0, stages: true, subtitle: true),
                    Layout(rows: 0, stages: false, subtitle: true),
                    Layout(rows: 0, stages: false, subtitle: false),
                ]
            }
            return stride(from: rows, through: 1, by: -1).map {
                Layout(rows: $0, stages: false, subtitle: true)
            } + [
                Layout(rows: 0, stages: false, subtitle: true),
                Layout(rows: 0, stages: false, subtitle: false),
            ]
        }
    }

    var body: some View {
        let card = self.card
        ViewThatFits(in: .vertical) {
            ForEach(layouts, id: \.self) { layout in
                content(card, layout)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .topLeading)
        // Past this, even the tightest layout would crop inside the Lock
        // Screen's 160 points; the card stops growing here instead.
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
    }

    private func content(_ card: FleetCard, _ layout: Layout) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            FleetHeader(card: card, isStale: isStale)
            switch card.hero {
            case .decision(let decision):
                WidgetDecisionCard(
                    decision: Self.widgetDecision(decision), compact: false, now: .now,
                    showsAge: false,
                    banner: .init(
                        face: AnyView(
                            FleetFace(
                                userId: state.agents.first(where: { $0.roomId == decision.roomId })?
                                    .mxid,
                                name: decision.agent, size: 30)),
                        questionLines: layout.questionLines, tight: layout.tightDecision))
            case .agent(let agent):
                FleetHero(
                    agent: agent, isStale: isStale, clock: clock, updated: state.updated,
                    subtitle: layout.subtitle, stages: layout.stages)
                if layout.rows > 0 {
                    FleetRows(agents: Array(card.rows.prefix(layout.rows)), isStale: isStale, clock: clock)
                }
            case nil:
                EmptyView()
            }
        }
        .frame(maxWidth: .infinity, alignment: .topLeading)
    }

    /// The hub's decision as the widgets hold one, so the one card that may
    /// draw amber draws it here too. Every option the hub sends is one a
    /// button may send (A4: "inline only"); whether it is still owed is
    /// asked of the snapshot when tapped (`WidgetAnswering`).
    static func widgetDecision(_ decision: FleetActivityAttributes.ContentState.Decision)
        -> WidgetSnapshot.Decision
    {
        WidgetSnapshot.Decision(
            kind: decision.kind == .gate ? .gate : .permission, roomId: decision.roomId,
            eventId: decision.eventId, agent: decision.agent, question: decision.question,
            options: decision.options.map {
                .init(id: $0.id, label: $0.label, inline: true, declines: $0.declines)
            },
            askedAtMs: 0)
    }
}

// MARK: - Header

/// "1 needs you  2 working            Updated 9:42 PM"
struct FleetHeader: View {
    let card: FleetCard
    let isStale: Bool

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            if card.counts.isEmpty {
                Text("All quiet")
                    .font(.caption)
                    .foregroundStyle(WidgetTheme.contentMuted)
            }
            ForEach(card.counts) { count in
                HStack(alignment: .firstTextBaseline, spacing: 4) {
                    Text("\(count.number)")
                        .font(.subheadline.weight(.bold).monospacedDigit())
                        .foregroundStyle(FleetTone.color(count.tone, isStale: false))
                    Text(count.word)
                        .font(.caption)
                        .foregroundStyle(WidgetTheme.content)
                }
                .lineLimit(1)
                .fixedSize()
            }
            Spacer(minLength: 4)
            ViewThatFits(in: .horizontal) {
                time
                Text(card.time, style: .time)
            }
            .font(.caption.monospacedDigit())
            .foregroundStyle(WidgetTheme.contentFaint)
            .lineLimit(1)
        }
    }

    /// Fixed, never ticking: the time is a fact about the last push, not a
    /// count of how long ago that was.
    private var time: Text {
        let at = Text(card.time, style: .time)
        if card.finished { return Text("Finished \(at)") }
        if isStale { return Text("No update since \(at)") }
        return Text("Updated \(at)")
    }
}

/// Tones to token colours. Amber is not among them: a count of decisions is
/// not a decision (docs/design-language.md §2).
enum FleetTone {
    static func color(_ tone: FleetCard.Tone, isStale: Bool) -> Color {
        switch tone {
        case .needsYou: return WidgetTheme.accent
        case .working: return isStale ? WidgetTheme.contentFaint : WidgetTheme.ok
        case .done: return WidgetTheme.ok
        case .failed: return WidgetTheme.danger
        }
    }
}

// MARK: - Hero

/// The hero: face (or finish mark), what it is doing, whose and how far, the
/// big clock, and the track.
struct FleetHero: View {
    typealias Agent = FleetActivityAttributes.ContentState.Agent

    let agent: Agent
    let isStale: Bool
    var clock: FleetClock = .live
    /// When the hub last updated the card: where a stale clock stops.
    let updated: Date
    var subtitle = true
    var stages = true

    var body: some View {
        let track = FleetCard.track(agent)
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .center, spacing: 10) {
                FleetFace(userId: agent.mxid, name: agent.name, size: 32, finish: finish)
                VStack(alignment: .leading, spacing: 1) {
                    Text(FleetCard.headline(agent))
                        .font(.callout.weight(.semibold))
                        .foregroundStyle(WidgetTheme.content)
                        .lineLimit(1)
                    if subtitle {
                        Text(FleetCard.subtitle(agent, stages: stages && track != nil))
                            .font(.caption)
                            .foregroundStyle(
                                agent.state == .failed ? WidgetTheme.danger : WidgetTheme.contentMuted)
                            .lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if agent.state == .working, let since = agent.sinceDate {
                    FleetClockText(since: since, isStale: isStale, clock: clock, updated: updated)
                        .font(.title2.weight(.semibold).monospacedDigit())
                }
            }
            if let track {
                FleetTrackView(track: track, isStale: isStale)
                if stages {
                    FleetStageLabels(track: track)
                }
            }
        }
    }

    private var finish: FleetFace.Finish? { FleetFace.finish(agent) }
}

/// How long the turn has run. Live, it ticks on its own between pushes
/// (`Text(timerInterval:)`) — the card's one moving part. Stale, it stops at
/// the last update, faint, since nobody knows what happened after.
struct FleetClockText: View {
    let since: Date
    let isStale: Bool
    let clock: FleetClock
    let updated: Date

    var body: some View {
        if isStale {
            Text(FleetTime.elapsed(since: since, now: max(since, updated)))
                .foregroundStyle(WidgetTheme.contentFaint)
        } else {
            switch clock {
            case .live:
                Text(timerInterval: since...Date.distantFuture, countsDown: false)
                    .multilineTextAlignment(.trailing)
                    .frame(maxWidth: 84, alignment: .trailing)
                    .foregroundStyle(WidgetTheme.content)
            case .frozen(let now):
                Text(FleetTime.elapsed(since: since, now: now))
                    .foregroundStyle(WidgetTheme.content)
            }
        }
    }
}

// MARK: - Track

/// The turn as a road: four stops, filled to where it is, with a marker at
/// the current position while it runs.
struct FleetTrackView: View {
    let track: FleetCard.Track
    let isStale: Bool
    /// The watch's form: no stops, a smaller marker.
    var small = false

    private var fill: Color {
        if track.failed { return WidgetTheme.danger }
        return isStale && track.moving ? WidgetTheme.contentFaint : WidgetTheme.ok
    }

    var body: some View {
        let height: CGFloat = small ? 12 : 16
        GeometryReader { geometry in
            let width = geometry.size.width
            let x = { (at: Double) in CGFloat(at) * width }
            ZStack(alignment: .leading) {
                Capsule()
                    .fill(WidgetTheme.border)
                    .frame(height: 4)
                Capsule()
                    .fill(fill)
                    .frame(width: max(4, x(track.position)), height: 4)
                if !small {
                    ForEach(FleetCard.Stage.allCases, id: \.self) { stage in
                        stop(stage)
                            .frame(width: 10, height: 10)
                            .position(x: x(stage.stop), y: height / 2)
                    }
                }
                if track.moving {
                    Circle()
                        .fill(fill)
                        .overlay(Circle().stroke(WidgetTheme.surface, lineWidth: 3))
                        .frame(width: height, height: height)
                        .position(x: x(track.position), y: height / 2)
                }
            }
            .frame(height: height)
        }
        .frame(height: height)
        // The stops at either end sit on the card's padding, not past it.
        .padding(.horizontal, small ? 6 : 5)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(track.label(track.stage))
    }

    @ViewBuilder
    private func stop(_ stage: FleetCard.Stage) -> some View {
        if track.failed && stage == track.stage {
            Circle().fill(WidgetTheme.danger)
        } else if track.reached(stage) {
            Circle().fill(isStale && track.moving ? WidgetTheme.contentFaint : WidgetTheme.ok)
        } else {
            Circle()
                .fill(WidgetTheme.surface)
                .overlay(Circle().strokeBorder(WidgetTheme.border, lineWidth: 2))
        }
    }
}

/// "Thinking   Tools 3/7   Writing   Done" — the current one in bold.
struct FleetStageLabels: View {
    let track: FleetCard.Track

    var body: some View {
        HStack(spacing: 0) {
            ForEach(FleetCard.Stage.allCases, id: \.self) { stage in
                if stage != .thinking { Spacer(minLength: 4) }
                let current = stage == track.stage
                Text(track.label(stage))
                    .font(.caption2.weight(current ? .semibold : .regular))
                    .foregroundStyle(
                        current
                            ? (track.failed ? WidgetTheme.danger : WidgetTheme.content)
                            : WidgetTheme.contentFaint)
                    .lineLimit(1)
                    .fixedSize()
            }
        }
    }
}

// MARK: - Rows

/// Everyone else, one line each, under a hairline.
struct FleetRows: View {
    let agents: [FleetActivityAttributes.ContentState.Agent]
    let isStale: Bool
    var clock: FleetClock = .live

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            ForEach(agents) { agent in
                FleetRow(agent: agent, isStale: isStale, clock: clock)
            }
        }
        .padding(.top, 6)
        .overlay(alignment: .top) {
            Rectangle().fill(WidgetTheme.border).frame(height: 1)
        }
    }
}

/// One agent: its face (or how its turn ended), "Name · step", a thin bar
/// while its tools run, and "3/7" or how long it has run.
struct FleetRow: View {
    typealias Agent = FleetActivityAttributes.ContentState.Agent

    let agent: Agent
    let isStale: Bool
    var clock: FleetClock = .live

    var body: some View {
        HStack(alignment: .center, spacing: 7) {
            FleetFace(userId: agent.mxid, name: agent.name, size: 18, finish: finish)
            VStack(alignment: .leading, spacing: 2) {
                (Text(agent.name).foregroundStyle(WidgetTheme.content)
                    + Text(" · ").foregroundStyle(WidgetTheme.contentFaint)
                    + Text(FleetCard.rowLine(agent)).foregroundStyle(
                        agent.state == .failed ? WidgetTheme.danger : WidgetTheme.contentMuted))
                    .font(.caption)
                    .lineLimit(1)
                if agent.state == .working, let progress = FleetCard.progress(agent) {
                    FleetBar(progress: progress, isStale: isStale)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            meta
                .font(.caption.monospacedDigit())
                .foregroundStyle(WidgetTheme.contentFaint)
                .lineLimit(1)
        }
    }

    @ViewBuilder
    private var meta: some View {
        if let count = FleetCard.toolCount(agent) {
            Text(count)
        } else if agent.state == .working, !isStale, let since = agent.sinceDate {
            switch clock {
            case .live:
                Text(timerInterval: since...Date.distantFuture, countsDown: false)
                    .multilineTextAlignment(.trailing)
                    .frame(maxWidth: 56, alignment: .trailing)
            case .frozen(let now):
                Text(FleetTime.elapsed(since: since, now: now))
            }
        }
    }

    private var finish: FleetFace.Finish? { FleetFace.finish(agent) }
}

/// A row's thin progress bar.
struct FleetBar: View {
    let progress: Double
    let isStale: Bool

    var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .leading) {
                Capsule().fill(WidgetTheme.border)
                Capsule()
                    .fill(isStale ? WidgetTheme.contentFaint : WidgetTheme.ok)
                    .frame(width: geometry.size.width * CGFloat(min(1, max(0, progress))))
            }
        }
        .frame(height: 3)
    }
}

// MARK: - Faces

/// An agent's face: its cached avatar (`AgentAvatarStore`, by user id), else
/// its initial on a tinted circle — or, once its turn has ended, a check or
/// an exclamation mark, like a delivery's "Delivered".
struct FleetFace: View {
    enum Finish { case done, failed }

    let userId: String?
    let name: String
    let size: CGFloat
    var finish: Finish?

    @Environment(\.fleetAvatar) private var avatar

    /// A check or an exclamation mark once the agent's turn has ended.
    static func finish(_ agent: FleetActivityAttributes.ContentState.Agent) -> Finish? {
        switch agent.state {
        case .done: .done
        case .failed: .failed
        default: nil
        }
    }

    var body: some View {
        Group {
            if let finish {
                Circle()
                    .fill(finish == .done ? WidgetTheme.ok : WidgetTheme.danger)
                    .overlay {
                        Image(systemName: finish == .done ? "checkmark" : "exclamationmark")
                            .font(.system(size: size * 0.48, weight: .bold))
                            .foregroundStyle(WidgetTheme.surface)
                    }
            } else if let userId, let image = avatar(userId) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .clipShape(Circle())
            } else {
                Circle()
                    .fill(WidgetTheme.accentSoft)
                    .overlay {
                        Text(name.prefix(1).uppercased())
                            .font(.system(size: size * 0.42, weight: .semibold))
                            .foregroundStyle(WidgetTheme.accent)
                    }
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

// MARK: - The watch

/// The card on the watch's Smart Stack (watchOS 11 shows the phone's Live
/// Activities there): the hero and its track, nothing else (spec 2026-09-30,
/// B3). An owed decision is said, amber, without its buttons — a tap opens
/// it on the phone.
struct FleetWatchCard: View {
    let state: FleetActivityAttributes.ContentState
    let isStale: Bool
    var clock: FleetClock = .live

    var body: some View {
        let card = FleetCard(state)
        Group {
            switch card.hero {
            case .decision(let decision):
                WidgetDecisionCard(
                    decision: FleetActivityCard.widgetDecision(decision), compact: false, now: .now,
                    showsAge: false,
                    banner: .init(
                        face: AnyView(
                            FleetFace(
                                userId: state.agents.first(where: { $0.roomId == decision.roomId })?
                                    .mxid,
                                name: decision.agent, size: 20)),
                        questionLines: 2, answers: false))
            case .agent(let agent):
                hero(agent)
            case nil:
                Text("All quiet")
                    .font(.caption)
                    .foregroundStyle(WidgetTheme.contentMuted)
            }
        }
        .padding(.horizontal, 11)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .topLeading)
    }

    private func hero(_ agent: FleetActivityAttributes.ContentState.Agent) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                FleetFace(userId: agent.mxid, name: agent.name, size: 20, finish: FleetFace.finish(agent))
                Text(FleetCard.headline(agent))
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(WidgetTheme.content)
                    .lineLimit(1)
            }
            if let track = FleetCard.track(agent) {
                FleetTrackView(track: track, isStale: isStale, small: true)
            }
            HStack(spacing: 4) {
                Text(watchLine(agent))
                    .foregroundStyle(agent.state == .failed ? WidgetTheme.danger : WidgetTheme.contentMuted)
                    .lineLimit(1)
                Spacer(minLength: 2)
                if agent.state == .working, let since = agent.sinceDate {
                    FleetClockText(since: since, isStale: isStale, clock: clock, updated: state.updated)
                }
            }
            .font(.caption2.monospacedDigit())
        }
    }

    /// "Artistic Lyra · 3/7" while working; how it ended once it has.
    private func watchLine(_ agent: FleetActivityAttributes.ContentState.Agent) -> String {
        switch agent.state {
        case .working, .needsYou:
            return [agent.name, FleetCard.toolCount(agent)].compactMap { $0 }.joined(separator: " · ")
        default:
            return FleetCard.subtitle(agent, stages: true)
        }
    }
}

/// Times for a frozen clock, worded like the system's own live ones.
enum FleetTime {
    /// "2:04" — as `Text(timerInterval:)` says it.
    static func elapsed(since: Date, now: Date) -> String {
        let formatter = DateComponentsFormatter()
        formatter.unitsStyle = .positional
        formatter.zeroFormattingBehavior = .pad
        formatter.allowedUnits = now.timeIntervalSince(since) >= 3600 ? [.hour, .minute, .second] : [.minute, .second]
        let text = formatter.string(from: max(0, now.timeIntervalSince(since))) ?? ""
        // Positional pads the leading unit too ("02:04"); the timer does not.
        return text.hasPrefix("0") && text.count > 4 ? String(text.dropFirst()) : text
    }
}

/// The Dynamic Island's small forms: how many need the reader (or are
/// working), and who is on top. Not the priority — the operator's phone has
/// no island — so kept to that.
struct FleetIslandCount: View {
    let state: FleetActivityAttributes.ContentState

    var body: some View {
        HStack(spacing: 4) {
            Circle()
                .fill(state.needsYou > 0 ? WidgetTheme.accentSoft : WidgetTheme.ok)
                .frame(width: 7, height: 7)
            Text("\(state.needsYou > 0 ? state.needsYou : state.working)")
                .font(.caption.weight(.semibold).monospacedDigit())
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(state.pulse)
    }
}
