import SwiftUI

// D9: the palette owns every ground.
//
// A `List` left to itself draws `systemGroupedBackground` behind a grouped
// list and `systemBackground` behind a plain one — which in dark mode is
// #000 and #1c1c1e, neither of them in `design/tokens.toml`. So the app read
// as a stock iOS app with purple buttons. These two modifiers are the one
// place that says what a list stands on, so no screen has to remember.

extension View {
    /// A plain list — the roster, the inbox, the agents directory — on the
    /// page's own ground.
    func paletteListGround() -> some View {
        scrollContentBackground(.hidden)
            .background(Theme.surface)
            .softEdgesUnderBars()
    }

    /// iOS 26: rows blur and fade as they pass under the floating bars,
    /// said explicitly. The build 20 screenshot had a row sharp right up to
    /// the tab bar's edge (2026-09-24); `.soft` is the style Apple gives
    /// iPhone scroll views, stated here so a list on a painted ground cannot
    /// fall back to anything else. Compiler-guarded: iOS 26 SDK.
    @ViewBuilder
    fileprivate func softEdgesUnderBars() -> some View {
        #if compiler(>=6.2)
        if #available(iOS 26, *) {
            scrollEdgeEffectStyle(.soft, for: .all)
        } else {
            self
        }
        #else
        self
        #endif
    }

    /// A grouped list — a panel of settings or details — whose rows sit on
    /// `surface` over a `surfaceSunken` field, which is the same relation a
    /// grouped list has by default, drawn from the palette.
    func paletteGroupedGround() -> some View {
        scrollContentBackground(.hidden)
            .background(Theme.surfaceSunken)
    }

    /// A row in a grouped panel.
    func paletteRow() -> some View {
        listRowBackground(Theme.surface)
    }

    /// A sheet's own ground, so the gap between its content and its edges is
    /// not the system's grey either.
    func paletteSheet() -> some View {
        presentationBackground(Theme.surfaceSunken)
    }
}
