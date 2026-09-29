import Foundation
import SupermessageFFI

extension LiveActivityToken: @retroactive @unchecked Sendable {}
extension LiveActivityTokenKind: @retroactive @unchecked Sendable {}

/// The two core calls the fleet Live Activity needs (`core::live_activity`),
/// as their own seam so a test can supply a hub that fails.
///
/// The core makes the HTTP call itself, with the session's access token: the
/// token never reaches Swift, and neither does the endpoint's shape.
public protocol LiveActivityTokenRegistering: Sendable {
    func registerLiveActivityToken(gatewayUrl: String, token: LiveActivityToken) async throws
    func unregisterLiveActivityToken(
        gatewayUrl: String, kind: LiveActivityTokenKind, activityId: String?) async throws
}

extension CoreClient: LiveActivityTokenRegistering {}

/// Sends ActivityKit's tokens to the hub, and keeps trying.
///
/// A token that does not reach the hub is a Lock Screen that never lights
/// up, and nothing the reader can see says why — so a failed send is retried
/// with backoff (2 s, 4 s, 8 s … at most five minutes apart, ten tries), and
/// the next launch sends every token again anyway. Nothing waits on it: the
/// caller submits and moves on.
///
/// Per token, the latest request wins. A new start token replaces the one
/// still being retried, and an activity that ended cancels the retries of its
/// update token before deleting it — a late success must not re-register a
/// token the hub was just told to forget.
public actor LiveActivityTokens {
    public enum Request: Equatable, Sendable {
        case register(LiveActivityToken)
        case unregister(kind: LiveActivityTokenKind, activityId: String?)

        struct Key: Hashable, Sendable {
            let kind: LiveActivityTokenKind
            let activityId: String?
        }

        var key: Key {
            switch self {
            case .register(let token): Key(kind: token.kind, activityId: token.activityId)
            case .unregister(let kind, let activityId): Key(kind: kind, activityId: activityId)
            }
        }
    }

    public struct Backoff: Sendable {
        public var first: Duration = .seconds(2)
        public var most: Duration = .seconds(300)
        public var attempts = 10

        public init() {}

        public init(first: Duration, most: Duration, attempts: Int) {
            self.first = first
            self.most = most
            self.attempts = attempts
        }

        /// How long to wait after failed attempt `n` (from 0).
        public func delay(after n: Int) -> Duration {
            let doubled = first * (1 << min(n, 20))
            return min(doubled, most)
        }
    }

    private let client: any LiveActivityTokenRegistering
    private let gateway: String
    private let backoff: Backoff
    private let sleep: @Sendable (Duration) async throws -> Void
    private var running: [Request.Key: Task<Void, Never>] = [:]

    public init(
        client: any LiveActivityTokenRegistering, gateway: String, backoff: Backoff = Backoff(),
        sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }
    ) {
        self.client = client
        self.gateway = gateway
        self.backoff = backoff
        self.sleep = sleep
    }

    /// Send `request`, replacing whatever was still being tried for the same
    /// token.
    public func submit(_ request: Request) {
        let key = request.key
        running[key]?.cancel()
        running[key] = Task { await self.attempt(request) }
    }

    /// Stop every retry — signing out; the core deletes what was registered.
    public func cancelAll() {
        running.values.forEach { $0.cancel() }
        running = [:]
    }

    /// Wait for everything submitted so far to land or give up. For tests.
    public func settled() async {
        for task in running.values { await task.value }
    }

    private func attempt(_ request: Request) async {
        for n in 0..<backoff.attempts {
            if Task.isCancelled { return }
            do {
                try await perform(request)
                return
            } catch {
                guard n + 1 < backoff.attempts else { return }
                do { try await sleep(backoff.delay(after: n)) } catch { return }
            }
        }
    }

    private func perform(_ request: Request) async throws {
        switch request {
        case .register(let token):
            try await client.registerLiveActivityToken(gatewayUrl: gateway, token: token)
        case .unregister(let kind, let activityId):
            try await client.unregisterLiveActivityToken(
                gatewayUrl: gateway, kind: kind, activityId: activityId)
        }
    }
}
