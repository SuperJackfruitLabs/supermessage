import SwiftUI
import WidgetKit

/// The widget extension: two home/lock-screen widgets and the Live Activity.
///
/// It has no core and opens no Matrix store — another process doing that
/// would be another client on one account. Everything it draws the app or
/// the Notification Service Extension wrote into the App Group
/// (`WidgetSnapshotStore`), or the app sent as activity state
/// (`AgentActivityAttributes`). Its buttons (`AnswerDecisionIntent`) run in
/// the app's process, not here.
@main
struct SupermessageWidgetBundle: WidgetBundle {
    var body: some Widget {
        NeedsYouWidget()
        AgentsWidget()
        AgentLiveActivity()
    }
}
