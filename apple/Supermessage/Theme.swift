import SwiftUI

/// The palette and type ramp, from `docs/superpowers/specs/2026-08-13-console-design.md`.
///
/// The identity that travels from the desktop is **structural**, not the
/// typefaces: serif for what agents write, sans for what the operator writes,
/// mono for data and sigils, and one colour reserved for one meaning. The
/// faces are the system's — `.serif` resolves to New York, `.monospaced` to SF
/// Mono — so Dynamic Type comes free and nothing is bundled.
enum Theme {
    // MARK: - Colour
    //
    // **Two palettes, one per appearance**, rather than one palette adapted.
    // Paper is a warm, light ground that reads as a record of what was done;
    // Slate is a cool, low-contrast dark for a console glanced at fifty times
    // a day. They are declared as asset-free dynamic colours so every use site
    // stays a single token and the two never drift apart.

    private static func dynamic(light: (Double, Double, Double), dark: (Double, Double, Double))
        -> Color
    {
        Color(
            UIColor { traits in
                let (r, g, b) = traits.userInterfaceStyle == .dark ? dark : light
                return UIColor(red: r, green: g, blue: b, alpha: 1)
            })
    }

    /// The page itself.
    static let ground = dynamic(
        light: (0.965, 0.957, 0.937),  // #f6f4ef
        dark: (0.090, 0.106, 0.133))  // #171b22
    /// Behind a chip, a segmented control, an avatar with no picture.
    static let sunken = dynamic(
        light: (0.906, 0.894, 0.859),  // #e7e4db
        dark: (0.137, 0.165, 0.208))  // #232a35
    /// Hairlines and dividers.
    static let hairline = dynamic(
        light: (0.867, 0.851, 0.812),  // #ddd9cf
        dark: (0.149, 0.188, 0.235))  // #26303c

    /// The chrome hue. Selection, focus, the send button, own bubbles.
    ///
    /// Steps back on Slate rather than brightening: a dark console wants its
    /// accent quieter so that amber — the one reserved signal — carries.
    static let accent = dynamic(
        light: (0.247, 0.294, 0.690),  // #3f4bb0
        dark: (0.498, 0.706, 0.847))  // #7fb4d8

    /// **Amber, and it means exactly one thing: the operator owes someone an
    /// answer.**
    ///
    /// It appears on a pending decision and nowhere else — not on unread
    /// badges, not on hover, not on warnings, not on the connection bar. The
    /// console spec calls any other use a review defect, and that rule crossed
    /// to iOS unchanged. If you are reaching for this and you are not drawing
    /// a decision, reach for something else.
    ///
    /// Darkened on Paper and lifted on Slate so it reads as the same signal on
    /// both grounds rather than the same *ink* on both grounds.
    static let signal = dynamic(
        light: (0.659, 0.400, 0.039),  // #a8660a
        dark: (0.910, 0.627, 0.180))  // #e8a02e

    static let danger = dynamic(
        light: (0.780, 0.243, 0.243),
        dark: (0.878, 0.416, 0.416))

    /// A room that is working. Never amber — this is good news, and amber is
    /// reserved for what a reader owes.
    static let ok = dynamic(
        light: (0.184, 0.490, 0.357),  // #2f7d5b
        dark: (0.373, 0.733, 0.573))  // #5fbb92

    // MARK: - Type
    //
    // Each style says what it is *for*, not what it looks like, so a caller
    // picks by meaning and the ramp stays consistent.

    /// What an agent wrote. Serif, because the timeline is a reading surface.
    static let body = Font.system(.body, design: .serif)
    /// What the operator wrote. Sans — a command, not prose.
    static let own = Font.system(.body)
    /// Sigils, roles, timestamps, counts. Data.
    ///
    /// **Monospaced on Paper, plain on Slate**, and that is a legibility
    /// decision rather than a stylistic one: SF Mono at caption size on a dark
    /// ground loses its counters and greys out, while on warm paper it reads
    /// as the record-keeping it is. Tabular figures are kept either way, so
    /// columns of timestamps still line up.
    static func meta(dark: Bool) -> Font {
        Font.system(.caption, design: dark ? .default : .monospaced).monospacedDigit()
    }

    /// A room or agent name.
    ///
    /// **Serif on Paper, sans on Slate.** The serif name is what makes the
    /// roster read as a record rather than a chat app, and it rhymes with the
    /// timeline's prose; on Slate the same face goes thin and the sans holds
    /// up better.
    static func name(dark: Bool) -> Font {
        dark
            ? Font.system(.subheadline, weight: .semibold)
            : Font.system(.subheadline, design: .serif, weight: .semibold)
    }

    /// Which appearance-dependent face a view wants.
    enum Face { case meta, name }

    /// Code, inline or block.
    static let code = Font.system(.callout, design: .monospaced)
}

/// Applies a face that changes with the appearance.
///
/// **A modifier rather than a static, and that distinction is the bug it
/// fixes.** The faces were computed properties reading
/// `UITraitCollection.current`, which SwiftUI has no reason to observe — so
/// switching between light and dark left the old typography on screen until
/// the app was relaunched. Reading `\.colorScheme` from the environment makes
/// the dependency one SwiftUI tracks, and the change lands immediately.
///
/// The colours needed no such fix: a `UIColor` built from a trait-resolving
/// provider is re-resolved by UIKit on every appearance change already.
private struct ThemedFace: ViewModifier {
    @Environment(\.colorScheme) private var scheme
    let face: Theme.Face

    func body(content: Content) -> some View {
        let dark = scheme == .dark
        return content.font(face == .meta ? Theme.meta(dark: dark) : Theme.name(dark: dark))
    }
}

extension View {
    /// Sigils, roles, timestamps, counts.
    func metaFace() -> some View { modifier(ThemedFace(face: .meta)) }
    /// A room or agent name.
    func nameFace() -> some View { modifier(ThemedFace(face: .name)) }
}
