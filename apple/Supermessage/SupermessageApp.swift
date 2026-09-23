import SwiftUI

@main
struct SupermessageApp: App {
    /// APNs token, notification actions and taps. See `Platform/AppDelegate`.
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            #if DEBUG
            // A long local room for reproducing scrolling, no account needed.
            if ProcessInfo.processInfo.arguments.contains("-fixtureTimeline") {
                ScrollFixtureRoot()
            } else {
                RootView()
            }
            #else
            RootView()
            #endif
        }
    }
}
