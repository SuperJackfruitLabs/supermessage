import SupermessageFFI
import SupermessageKit
import SwiftUI

/// A message body, drawn from blocks the core already parsed.
///
/// **This view parses nothing.** Both rendering paths — the sanitised
/// `formatted_body` a human's client sent, and the raw markdown an agent
/// wrote — were turned into one block tree by `core::rich`, so the rule that
/// raw HTML is dropped rather than escaped is made once in Rust and inherited
/// here rather than re-argued.
///
/// **No syntax highlighting**, deliberately. The whole palette runs on one
/// accent, and a code block lit in six competing hues would be the loudest
/// thing on screen. The desktop refused it for the same reason, and the
/// reasoning is stronger on a phone.
struct RichTextView: View {
    let blocks: [RichBlock]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(Array(blocks.enumerated()), id: \.offset) { _, block in
                block.view
            }
        }
    }
}

extension RichBlock {
    @ViewBuilder fileprivate var view: some View {
        switch self {
        case let .paragraph(inlines):
            Text(RichTextFolding.attributed(inlines))
                .textSelection(.enabled)

        case let .heading(level, inlines):
            Text(RichTextFolding.attributed(inlines))
                .font(.system(headingStyle(for: level), design: .serif, weight: .semibold))
                .textSelection(.enabled)

        case let .codeBlock(_, text):
            // Scrolls inside its own container, so a long line never makes the
            // page scroll sideways.
            ScrollView(.horizontal, showsIndicators: false) {
                Text(text)
                    .font(Theme.code)
                    .textSelection(.enabled)
                    .padding(10)
            }
            .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 6))

        case let .blockQuote(blocks):
            // Overlay rather than an HStack sibling — see ReplyQuote in
            // TimelineRowView for why a bare `Rectangle` stretches a row.
            RichTextView(blocks: blocks)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.leading, 12)
                .overlay(alignment: .leading) {
                    Rectangle().fill(.tertiary).frame(width: 2)
                }

        case let .listBlock(ordered, start, items):
            VStack(alignment: .leading, spacing: 6) {
                ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                    HStack(alignment: .top, spacing: 8) {
                        Text(ordered ? "\(Int(start) + index)." : "•")
                            .font(Theme.body)
                            .monospacedDigit()
                        RichTextView(blocks: item.blocks)
                    }
                }
            }

        case .thematicBreak:
            Divider()

        case let .table(header, rows):
            // Also scrolls inside itself. A wide table is the classic way a
            // reading column ends up horizontally scrollable as a whole.
            ScrollView(.horizontal, showsIndicators: false) {
                Grid(alignment: .leading, horizontalSpacing: 14, verticalSpacing: 6) {
                    if !header.isEmpty {
                        GridRow {
                            ForEach(Array(header.enumerated()), id: \.offset) { _, cell in
                                Text(RichTextFolding.attributed(cell.inlines))
                                    .font(.system(.callout, weight: .semibold))
                            }
                        }
                        Divider()
                    }
                    ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                        GridRow {
                            ForEach(Array(row.cells.enumerated()), id: \.offset) { _, cell in
                                Text(RichTextFolding.attributed(cell.inlines))
                                    .font(.callout)
                            }
                        }
                    }
                }
                .padding(.vertical, 4)
            }
        }
    }

    /// Explicit ladder rather than arithmetic on a text style: a level that
    /// somehow arrived out of range degrades to body text instead of producing
    /// something the type ramp has no rung for.
    private func headingStyle(for level: UInt8) -> Font.TextStyle {
        switch level {
        case 1: return .title
        case 2: return .title2
        case 3: return .title3
        case 4, 5, 6: return .headline
        default: return .body
        }
    }
}
