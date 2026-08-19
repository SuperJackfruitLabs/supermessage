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

    /// The chrome hue. Selection, focus, the send button, own bubbles.
    static let accent = Color(red: 0.259, green: 0.286, blue: 0.769)  // #4249c4

    /// **Amber, and it means exactly one thing: the operator owes someone an
    /// answer.**
    ///
    /// It appears on a pending decision and nowhere else — not on unread
    /// badges, not on hover, not on warnings, not on the connection bar. The
    /// console spec calls any other use a review defect, and that rule crossed
    /// to iOS unchanged. If you are reaching for this and you are not drawing
    /// a decision, reach for something else.
    static let signal = Color(red: 0.851, green: 0.541, blue: 0.043)  // #d98a0b

    static let danger = Color(red: 0.784, green: 0.243, blue: 0.243)

    // MARK: - Type
    //
    // Each style says what it is *for*, not what it looks like, so a caller
    // picks by meaning and the ramp stays consistent.

    /// What an agent wrote. Serif, because the timeline is a reading surface.
    static let body = Font.system(.body, design: .serif)
    /// What the operator wrote. Sans — a command, not prose.
    static let own = Font.system(.body)
    /// Sigils, roles, timestamps, counts. Data.
    static let meta = Font.system(.caption, design: .monospaced)
    /// A room or agent name.
    static let name = Font.system(.subheadline, weight: .semibold)
    /// Code, inline or block.
    static let code = Font.system(.callout, design: .monospaced)
}
