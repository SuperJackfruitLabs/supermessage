import Foundation
import Observation
import SupermessageFFI

/// Who is typing in the focused room.
///
/// Scoped to that room because the channel is: the core only reports typing
/// for the room it has focused, so there is nothing to show on a roster row
/// and pretending otherwise would invent it.
@MainActor
@Observable
public final class TypingStore {
    /// Who, by **user id**, and what to call them.
    ///
    /// Keyed on the id rather than the name, because the name is not an
    /// identity: the core hands out `label` ("Super Chotu") for the line and
    /// a message arrives carrying `senderName` ("Super Chotu (Hermes on
    /// Guild)"), and matching one against the other is how the indicator got
    /// stuck. Two strings that describe the same person are not the same
    /// string; the id is.
    public private(set) var typers: [(userId: String, label: String)] = []

    private var roomId: String?

    public init() {}

    public func handle(roomId: String, users: [TypingUserDto]) {
        guard roomId == self.roomId else { return }
        typers = users.map { ($0.userId, $0.label) }
    }

    /// Someone spoke, so they are no longer about to.
    ///
    /// Matrix typing notices expire on a server-side timeout, and a sender
    /// that never explicitly retracts one leaves the line up for as long as
    /// that timeout runs — which is why "X is typing…" sat on screen long
    /// after X's message had arrived. The client does not have to wait for
    /// the timeout: the message is better evidence than the notice.
    ///
    /// **Takes user ids.** It used to take display names, and the names it
    /// was given were the timeline's composed attribution while the ones it
    /// held were the raw profile names — so nothing ever matched and nothing
    /// was ever removed. The bug was invisible because the code read as
    /// though it did the right thing.
    ///
    /// Deliberately not latching. An agent that sends one message and starts
    /// writing the next is typing again, and the next notice must be able to
    /// bring the line back.
    public func messagesArrived(from senderIds: [String]) {
        guard !typers.isEmpty else { return }
        typers.removeAll { senderIds.contains($0.userId) }
    }

    public func focus(_ roomId: String?) {
        self.roomId = roomId
        typers = []
    }

    /// The line to show, or `nil` when nobody is typing.
    ///
    /// Names rather than a count: in a room of agents, *which* one is about to
    /// speak is the useful half.
    public var line: String? {
        let names = typers.map(\.label)
        switch names.count {
        case 0: return nil
        case 1: return "\(names[0]) is typing…"
        case 2: return "\(names[0]) and \(names[1]) are typing…"
        default: return "\(names[0]) and \(names.count - 1) others are typing…"
        }
    }
}
