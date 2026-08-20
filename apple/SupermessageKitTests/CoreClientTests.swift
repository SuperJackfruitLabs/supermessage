import Testing

@testable import SupermessageKit

struct CoreClientTests {
    @Test("a call runs on the core's own queue, not the cooperative pool")
    @MainActor
    func callsRunOnTheDedicatedQueue() async throws {
        // The invariant, and the reason it is asserted this way.
        //
        // "Not the main thread" was the obvious assertion and it is useless:
        // an actor is already off the main thread, so it holds for every
        // plausible implementation — including `Task.detached`, which is the
        // one this must not be. A detached task runs on Swift's *cooperative*
        // pool, which is sized to the core count and assumes tasks yield
        // rather than block. Every `Core` method blocks, so a handful of
        // concurrent calls would occupy the pool and hang work that has
        // nothing to do with the network.
        //
        // The queue label is what tells the two apart.
        let client = CoreClient(dataDirectory: CoreClient.dataDirectory())
        let label = await client.probeQueueLabel()
        #expect(label == CoreClient.queueLabel)
    }

    @Test("many blocking calls at once do not starve anything")
    @MainActor
    func concurrentCallsAllComplete() async throws {
        // The failure the queue prevents, made observable: far more
        // simultaneous blocking calls than the cooperative pool has threads.
        // On the pool this deadlocks; on a real queue it simply finishes.
        let client = CoreClient(dataDirectory: CoreClient.dataDirectory())
        await withTaskGroup(of: String.self) { group in
            for _ in 0..<64 {
                group.addTask { await client.probeQueueLabel() }
            }
            var finished = 0
            for await _ in group { finished += 1 }
            #expect(finished == 64)
        }
    }

    @Test("the data directory sits inside the app's own container")
    func dataDirectoryIsSandboxed() {
        // Not a path of its own choosing: inside the container it inherits the
        // sandbox and the backup rules rather than inventing its own.
        let path = CoreClient.dataDirectory()
        #expect(path.contains("Application Support"))
        #expect(path.hasSuffix("supermessage"))
    }
}
