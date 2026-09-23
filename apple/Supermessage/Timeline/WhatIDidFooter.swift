import SupermessageFFI
import SupermessageKit
import SwiftUI

/// "Thought for 12s · 4 steps", above the agent's newest answer once its turn
/// has finished (T6).
///
/// `LiveStore` keeps a finished turn's tool calls and reasoning until the
/// next turn starts — nothing else on screen carries them — and this is where
/// they go once the answer has landed: collapsed, between the agent's name
/// and its answer, because that is the order it happened in. Open, it shows
/// the reasoning and lists each step with the core's own word for how it
/// ended.
///
/// Draws nothing for a turn that is still going (the live card is showing it)
/// or one that left no record.
struct WhatIDidFooter: View {
    let live: LiveStore

    @State private var open = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        if live.finished, !live.tools.isEmpty || live.thought != nil {
            DisclosureGroup(
                isExpanded: $open.animation(reduceMotion ? nil : .snappy(duration: 0.25))
            ) {
                VStack(alignment: .leading, spacing: 6) {
                    if let thought = live.thought {
                        Text(thought)
                            .font(.footnote)
                            .foregroundStyle(Theme.contentMuted)
                            .textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.bottom, live.tools.isEmpty ? 0 : 4)
                    }
                    ForEach(live.tools) { tool in
                        Step(tool: tool)
                    }
                }
                .padding(.top, 6)
            } label: {
                Text(
                    TurnRecordLabel.text(
                        thought: live.thought != nil, steps: live.tools.count,
                        seconds: live.elapsed(at: Date())))
                    .font(ThemeType.ui)
                    .foregroundStyle(Theme.contentMuted)
            }
            .tint(Theme.contentMuted)
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(Theme.surfaceSunken, in: RoundedRectangle(cornerRadius: Metrics.radiusCard))
            .frame(maxWidth: MessageMeasure.card, alignment: .leading)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

/// One step of a finished turn: what it was, and how it ended.
private struct Step: View {
    let tool: LiveStore.ToolCall

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            Image(systemName: icon)
                .imageScale(.small)
                .foregroundStyle(
                    tool.phase == .failed ? AnyShapeStyle(Theme.danger) : AnyShapeStyle(Theme.contentFaint))
            Text(tool.title)
                .font(.footnote)
                .foregroundStyle(Theme.contentMuted)
                .lineLimit(2)
            Spacer(minLength: 6)
            // The core's word — "Done", "Failed" — rendered as given.
            Text(tool.statusLabel)
                .metaFace()
                .fixedSize()
                .foregroundStyle(
                    tool.phase == .failed ? AnyShapeStyle(Theme.danger) : AnyShapeStyle(Theme.contentFaint))
        }
        .accessibilityElement(children: .combine)
    }

    private var icon: String {
        switch tool.phase {
        case .done: return "checkmark.circle"
        case .failed: return "xmark.circle"
        case .running: return "arrow.triangle.2.circlepath"
        case .queued, .unknown: return "clock"
        }
    }
}

/// Widths the timeline's messages are set to.
enum MessageMeasure {
    /// A peer's or an agent's card: a readable line length, not the column.
    static let card: CGFloat = 600
    /// Your own bubble, narrower so the two sides of the conversation stay
    /// distinguishable at a glance on an iPad.
    static let own: CGFloat = 520
}

#if DEBUG
// A finished turn's record above the answer it produced, collapsed (T6).
#Preview("What I did") {
    PreviewGround(width: 390) {
        TimelineRowView(
            row: PreviewFixtures.agentMessage, attribution: "Atlas",
            media: PreviewFixtures.mediaCache(), faces: PreviewFixtures.faceCache(),
            prelude: AnyView(WhatIDidFooter(live: PreviewFixtures.finishedTurn())))
    }
    .environment(\.rendersStill, true)
}
#endif
