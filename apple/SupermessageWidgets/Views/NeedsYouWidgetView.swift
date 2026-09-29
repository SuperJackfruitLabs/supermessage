// Compiled into the widget extension, and into the app so the widgets'
// previews render with its other previews.

import AppIntents
import SwiftUI
import WidgetKit

#if !SM_WIDGET_EXTENSION
import SupermessageKit
#endif

/// The decisions waiting on the reader: who is asking, what, and the two
/// answers a tap can give.
///
/// Decides nothing. Which decisions exist, what each asks, which answers a
/// button may send, what a sent one says and the counts are all the core's
/// (`core::widget`), read from the snapshot; this only lays them out.
struct NeedsYouWidgetView: View {
    let entry: SnapshotEntry
    let family: WidgetFamily

    var body: some View {
        switch entry.content {
        case let .snapshot(snapshot):
            if let frame = entry.frame {
                content(snapshot, frame)
            } else {
                WidgetUnavailableView(content: .waiting, family: family)
            }
        case .unavailable, .signedOut, .waiting:
            WidgetUnavailableView(content: entry.content, family: family)
        }
    }

    @ViewBuilder
    private func content(_ snapshot: WidgetSnapshot, _ frame: WidgetSnapshot.Frame) -> some View {
        let listed = snapshot.listedDecisions
        switch family {
        case .accessoryInline:
            // The fleet in a line: "2 working · 1 needs you".
            Text(frame.pulse)
        case .accessoryCircular:
            ZStack {
                AccessoryWidgetBackground()
                VStack(spacing: 0) {
                    Text(frame.needsYouCount)
                        .font(.title2.weight(.semibold))
                        .minimumScaleFactor(0.6)
                    Image(systemName: "hand.raised")
                        .font(.caption2)
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(frame.needsYouLine)
        case .accessoryRectangular:
            rectangular(listed.first(where: { $0.answered == nil }), frame)
        case .systemSmall:
            small(listed.first, frame)
        default:
            list(listed, frame, limit: family == .systemLarge ? 3 : 2)
        }
    }

    // MARK: - Lock Screen

    private func rectangular(_ next: WidgetSnapshot.Decision?, _ frame: WidgetSnapshot.Frame)
        -> some View
    {
        VStack(alignment: .leading, spacing: 1) {
            Label(frame.needsYouLine, systemImage: "hand.raised")
                .font(.headline)
                .widgetAccentable()
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            if let next {
                Text("\(next.agent): \(next.question)")
                    .font(.caption)
                    .multilineTextAlignment(.leading)
                    .lineLimit(2)
            } else {
                Text(frame.pulse)
                    .font(.caption)
                    .lineLimit(2)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .widgetURL(next.flatMap { AppLink.decision(roomId: $0.roomId, eventId: $0.eventId) })
    }

    // MARK: - Home Screen

    private func header(_ frame: WidgetSnapshot.Frame) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Label("Needs you", systemImage: "hand.raised")
                .font(.caption.weight(.semibold))
                .foregroundStyle(WidgetTheme.contentMuted)
                .lineLimit(1)
            Spacer(minLength: 4)
            // Nothing owed: the body says so, with the fleet's line — no
            // count of zero up here to repeat it.
            if frame.needsYou > 0 {
                Text(frame.needsYouCount)
                    .font(.caption.weight(.semibold).monospacedDigit())
                    .foregroundStyle(WidgetTheme.accent)
                    .lineLimit(1)
                    .contentTransition(.numericText())
            }
        }
    }

    private func small(_ top: WidgetSnapshot.Decision?, _ frame: WidgetSnapshot.Frame) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            header(frame)
            if let top {
                WidgetDecisionCard(decision: top, compact: true, now: entry.date)
            } else {
                AllClear(frame: frame)
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    /// Up to `limit` cards, fewer when they do not fit — a larger text size
    /// or a two-line question — so the stack never overflows and the widget
    /// never crops its header; the rest are counted instead.
    private func list(
        _ decisions: [WidgetSnapshot.Decision], _ frame: WidgetSnapshot.Frame, limit: Int
    ) -> some View {
        let most = max(1, min(limit, decisions.count))
        return ViewThatFits(in: .vertical) {
            ForEach((1...most).reversed(), id: \.self) { cards in
                list(decisions, frame, cards: cards)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    private func list(
        _ decisions: [WidgetSnapshot.Decision], _ frame: WidgetSnapshot.Frame, cards: Int
    ) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            header(frame)
            if decisions.isEmpty {
                AllClear(frame: frame)
            }
            ForEach(decisions.prefix(cards)) { decision in
                WidgetDecisionCard(decision: decision, compact: false, now: entry.date)
            }
            if decisions.count > cards {
                Text("\(decisions.count - cards) more in the app")
                    .font(.caption2)
                    .foregroundStyle(WidgetTheme.contentFaint)
            }
        }
        .frame(maxWidth: .infinity, alignment: .topLeading)
    }
}

/// Nothing owed: said as a fact, with what the fleet is doing instead.
private struct AllClear: View {
    let frame: WidgetSnapshot.Frame

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(frame.needsYouLine)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(WidgetTheme.content)
            Text(frame.pulse)
                .font(.caption)
                .foregroundStyle(WidgetTheme.contentMuted)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// One decision, on a widget: who asks, what, how long ago, and the answers
/// a button may send.
///
/// **The widgets' `DecisionCard`, and the only widget view that may draw
/// amber** — only while the decision is owed. The fleet Live Activity draws
/// its decision with this too, so the Lock Screen has the same one amber
/// element. Once sent it says so in the
/// core's words ("Sent: Approve · waiting for the board"), in the muted rank:
/// sent is not resolved, and the board's receipt is what takes it away.
///
/// The text is a link to the card in the app; the buttons answer in place.
/// "Request changes" has no button — feedback needs words, and the card is
/// where they are typed.
struct WidgetDecisionCard: View {
    let decision: WidgetSnapshot.Decision
    /// The small family: the question takes the width, the buttons go under.
    let compact: Bool
    /// The entry's moment, which the age is measured from — WidgetKit draws
    /// later entries ahead of time.
    let now: Date
    /// Whether to say how long ago it was asked. The fleet Live Activity
    /// does not know — the hub's decision carries no time.
    var showsAge = true
    /// The fleet card's form (spec 2026-09-30, B2): who asks beside their
    /// face, the question in large type under the name, and the two answers
    /// as wide buttons across the bottom — in place of the card's hero.
    var banner: Banner?

    struct Banner {
        /// The asker's face, drawn by the caller (a cached avatar or
        /// initials).
        var face: AnyView
        /// At most this many lines of question; the card asks for one when
        /// two do not fit.
        var questionLines = 2
        /// Whether to draw the buttons. The watch's Smart Stack is a glance:
        /// a tap there opens the card on the phone.
        var answers = true
    }

    private var owed: Bool { decision.answered == nil }

    var body: some View {
        if let banner { bannerBody(banner) } else { standard }
    }

    /// The fleet card's decision: the one amber element on the Lock Screen.
    private func bannerBody(_ banner: Banner) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Link(destination: link) {
                HStack(alignment: .center, spacing: 8) {
                    banner.face
                    VStack(alignment: .leading, spacing: 1) {
                        Text(decision.agent)
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(owed ? WidgetTheme.signal : WidgetTheme.contentMuted)
                            .lineLimit(1)
                        Text(decision.question)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(WidgetTheme.content)
                            .multilineTextAlignment(.leading)
                            .lineLimit(banner.questionLines)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            if banner.answers, owed, !decision.buttons.isEmpty {
                HStack(spacing: 8) {
                    ForEach(decision.buttons) { option in
                        let intent = AnswerDecisionIntent(
                            roomId: decision.roomId, eventId: decision.eventId, optionId: option.id)
                        if option.declines {
                            Button(intent: intent) { wideLabel(option) }
                                .buttonStyle(.bordered)
                                .tint(WidgetTheme.contentMuted)
                        } else {
                            Button(intent: intent) { wideLabel(option) }
                                .buttonStyle(.borderedProminent)
                                .tint(WidgetTheme.signal)
                        }
                    }
                }
                .buttonBorderShape(.capsule)
                .controlSize(.regular)
            }
        }
        .padding(.horizontal, 11)
        .padding(.vertical, 9)
        .background {
            RoundedRectangle(cornerRadius: Metrics.radiusCard)
                .fill(owed ? WidgetTheme.signal.opacity(0.10) : WidgetTheme.surfaceSunken)
            RoundedRectangle(cornerRadius: Metrics.radiusCard)
                .stroke(owed ? WidgetTheme.signal : WidgetTheme.border, lineWidth: 1)
        }
    }

    private func wideLabel(_ option: WidgetSnapshot.Option) -> some View {
        Text(option.label)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(option.declines ? WidgetTheme.content : WidgetTheme.signalSoft)
            .lineLimit(1)
            .minimumScaleFactor(0.8)
            .frame(maxWidth: .infinity)
    }

    @ViewBuilder private var standard: some View {
        let layout =
            compact
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 6))
            : AnyLayout(HStackLayout(alignment: .center, spacing: 8))
        layout {
            Link(destination: link) { text }
            if owed, !decision.buttons.isEmpty {
                buttons
            }
        }
        .padding(.horizontal, compact ? 0 : 8)
        .padding(.vertical, compact ? 0 : 6)
        .background {
            if !compact {
                RoundedRectangle(cornerRadius: Metrics.radiusCard)
                    .fill(owed ? WidgetTheme.signal.opacity(0.10) : WidgetTheme.surfaceSunken)
                RoundedRectangle(cornerRadius: Metrics.radiusCard)
                    .stroke(owed ? WidgetTheme.signal : WidgetTheme.border, lineWidth: 1)
            }
        }
    }

    private var link: URL {
        AppLink.decision(roomId: decision.roomId, eventId: decision.eventId)
            ?? URL(string: "supermessage://")!
    }

    private var text: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 4) {
                Text(decision.agent)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(owed ? WidgetTheme.content : WidgetTheme.contentMuted)
                    .lineLimit(1)
                if showsAge {
                    Text(WidgetAge.since(decision.askedAt, now: now))
                        .font(.caption2)
                        .foregroundStyle(WidgetTheme.contentFaint)
                        .lineLimit(1)
                }
            }
            Text(decision.question)
                .font(compact ? .caption : .footnote)
                .foregroundStyle(WidgetTheme.content)
                .multilineTextAlignment(.leading)
                .lineLimit(compact ? 3 : 2)
            if let answered = decision.answered {
                Label(answered.line, systemImage: "paperplane")
                    .font(.caption2)
                    .foregroundStyle(WidgetTheme.contentMuted)
                    .lineLimit(1)
            } else if decision.buttons.isEmpty {
                Text("Open to answer")
                    .font(.caption2)
                    .foregroundStyle(WidgetTheme.contentMuted)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var buttons: some View {
        HStack(spacing: 6) {
            ForEach(decision.buttons) { option in
                let intent = AnswerDecisionIntent(
                    roomId: decision.roomId, eventId: decision.eventId, optionId: option.id)
                if option.declines {
                    Button(intent: intent) { label(option) }
                        .buttonStyle(.bordered)
                        .tint(WidgetTheme.contentMuted)
                } else {
                    Button(intent: intent) { label(option) }
                        .buttonStyle(.borderedProminent)
                        .tint(WidgetTheme.signal)
                }
            }
        }
        .controlSize(.small)
        .fixedSize(horizontal: !compact, vertical: false)
    }

    private func label(_ option: WidgetSnapshot.Option) -> some View {
        Text(option.label)
            .font((compact ? Font.caption2 : Font.caption).weight(.semibold))
            .foregroundStyle(option.declines ? WidgetTheme.content : WidgetTheme.signalSoft)
            .lineLimit(1)
            .minimumScaleFactor(0.8)
            .frame(maxWidth: compact ? .infinity : nil)
    }
}

/// The honest empty states, shared by both widgets.
struct WidgetUnavailableView: View {
    let content: SnapshotEntry.Content
    let family: WidgetFamily

    var body: some View {
        switch family {
        case .accessoryInline:
            Text(message)
        case .accessoryCircular:
            ZStack {
                AccessoryWidgetBackground()
                Image(systemName: "hand.raised").font(.title3)
            }
            .accessibilityLabel(message)
        case .accessoryRectangular:
            VStack(alignment: .leading) {
                Text("supermessage").font(.headline)
                Text(message).font(.caption).lineLimit(2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        default:
            VStack(alignment: .leading, spacing: 4) {
                Text("supermessage")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(WidgetTheme.accent)
                Text(message)
                    .font(.caption)
                    .foregroundStyle(WidgetTheme.contentMuted)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        }
    }

    private var message: String {
        switch content {
        case .unavailable: return "Open the app to see what needs you."
        case .signedOut: return "Sign in to see what needs you."
        case .waiting: return "Open the app once to start."
        case .snapshot: return ""
        }
    }
}

/// How long ago, in whole units — "3 min", "2 hr" — fixed at the entry's
/// moment. Not `Text(_:style: .relative)`: that ticks in seconds ("3 min,
/// 12 sec"), which a glance does not need and a Home Screen row cannot fit.
/// The timeline adds an entry every few minutes so it stays close
/// (`SnapshotEntry.timeline`).
enum WidgetAge {
    static func since(_ date: Date, now: Date) -> String {
        let formatter = DateComponentsFormatter()
        formatter.unitsStyle = .abbreviated
        formatter.maximumUnitCount = 1
        formatter.allowedUnits = [.day, .hour, .minute]
        let seconds = max(60, now.timeIntervalSince(date))
        return formatter.string(from: seconds) ?? ""
    }
}
