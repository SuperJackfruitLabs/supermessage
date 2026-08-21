import Testing

@testable import SupermessageKit
import SupermessageFFI

struct EventPumpTests {
    /// A typing record. The pump does not care what is inside one — these
    /// tests are about ordering and delivery — so the label carries the
    /// marker each case asserts on.
    func typist(_ label: String) -> TypingUserDto {
        TypingUserDto(userId: "@\(label):x.org", displayName: label, label: label)
    }

    /// The one the probe never ran.
    ///
    /// `DiffEnvelope` carries a `seq`, and the timeline's recovery logic is
    /// built on those arriving in the order they were emitted. A pump that
    /// spawned a task per event would reorder them under load and corrupt the
    /// reader's view — and it would look like a rendering bug rather than a
    /// threading one, which is why it is worth ten thousand events to pin.
    @Test("ten thousand events arrive in the order they were emitted")
    func preservesOrderUnderLoad() async throws {
        let pump = EventPump()
        let count = 10_000

        // Emitted from a background thread, because that is where the core
        // emits: UniFFI invokes a callback on whatever thread called it, which
        // here is a tokio worker or a matrix-sdk event handler.
        Task.detached {
            for seq in 0..<count {
                pump.onEvent(event: .typing(roomId: "!r:x.org", users: [typist("\(seq)")]))
            }
            pump.finish()
        }

        var seen: [Int] = []
        for await event in pump.events {
            if case let .typing(_, users) = event, let first = users.first, let n = Int(first.label) {
                seen.append(n)
            }
        }

        #expect(seen.count == count, "events were dropped")
        #expect(seen == Array(0..<count), "events were reordered")
    }

    /// Signing out and back in without killing the app.
    ///
    /// `finish()` is terminal — an `AsyncStream.Continuation` cannot be
    /// restarted — so a `Session` that finishes its pump on sign-out and holds
    /// the same pump for its whole life has nothing to deliver through
    /// afterwards. The app looked signed in and received nothing at all, with
    /// no error and no crash, until it was force-quit (issue #28).
    ///
    /// The pump's *identity* has to survive, because the core holds it as a
    /// sink; only the channel inside it may be replaced.
    @Test("a pump delivers again after it is finished and reset")
    func resetRevivesAFinishedPump() async {
        let pump = EventPump()
        pump.finish()

        // The old stream is done. Draining it here is what a `Session` does on
        // sign-out, and it must not hang.
        for await _ in pump.events {}

        pump.reset()

        pump.onEvent(event: .typing(roomId: "!r:x.org", users: [typist("after")]))
        pump.finish()

        var labels: [String] = []
        for await event in pump.events {
            if case let .typing(_, users) = event, let first = users.first {
                labels.append(first.label)
            }
        }
        #expect(labels == ["after"], "a reset pump delivered nothing — sign-in after sign-out is silent")
    }

    @Test("the stream ends when the pump is finished")
    func finishEndsTheStream() async {
        let pump = EventPump()
        pump.finish()
        var received = 0
        for await _ in pump.events { received += 1 }
        #expect(received == 0)
    }

    @Test("an event emitted before anyone listens is not lost")
    func bufferedBeforeConsumption() async {
        // The buffer is unbounded on purpose. Dropping the oldest would drop a
        // diff envelope, and a dropped envelope is a gap the tracker cannot
        // tell from a lost one — recoverable, but only by a resync nobody
        // asked for.
        let pump = EventPump()
        pump.onEvent(event: .typing(roomId: "!r:x.org", users: [typist("early")]))
        pump.finish()

        var seen: [String] = []
        for await event in pump.events {
            if case let .typing(_, users) = event { seen.append(contentsOf: users.map(\.label)) }
        }
        #expect(seen == ["early"])
    }
}
