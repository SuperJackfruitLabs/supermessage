import Testing

@testable import SupermessageKit

/// The pacing behind the streaming reveal.
///
/// The rule this exists to hold: **the network does not decide the animation
/// speed.** A model that emits twenty tokens in one frame and then pauses
/// would otherwise dump half a paragraph at once and then stall, which reads
/// as a fault in the app rather than in the model.
@MainActor
struct StreamingTextTests {
    @Test("a small backlog reveals a character at a time")
    func slowStream() {
        #expect(StreamingText.batch(forBacklog: 1) == 1)
        #expect(StreamingText.batch(forBacklog: 19) == 1)
    }

    @Test("a bigger backlog reveals faster, so a quick model is not held back")
    func fastStream() {
        #expect(StreamingText.batch(forBacklog: 50) == 2)
        #expect(StreamingText.batch(forBacklog: 200) == 4)
        #expect(StreamingText.batch(forBacklog: 5_000) == 12)
    }

    @Test("a batch never overruns what is actually waiting")
    func neverOverruns() {
        // The subscript that reveals a batch would trap past the end.
        for backlog in [0, 1, 2, 3] {
            #expect(StreamingText.batch(forBacklog: backlog) <= backlog)
        }
    }

    @Test("the same text twice changes nothing")
    func idempotent() {
        let s = StreamingText()
        s.accept("Hello")
        s.finish()
        let before = s.text
        s.accept("Hello")
        #expect(s.text == before)
    }

    @Test("finishing drains whatever was still waiting")
    func finishDrains() {
        // The turn has ended, so the reader is waiting on an animation rather
        // than on a model — the rest should land at once.
        let s = StreamingText()
        s.accept("The whole answer, arriving in one go.")
        s.finish()
        #expect(s.text == "The whole answer, arriving in one go.")
        #expect(s.revealed == 0, "nothing should still be animating once it has landed")
    }

    @Test("a stream that rewrites itself lands whole rather than animating nonsense")
    func rewriteLandsWhole() {
        // A resend after a reconnect: the new text is not an extension of
        // what is on screen, so there is no meaningful "new" part to fade in.
        let s = StreamingText()
        s.accept("First attempt")
        s.finish()
        s.accept("Completely different text")
        #expect(s.text == "Completely different text")
    }

    @Test("clearing forgets the turn entirely")
    func clearing() {
        let s = StreamingText()
        s.accept("Something")
        s.clear()
        #expect(s.text.isEmpty)
        #expect(s.revealed == 0)
    }
}
