#if DEBUG
import Observation
import SwiftUI
import UIKit

/// Watches the timeline while an answer streams, for `-fixtureStreaming`.
///
/// The stutter of the 2026-09-23 Guild recording was the list **animating**
/// its own resizing: each new line of a streaming answer was laid out at
/// once, and the cells then glided to their new places over the next frames
/// — the history snapping and sliding a line at a time. It is invisible to
/// layout (every height the layout reports is a whole line) and visible only
/// on screen, so this compares the two: on every frame, whether any visible
/// cell is drawn somewhere other than where the layout put it.
@MainActor
@Observable
final class StreamProbe {
    static let shared = StreamProbe()
    /// On only in the streaming fixture, so nothing else pays for it.
    static let isOn = ProcessInfo.processInfo.arguments.contains("-fixtureStreaming")

    /// Frames in which some visible cell was on screen away from its layout
    /// position: UIKit animating a move or a resize.
    private(set) var animatingFrames = 0
    /// Times the live turn's cell grew, so a run that streamed nothing
    /// cannot pass.
    private(set) var growths = 0
    private(set) var finished = false

    private var lastHeight: CGFloat?
    private weak var list: UICollectionView?
    private var link: CADisplayLink?

    var summary: String {
        "stream-probe finished=\(finished) animating=\(animatingFrames) growths=\(growths)"
    }

    func watch(_ list: UICollectionView) {
        self.list = list
        link?.invalidate()
        let link = CADisplayLink(target: self, selector: #selector(tick))
        link.add(to: .main, forMode: .common)
        self.link = link
    }

    func finish() {
        finished = true
        link?.invalidate()
        link = nil
    }

    @objc private func tick() {
        guard let list, !finished else { return }
        let offLayout = list.visibleCells.contains { cell in
            guard let shown = cell.layer.presentation()?.frame else { return false }
            return abs(shown.minY - cell.frame.minY) > 0.5 || abs(shown.height - cell.frame.height) > 0.5
        }
        if offLayout { animatingFrames += 1 }
        // The live turn is item 0: the newest end of the inverted list.
        if let live = list.cellForItem(at: IndexPath(item: 0, section: 0)) {
            if let last = lastHeight, live.frame.height > last { growths += 1 }
            lastHeight = live.frame.height
        }
    }
}
#endif
