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

    /// What the window is told. `.unspecified` follows the phone.
    var interfaceStyle: UIUserInterfaceStyle {
        switch self {
        case .system: .unspecified
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

/// The account's choice of dark style and accent.
///
/// Read by `Theme` from `ThemeState`, not carried as a trait — see
/// `Theme.dynamic` for what the trait route did on iOS 26.
struct ThemeChoice: Hashable, Sendable {
    var darkStyle: DarkStyle
    /// A name from `ThemeAccents.names`, or `nil` for the house violet.
    var accent: String?

    static let standard = ThemeChoice(darkStyle: .tinted, accent: nil)

    /// The whole palette for this choice, light or dark.
    ///
    /// Black keeps its black grounds whatever the accent — that is what
    /// choosing it means — and takes only the accent's own roles.
    func palette(dark: Bool) -> Palette {
        let set = accent.flatMap(ThemeAccents.set(named:))
        guard dark else { return set?.paper ?? ThemeTokens.paper }
        if darkStyle == .black { return set?.black ?? ThemeTokens.black }
        return set?.dark ?? ThemeTokens.dark
    }
}

/// The choice in force. Observable, so a body that reads a `Theme` colour
/// is redrawn when it changes; set only by `appliesAppearance`.
@Observable
final class ThemeState: @unchecked Sendable {
    static let shared = ThemeState()
    var choice: ThemeChoice
    /// The scheme the root sees. `nil` outside the app (previews, tests),
    /// where colours stay dynamic — see `ThemeColors`.
    var scheme: ColorScheme?

    /// Starts on the stored choice, so the first frame is not drawn in the
    /// house violet and then repainted.
    private init() {
        // Under tests, the house theme: previews and UI tests render in this
        // app and must not take whatever accent the simulator last stored.
        if ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil {
            choice = .standard
            return
        }
        let defaults = UserDefaults.standard
        let accent = defaults.string(forKey: AppearanceSettings.accentKey) ?? ""
        choice = ThemeChoice(
            darkStyle: DarkStyle(rawValue: defaults.string(forKey: AppearanceSettings.darkStyleKey) ?? "")
                ?? .tinted,
            accent: accent.isEmpty ? nil : accent)
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
    static let underTest = ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil

    @AppStorage(AppearanceSettings.modeKey) private var mode = AppearanceMode.system.rawValue
    @AppStorage(AppearanceSettings.darkStyleKey) private var darkStyle = DarkStyle.tinted.rawValue
    @AppStorage(AppearanceSettings.accentKey) private var accent = ""
    /// Right here at the root, where it has always been right; recorded so
    /// the colours below do not have to ask UIKit (see `ThemeColors`).
    @Environment(\.colorScheme) private var scheme

    func body(content: Content) -> some View {
        let choice = ThemeChoice(
            darkStyle: DarkStyle(rawValue: darkStyle) ?? .tinted,
            accent: accent.isEmpty ? nil : accent)
        content
            #if DEBUG
            .task { await AppearanceCycleShell.run() }
            #endif
            .onChange(of: choice, initial: true) {
                guard !Self.underTest else { return }
                ThemeState.shared.choice = choice
            }
            .onChange(of: scheme, initial: true) {
                // Not under tests: previews render in this app, each in its
                // own scheme, and a scheme recorded here would paint every
                // dark preview in the host's light (2026-09-25). Unrecorded,
                // colours stay dynamic — see `ThemeColors`.
                guard !Self.underTest else { return }
                ThemeState.shared.scheme = scheme
            }
            .background(
                WindowTraits(style: (AppearanceMode(rawValue: mode) ?? .system).interfaceStyle))
    }
}

/// Puts the scheme on the window.
///
/// **Set on the window, not with `.preferredColorScheme`.** Build 19 used
/// the modifier; the window is the one place every part of the app,
/// UIKit's included, reads the scheme from. (The glass lagging a switch in
/// builds 19 and 20 turned out to be the plain list style — see
/// `RoomListView` — not either way of setting it.)
private struct WindowTraits: UIViewRepresentable {
    let style: UIUserInterfaceStyle

    func makeUIView(context: Context) -> Applier {
        let view = Applier()
        view.isUserInteractionEnabled = false
        return view
    }

    func updateUIView(_ view: Applier, context: Context) {
        view.style = style
        view.apply()
    }

    /// Applies as soon as it joins a window, so a launch does not draw one
    /// frame in the phone's scheme before the account's.
    final class Applier: UIView {
        var style = UIUserInterfaceStyle.unspecified

        override func didMoveToWindow() {
            super.didMoveToWindow()
            apply()
        }

        func apply() {
            guard let window, window.overrideUserInterfaceStyle != style else { return }
            window.overrideUserInterfaceStyle = style
        }
    }
}

extension View {
    /// The account's appearance: scheme, dark style and accent.
    func appliesAppearance() -> some View { modifier(AppliesAppearance()) }
}
