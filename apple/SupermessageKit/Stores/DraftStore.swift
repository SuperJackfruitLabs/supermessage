import Foundation
import Observation

/// What is half-typed, per room.
///
/// Scoped by room and kept when the reader switches away, because a draft that
/// vanished on a room switch would lose work — and the desktop already learned
/// that the *opposite* mistake is worse: a draft that followed the reader into
/// another room once put a half-written message in front of the wrong agent.
@MainActor
@Observable
public final class DraftStore {
    private var drafts: [String: String] = [:]

    public init() {}

    public func draft(for roomId: String) -> String {
        drafts[roomId] ?? ""
    }

    public func set(_ text: String, for roomId: String) {
        if text.isEmpty {
            drafts.removeValue(forKey: roomId)
        } else {
            drafts[roomId] = text
        }
    }

    public func clear(_ roomId: String) {
        drafts.removeValue(forKey: roomId)
    }

    public func clearAll() {
        drafts.removeAll()
    }
}
