// Compiled into SupermessageKit and the Notification Service Extension — the
// two processes that write the widgets' snapshot. Foundation only: the merge
// itself is the core's, handed in as `transform`.

import Foundation

/// Read, merge and write the widgets' snapshot as one step, across processes.
///
/// The app and the Notification Service Extension both write it, and each
/// write is read → merge (in the core) → write. Two of those interleaved
/// would lose one: the extension reads, the app reads, the extension writes a
/// new decision, the app writes its roster over it — and the decision is
/// gone. So the whole step runs under an exclusive `flock` on a lock file
/// beside the snapshot. `flock` is per open file description and released by
/// the kernel when the process dies, so a writer killed mid-step (the
/// extension's thirty seconds) never leaves the lock held.
///
/// The write itself is atomic (a temporary file renamed over the old), so a
/// reader that takes no lock — the widget — sees one whole snapshot.
///
/// Which write wins is not decided here. The core compares what it is handed
/// with what is stored — a roster older than the stored one loses, a pushed
/// line older than the agent's last one is ignored (`core::widget`) — and
/// this only guarantees it is handed the latest.
public struct WidgetSnapshotWriter: Sendable {
    /// What the core returned for one step.
    public struct Result: Equatable, Sendable {
        public var json: String
        public var changed: Bool
        public var reload: Bool

        public init(json: String, changed: Bool, reload: Bool) {
            self.json = json
            self.changed = changed
            self.reload = reload
        }
    }

    public let directory: URL

    public init(directory: URL) {
        self.directory = directory
    }

    /// The App Group's, or `nil` when this build has no group.
    public static func shared() -> WidgetSnapshotWriter? {
        WidgetSnapshotStore.directory().map(WidgetSnapshotWriter.init(directory:))
    }

    var lockURL: URL { directory.appendingPathComponent("snapshot.lock") }

    /// Hand the stored JSON (`nil` when there is none) to `transform`, and
    /// store what it returns when it says something changed — all while
    /// holding the lock. `nil` when the lock or the write failed; the
    /// snapshot is then as it was.
    @discardableResult
    public func update(_ transform: (String?) -> Result) -> Result? {
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let fd = open(lockURL.path, O_RDWR | O_CREAT, 0o644)
        guard fd >= 0 else { return nil }
        defer { close(fd) }
        // Blocking: the other writer holds it for one merge, milliseconds.
        guard flockRetrying(fd, LOCK_EX) == 0 else { return nil }
        defer { _ = flock(fd, LOCK_UN) }

        let result = transform(WidgetSnapshotStore.readJSON(in: directory))
        guard result.changed else { return result }
        do {
            try Data(result.json.utf8).write(
                to: WidgetSnapshotStore.snapshotURL(in: directory), options: .atomic)
        } catch {
            return nil
        }
        return result
    }

    /// `flock`, again if a signal interrupted it.
    private func flockRetrying(_ fd: Int32, _ operation: Int32) -> Int32 {
        while true {
            let status = flock(fd, operation)
            if status == 0 || errno != EINTR { return status }
        }
    }
}
