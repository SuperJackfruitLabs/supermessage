import ActivityKit
import Foundation
import SupermessageFFI
import SupermessageKit

/// Hands ActivityKit's tokens for the fleet Live Activity to the hub.
///
/// **The app never starts, updates or ends the card.** The hub does, with
/// APNs pushes (spec 2026-09-29): it starts the card with this device's
/// push-to-start token when an agent becomes active, updates it with the
/// activity's own update token as steps advance and decisions arrive, and
/// ends it once the fleet has been quiet for fifteen minutes. That is what
/// keeps it live with the app suspended or force-quit — the agent activity
/// this replaces was started and updated by the app, and froze the moment
/// iOS suspended it.
///
/// So this only relays tokens, through the core (`LiveActivityTokens`,
/// `core::live_activity`), which sends them to the hub on the push gateway's
/// origin with the session's access token:
///
/// - the push-to-start token, on sign-in and on every launch, and each time
///   ActivityKit rotates it;
/// - each running activity's update token, for the activities already
///   running at launch and every one the hub starts later;
/// - a deletion when an activity ends. Signing out deletes the lot, in the
///   core, before the access token goes.
///
/// **Needs the widget extension** (the card's layouts live there) and a push
/// gateway; without either there is nobody to push the card, so nothing is
/// registered.
@MainActor
final class FleetActivityController {
    private var tokens: LiveActivityTokens?
    private var watchers: [Task<Void, Never>] = []
    private var watched: Set<String> = []

    /// Whether this build carries a widget extension to draw with.
    private let hasExtension: Bool = {
        guard let plugins = Bundle.main.builtInPlugInsURL,
            let contents = try? FileManager.default.contentsOfDirectory(atPath: plugins.path)
        else { return false }
        return contents.contains { $0.hasSuffix(".appex") }
    }()

    /// Start relaying tokens for `session`, once per sign-in or launch.
    func start(session: Session) {
        guard tokens == nil, hasExtension,
            ActivityAuthorizationInfo().areActivitiesEnabled,
            let gateway = PushConfiguration.gatewayURL(from: Bundle.main.infoDictionary),
            let client = session.liveActivityTokenClient
        else { return }
        let tokens = LiveActivityTokens(client: client, gateway: gateway)
        self.tokens = tokens
        let sandbox = PushConfiguration.isSandbox()

        watchers.append(
            Task {
                for await data in Activity<FleetActivityAttributes>.pushToStartTokenUpdates {
                    let token = LiveActivityToken(
                        kind: .start, token: PushConfiguration.hex(data), sandbox: sandbox,
                        activityId: nil)
                    await tokens.submit(.register(token))
                }
            })
        for activity in Activity<FleetActivityAttributes>.activities {
            watch(ActivityHandle(activity), tokens: tokens, sandbox: sandbox)
        }
        watchers.append(
            Task { [weak self] in
                for await activity in Activity<FleetActivityAttributes>.activityUpdates {
                    self?.watch(ActivityHandle(activity), tokens: tokens, sandbox: sandbox)
                }
            })
    }

    /// Stop relaying and take the card off the Lock Screen: signed out. The
    /// core's logout has already asked the hub to forget the tokens.
    func stop() {
        watchers.forEach { $0.cancel() }
        watchers = []
        watched = []
        if let tokens {
            Task { await tokens.cancelAll() }
        }
        tokens = nil
        for activity in Activity<FleetActivityAttributes>.activities {
            let handle = ActivityHandle(activity)
            Task { await handle.activity.end(nil, dismissalPolicy: .immediate) }
        }
    }

    /// Relay one activity's update tokens until it ends, then delete it.
    private func watch(_ handle: ActivityHandle, tokens: LiveActivityTokens, sandbox: Bool) {
        let id = handle.activity.id
        guard watched.insert(id).inserted else { return }
        watchers.append(
            Task {
                for await data in handle.activity.pushTokenUpdates {
                    let token = LiveActivityToken(
                        kind: .update, token: PushConfiguration.hex(data), sandbox: sandbox,
                        activityId: id)
                    await tokens.submit(.register(token))
                }
            })
        watchers.append(
            Task { [weak self] in
                for await state in handle.activity.activityStateUpdates
                where state == .ended || state == .dismissed {
                    await tokens.submit(.unregister(kind: .update, activityId: id))
                    self?.watched.remove(id)
                    return
                }
            })
    }
}

/// An `Activity` carried into a `Task`.
///
/// `Activity` is not `Sendable`, and Swift 6.2 (Xcode 26) refuses to send it
/// into the task that watches it — which Xcode 16 accepted, so this surfaced
/// only in the TestFlight archive. ActivityKit documents an activity as safe
/// to observe and end from any context; this box says so to the compiler,
/// and only this file creates one.
private struct ActivityHandle: @unchecked Sendable {
    let activity: Activity<FleetActivityAttributes>
    init(_ activity: Activity<FleetActivityAttributes>) { self.activity = activity }
}
