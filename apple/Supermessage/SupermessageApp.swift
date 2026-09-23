import SwiftUI

@main
struct SupermessageApp: App {
    /// APNs token, notification actions and taps. See `Platform/AppDelegate`.
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}
