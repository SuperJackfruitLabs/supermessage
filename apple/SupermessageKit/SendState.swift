import Foundation
import SupermessageFFI

/// What happened to a message this account sent.
///
/// The core hands over a `DeliveryState` enum. It used to be a string, kept
/// so a new core value could not break this build — but the core ships inside
/// this app, so there is no version skew to survive, and the string's real
/// cost showed up instead: a preview fixture spelled it `"sending"` and
/// `"failed"`, both rows fell through to "unknown", and the snapshot of the
/// one state a reader must never miss drew two delivered messages. A new
/// core case now fails to compile here, which is the point.
///
/// **Only own messages have one.** A peer's message arrived, which is the only
/// send state a reader could want to know about it.
public enum SendState: Equatable, Sendable {
    /// On its way. Worth showing only once it has been a while — a send that
    /// lands immediately should not flicker a spinner at anyone.
    case sending
    /// The homeserver has it.
    case sent
    /// It did not go. **The one state a reader must never miss**, because the
    /// message is sitting on this phone looking exactly like one that landed.
    case failed

    public init(_ state: DeliveryState?) {
        switch state {
        case .notSentYet: self = .sending
        case .sendingFailed: self = .failed
        // A peer's message carries no send state: it is on the server by
        // definition.
        case .sent, nil: self = .sent
        }
    }

    /// Whether a reader needs to be told.
    ///
    /// A message that landed is the unremarkable case and says nothing; every
    /// bubble carrying a tick is chrome on the ordinary. Failure always shows.
    public var isWorthShowing: Bool {
        switch self {
        case .failed, .sending: return true
        case .sent: return false
        }
    }

    /// The words for it. Plain, because a symbol alone cannot say "tap to try
    /// again" and this is the one place ambiguity costs a message.
    public var label: String? {
        switch self {
        case .sending: return "Sending…"
        case .failed: return "Not sent"
        case .sent: return nil
        }
    }
}
