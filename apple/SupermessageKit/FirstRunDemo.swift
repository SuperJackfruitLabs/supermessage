import Foundation
import Observation
import SupermessageFFI

/// The first-run demo: a sample agent asks a permission and the reader
/// approves it.
///
/// **Local, and nothing is sent.** This is not a Matrix room and holds no
/// session — there is no client in here to send through, which is the
/// strongest form of that promise. The script (every line of copy, the card,
/// the pacing) and the step rules come from the core; this object only holds
/// the step and the reader's answer.
@MainActor
@Observable
public final class FirstRunDemo {
    public private(set) var step: FirstRunStep = .greeting
    /// The option the reader chose, once they have.
    public private(set) var answer: String?

    /// What the demo says and shows, from the core.
    public let script = firstRunScript()

    public init() {}

    /// Move the conversation along. Each call advances one step and stops at
    /// the card: only the reader's answer gets past `waiting`.
    public func advance() {
        step = firstRunAdvance(step: step)
    }

    /// Answer the card. Returns whether the answer was taken — `false` when
    /// there is no card to answer yet, or it was already answered.
    @discardableResult
    public func answer(_ optionId: String) -> Bool {
        guard let next = firstRunAnswer(step: step) else { return false }
        answer = optionId
        step = next
        return true
    }
}
