import Foundation
import SupermessageFFI

extension NotificationAnswer {
    /// The notification answer a widget's button sends: the same two paths a
    /// notification's actions take, so a widget cannot answer differently.
    public init(_ answer: WidgetAnswer) {
        if let gateId = answer.gateId {
            self = .gate(
                roomId: answer.roomId, gateEventId: answer.eventId, gateId: gateId,
                optionId: answer.optionId, comment: nil, prompt: answer.prompt)
        } else {
            self = .permission(roomId: answer.roomId, optionId: answer.optionId)
        }
    }
}

/// A widget's Allow / Reject / Approve button, answered with no room open.
///
/// Runs in the app's process (see `AnswerDecisionIntent`): the snapshot says
/// whether the decision is still owed and what the button means, the tap is
/// marked sent at once so the widget stops offering it, the answer goes the
/// way a notification's does (`NotificationAnswerer`), and a failed send makes
/// the decision owed again.
public enum WidgetAnswering {
    public enum Outcome: Equatable, Sendable {
        /// Delivered. For a gate that is *answered*; the board's receipt
        /// is what resolves it.
        case sent
        /// The snapshot does not owe this answer — resolved, already sent, or
        /// not a button's to send. Nothing went to the room.
        case notOwed
        /// It did not land. The decision is owed again.
        case failed
    }

    /// Answer, and say how it went. `reload` is called whenever the snapshot
    /// changed, so the widgets redraw from it.
    public static func answer(
        roomId: String, eventId: String, optionId: String, feed: WidgetFeed,
        via client: any NotificationAnswering, within budget: Duration = NotificationAnswerer.budget,
        reload: @Sendable () -> Void
    ) async -> Outcome {
        guard let owed = feed.answer(roomId: roomId, eventId: eventId, optionId: optionId) else {
            // Redraw anyway: the widget was showing a button the snapshot no
            // longer has.
            reload()
            return .notOwed
        }
        if feed.markAnswered(roomId: roomId, eventId: eventId, optionId: optionId) { reload() }
        let landed = await NotificationAnswerer.send(
            NotificationAnswer(owed), via: client, within: budget)
        guard landed else {
            if feed.clearAnswer(roomId: roomId, eventId: eventId) { reload() }
            return .failed
        }
        return .sent
    }
}
