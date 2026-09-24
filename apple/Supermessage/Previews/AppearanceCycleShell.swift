import SwiftUI
import UIKit

#if DEBUG
/// `-appearanceCycleShell`: on the real signed-in shell, switches the
/// account's mode dark, light, dark, light a few seconds apart and saves a
/// picture of the window after each — to `Documents/theme-diag/`, pulled off
/// the phone with `devicectl device copy from`. How the plain-list glass bug
/// (see `RoomListView`) was found and checked on a device without a hand on
/// it; the simulator does not show it.
enum AppearanceCycleShell {
    static let isOn = ProcessInfo.processInfo.arguments.contains("-appearanceCycleShell")

    @MainActor
    static func run() async {
        guard isOn else { return }
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("theme-diag")
        try? FileManager.default.removeItem(at: dir)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try? await Task.sleep(for: .seconds(6))
        snapshot(to: dir, name: "0-start")
        for (i, mode) in ["dark", "light", "dark", "light"].enumerated() {
            UserDefaults.standard.set(mode, forKey: AppearanceSettings.modeKey)
            try? await Task.sleep(for: .seconds(2.5))
            snapshot(to: dir, name: "\(i + 1)-\(mode)")
        }
        UserDefaults.standard.set(AppearanceMode.system.rawValue, forKey: AppearanceSettings.modeKey)
    }

    /// The same cycle, driven through a closure instead of the stored mode —
    /// for the pure `.preferredColorScheme` control.
    @MainActor
    static func run(setting: @MainActor (ColorScheme?) -> Void) async {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("theme-diag")
        try? FileManager.default.removeItem(at: dir)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try? await Task.sleep(for: .seconds(6))
        snapshot(to: dir, name: "0-start")
        for (i, scheme) in [ColorScheme.dark, .light, .dark, .light].enumerated() {
            setting(scheme)
            try? await Task.sleep(for: .seconds(2.5))
            snapshot(to: dir, name: "\(i + 1)-\(scheme == .dark ? "dark" : "light")")
        }
    }

    @MainActor
    private static func snapshot(to dir: URL, name: String) {
        guard let window = UIApplication.shared.connectedScenes
            .compactMap({ ($0 as? UIWindowScene)?.keyWindow }).first
        else { return }
        let renderer = UIGraphicsImageRenderer(bounds: window.bounds)
        let image = renderer.image { _ in
            _ = window.drawHierarchy(in: window.bounds, afterScreenUpdates: false)
        }
        try? image.pngData()?.write(to: dir.appendingPathComponent("\(name).png"))
    }
}
#endif
