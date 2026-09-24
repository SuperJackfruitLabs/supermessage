import SupermessageFFI
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
///
/// ## An activity card (A2)
///
/// Shaped as a card that answers, in order, the questions a reader waiting on
/// an agent has: who, is it still going, for how long, what is it doing right
/// now, how far has it got, and did anything fail. The individual tool calls
/// are one tap away rather than a growing list: a dozen rows of `read …`
/// says only that a dozen things happened.
struct LiveTurnView: View {
    let live: LiveStore
    let writerName: String

    @State private var showsThought = false
    @State private var showsSteps = false
    /// Whether the reader has asked for less movement.
    ///
    /// The running indicator beside the current step is the only moving part
    /// of this view, and it says exactly what the word beside it already
    /// says. Redundant motion is the easiest kind to drop, and dropping it
    /// costs a reader nothing: the label stays.
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// Whether a still frame is being taken of this view.
    ///
    /// Separate from `reduceMotion`, which is read-only — SwiftUI owns it,
    /// and `.environment(\.rendersStill, true)` does not compile.
    /// So a preview cannot ask for the accessible rendering, and needs its
    /// own way to say "nothing that never settles". It also stops the clock:
    /// a still frame measures elapsed time to the turn's last event rather
    /// than to whenever the shutter happened to open.
    @Environment(\.rendersStill) private var rendersStill
    /// Paces the answer onto the screen — see `StreamingText`.
    @State private var stream = StreamingText()

    /// The header's name for the agent when the room has one (D11), and the
    /// timeline's attribution otherwise.
    private var name: String { live.agentName ?? writerName }

    var body: some View {
        if live.isLive {
            VStack(alignment: .leading, spacing: 8) {
                header

                // "Writing the answer" says nothing the answer, streaming in
                // under it, does not already show — so it goes once the text
                // is visible. Thinking, a tool call and starting still need
                // saying, because nothing else on screen shows them.
                if !live.finished, live.currentStep != nil || stream.text.isEmpty {
                    currentStep
                }

                if !live.tools.isEmpty {
                    stepSummary
                }

                if let thought = live.thought {
                    DisclosureGroup(isExpanded: $showsThought) {
                        Text(thought)
                            .font(.footnote)
                            .foregroundStyle(Theme.contentMuted)
                            .textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    } label: {
                        Text("Reasoning").metaFace().foregroundStyle(Theme.contentMuted)
                    }
                }

                if !live.tools.isEmpty {
                    DisclosureGroup(isExpanded: $showsSteps) {
                        VStack(alignment: .leading, spacing: 6) {
                            ForEach(live.tools) { tool in
                                ToolRow(tool: tool)
                            }
                        }
                        .padding(.top, 4)
                    } label: {
                        Text(live.tools.count == 1 ? "1 step" : "All \(live.tools.count) steps")
                            .metaFace()
                            .foregroundStyle(Theme.contentMuted)
                    }
                }

                if !stream.text.isEmpty {
                    // Formatted as it arrives, through the same parser and
                    // renderer the landed message uses — so `**What I
                    // observe:**` is bold while it streams, not raw markup
                    // that reflows into bold when the message lands
                    // (2026-09-24). See `StreamingRichView` for how that stays
                    // cheap at a reveal every 20ms.
                    //
                    // Paced by `StreamingText` rather than drawn straight from
                    // the delta: what arrives in bursts should not appear in
                    // bursts. See that type for why.
                    StreamingRichView(text: stream.text, revealed: stream.revealed)
                }
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.surfaceRaised, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .stroke(Theme.border, lineWidth: 1)
            )
            .padding(.vertical, 6)
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

    /// Who, whether they are still at it, and for how long.
    private var header: some View {
        HStack(spacing: 6) {
            Text(name).nameFace().lineLimit(1)
            Spacer(minLength: 8)
            // What this is: a turn in progress, or the record of the one that
            // just finished. Saying "Working" over a finished turn would be
            // the app claiming something that is no longer true.
            Text(live.finished ? "Done" : "Working")
                .metaFace()
                .foregroundStyle(live.finished ? Theme.ok : Theme.accent)
            elapsed
        }
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder private var elapsed: some View {
        if rendersStill || live.finished {
            // Still: to the turn's last event (or its end), never to `Date()`.
            elapsedLabel(at: live.lastActivityAt ?? .distantPast)
        } else {
            SwiftUI.TimelineView(.periodic(from: live.startedAt ?? .now, by: 1)) { context in
                elapsedLabel(at: context.date)
            }
        }
    }

    @ViewBuilder private func elapsedLabel(at now: Date) -> some View {
        if let seconds = live.elapsed(at: now) {
            Text("· " + ElapsedTime.label(seconds))
                .metaFace()
                .monospacedDigit()
                .foregroundStyle(Theme.contentMuted)
        }
    }

    /// What the agent is doing this moment, with the one moving part.
    private var currentStep: some View {
        HStack(spacing: 8) {
            if reduceMotion || rendersStill {
                Image(systemName: "circle.dotted")
                    .imageScale(.small)
                    .foregroundStyle(Theme.accent)
            } else {
                ProgressView().controlSize(.mini).tint(Theme.accent)
            }
            Text(currentStepTitle)
                .font(.subheadline)
                .foregroundStyle(Theme.content)
                .lineLimit(2)
        }
        .accessibilityElement(children: .combine)
    }

    private var currentStepTitle: String {
        if let step = live.currentStep { return step.title }
        if live.answer != nil { return "Writing the answer" }
        if live.thought != nil { return "Thinking" }
        return "Starting"
    }

    /// How far it has got, and what went wrong — the failure first in the
    /// reader's eye, because it is the one state that still matters once the
    /// turn has ended.
    private var stepSummary: some View {
        VStack(alignment: .leading, spacing: 3) {
            let done = live.completedSteps
            Label(done == 1 ? "1 step done" : "\(done) steps done", systemImage: "checkmark.circle")
                .metaFace()
                .foregroundStyle(Theme.contentMuted)
            if let failed = live.failedStep {
                Label("\(failed.title) failed", systemImage: "xmark.circle")
                    .metaFace()
                    .foregroundStyle(Theme.danger)
                    .lineLimit(2)
            }
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
            Image(systemName: icon)
                .imageScale(.small)
                .foregroundStyle(tool.phase == .failed ? AnyShapeStyle(Theme.danger) : AnyShapeStyle(Theme.contentMuted))
                .symbolEffect(.pulse, isActive: tool.phase == .running)
            // The title can have the line; the status word is short and must
            // never be what gets squeezed.
            Text(tool.title).metaFace().lineLimit(1).layoutPriority(0)
            Spacer(minLength: 4)
            // The core's word — "Running", "Done" — rather than ACP's
            // `in_progress`, and no separate `kind`: the glyph already says
            // what happened, and `read … read` said it twice.
            Text(tool.statusLabel)
                .metaFace()
                .lineLimit(1)
                .fixedSize()
                .layoutPriority(1)
                .foregroundStyle(
                    tool.phase == .failed
                        ? AnyShapeStyle(Theme.danger) : AnyShapeStyle(Theme.contentFaint))
        }
        .foregroundStyle(Theme.contentMuted)
        .accessibilityElement(children: .combine)
    }

    /// The status, as a glyph. A list of a dozen identical gears says only
    /// that a dozen things happened.
    private var icon: String {
        switch tool.phase {
        case .done: return "checkmark.circle"
        case .failed: return "xmark.circle"
        case .running: return "arrow.triangle.2.circlepath"
        case .queued, .unknown: return "clock"
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
                .foregroundStyle(Theme.contentFaint)
            Text(text)
                .font(.system(.caption, design: .monospaced))
                .foregroundStyle(Theme.contentMuted)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

#if DEBUG
// A turn in flight: a thought, three tools with one failed, and an answer
// arriving.
//
// None of this is history. It is not persisted, not paginated, and a device
// that was asleep never sees it — which is exactly why it is worth a still
// frame. In a running app these states are gone in seconds, so this is the
// only way to look at the failed-tool row at all.
// `.rendersStill` on all three, and not only to make them sit still for the
// camera. A spinner has no canonical frame: five renders of this preview
// disagreed in a 37×37 box at 396 pixels, which is exactly the mini
// `ProgressView` and nothing else. With the motion gone the frame is
// reproducible, and these three stop being the only iOS previews no gate
// looks at.
//
// What that costs is stated rather than hidden: for these three the baseline
// defends the reduce-motion rendering, so a change to the spinner itself
// would not be caught. Everything else in the frame — layout, type, colour,
// the disclosure, the streamed text — is now covered, where before none of
// it was.
#Preview("Mid-turn") {
    PreviewGround { LiveTurnView(live: PreviewFixtures.liveStore(), writerName: "Atlas") }
        .environment(\.rendersStill, true)
}

// Thinking, with nothing to show yet.
//
// The first thing a reader sees after sending, and the state most likely to
// have been drawn once and never looked at again.
#Preview("Thinking only") {
    PreviewGround {
        LiveTurnView(
            live: PreviewFixtures.liveStore(thinking: true, answering: false, tools: false),
            writerName: "Atlas")
    }
    .environment(\.rendersStill, true)
}

// Answering with no thought and no tools: a plain reply streaming in.
#Preview("Answering only") {
    PreviewGround {
        LiveTurnView(
            live: PreviewFixtures.liveStore(thinking: false, answering: true, tools: false),
            writerName: "Atlas")
    }
    .environment(\.rendersStill, true)
}

// A long turn: 1m 04s in, two steps done, one failed, one running. The clock
// is the fixture's, so the elapsed time is the same on every render.
#Preview("Activity card") {
    PreviewGround {
        LiveTurnView(live: ComposerRevampFixtures.longTurn(), writerName: "Atlas")
    }
    .environment(\.rendersStill, true)
}

// The record of that turn once it has finished: "Done", how long it took,
// and the failure still named.
#Preview("Activity card, finished") {
    PreviewGround {
        LiveTurnView(live: ComposerRevampFixtures.longTurn(finished: true), writerName: "Atlas")
    }
    .environment(\.rendersStill, true)
}
#endif

/// A streaming answer, formatted as it arrives.
///
/// Completed lines render as blocks — markdown through the core's
/// `richBlocksFromMarkdown`, the parser the landed message uses, drawn by the
/// same `RichTextView` — and only the line still being written stays plain,
/// with its newest glyphs fading in (`StreamingTextView`). A line's markup
/// can only be read once the line is whole, so this is the earliest the
/// formatting can be right, and the plain tail is one line long at most.
///
/// **Parsed when a line completes, not on every reveal.** The reveal ticks
/// every 20ms; the completed part changes only at a newline, and the cache
/// makes every other tick a string comparison.
struct StreamingRichView: View {
    let text: String
    let revealed: Int

    @State private var cache = MarkdownBlockCache()

    var body: some View {
        let (settled, tail) = Self.split(text)
        VStack(alignment: .leading, spacing: 8) {
            if !settled.isEmpty {
                RichTextView(blocks: cache.blocks(for: settled))
            }
            if !tail.isEmpty {
                StreamingTextView(text: tail, revealed: min(revealed, tail.count))
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// Everything up to the last newline, and the line after it.
    static func split(_ text: String) -> (settled: String, tail: String) {
        guard let newline = text.lastIndex(of: "\n") else { return ("", text) }
        let settled = String(text[..<newline]).trimmingCharacters(in: .newlines)
        let tail = String(text[text.index(after: newline)...])
        return (settled, tail)
    }
}

/// The blocks for the last completed prefix, kept until the prefix changes.
@MainActor
final class MarkdownBlockCache {
    private var source = ""
    private var cached: [RichBlock] = []

    func blocks(for text: String) -> [RichBlock] {
        if text != source {
            source = text
            cached = richBlocksFromMarkdown(source: text)
        }
        return cached
    }
}

