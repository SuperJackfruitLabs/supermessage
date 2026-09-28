import Foundation
import SupermessageFFI

extension CoreOptions: @retroactive @unchecked Sendable {}

/// Where a core keeps its stores and secrets, for the app and for the
/// Notification Service Extension.
///
/// The extension decrypts a push with the app's own crypto store and signs in
/// with the app's own session, so both must be somewhere both processes can
/// reach: the stores in the App Group container, the secrets in a keychain
/// access group both are entitled to. Shared as source with the extension
/// (see `apple/SupermessageNotificationService/nse.yml`), so the two cannot
/// disagree about a path.
///
/// **Only when this build has the App Group.** A build generated without it
/// (`SM_NSE`/`SM_EXTENSIONS` off) has no container, and keeps everything in the
/// app's own Application Support exactly as before.
public enum CoreLocation {
    /// The App Group the app and its extensions share.
    public static let appGroup = "group.dev.supermessage.ios"

    /// Info.plist keys, from the `SM_KEYCHAIN_GROUP` and
    /// `SM_LEGACY_KEYCHAIN_GROUP` build settings — empty unless the build has
    /// the `keychain-access-groups` entitlement to go with them.
    public static let keychainGroupKey = "SMKeychainAccessGroup"
    public static let legacyKeychainGroupKey = "SMLegacyKeychainAccessGroup"

    /// Which process a core is for. The raw value is the name it holds the
    /// cross-process store lock under, and must differ between the two.
    public enum Process: String, Sendable {
        case app = "main"
        case notificationService = "nse"
    }

    /// The options for `process`, or `nil` when it cannot work here — the
    /// extension without the App Group has no store to read.
    ///
    /// Pure, so the choice is testable without a container:
    /// - `groupDirectory` is where the stores go when there is a group;
    /// - `legacyDirectory` is where the app kept them before, and where it
    ///   keeps them still when there is no group. The app moves them from
    ///   there once; the extension never had any.
    public static func options(
        process: Process, info: [String: Any]?, groupDirectory: URL?, legacyDirectory: URL
    ) -> CoreOptions? {
        let keychainGroup = nonEmpty(info?[keychainGroupKey])
        switch (process, groupDirectory) {
        case (.notificationService, nil):
            return nil
        case (.notificationService, let group?):
            return CoreOptions(
                dataDir: group.path, processName: process.rawValue, legacyDataDir: nil,
                keychainAccessGroup: keychainGroup, legacyKeychainAccessGroup: nil)
        case (.app, nil):
            return CoreOptions(
                dataDir: legacyDirectory.path, processName: process.rawValue, legacyDataDir: nil,
                keychainAccessGroup: nil, legacyKeychainAccessGroup: nil)
        case (.app, let group?):
            return CoreOptions(
                dataDir: group.path, processName: process.rawValue,
                legacyDataDir: legacyDirectory.path, keychainAccessGroup: keychainGroup,
                legacyKeychainAccessGroup: keychainGroup == nil
                    ? nil : nonEmpty(info?[legacyKeychainGroupKey]))
        }
    }

    /// The options for `process` in this build, on this device.
    public static func current(_ process: Process) -> CoreOptions? {
        options(
            process: process, info: Bundle.main.infoDictionary, groupDirectory: groupDirectory(),
            legacyDirectory: legacyDirectory())
    }

    /// `<App Group>/supermessage`, or `nil` without the group.
    public static func groupDirectory() -> URL? {
        guard
            let container = FileManager.default.containerURL(
                forSecurityApplicationGroupIdentifier: appGroup)
        else { return nil }
        let directory = container.appendingPathComponent("supermessage", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    /// `Application Support/supermessage` in this process's own container —
    /// where every build before the extension kept the stores.
    public static func legacyDirectory() -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let directory = base.appendingPathComponent("supermessage", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    /// An unexpanded `$(…)` counts as empty: a build setting nobody set must
    /// not become an access group the keychain refuses.
    private static func nonEmpty(_ value: Any?) -> String? {
        guard let text = (value as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
            !text.isEmpty, !text.contains("$(")
        else { return nil }
        return text
    }
}
