import Foundation
import SupermessageFFI

/// What happened to a message this account sent.
///
/// The core's vocabulary is a string — `"notSentYet"`, `"sendingFailed"`,
/// `"sent"` — and it stays a string on the wire for the reason
/// `ConnectionStore` gives: a value the core owns can gain a case without this
/// app failing to build. This is the reading of it, with `unknown` as what
/// that costs.
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
    /// A state this build has not been taught. Drawn as nothing rather than
    /// guessed at.
    case unknown

    public init(_ raw: String?) {
        switch raw {
        case "notSentYet": self = .sending
        case "sendingFailed": self = .failed
        case "sent": self = .sent
        case nil: self = .sent
        default: self = .unknown
        }
    }

    /// Whether a reader needs to be told.
    ///
    /// A message that landed is the unremarkable case and says nothing; every
    /// bubble carrying a tick is chrome on the ordinary. Failure always shows.
    public var isWorthShowing: Bool {
        switch self {
        case .failed, .sending: return true
        case .sent, .unknown: return false
        }
    }

    /// The words for it. Plain, because a symbol alone cannot say "tap to try
    /// again" and this is the one place ambiguity costs a message.
    public var label: String? {
        switch self {
        case .sending: return "Sending…"
        case .failed: return "Not sent"
        case .sent, .unknown: return nil
        }
    }
}
