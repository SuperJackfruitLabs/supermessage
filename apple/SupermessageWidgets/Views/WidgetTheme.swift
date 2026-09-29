// Compiled into the widget extension, and into the app so the widgets'
// previews render with its other previews.

import SwiftUI
import UIKit

/// The palette, for the widgets.
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
    /// A turn that failed.
    static let danger = dynamic(\.danger)

    /// Amber, and its ground. **Only `WidgetDecisionCard` may draw these** —
    /// the widgets' counterpart of the app's `DecisionCard`, and like it drawn
    /// only while the decision is still owed (docs/design-language.md §2). A
    /// count of decisions, a "needs you" dot on an agent, a decision already
    /// sent: none of those is a pending decision, and they use the accent.
    static let signal = dynamic(\.signal)
    static let signalSoft = dynamic(\.signalSoft)
}
