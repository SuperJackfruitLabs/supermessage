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
    ///
    /// **The list's own background, coloured — not hidden and painted
    /// behind.** On iOS 26 devices the bars' glass samples what is under it
    /// at the instant light and dark switch. `scrollContentBackground(.hidden)`
    /// with a colour behind the list left the glass sampling the old page and
    /// keeping it — dark buttons and tab bar over a light page — until a
    /// scroll (builds 19–20). Bisected on the phone: hiding the list's
    /// background is the trigger, whatever is painted behind it; a dynamic
    /// `UIColor` on the collection view itself, which UIKit resolves at the
    /// switch, is correct at every step (2026-09-25).
    ///
    /// iOS 26 only: before it there is no glass to lag, the collection view
    /// sits where `ListGround` cannot find it (an iOS 18 list stayed system
    /// black), and hiding the background and painting behind has always
    /// been right.
    @ViewBuilder
    func paletteListGround() -> some View {
        if #available(iOS 26, *) {
            background(ListGround(choice: ThemeState.shared.choice, role: \.surface))
                .softEdgesUnderBars()
        } else {
            scrollContentBackground(.hidden).background(Theme.surface)
        }
    }

    /// iOS 26: how rows meet the floating bars.
    ///
    /// **No edge effect, top or bottom.** The effect fades content toward the
    /// system's background colour, so over our own page colour it drew a
    /// solid strip behind the tab bar with the list cut off at its edge
    /// (screenshot, 2026-09-25) — on a system-coloured page the same fade is
    /// invisible, which is why Mail shows none. Hidden, the rows run behind
    /// the tab bar and its glass blurs them itself.
    ///
    /// The top likewise: the same fade drew a band behind the header, and an
    /// opaque classic header beside a see-through tab bar would be two
    /// designs on one screen. Compiler-guarded: iOS 26 SDK.
    @ViewBuilder
    fileprivate func softEdgesUnderBars() -> some View {
        #if compiler(>=6.2)
        if #available(iOS 26, *) {
            scrollEdgeEffectHidden(true, for: .all)
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
    ///
    /// Coloured on the list itself, for the reason `paletteListGround` gives:
    /// hidden and painted behind, the Done button in Account kept the old
    /// scheme's glass after a switch (2026-09-25).
    @ViewBuilder
    func paletteGroupedGround() -> some View {
        if #available(iOS 26, *) {
            background(ListGround(choice: ThemeState.shared.choice, role: \.surfaceSunken))
        } else {
            scrollContentBackground(.hidden).background(Theme.surfaceSunken)
        }
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


/// Colours the collection view a `List` is drawn in, with a palette role as
/// a dynamic colour — see `paletteListGround`.
///
/// Sits in the list's `.background`, which SwiftUI places beside the
/// collection view rather than inside it, so the list is found among the
/// nearest ancestors' subviews. If a future SwiftUI draws a `List`
/// differently this finds nothing and the list keeps the system's
/// background: wrong colour, never a broken screen.
private struct ListGround: UIViewRepresentable {
    /// Here so a change of accent or dark style re-runs `updateUIView`.
    let choice: ThemeChoice
    let role: KeyPath<Palette, Color>

    func makeUIView(context: Context) -> Colourer {
        let view = Colourer()
        view.isUserInteractionEnabled = false
        return view
    }

    func updateUIView(_ view: Colourer, context: Context) {
        let choice = choice
        let role = role
        view.color = UIColor { traits in
            UIColor(choice.palette(dark: traits.userInterfaceStyle == .dark)[keyPath: role])
        }
        view.apply()
        // And after this pass, for a list that is not in the hierarchy yet.
        DispatchQueue.main.async { view.apply() }
    }

    /// Colours the list on every layout as well as on update: layout runs
    /// before the first frame is drawn, so the list is never seen in the
    /// system's colour — not on screen, and not in a preview captured the
    /// moment it is laid out.
    final class Colourer: UIView {
        var color: UIColor?

        override func layoutSubviews() {
            super.layoutSubviews()
            apply()
        }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            apply()
        }

        func apply() {
            guard let color else { return }
            var ancestor = superview
            for _ in 0..<6 {
                if let list = ancestor?.firstList() {
                    if list.backgroundColor != color { list.backgroundColor = color }
                    return
                }
                ancestor = ancestor?.superview
            }
        }
    }
}

extension UIView {
    /// The first collection view under this one that is a list, not a bar.
    ///
    /// On iOS 26 the tab bar and the navigation bar are built on collection
    /// views of their own. The first version of this took the first one it
    /// met, which was the tab bar's: a solid strip of page colour behind the
    /// glass, the list cut off at its edge (2026-09-25). Bars are skipped
    /// whole — nothing inside one is a list.
    fileprivate func firstList() -> UICollectionView? {
        for sub in subviews {
            if sub is UITabBar || sub is UINavigationBar { continue }
            if let list = sub as? UICollectionView { return list }
            if let match = sub.firstList() { return match }
        }
        return nil
    }
}
