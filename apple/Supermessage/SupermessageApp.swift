import SwiftUI

@main
struct SupermessageApp: App {
    /// APNs token, notification actions and taps. See `Platform/AppDelegate`.
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            appRoot
        }
    }

    @ViewBuilder private var appRoot: some View {
            Group {
                #if DEBUG
                // A long local room for reproducing scrolling, no account needed.
                if ProcessInfo.processInfo.arguments.contains("-fixtureShell") {
                    // The signed-in shell over sample rooms, for checking
                    // chrome on a simulator with no account.
                    SignedInView(
                        session: ProcessInfo.processInfo.arguments.contains("-fixtureLong")
                            ? NavigationRevampFixtures.longFleetSession()
                            : NavigationRevampFixtures.fleetSession())
                } else if ProcessInfo.processInfo.arguments.contains("-fixtureTimeline")
                    || ProcessInfo.processInfo.arguments.contains("-fixtureStreaming")
                {
                    ScrollFixtureRoot()
                } else {
                    RootView()
                }
                #else
                RootView()
                #endif
            }
            // Scheme, dark style and accent, from Account → Appearance.
            .appliesAppearance()
    }
}
