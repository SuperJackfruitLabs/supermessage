import Foundation
import SupermessageFFI
import Testing

@testable import SupermessageKit

/// Handing ActivityKit's tokens to the hub, through a hub that fails.
struct LiveActivityTokensTests {
    struct Refused: Error {}

    /// A hub that refuses the first `failures` calls, and records them all.
    actor Hub: LiveActivityTokenRegistering {
        var failures: Int
        var calls: [String] = []

        init(failures: Int) { self.failures = failures }

        func registerLiveActivityToken(gatewayUrl: String, token: LiveActivityToken) async throws {
            calls.append("register \(token.kind) \(token.token) \(token.activityId ?? "-") @\(gatewayUrl)")
            if failures > 0 {
                failures -= 1
                throw Refused()
            }
        }

        func unregisterLiveActivityToken(
            gatewayUrl: String, kind: LiveActivityTokenKind, activityId: String?
        ) async throws {
            calls.append("delete \(kind) \(activityId ?? "-")")
            if failures > 0 {
                failures -= 1
                throw Refused()
            }
        }
    }

    /// The waits asked for, without waiting.
    actor Waits {
        var asked: [Duration] = []
        func wait(_ d: Duration) { asked.append(d) }
    }

    static func start(_ token: String = "aa") -> LiveActivityToken {
        LiveActivityToken(kind: .start, token: token, sandbox: false, activityId: nil)
    }

    @Test("a refused token is tried again, waiting longer each time")
    func retriesWithBackoff() async {
        let hub = Hub(failures: 3)
        let waits = Waits()
        let tokens = LiveActivityTokens(
            client: hub, gateway: "https://hub", sleep: { await waits.wait($0) })
        await tokens.submit(.register(Self.start()))
        await tokens.settled()
        #expect(await hub.calls.count == 4, "three refusals, then it landed")
        #expect(await waits.asked == [.seconds(2), .seconds(4), .seconds(8)])
    }

    @Test("the wait stops growing at five minutes, and the tries stop at ten")
    func givesUp() async {
        let hub = Hub(failures: 100)
        let waits = Waits()
        let tokens = LiveActivityTokens(
            client: hub, gateway: "https://hub", sleep: { await waits.wait($0) })
        await tokens.submit(.register(Self.start()))
        await tokens.settled()
        #expect(await hub.calls.count == 10)
        let asked = await waits.asked
        #expect(asked.count == 9, "no wait after the last try")
        #expect(asked.last == .seconds(300))
        #expect(asked.max() == .seconds(300))
    }

    @Test("a newer token replaces one still being retried")
    func newerWins() async {
        let hub = Hub(failures: 100)
        // Long enough that the second token arrives while the first waits;
        // cancelling that wait is what ends the first token's retries.
        let tokens = LiveActivityTokens(
            client: hub, gateway: "https://hub",
            backoff: .init(first: .seconds(2), most: .seconds(2), attempts: 3),
            sleep: { _ in try await Task.sleep(for: .milliseconds(300)) })
        await tokens.submit(.register(Self.start("old")))
        while await hub.calls.isEmpty { await Task.yield() }
        await tokens.submit(.register(Self.start("new")))
        await tokens.settled()
        let calls = await hub.calls
        #expect(calls.filter { $0.contains(" old ") }.count == 1, "\(calls)")
        #expect(calls.contains { $0.contains(" new ") })
    }

    @Test("an ended activity's token is deleted, with its id")
    func deletes() async {
        let hub = Hub(failures: 0)
        let tokens = LiveActivityTokens(client: hub, gateway: "https://hub")
        await tokens.submit(.unregister(kind: .update, activityId: "act-1"))
        await tokens.settled()
        #expect(await hub.calls == ["delete update act-1"])
    }
}
