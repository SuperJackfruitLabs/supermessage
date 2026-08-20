import Foundation
import Observation

/// Paces an agent's answer onto the screen.
///
/// **The network must not decide the animation speed.** A model that emits
/// twenty tokens in one frame and then pauses produces bursts — half a
/// paragraph appearing at once, then nothing — and a slow model produces a
/// stutter. Both look like a fault in the app rather than in the model.
///
/// So deltas go into a buffer and are revealed on this type's own clock, a
/// few characters per tick, faster when it is falling behind. What the view
/// renders is `text`; what arrived is `buffer`, and the gap between them is
/// what keeps the reveal steady whatever the model does.
///
/// The core already de-duplicates and orders the stream (`live::accept`), and
/// each delta is the **whole answer so far** rather than an increment — so
/// this takes the full text and works out what is new, rather than appending.
@MainActor
@Observable
public final class StreamingText {
    /// What is on screen.
    public private(set) var text = ""
    /// How many characters of `text` are new enough to still be animating in.
    ///
    /// The view fades exactly these. Without it the whole paragraph would
    /// re-animate on every tick — the trap with a plain `contentTransition`,
    /// which transitions far more of the string than intended.
    public private(set) var revealed = 0

    private var pending = ""
    private var task: Task<Void, Never>?

    /// How long between reveals. Short enough to read as motion rather than
    /// as steps, long enough that each tick is a frame's worth of work.
    static let tick = Duration.milliseconds(20)

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
    }

    private func start() {
        guard task == nil else { return }
        task = Task { [weak self] in
            while let self, !self.pending.isEmpty {
                let take = Self.batch(forBacklog: self.pending.count)
                let end = self.pending.index(self.pending.startIndex, offsetBy: take)
                self.text += self.pending[..<end]
                self.pending.removeSubrange(..<end)
                self.revealed = take
                try? await Task.sleep(for: Self.tick)
                if Task.isCancelled { return }
            }
            self?.revealed = 0
            self?.task = nil
        }
    }

    /// How many characters to reveal this tick.
    ///
    /// Grows with the backlog so a fast model is not held to a crawl and a
    /// slow one is not made to look sluggish. The reveal stays smooth either
    /// way, because the *rate* changes rather than the rhythm.
    static func batch(forBacklog backlog: Int) -> Int {
        let size: Int
        switch backlog {
        case ..<20: size = 1
        case ..<100: size = 2
        case ..<400: size = 4
        default: size = 12
        }
        return min(size, backlog)
    }
}
