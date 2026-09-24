import SupermessageKit
import SwiftUI

/// The line of an agent's answer still being written.
///
/// **The newest chunk fades in as one.** `StreamingText` hands over a
/// sentence at a time, and the whole of it goes from clear to settled over
/// about 450ms — no per-character wave, no typing. The typing effect this
/// replaced made the app look like it was performing a speed; a sentence
/// that simply arrives reads as the model having said it (2026-09-24).
///
/// **Formatted while unfinished.** Inline markup in the line — `**bold**`,
/// `_emphasis_`, `` `code` `` — is closed for the moment and rendered, so the
/// reader never sees asterisks that turn into bold when the closing pair
/// arrives. A line's block form (a heading, a list) waits for the line to
/// complete, when `StreamingRichView` hands it to the real parser.
///
/// Deliberately **not** `.contentTransition(.opacity)` on the whole `Text`,
/// which transitions the content of the view and so re-animates far more of
/// the string than arrived. `TextRenderer` works at glyph granularity, which
/// is the level this wants (WWDC24's "Create custom visual effects with
/// SwiftUI").
struct StreamingTextView: View {
    let text: String
    /// How many trailing characters are the chunk still arriving.
    let revealed: Int
    /// Changes with every chunk; the fade restarts on it.
    var chunk: Int = 0

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.rendersStill) private var rendersStill
    /// When the current chunk arrived, for the fade's clock.
    @State private var arrived = Date.distantPast
    /// Whether a fade is under way — the clock runs only then.
    @State private var fading = false

    /// Long enough to be seen as a fade. Build 19's 0.2s, eased hard at the
    /// start, was two frames of half-opacity and read as the text simply
    /// appearing (2026-09-24).
    static let fade: TimeInterval = 0.45

    @ViewBuilder
    private var content: some View {
        let formatted = InlineMarkdown.attributed(text)
        if reduceMotion || rendersStill || revealed == 0 {
            Text(formatted)
        } else {
            let settled = InlineMarkdown.attributed(String(text.dropLast(revealed))).characters.count
            // Only runs while a fade is under way; paused, it costs nothing
            // between chunks.
            SwiftUI.TimelineView(.animation(minimumInterval: nil, paused: !fading)) { context in
                Text(formatted)
                    .textRenderer(ArrivingChunk(settled: settled, progress: progress(at: context.date)))
            }
        }
    }

    private func progress(at date: Date) -> Double {
        min(1, max(0, date.timeIntervalSince(arrived) / Self.fade))
    }

    var body: some View {
        content
        .font(Theme.body)
        .frame(maxWidth: .infinity, alignment: .leading)
        .onChange(of: chunk, initial: true) {
            arrived = Date()
            fading = true
        }
        .task(id: chunk) {
            try? await Task.sleep(for: .seconds(Self.fade + 0.05))
            if !Task.isCancelled { fading = false }
        }
    }
}

/// Draws a run of text with its newest chunk partway in.
///
/// Everything before `settled` is simply text; everything after it is drawn
/// at `progress`'s opacity with a three-point rise, all together.
private struct ArrivingChunk: TextRenderer {
    let settled: Int
    let progress: Double

    func draw(layout: Text.Layout, in context: inout GraphicsContext) {
        // Ease in and out: it starts clear and gathers, rather than being
        // most of the way there in the first frame.
        let eased = progress * progress * (3 - 2 * progress)
        var arriving = context
        arriving.opacity = eased
        arriving.translateBy(x: 0, y: (1 - eased) * 3)

        var index = 0
        for line in layout {
            for run in line {
                for glyph in run {
                    defer { index += 1 }
                    if index < settled {
                        context.draw(glyph)
                    } else {
                        arriving.draw(glyph)
                    }
                }
            }
        }
    }
}

/// Inline markdown for a line that is not finished yet.
enum InlineMarkdown {
    /// `line` with its inline markup rendered, any marker still open closed
    /// for the moment. Falls back to the plain line when it will not parse.
    static func attributed(_ line: String) -> AttributedString {
        let closed = OpenMarkup.close(line)
        let options = AttributedString.MarkdownParsingOptions(
            interpretedSyntax: .inlineOnlyPreservingWhitespace)
        return (try? AttributedString(markdown: closed, options: options))
            ?? AttributedString(line)
    }
}

#if DEBUG
// Mid-arrival: the last sentence is still coming in. A still frame renders
// it settled (`rendersStill`), so what this shows is the formatting of an
// unfinished line — the bold is closed although its pair has not arrived.
#Preview("Arriving") {
    PreviewGround {
        StreamingTextView(
            text: "It is sorted by **pending first, then by last", revealed: 18)
    }
    .environment(\.rendersStill, true)
}

// Nothing new: what the reader looks at for all but the last moment of a turn.
#Preview("Settled") {
    PreviewGround {
        StreamingTextView(
            text: "It is sorted by pending first, then by last activity.", revealed: 0)
    }
}
#endif
