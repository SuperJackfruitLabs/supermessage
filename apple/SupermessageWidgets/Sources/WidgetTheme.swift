import SwiftUI
import UIKit

/// The palette, for the extension.
///
/// The same binding `Theme` makes in the app — `paper` for light, `dark`
/// for dark — over the same generated `ThemeTokens.swift`, which this target
/// compiles from `apple/Supermessage/Generated`. `Theme.swift` itself is not
/// shared: it carries fonts and faces the extension has no use for.
enum WidgetTheme {
    private static func dynamic(_ role: KeyPath<Palette, Color>) -> Color {
        Color(
            UIColor { traits in
                let palette = traits.userInterfaceStyle == .dark ? ThemeTokens.dark : ThemeTokens.paper
                return UIColor(palette[keyPath: role])
            })
    }

    static let surface = dynamic(\.surface)
    static let surfaceSunken = dynamic(\.surfaceSunken)
    static let surfaceRaised = dynamic(\.surfaceRaised)
    static let border = dynamic(\.border)
    static let content = dynamic(\.content)
    static let contentMuted = dynamic(\.contentMuted)
    static let contentFaint = dynamic(\.contentFaint)
    static let accent = dynamic(\.accent)
    static let accentSoft = dynamic(\.accentSoft)
    static let ok = dynamic(\.ok)
    // Deliberately no `signal`. Amber means a pending decision and only
    // `DecisionCard` may draw it (AGENTS.md); a count of rooms that need you
    // is a pointer to decisions, not one, so it is drawn in the accent.
}
