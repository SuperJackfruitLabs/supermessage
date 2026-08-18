import SwiftUI

/// The app's one window, for now.
///
/// Serif because that is what this app is for: agents write at length, and the
/// timeline is a reading surface. `Font.system(design:.serif)` resolves to New
/// York — no bundled face, and Dynamic Type for free.
struct RootView: View {
    var body: some View {
        Text("supermessage")
            .font(.system(.title, design: .serif))
    }
}
