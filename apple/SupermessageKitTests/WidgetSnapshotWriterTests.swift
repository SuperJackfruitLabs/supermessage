import Foundation
import Testing

@testable import SupermessageKit

/// The lock and the atomic write around a snapshot merge. Foundation only —
/// the merge is a stand-in that counts, so a lost update shows as a count
/// that came up short.
struct WidgetSnapshotWriterTests {
    private func directory() -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("widget-writer-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    /// Read the stored count, add one: the shape of every real merge.
    private static func increment(_ stored: String?) -> WidgetSnapshotWriter.Result {
        let count = stored.flatMap { Int($0) } ?? 0
        return .init(json: String(count + 1), changed: true, reload: false)
    }

    @Test("two writers, each reading and writing back, never lose one another's write")
    func twoWritersLoseNothing() async {
        let dir = directory()
        // Two writers with their own descriptors, as the app and the
        // extension have: `flock` is per open file, so a lock shared by one
        // writer object would prove nothing about two processes.
        let app = WidgetSnapshotWriter(directory: dir)
        let extensionWriter = WidgetSnapshotWriter(directory: dir)
        let rounds = 200
        await withTaskGroup(of: Void.self) { group in
            for writer in [app, extensionWriter] {
                group.addTask {
                    for _ in 0..<rounds {
                        writer.update { stored in
                            // Widen the window between the read and the
                            // write, where an unlocked writer loses.
                            usleep(50)
                            return Self.increment(stored)
                        }
                    }
                }
            }
        }
        #expect(WidgetSnapshotStore.readJSON(in: dir) == String(2 * rounds))
    }

    @Test("a merge that changed nothing is not written")
    func unchangedIsNotWritten() throws {
        let dir = directory()
        let writer = WidgetSnapshotWriter(directory: dir)
        writer.update { _ in .init(json: "first", changed: true, reload: true) }
        let result = writer.update { stored in
            #expect(stored == "first")
            return .init(json: "second", changed: false, reload: false)
        }
        #expect(result?.changed == false)
        #expect(WidgetSnapshotStore.readJSON(in: dir) == "first")
    }

    @Test("the first merge is handed nothing, not an empty string")
    func firstIsNil() {
        let writer = WidgetSnapshotWriter(directory: directory())
        var seen: String?? = .none
        writer.update { stored in
            seen = .some(stored)
            return .init(json: "x", changed: true, reload: false)
        }
        #expect(seen == .some(nil))
    }
}
