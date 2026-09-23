import Foundation
import Observation

/// The first-run demo: a sample agent asks a permission and the reader
/// approves it.
///
/// **Local, and nothing is sent.** This is not a Matrix room and holds no
/// session — there is no client in here to send through, which is the
/// strongest form of that promise. Every step is a value on this object.
@MainActor
@Observable
public final class FirstRunDemo {
    public enum Step: Int, Comparable, Sendable {
        /// Atlas is typing its first message.
        case greeting
        /// The message has landed; the permission request is on its way.
        case asking
        /// The permission card is on screen, waiting on the reader.
        case waiting
        /// The reader answered; the receipt is showing.
        case approved

        public static func < (lhs: Step, rhs: Step) -> Bool { lhs.rawValue < rhs.rawValue }
    }

    public private(set) var step: Step = .greeting
    /// The option the reader chose, once they have.
    public private(set) var answer: String?

    public static let agentName = "Atlas"
    public static let greeting =
        "Hi — I'm Atlas, a sample agent. I'd like to tidy the release notes before today's build."
    public static let request = "Can I edit docs/release-notes.md?"

    public init() {}

    /// Move the conversation along. Each call advances one step and stops at
    /// the card: only the reader's answer gets past `waiting`.
    public func advance() {
        switch step {
        case .greeting: step = .asking
        case .asking: step = .waiting
        case .waiting, .approved: break
        }
    }

    /// Answer the card. Returns whether the answer was taken — `false` when
    /// there is no card to answer yet, or it was already answered.
    @discardableResult
    public func answer(_ optionId: String) -> Bool {
        guard step == .waiting else { return false }
        answer = optionId
        step = .approved
        return true
    }
}
