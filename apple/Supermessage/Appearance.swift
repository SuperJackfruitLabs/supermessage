import SwiftUI
import UIKit

/// The three appearance choices an account can make: light or dark, which
/// dark, and which accent.
///
/// Telegram's shape, cut down to what the palette can honour. Every value
/// here names a palette in `design/tokens.toml` whose contrast contracts the
/// generator has already checked, so no choice can produce an unreadable
/// screen — which is why there is no colour wheel.
enum AppearanceMode: String, CaseIterable, Identifiable {
    case system, light, dark

    var id: String { rawValue }

    var label: String {
        switch self {
        case .system: "System"
        case .light: "Light"
        case .dark: "Dark"
        }
    }

    /// What `.preferredColorScheme` takes; `nil` follows the phone.
    var colorScheme: ColorScheme? {
        switch self {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }
}

/// Which palette "dark" means.
///
/// `tinted` is the house dark, violet-grey grounds. `black` is true #000 for
/// OLED — D9 kept #000 out of the default, and it stays out: this is a
/// choice somebody makes, not what they are given.
enum DarkStyle: String, CaseIterable, Identifiable {
    case tinted, black

    var id: String { rawValue }
    var label: String { self == .tinted ? "Tinted" : "Black" }
}

/// The resolved choice, carried as a UIKit trait so dynamic colours can see
/// it.
///
/// **Why a trait and not a global.** `Theme`'s colours are `UIColor`
/// providers, and UIKit only re-runs a provider when the trait collection
/// changes. A global the provider read would be right for every colour
/// resolved *after* the change and wrong for every one already on screen
/// until something else happened to redraw it. As a trait, setting it is
/// the change, and everything re-resolves together.
struct ThemeChoice: Equatable, Sendable {
    var darkStyle: DarkStyle
    /// A name from `ThemeAccents.names`, or `nil` for the house violet.
    var accent: String?

    static let standard = ThemeChoice(darkStyle: .tinted, accent: nil)

    /// The palette's accent roles, in the appearance the traits describe.
    func accentRoles(dark: Bool) -> AccentPalette? {
        guard let accent, let set = ThemeAccents.set(named: accent) else { return nil }
        if dark { return darkStyle == .black ? set.black : set.dark }
        return set.paper
    }
}

struct ThemeChoiceTrait: UITraitDefinition {
    static let defaultValue = ThemeChoice.standard
    static let affectsColorAppearance = true
    static let name = "SupermessageThemeChoice"
}

extension UITraitCollection {
    var themeChoice: ThemeChoice { self[ThemeChoiceTrait.self] }
}

extension UIMutableTraits {
    var themeChoice: ThemeChoice {
        get { self[ThemeChoiceTrait.self] }
        set { self[ThemeChoiceTrait.self] = newValue }
    }
}

private struct ThemeChoiceKey: EnvironmentKey {
    static let defaultValue = ThemeChoice.standard
}

/// Bridged so SwiftUI's environment and UIKit's traits are one value: a
/// `Color` built from a `UIColor` provider resolves against a trait
/// collection SwiftUI makes from the environment, and this is how the
/// choice gets into it.
extension ThemeChoiceKey: UITraitBridgedEnvironmentKey {
    static func read(from traitCollection: UITraitCollection) -> ThemeChoice {
        traitCollection.themeChoice
    }

    static func write(to mutableTraits: inout UIMutableTraits, value: ThemeChoice) {
        mutableTraits.themeChoice = value
    }
}

extension EnvironmentValues {
    var themeChoice: ThemeChoice {
        get { self[ThemeChoiceKey.self] }
        set { self[ThemeChoiceKey.self] = newValue }
    }
}

extension ThemeAccents {
    static func set(named name: String) -> AccentSet? {
        switch name {
        case "blue": blue
        case "graphite": graphite
        case "green": green
        case "pink": pink
        case "teal": teal
        default: nil
        }
    }
}

/// The stored settings, and the one modifier that applies them.
enum AppearanceSettings {
    static let modeKey = "appearance.mode"
    static let darkStyleKey = "appearance.darkStyle"
    /// Empty for the house accent: `@AppStorage` has no optional string.
    static let accentKey = "appearance.accent"
}

private struct AppliesAppearance: ViewModifier {
    @AppStorage(AppearanceSettings.modeKey) private var mode = AppearanceMode.system.rawValue
    @AppStorage(AppearanceSettings.darkStyleKey) private var darkStyle = DarkStyle.tinted.rawValue
    @AppStorage(AppearanceSettings.accentKey) private var accent = ""

    func body(content: Content) -> some View {
        let choice = ThemeChoice(
            darkStyle: DarkStyle(rawValue: darkStyle) ?? .tinted,
            accent: accent.isEmpty ? nil : accent)
        content
            .environment(\.themeChoice, choice)
            .preferredColorScheme(AppearanceMode(rawValue: mode)?.colorScheme)
            .background(WindowTraits(choice: choice))
    }
}

/// Puts the choice on the window as well as in the environment.
///
/// The environment reaches SwiftUI views and the UIKit views SwiftUI hosts;
/// the window override reaches what SwiftUI does not own — the timeline's
/// collection view and its cells, sheets' presentation chrome, the keyboard
/// accessory. Both, so there is no surface left on the old accent.
private struct WindowTraits: UIViewRepresentable {
    let choice: ThemeChoice

    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.isUserInteractionEnabled = false
        return view
    }

    func updateUIView(_ view: UIView, context: Context) {
        let choice = choice
        // The window is not there on the first update; a hop gets it.
        DispatchQueue.main.async {
            // Compared against the window's resolved traits: reading an
            // override that was never set throws rather than returning the
            // default.
            guard let window = view.window, window.traitCollection.themeChoice != choice else { return }
            window.traitOverrides.themeChoice = choice
        }
    }
}

extension View {
    /// The account's appearance: scheme, dark style and accent.
    func appliesAppearance() -> some View { modifier(AppliesAppearance()) }
}
