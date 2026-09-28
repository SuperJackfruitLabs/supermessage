import Foundation
import SupermessageFFI

extension PushRegistration: @retroactive @unchecked Sendable {}

/// Registering this device with the homeserver as an APNs pusher.
///
/// **Only when a gateway is configured.** `SMPushGatewayURL` in Info.plist is
/// empty unless the build is generated with `SM_PUSH_GATEWAY_URL` — the
/// TestFlight workflow sets the AgentPod hub's
/// `https://hub.agentpod.dev/_matrix/push/v1/notify` (operator decision of
/// 2026-09-28). A pusher pointing nowhere would have the homeserver POST every
/// notification into a void and log each failure, so an empty key means no
/// pusher at all — and local notifications are the only kind this app shows.
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

    /// The Info.plist key naming the build configuration's APNs environment
    /// (`SM_APS_ENVIRONMENT`: `development` for Debug, `production` for
    /// Release) — the answer when the build carries no provisioning profile.
    public static let apsEnvironmentKey = "SMAPSEnvironment"

    /// Whether this build's device token is a sandbox one.
    ///
    /// The token's environment is the signing profile's `aps-environment`,
    /// not the compiler's `DEBUG`: a Release build signed for development
    /// gets a sandbox token, and a TestFlight build — Release, App Store
    /// profile — a production one. Naming the wrong one sends every push to
    /// an APNs that refuses the token. So the embedded profile decides when
    /// there is one; an App Store install has none, and the configuration's
    /// own value (production for Release) stands in.
    public static func isSandbox(provisioningProfile: Data?, info: [String: Any]?) -> Bool {
        if let profile = provisioningProfile, let environment = apsEnvironment(in: profile) {
            return environment == "development"
        }
        return (info?[apsEnvironmentKey] as? String) == "development"
    }

    /// `Entitlements.aps-environment` from a `.mobileprovision`: a CMS
    /// envelope around a plain XML plist, which is read by finding the plist
    /// rather than by verifying the envelope — the signature was the
    /// installer's to check, and this only needs one string out of it.
    static func apsEnvironment(in profile: Data) -> String? {
        guard let start = profile.range(of: Data("<?xml".utf8)),
            let end = profile.range(of: Data("</plist>".utf8), in: start.lowerBound..<profile.endIndex)
        else { return nil }
        let plist = profile.subdata(in: start.lowerBound..<end.upperBound)
        guard
            let root = try? PropertyListSerialization.propertyList(from: plist, format: nil)
                as? [String: Any],
            let entitlements = root["Entitlements"] as? [String: Any]
        else { return nil }
        return entitlements["aps-environment"] as? String
    }

    /// `isSandbox` for the running app.
    public static func isSandbox(bundle: Bundle = .main) -> Bool {
        let profile = bundle.url(forResource: "embedded", withExtension: "mobileprovision")
            .flatMap { try? Data(contentsOf: $0) }
        return isSandbox(provisioningProfile: profile, info: bundle.infoDictionary)
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

/// Stopping sync while the app is away, so the Notification Service
/// Extension can take the stores' lock (`Session::pause_sync` in the core).
/// Its own seam for the reason `PushRegistering` has one.
public protocol SyncPausing: Sendable {
    func syncPause() async
    func syncResume() async
}

extension CoreClient: SyncPausing {}
