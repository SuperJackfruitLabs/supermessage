import SwiftUI
import WidgetKit

/// The widget extension: two home/lock-screen widgets and the Live Activity.
///
/// It has no core and opens no Matrix store — a second process doing that
/// would be a second client on one account. Everything it draws the app
/// wrote into the App Group (`WidgetSnapshotStore`) or sent as activity
/// state (`AgentActivityAttributes`).
@main
struct SupermessageWidgetBundle: WidgetBundle {
    var body: some Widget {
        NeedsYouWidget()
        AgentsWidget()
        AgentLiveActivity()
    }
}
