import SupermessageKit
import SwiftUI

/// An agent's turn: while it is arriving, and the record of it afterwards.
///
/// It sits where the message will land, in the same measure and the same face,
/// because it is about to *become* that message — an answer that arrives as
/// `**bold**` and settles into bold is the seam this closes.
///
/// **It does not disappear when the turn ends.** The streamed answer does,
/// because the real message arrives on the timeline and says it better — but
/// the reasoning and the tool calls stay, because nothing else on screen
/// carries them. Throwing them away the instant the answer landed meant the
/// record of *how* an agent reached its answer was only ever visible while it
/// was still being written. They go when the next turn starts.
///
/// The reasoning is collapsed by default: it is context, not the answer, and
/// an operator scanning a room wants the conclusion first.
struct LiveTurnView: View {
    let live: LiveStore
    let writerName: String

    @State private var showsThought = false
    /// Paces the answer onto the screen — see `StreamingText`.
    @State private var stream = StreamingText()

    var body: some View {
        if live.isLive {
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 6) {
                    Text(writerName).metaFace().textCase(.uppercase)
                    // What this is: a turn in progress, or the record of the
                    // one that just finished. Saying "writing…" over a
                    // finished turn would be the app claiming something that
                    // is no longer true.
                    Text(live.finished ? "last turn" : "writing…")
                        .metaFace()
                        .foregroundStyle(.secondary)
                    if !live.finished {
                        ProgressView().controlSize(.mini)
                    }
                }

                if let thought = live.thought {
                    DisclosureGroup(isExpanded: $showsThought) {
                        Text(thought)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    } label: {
                        Text("Reasoning").metaFace().foregroundStyle(.secondary)
                    }
                }

                ForEach(live.tools) { tool in
                    ToolRow(tool: tool)
                }

                if !stream.text.isEmpty {
                    // Plain text, not blocks. Parsing every frame would cost a
                    // round trip per keystroke of the agent's; the landed
                    // message renders through the same parser moments later,
                    // and `whitespace` preservation is what keeps the shape
                    // steady across that hand-off.
                    //
                    // Paced by `StreamingText` rather than drawn straight from
                    // the delta: what arrives in bursts should not appear in
                    // bursts. See that type for why.
                    StreamingTextView(text: stream.text, revealed: stream.revealed)
                }
            }
            .padding(.vertical, 8)
            // A finished turn steps back: it is a record beside the
            // conversation rather than something happening in it.
            .opacity(live.finished ? 0.85 : 1)
            .onChange(of: live.answer) { _, next in
                guard let next else {
                    // The turn ended: drain whatever is still queued rather
                    // than animating into an empty card.
                    stream.finish()
                    stream.clear()
                    return
                }
                stream.accept(next)
            }
            .task(id: writerName) { stream.clear() }
        }
    }
}

/// One tool call, openable when there is something behind it.
///
/// A row that says `Read src/main.ts · completed` answers *what* and *whether*
/// and nothing else. What it was given and what it returned are the two
/// questions anyone debugging an agent actually has, so they live one tap
/// away rather than nowhere.
///
/// A row with nothing behind it stays a plain row: a disclosure triangle that
/// opens onto an empty box says there is something to see.
private struct ToolRow: View {
    let tool: LiveStore.ToolCall

    @State private var open = false

    var body: some View {
        if tool.hasDetail {
            DisclosureGroup(isExpanded: $open) {
                VStack(alignment: .leading, spacing: 8) {
                    if !tool.locations.isEmpty {
                        Detail(label: "Touched", text: tool.locations.joined(separator: "\n"))
                    }
                    if let input = tool.input {
                        Detail(label: "Input", text: input)
                    }
                    if let output = tool.output {
                        Detail(label: "Output", text: output)
                    }
                }
                .padding(.top, 4)
                .frame(maxWidth: .infinity, alignment: .leading)
            } label: {
                summary
            }
        } else {
            summary
        }
    }

    private var summary: some View {
        HStack(spacing: 6) {
            Image(systemName: icon).imageScale(.small)
            Text(tool.title).metaFace().lineLimit(1)
            if let kind = tool.kind {
                Text(kind).metaFace().foregroundStyle(.tertiary)
            }
            Spacer(minLength: 4)
            Text(tool.status)
                .metaFace()
                .foregroundStyle(
                    tool.status == "failed"
                        ? AnyShapeStyle(Theme.danger) : AnyShapeStyle(.tertiary))
        }
        .foregroundStyle(.secondary)
    }

    /// The status, as a glyph. A list of a dozen identical gears says only
    /// that a dozen things happened.
    private var icon: String {
        switch tool.status {
        case "completed": return "checkmark.circle"
        case "failed": return "xmark.circle"
        case "in_progress": return "arrow.triangle.2.circlepath"
        default: return "clock"
        }
    }
}

/// A labelled block of tool text.
///
/// Monospaced and selectable: it is data — a command, a diff, a path list —
/// and the reason to open it is usually to copy it somewhere else. Bounded by
/// the core before it ever arrives here (`live::bound_tool_text`), so this
/// only has to lay it out.
private struct Detail: View {
    let label: String
    let text: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .metaFace()
                .textCase(.uppercase)
                .foregroundStyle(.tertiary)
            Text(text)
                .font(.system(.caption, design: .monospaced))
                .foregroundStyle(.secondary)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}
