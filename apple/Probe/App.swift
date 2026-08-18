import SwiftUI

/// The probe app.
///
/// Throwaway by design — see `apple/project.yml`. It exists to prove the FFI
/// boundary carries a real session and a live event stream, and to be deleted
/// once it has.
@main
struct ProbeApp: App {
    @StateObject private var model = ProbeModel()

    var body: some Scene {
        WindowGroup {
            ProbeView(model: model)
        }
    }
}
