import Foundation
import Observation

/// Who is typing in the focused room.
///
/// Scoped to that room because the channel is: the core only reports typing
/// for the room it has focused, so there is nothing to show on a roster row
/// and pretending otherwise would invent it.
@MainActor
@Observable
public final class TypingStore {
    public private(set) var names: [String] = []

    private var roomId: String?

    public init() {}

    public func handle(roomId: String, users: [String]) {
        guard roomId == self.roomId else { return }
        names = users
    }

    public func focus(_ roomId: String?) {
        self.roomId = roomId
        names = []
    }

    /// The line to show, or `nil` when nobody is typing.
    ///
    /// Names rather than a count: in a room of agents, *which* one is about to
    /// speak is the useful half.
    public var line: String? {
        switch names.count {
        case 0: return nil
        case 1: return "\(names[0]) is typing…"
        case 2: return "\(names[0]) and \(names[1]) are typing…"
        default: return "\(names[0]) and \(names.count - 1) others are typing…"
        }
    }
}
