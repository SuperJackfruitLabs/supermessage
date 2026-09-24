import Foundation
import SupermessageKit

/// Opening a `supermessage://` link — from a widget or a Live Activity —
/// becomes the same navigation request a tapped notification makes.
enum AppLinks {
    @MainActor
    static func handle(_ url: URL) {
        guard let room = AppLink.roomId(in: url) else { return }
        NotificationRouter.shared.request(roomId: room)
    }
}
