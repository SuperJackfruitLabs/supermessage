// Compiled into TWO targets: SupermessageKit, whose `AgentAvatarCache` writes
// here, and the SupermessageWidgets extension, which only reads. Foundation
// only: the extension has no core, and the index below is how it finds a
// file the core named.

import Foundation

/// The agents' avatars, in `<App Group>/avatars` (spec 2026-09-30, B4).
///
/// A Live Activity cannot load an image from the network, so the app keeps
/// each agent's picture here, 64×64 PNG, while it runs; the fleet card reads
/// it by the agent's Matrix user id and draws initials when there is none.
///
/// **The file's name is the core's** (`widget::avatar_file_name`: the SHA-256
/// of the user id). The extension cannot ask the core, so the app writes an
/// index beside the files — user id to file name, exactly as the core gave
/// it — and this reads that. One rule, in one place; this only looks it up.
public enum AgentAvatarStore {
    static let indexName = "index.json"

    /// `<App Group>/avatars`, created if missing; `nil` without the group.
    public static func directory() -> URL? {
        guard
            let container = FileManager.default.containerURL(
                forSecurityApplicationGroupIdentifier: WidgetSnapshotStore.appGroup)
        else { return nil }
        let directory = container.appendingPathComponent("avatars", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    /// User id → file name, as the app last wrote it.
    public static func index(in directory: URL) -> [String: String] {
        guard let data = try? Data(contentsOf: directory.appendingPathComponent(indexName)),
            let index = try? JSONDecoder().decode([String: String].self, from: data)
        else { return [:] }
        return index
    }

    /// The cached PNG for `userId`, or `nil` when there is none.
    public static func imageData(for userId: String, in directory: URL? = directory()) -> Data? {
        guard let directory, let name = index(in: directory)[userId],
            // A name the core made is a bare file name; anything else is not
            // one this reads.
            !name.contains("/"), !name.hasPrefix(".")
        else { return nil }
        return try? Data(contentsOf: directory.appendingPathComponent(name))
    }
}
