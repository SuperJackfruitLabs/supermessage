import Foundation
import Observation

/// Paces an agent's answer onto the screen, a phrase at a time.
///
/// **The network must not decide the animation speed.** A model that emits
/// twenty tokens in one frame and then pauses produces bursts — half a
/// paragraph appearing at once, then nothing — and a slow model produces a
/// stutter. Both look like a fault in the app rather than in the model.
///
/// So deltas go into a buffer and are revealed on this type's own clock. What
/// the view renders is `text`; what arrived is `pending`, and the gap between
/// them is what keeps the reveal steady whatever the model does.
///
/// **A chunk at a time, not a character.** The typing effect this replaced
/// revealed a few characters every 20ms, which read as the app typing — a
/// performance of a speed the model does not have, and the line under it
/// reflowing fifty times a second (2026-09-24). A delta is already about a
/// sentence (the hub and the Hermes plugin both send one), so each is cut at
/// its sentence boundaries and each piece lands whole and fades in as one.
/// Reading follows sentences; this is the unit the eye already uses.
///
/// **Chunks are spread over the gap to the next delta.** Each delta's pieces
/// are timed across the interval the next one is expected to take (a running
/// average of the gaps so far), so the last lands as more arrives rather than
/// all at once and then a wait.
///
/// The core already de-duplicates and orders the stream (`live::accept`), and
/// each delta is the **whole answer so far** rather than an increment — so
/// this takes the full text and works out what is new, rather than appending.
@MainActor
@Observable
public final class StreamingText {
    /// What is on screen.
    public private(set) var text = ""
    /// How many trailing characters of `text` are the newest chunk. Kept
    /// until the next chunk replaces it, so the view can finish its fade
    /// without the chunk moving out from under it; zero once the turn ends.
    public private(set) var revealed = 0
    /// Bumped with every chunk, so a view can start its fade on the change
    /// even when two chunks happen to be the same length.
    public private(set) var chunk = 0

    private var pending = ""
    private var task: Task<Void, Never>?
    /// When the reveal of what is pending should be done: the next delta's
    /// expected arrival.
    private var deadline = ContinuousClock.now
    private var lastArrival: ContinuousClock.Instant?
    /// The running estimate of the gap between deltas.
    private var interval = StreamingText.defaultInterval

    /// The gap assumed before two deltas have been seen. About what both
    /// the hub and the Hermes plugin produce: a sentence at a time.
    static let defaultInterval = 0.5
    /// Bounds on the estimate. Below: a burst of deltas should not make the
    /// reveal frantic. Above: a model that pauses to think should not make
    /// the next sentence crawl out over seconds.
    static let intervalRange = 0.15...1.5
    /// The least time between chunks: long enough for one to finish most of
    /// its fade before the next starts.
    static let minimumGap = 0.28
    /// The longest a chunk may be. A delta with no sentence boundary in its
    /// first this-many characters is cut at a word instead, so a paragraph
    /// sent whole does not land as a wall.
    static let longestChunk = 160

    public init() {}

    /// Accept the answer as it stands. Idempotent: the same text twice does
    /// nothing.
    public func accept(_ full: String) {
        guard full != text + pending else { return }

        // A stream that rewrote its history rather than extending it — a
        // resend after a reconnect, say. Nothing sensible can be animated
        // out of that, so it lands whole.
        guard full.hasPrefix(text) else {
            finish(full)
            return
        }

        let now = ContinuousClock.now
        if let last = lastArrival {
            interval = Self.nextInterval(previous: interval, gap: (now - last).seconds)
        }
        lastArrival = now
        deadline = now + .seconds(interval)
        pending = String(full.dropFirst(text.count))
        start()
    }

    /// The turn ended. Drain whatever is left immediately: the reader is now
    /// waiting on an animation rather than on a model.
    public func finish(_ full: String? = nil) {
        task?.cancel()
        task = nil
        if let full { text = full } else { text += pending }
        pending = ""
        revealed = 0
    }

    public func clear() {
        task?.cancel()
        task = nil
        text = ""
        pending = ""
        revealed = 0
        lastArrival = nil
        interval = Self.defaultInterval
    }

    private func start() {
        guard task == nil else { return }
        task = Task { [weak self] in
            while let self, !self.pending.isEmpty {
                let length = Self.nextChunk(in: self.pending)
                let end = self.pending.index(self.pending.startIndex, offsetBy: length)
                self.text += self.pending[..<end]
                self.pending.removeSubrange(..<end)
                self.revealed = length
                self.chunk &+= 1

                let left = (self.deadline - ContinuousClock.now).seconds
                let wait = Self.gap(timeLeft: left, chunksLeft: Self.chunkCount(in: self.pending))
                try? await Task.sleep(for: .seconds(wait))
                if Task.isCancelled { return }
            }
            self?.task = nil
        }
    }

    /// How long to wait after a chunk: what is left of the gap, shared among
    /// the chunks still waiting and the one just shown. Never less than
    /// `minimumGap`, so a model far ahead of the screen gets a quick cadence
    /// rather than a flood.
    static func gap(timeLeft: Double, chunksLeft: Int) -> Double {
        max(minimumGap, timeLeft / Double(chunksLeft + 1))
    }

    /// The length of the next chunk of `pending`: up to and including the
    /// first sentence boundary, or a line break, when there is one within
    /// `longestChunk`; otherwise the last word boundary before it; otherwise
    /// everything, when it is short enough to be a phrase.
    static func nextChunk(in pending: String) -> Int {
        let chars = Array(pending)
        guard chars.count > 1 else { return chars.count }
        let limit = min(chars.count, longestChunk)
        var i = 0
        while i < limit {
            let c = chars[i]
            if c == "\n" { return i + 1 }
            if c == "." || c == "!" || c == "?" || c == "…" {
                // The boundary is the space after the stop: "3.5" and "e.g."
                // mid-word are not sentence ends, and the space goes with
                // the sentence it closes rather than opening the next.
                var j = i + 1
                while j < chars.count, "\"')]*_".contains(chars[j]) { j += 1 }
                if j < chars.count, chars[j] == " " || chars[j] == "\n" {
                    return chars[j] == " " ? j + 1 : j
                }
                // A stop at the very end of what has arrived: the sentence
                // is complete as far as anyone can know.
                if j == chars.count { return j }
            }
            i += 1
        }
        if chars.count <= longestChunk { return chars.count }
        if let space = chars[..<longestChunk].lastIndex(of: " "), space > 0 { return space + 1 }
        return longestChunk
    }

    /// How many chunks `pending` will take.
    static func chunkCount(in pending: String) -> Int {
        var rest = Substring(pending)
        var count = 0
        while !rest.isEmpty {
            rest = rest.dropFirst(nextChunk(in: String(rest)))
            count += 1
        }
        return count
    }

    /// The next estimate of the gap between deltas: a running average,
    /// weighted to the recent, within `intervalRange`.
    static func nextInterval(previous: Double, gap: Double) -> Double {
        let clamped = min(max(gap, intervalRange.lowerBound), intervalRange.upperBound)
        return previous * 0.6 + clamped * 0.4
    }
}

extension Duration {
    /// This duration in seconds, as a `Double`.
    var seconds: Double {
        let (whole, attos) = components
        return Double(whole) + Double(attos) / 1e18
    }
}
