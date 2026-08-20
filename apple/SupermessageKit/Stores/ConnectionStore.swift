import Foundation
import Observation
import SupermessageFFI

/// Whether the core is talking to the homeserver.
///
/// The vocabulary is the core's — `"live"`, `"connecting"`, `"offline"` — and
/// it is deliberately not re-modelled into a Swift enum here. A string the
/// core owns can gain a value without this app failing to build, and the
/// `unknown` case below is what that costs: one branch instead of a crash.
@MainActor
@Observable
public final class ConnectionStore {
    public private(set) var state: Connection = .connecting
    public private(set) var message: String?

    public enum Connection: Equatable {
        case live
        case connecting
        case offline
        /// Sync failed. The core carries the reason in `message`, and it now
        /// retries on a backoff rather than staying broken — this state is
        /// what the reader sees while that is happening.
        case error
        /// Something the core started saying that this build has not been
        /// taught. Rendered as connecting, because that is the honest reading
        /// of "we do not know yet".
        case unknown(String)
    }

    public init() {}

    public func apply(_ raw: ConnectionState) {
        switch raw.state {
        case "live": state = .live
        case "connecting": state = .connecting
        case "offline": state = .offline
        // The core emits this on a sync failure and it was missing here, so
        // it fell through to `.unknown("error")` and the bar showed the bare
        // word "error" with no explanation beside it.
        case "error": state = .error
        default: state = .unknown(raw.state)
        }
        message = raw.message
    }

    /// Whether the bar should be on screen at all.
    ///
    /// Live is the common case and says nothing worth a row of chrome. It is
    /// never amber — amber means the operator owes someone an answer, and a
    /// flaky connection is not that.
    public var isWorthShowing: Bool {
        state != .live
    }
}
