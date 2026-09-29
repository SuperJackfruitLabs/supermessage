// Compiled into the widget extension, which draws the Live Activity, and into
// the app so its previews render with the app's other previews.

import SwiftUI
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

/// The fleet on the Lock Screen: what needs the reader, and what each agent
/// is doing now (spec 2026-09-29, B3).
///
/// Laid out for the Lock Screen of the smallest phone (an iPhone 13 mini, no
/// Dynamic Island, so this *is* the card) at larger text:
///
/// - **One line of header:** the fleet in words ("2 working · 1 needs you")
///   and how fresh this is ("12 sec ago"), or "Waiting for updates" once the
///   hub's stale date passes — never a frozen "working".
/// - **The decision on top**, drawn by `WidgetDecisionCard` — the one amber
///   element, with the buttons that answer it in place.
/// - **Then up to three agents**, one dot and two short lines each: the name
///   and what it is at (steps done, and a clock that ticks without a push),
///   then the step it is on or how its turn ended.
///
/// The Lock Screen gives a Live Activity 160 points at most, and a cropped
/// card loses its last line without saying so. So, as the widgets do
/// (`AgentsWidgetView`), the agents drop one at a time through `ViewThatFits`
/// until the card fits, and the rest are counted ("2 more").
///
/// Decides nothing: the agents, their order, the state, the step and the
/// counts are the hub's.
struct FleetActivityCard: View {
    let state: FleetActivityAttributes.ContentState
    let isStale: Bool
    var clock: FleetClock = .live

    var body: some View {
        let most = min(3, state.agents.count)
        ViewThatFits(in: .vertical) {
            ForEach((0...most).reversed(), id: \.self) { rows in
                card(rows: rows, counted: true)
            }
            card(rows: 0, counted: false)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .topLeading)
    }

    private func card(rows: Int, counted: Bool) -> some View {
        let unlisted = state.agents.count - rows + state.more
        return VStack(alignment: .leading, spacing: 8) {
            header
            if let decision = state.decision {
                WidgetDecisionCard(
                    decision: FleetActivityCard.widgetDecision(decision), compact: false,
                    now: now, showsAge: false)
            }
            if rows > 0 {
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(state.agents.prefix(rows)) { agent in
                        FleetAgentRow(agent: agent, isStale: isStale, clock: clock)
                    }
                }
            }
            if counted, unlisted > 0 {
                Text(unlisted == 1 ? "1 more agent" : "\(unlisted) more agents")
                    .font(.caption2)
                    .foregroundStyle(WidgetTheme.contentFaint)
                    .lineLimit(1)
            }
        }
        .frame(maxWidth: .infinity, alignment: .topLeading)
    }

    private var now: Date {
        switch clock {
        case .live: return .now
        case .frozen(let date): return date
        }
    }

    private var header: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text(state.pulse)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(WidgetTheme.content)
                .lineLimit(1)
                .layoutPriority(1)
            Spacer(minLength: 4)
            freshness
                .font(.caption)
                .foregroundStyle(WidgetTheme.contentFaint)
                .lineLimit(1)
        }
    }

    @ViewBuilder
    private var freshness: some View {
        if isStale {
            Text("Waiting for updates")
        } else {
            switch clock {
            case .live:
                Text("\(Text(state.updated, style: .relative)) ago")
                    .multilineTextAlignment(.trailing)
            case .frozen(let now):
                Text("\(FleetTime.ago(state.updated, now: now)) ago")
            }
        }
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

/// One agent: a dot, its name and what it is at, and under them the step it
/// is on or how its turn ended.
struct FleetAgentRow: View {
    typealias Agent = FleetActivityAttributes.ContentState.Agent

    let agent: Agent
    let isStale: Bool
    var clock: FleetClock = .live

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Circle()
                    .fill(dot)
                    .frame(width: 7, height: 7)
                    .alignmentGuide(.firstTextBaseline) { $0[.bottom] - 1 }
                Text(agent.name)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(WidgetTheme.content)
                    .lineLimit(1)
                Spacer(minLength: 4)
                meta
                    .font(.caption.monospacedDigit())
                    .lineLimit(1)
            }
            if let detail {
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(agent.state == .failed ? WidgetTheme.danger : WidgetTheme.contentMuted)
                    .lineLimit(1)
                    .padding(.leading, 13)
            }
        }
    }

    /// What it is at, on the right. A working agent's clock ticks on its
    /// own (`Text(timerInterval:)`), so it moves between pushes — unless the
    /// card is stale, when a moving clock would claim progress nobody knows.
    @ViewBuilder
    private var meta: some View {
        switch agent.state {
        case .working:
            HStack(spacing: 4) {
                if let completed = agent.completed, let total = agent.total, total > 0 {
                    Text("\(completed)/\(total)")
                        .foregroundStyle(WidgetTheme.contentMuted)
                }
                if !isStale, let since = agent.sinceDate {
                    switch clock {
                    case .live:
                        Text(timerInterval: since...Date.distantFuture, countsDown: false)
                            .multilineTextAlignment(.trailing)
                            .frame(maxWidth: 64, alignment: .trailing)
                            .foregroundStyle(WidgetTheme.contentFaint)
                    case .frozen(let now):
                        Text(FleetTime.elapsed(since: since, now: now))
                            .foregroundStyle(WidgetTheme.contentFaint)
                    }
                }
            }
        case .needsYou:
            Text("needs you").foregroundStyle(WidgetTheme.accent)
        case .active:
            Text("active").foregroundStyle(WidgetTheme.contentFaint)
        case .done:
            Text("done").foregroundStyle(WidgetTheme.ok)
        case .failed:
            Text("failed").foregroundStyle(WidgetTheme.danger)
        }
    }

    /// The step it is on or how its turn ended, as the hub put it; a plain
    /// word only when the hub sent none.
    private var detail: String? {
        if let step = agent.step, !step.isEmpty { return step }
        switch agent.state {
        case .working: return "Working"
        case .needsYou: return "Waiting for you"
        case .done: return agent.total.map { $0 == 1 ? "Done · 1 step" : "Done · \($0) steps" }
        case .failed, .active: return nil
        }
    }

    private var dot: Color {
        if isStale { return WidgetTheme.contentFaint }
        switch agent.state {
        case .working, .done: return WidgetTheme.ok
        case .needsYou: return WidgetTheme.accent
        case .failed: return WidgetTheme.danger
        case .active: return WidgetTheme.contentFaint
        }
    }
}

/// Times for a frozen clock, worded like the system's own live ones.
enum FleetTime {
    /// "12 sec", "3 min" — as `Text(_:style: .relative)` says it.
    static func ago(_ date: Date, now: Date) -> String {
        let formatter = DateComponentsFormatter()
        formatter.unitsStyle = .short
        formatter.maximumUnitCount = 1
        formatter.allowedUnits = [.hour, .minute, .second]
        return formatter.string(from: max(0, now.timeIntervalSince(date))) ?? ""
    }

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
