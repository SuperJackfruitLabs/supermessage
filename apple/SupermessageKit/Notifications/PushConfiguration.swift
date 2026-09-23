import Foundation
import SupermessageFFI

extension PushRegistration: @retroactive @unchecked Sendable {}

/// Registering this device with the homeserver as an APNs pusher.
///
/// **Only when a gateway is configured.** `SMPushGatewayURL` in Info.plist is
/// empty by default because no gateway is deployed (AGENTS.md: Sygnal is
/// infrastructure, not yet running). A pusher pointing nowhere would have the
/// homeserver POST every notification into a void and log each failure, so
/// an empty key means no pusher at all — and local notifications are the only
/// kind this app shows.
///
/// `event_id_only` is the core's decision (`core::push::pusher_for`), not
/// this file's: nothing here can make a push carry message content.
public enum PushConfiguration {
    /// The Info.plist key naming the gateway's `/_matrix/push/v1/notify` URL.
    public static let gatewayKey = "SMPushGatewayURL"

    /// The configured gateway, or `nil` when there is none.
    ///
    /// Anything that is not an absolute `https` URL counts as none: an
    /// unexpanded `$(SM_PUSH_GATEWAY_URL)` or a typo must not become a
    /// pusher the homeserver retries forever.
    public static func gatewayURL(from info: [String: Any]?) -> String? {
        guard let raw = info?[gatewayKey] as? String else { return nil }
        let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: value), url.scheme == "https", url.host != nil else {
            return nil
        }
        return value
    }

    /// An APNs device token as the gateway expects it: lowercase hex.
    public static func hex(_ token: Data) -> String {
        token.map { String(format: "%02x", $0) }.joined()
    }

    /// The gateway's app id for this build.
    ///
    /// The gateway routes on it, and a sandbox token sent to production APNs
    /// is refused — so a development build names the sandbox with a `.dev`
    /// suffix, the convention the core's `PushRegistration` documents.
    public static func appId(bundleId: String, sandbox: Bool) -> String {
        sandbox ? "\(bundleId).dev" : bundleId
    }

    public static func registration(
        token: Data, gateway: String, bundleId: String, sandbox: Bool,
        deviceName: String, language: String
    ) -> PushRegistration {
        PushRegistration(
            pushkey: hex(token), appId: appId(bundleId: bundleId, sandbox: sandbox),
            appDisplayName: "supermessage", deviceDisplayName: deviceName, lang: language,
            gatewayUrl: gateway)
    }
}

/// The one core call push needs, stated as its own seam so a test can supply
/// a fake without `Session`'s whole client.
public protocol PushRegistering: Sendable {
    func registerPusher(registration: PushRegistration) async throws
}

extension CoreClient: PushRegistering {}
