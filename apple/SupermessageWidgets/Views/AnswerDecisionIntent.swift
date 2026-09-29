// Compiled into the widget extension, which draws the buttons, and into the
// app, which runs them.

import AppIntents
import Foundation

/// A widget's Allow / Reject / Approve button.
///
/// ## Where it runs, and why there
///
/// **In the app's process, never the widget extension's.** An intent behind a
/// widget's `Button(intent:)` runs in the extension by default. Answering
/// needs the Matrix client — the core, the account's session from the shared
/// keychain, and the crypto store for an encrypted room — and building one in
/// the widget extension would be a third process opening the account's
/// stores beside the app and the Notification Service Extension, inside a
/// memory ceiling (~30 MB) the core has not been measured against, with a
/// third name on the stores' cross-process lock. The app already answers
/// from a notification with no scene and no sync (`NotificationAnswerer`,
/// behind a background launch); a widget's tap is the same act.
///
/// `LiveActivityIntent` is what moves it: the system runs one in the app's
/// process — launching the app in the background if it is not running — and
/// `openAppWhenRun` stays `false`, so the reader stays where they are. The
/// type is compiled into both targets because the widget must name it and
/// the app must run it. In the widget extension `DecisionIntentHandling.run`
/// is never set, so if the system ever did run it there, it would send
/// nothing rather than answer from a half-built client.
///
/// The button carries only which decision and which option. What that means
/// — whether it is still owed, what a gate needs to be answered, what the
/// room is told — is read from the snapshot by the core in the app
/// (`WidgetAnswering`), so a stale widget cannot answer a gate the board has
/// already resolved.
struct AnswerDecisionIntent: LiveActivityIntent {
    static var title: LocalizedStringResource { "Answer a decision" }
    static var description: IntentDescription {
        IntentDescription("Answers a permission request or an approval gate from a widget.")
    }
    /// Not an action for Shortcuts or Siri: it answers one decision the
    /// widget named, and means nothing without it.
    static var isDiscoverable: Bool { false }
    static var openAppWhenRun: Bool { false }

    @Parameter(title: "Room") var roomId: String
    @Parameter(title: "Event") var eventId: String
    @Parameter(title: "Option") var optionId: String

    init() {}

    init(roomId: String, eventId: String, optionId: String) {
        self.roomId = roomId
        self.eventId = eventId
        self.optionId = optionId
    }

    func perform() async throws -> some IntentResult {
        let run = await DecisionIntentHandling.run
        await run?(roomId, eventId, optionId)
        return .result()
    }
}

/// What `AnswerDecisionIntent` does, installed by the app at launch
/// (`AppDelegate`). Unset in the widget extension.
@MainActor
enum DecisionIntentHandling {
    static var run: (@Sendable (_ roomId: String, _ eventId: String, _ optionId: String) async -> Void)?
}
