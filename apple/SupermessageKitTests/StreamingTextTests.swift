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

    // ─── Chunks ──────────────────────────────────────────────────────────

    /// Cut `text` the way the reveal loop does.
    private func chunks(_ text: String) -> [String] {
        var rest = Substring(text)
        var out: [String] = []
        while !rest.isEmpty {
            let n = StreamingText.nextChunk(in: String(rest))
            out.append(String(rest.prefix(n)))
            rest = rest.dropFirst(n)
        }
        return out
    }

    @Test("a delta is cut at its sentences, each keeping its trailing space")
    func sentences() {
        #expect(chunks("First point. Second one! A third? Done.") == [
            "First point. ", "Second one! ", "A third? ", "Done.",
        ])
    }

    @Test("a number or an abbreviation is not a sentence end")
    func notEveryStop() {
        #expect(chunks("Version 3.5 is out, e.g.in beta. Next.") == [
            "Version 3.5 is out, e.g.in beta. ", "Next.",
        ])
    }

    @Test("a closing quote or bracket stays with its sentence")
    func closers() {
        #expect(chunks("He said \"stop.\" Then (quietly.) left.") == [
            "He said \"stop.\" ", "Then (quietly.) ", "left.",
        ])
        #expect(chunks("**Bold.** Plain.") == ["**Bold.** ", "Plain."])
    }

    @Test("a line break ends a chunk, so a list lands an item at a time")
    func lines() {
        #expect(chunks("- one\n- two\n- three") == ["- one\n", "- two\n", "- three"])
    }

    @Test("a phrase with no boundary lands whole")
    func phrase() {
        #expect(chunks("still thinking about") == ["still thinking about"])
    }

    @Test("a run-on paragraph is cut at a word rather than landing as a wall")
    func runOn() {
        let words = Array(repeating: "word", count: 80).joined(separator: " ")
        let cut = chunks(words)
        #expect(cut.count > 1)
        #expect(cut.allSatisfy { $0.count <= StreamingText.longestChunk })
        #expect(cut.dropLast().allSatisfy { $0.hasSuffix(" ") }, "cut mid-word: \(cut)")
        #expect(cut.joined() == words)
    }

    @Test("chunks never lose or repeat a character")
    func lossless() {
        let text = "A. B! C? \"D.\" (e.) 3.14 …and so… on\nnext line. " + String(repeating: "x", count: 400)
        #expect(chunks(text).joined() == text)
        #expect(StreamingText.chunkCount(in: text) == chunks(text).count)
    }

    // ─── Timing ─────────────────────────────────────────────────────────

    @Test("chunks share the gap to the next delta")
    func sharesTheGap() {
        // 1.2s left, two more chunks after this one: 0.4s each.
        #expect(abs(StreamingText.gap(timeLeft: 1.2, chunksLeft: 2) - 0.4) < 0.001)
    }

    @Test("past the deadline, or with a big backlog, chunks still keep a readable cadence")
    func minimumGap() {
        #expect(StreamingText.gap(timeLeft: -1, chunksLeft: 3) == StreamingText.minimumGap)
        #expect(StreamingText.gap(timeLeft: 0.5, chunksLeft: 40) == StreamingText.minimumGap)
    }

    @Test("the gap estimate follows the stream, within bounds")
    func intervalEstimate() {
        var estimate = StreamingText.defaultInterval
        for _ in 0..<20 { estimate = StreamingText.nextInterval(previous: estimate, gap: 0.3) }
        #expect(abs(estimate - 0.3) < 0.01)
        #expect(StreamingText.nextInterval(previous: 0.5, gap: 30) <= StreamingText.intervalRange.upperBound)
        #expect(StreamingText.nextInterval(previous: 0.5, gap: 0) >= StreamingText.intervalRange.lowerBound * 0.4)
    }

    @Test("the first chunk is on screen at once, whole")
    func firstChunkImmediately() async {
        let s = StreamingText()
        s.accept("One sentence. Another one.")
        // The loop's first pass runs before its first sleep.
        await Task.yield()
        #expect(s.text == "One sentence. ")
        #expect(s.revealed == "One sentence. ".count)
        #expect(s.chunk == 1)
        s.clear()
    }
}
