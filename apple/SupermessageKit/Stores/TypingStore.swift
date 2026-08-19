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

    /// Someone spoke, so they are no longer about to.
    ///
    /// Matrix typing notices expire on a server-side timeout, and a sender
    /// that never explicitly retracts one leaves the line up for as long as
    /// that timeout runs — which is why "ganesha is typing…" sat on screen
    /// long after ganesha's message had arrived. The client does not have to
    /// wait for the timeout: the message is better evidence than the notice.
    ///
    /// Deliberately not latching. An agent that sends one message and starts
    /// writing the next is typing again, and the next notice must be able to
    /// bring the line back.
    public func messagesArrived(from senders: [String]) {
        guard !names.isEmpty else { return }
        names.removeAll { senders.contains($0) }
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
