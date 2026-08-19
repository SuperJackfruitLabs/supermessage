import SupermessageKit
import SwiftUI

/// An agent's turn while it is still arriving.
///
/// It sits where the message will land, in the same measure and the same face,
/// because it is about to *become* that message — an answer that arrives as
/// `**bold**` and settles into bold is the seam this closes.
///
/// The reasoning is collapsed by default: it is context, not the answer, and
/// an operator scanning a room wants the conclusion first.
struct LiveTurnView: View {
    let live: LiveStore
    let writerName: String

    @State private var showsThought = false

    var body: some View {
        if live.isLive {
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 6) {
                    Text(writerName).metaFace().textCase(.uppercase)
                    Text("writing…").metaFace().foregroundStyle(.secondary)
                }

                if let thought = live.thought {
                    DisclosureGroup(isExpanded: $showsThought) {
                        Text(thought)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    } label: {
                        Text("Reasoning").metaFace().foregroundStyle(.secondary)
                    }
                }

                ForEach(live.tools) { tool in
                    HStack(spacing: 6) {
                        Image(systemName: "gearshape").imageScale(.small)
                        Text(tool.title).metaFace().lineLimit(1)
                        Text(tool.status).metaFace().foregroundStyle(.tertiary)
                    }
                    .foregroundStyle(.secondary)
                }

                if let answer = live.answer {
                    // Plain text, not blocks. Parsing every frame would cost a
                    // round trip per keystroke of the agent's; the landed
                    // message renders through the same parser moments later,
                    // and `whitespace` preservation is what keeps the shape
                    // steady across that hand-off.
                    (Text(answer) + Text(" ▍").foregroundStyle(.secondary))
                        .font(Theme.body)
                }
            }
            .padding(.vertical, 8)
        }
    }
}
