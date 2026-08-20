import SupermessageKit
import SwiftUI

/// An agent's answer as it is being written.
///
/// The cursor this replaced — a `▍` after the text — said "still going" and
/// nothing else, and every delta re-laid the whole paragraph with no sense of
/// where the new words were. This fades in only the characters that just
/// arrived: opacity and a two-point rise over about 160ms, which is enough to
/// draw the eye to the newest words and little enough to disappear into
/// reading.
///
/// Deliberately **not** `.contentTransition(.opacity)` on the whole `Text`.
/// That transitions the content of the view, so a string that grows by three
/// characters re-animates far more of itself than intended — the paragraph
/// shimmers on every tick. `TextRenderer` works at glyph granularity, which
/// is the level this actually wants (WWDC24's "Create custom visual effects
/// with SwiftUI").
struct StreamingTextView: View {
    let text: String
    /// How many trailing characters are new. See `StreamingText.revealed`.
    let revealed: Int

    var body: some View {
        Text(text)
            .font(Theme.body)
            .textRenderer(
                ArrivingGlyphs(
                    // Animatable, so SwiftUI interpolates the boundary rather
                    // than jumping it: the fade runs over the whole tick
                    // instead of landing in one frame.
                    settled: Double(max(0, text.count - revealed)),
                    total: text.count))
            .animation(.easeOut(duration: 0.16), value: text.count)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Draws a run of text with its newest glyphs still arriving.
///
/// `settled` is how many glyphs are fully in place, as a `Double` so it can be
/// animated: SwiftUI drives it from the previous value to the new one, and
/// each glyph crosses the boundary in turn.
private struct ArrivingGlyphs: TextRenderer, Animatable {
    var settled: Double
    let total: Int

    var animatableData: Double {
        get { settled }
        set { settled = newValue }
    }

    func draw(layout: Text.Layout, in context: inout GraphicsContext) {
        var index = 0
        for line in layout {
            for run in line {
                for glyph in run {
                    defer { index += 1 }

                    // Everything before the boundary is simply text. Drawing
                    // it through the same per-glyph path as the newest few
                    // would mean a context copy per glyph for a whole
                    // conversation's worth of characters.
                    guard Double(index) >= settled else {
                        context.draw(glyph)
                        continue
                    }

                    // How far this glyph has come, 0 at the boundary and 1 a
                    // few glyphs past it. Spread over a short run so the
                    // arrival reads as a wave rather than a hard edge.
                    let progress = min(1, max(0, (Double(index) - settled) / 6))
                    let appearing = 1 - progress

                    var copy = context
                    copy.opacity = 1 - appearing
                    copy.translateBy(x: 0, y: appearing * 2)
                    copy.addFilter(.blur(radius: appearing * 1.2))
                    copy.draw(glyph)
                }
            }
        }
    }
}
