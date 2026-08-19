import SupermessageFFI
import SupermessageKit
import SwiftUI

/// The reading surface.
///
/// ## `ScrollView` + `LazyVStack`, not `List`
///
/// `List` imposes separators, insets and selection behaviour that fight an
/// editorial layout, and its cell reuse makes precise scroll anchoring harder
/// rather than easier. This needs exact control of both.
///
/// ## Anchoring, which is the hard part
///
/// `.defaultScrollAnchor(.bottom)` opens at the newest message. When
/// `paginateBack` prepends twenty older rows, `.scrollPosition` bound to the
/// **topmost visible row's id** holds that row where it is and lets the
/// content grow upward off-screen. Anchor to the bottom instead and the view
/// jumps every time history arrives, which is the failure people notice.
///
/// `onScrollGeometryChange` (iOS 18) drives both the pagination trigger and
/// the distance-from-bottom that follow-scroll needs — it is why this app
/// targets 18 rather than 17.
struct TimelineView: View {
    let session: Session
    let timeline: TimelineStore

    @State private var anchorId: String?
    @State private var distanceFromBottom: CGFloat = 0
    @State private var previousCount = 0
    @State private var hasSettled = false

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    if timeline.isPaginating {
                        ProgressView()
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                    }
                    ForEach(timeline.items, id: \.item.id) { row in
                        TimelineRowView(row: row)
                            .id(row.item.id)
                            // The reading column: every row lays out inside
                            // one centred measure, so a phone and an iPad
                            // detail pane read the same way.
                            //
                            // The horizontal inset is not decoration. Prose
                            // set flush to a screen edge is hard to read and
                            // looks like a layout fault — and on a phone the
                            // edge is also where the hand is.
                            .padding(.horizontal, 16)
                            .frame(maxWidth: 712, alignment: .leading)
                            .frame(maxWidth: .infinity, alignment: .center)
                    }
                }
                .scrollTargetLayout()
            }
            .defaultScrollAnchor(.bottom)
            .scrollPosition(id: $anchorId, anchor: .top)
            .onScrollGeometryChange(for: CGFloat.self) { geometry in
                // Distance from the bottom, in points: total content minus
                // what is above the fold and what is visible.
                geometry.contentSize.height - geometry.contentOffset.y
                    - geometry.containerSize.height
            } action: { _, distance in
                distanceFromBottom = distance
                // Near the top and there is more: fetch it. The threshold is
                // a screen, so the rows land before the reader reaches them.
                if geometry(distance) {
                    Task { await timeline.paginateBack() }
                }
            }
            .onChange(of: timeline.items.count) { previous, next in
                defer { previousCount = next }

                if TimelineFollow.shouldSettleAtBottom(
                    previous: previous, next: next, settled: hasSettled)
                {
                    hasSettled = true
                    scrollToBottom(proxy)
                    return
                }
                if TimelineFollow.shouldRepin(
                    distanceFromBottom: distanceFromBottom, grew: next > previous)
                {
                    scrollToBottom(proxy)
                }
            }
            .onChange(of: timeline.roomId) { _, _ in
                // A different room is a different reading position.
                hasSettled = false
                previousCount = 0
                distanceFromBottom = 0
            }
        }
        .task(id: timeline.roomId) {
            await timeline.markRead()
        }
    }

    /// Whether the reader is close enough to the top to want more history.
    private func geometry(_ distanceFromBottom: CGFloat) -> Bool {
        // Expressed against the *bottom* distance because that is what the
        // geometry reader gives; a large value means a long way up.
        timeline.canPaginate && !timeline.isPaginating
            && distanceFromBottom > 0
            && anchorId == timeline.items.first?.item.id
    }

    private func scrollToBottom(_ proxy: ScrollViewProxy) {
        guard let last = timeline.items.last?.item.id else { return }
        withAnimation(.easeOut(duration: 0.18)) {
            proxy.scrollTo(last, anchor: .bottom)
        }
    }
}
