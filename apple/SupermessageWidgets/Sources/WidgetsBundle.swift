import SwiftUI
import WidgetKit

/// The widget extension: two home/lock-screen widgets and the fleet's Live
/// Activity.
///
/// It has no core and opens no Matrix store — another process doing that
/// would be another client on one account. Everything it draws the app or
/// the Notification Service Extension wrote into the App Group
/// (`WidgetSnapshotStore`), or the hub pushed as activity state
/// (`FleetActivityAttributes`). Its buttons (`AnswerDecisionIntent`) run in
/// the app's process, not here.
@main
struct SupermessageWidgetBundle: WidgetBundle {
    var body: some Widget {
        NeedsYouWidget()
        AgentsWidget()
        FleetLiveActivity()
    }
}
