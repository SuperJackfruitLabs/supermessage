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
                    ForEach(Array(timeline.items.enumerated()), id: \.element.item.id) { index, row in
                        TimelineRowView(
                            row: row,
                            continuesRun: TimelineGrouping.continuesRun(
                                row, after: index > 0 ? timeline.items[index - 1] : nil))
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
                    LiveTurnView(live: session.live, writerName: writerName)
                        .padding(.horizontal, 16)
                        .frame(maxWidth: 712, alignment: .leading)
                        .frame(maxWidth: .infinity, alignment: .center)
                }
                .scrollTargetLayout()
            }
            .defaultScrollAnchor(.bottom)
            .scrollPosition(id: $anchorId, anchor: .top)
            .onScrollGeometryChange(for: ScrollMetrics.self) { geometry in
                ScrollMetrics(
                    // How far the reader has scrolled back into history.
                    fromTop: geometry.contentOffset.y,
                    // Total content minus what is above the fold and what is
                    // visible.
                    fromBottom: geometry.contentSize.height - geometry.contentOffset.y
                        - geometry.containerSize.height)
            } action: { _, metrics in
                distanceFromBottom = metrics.fromBottom
                // Near the top and there is more: fetch it, a screen ahead of
                // the reader so the rows land before they are looked at.
                if TimelineFollow.wantsOlderHistory(
                    distanceFromTop: metrics.fromTop,
                    canPaginate: timeline.canPaginate,
                    isPaginating: timeline.isPaginating,
                    hasSettled: hasSettled)
                {
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
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if let line = session.typing.line {
                Text(line)
                    .font(Theme.meta)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 6)
                    .background(.bar)
            }
        }
    }

    /// Who the live turn belongs to.
    ///
    /// The last peer message's sender, falling back to the room's own name for
    /// the one case a timeline cannot answer: an agent's very first message in
    /// a room nobody has spoken in yet.
    private var writerName: String {
        timeline.items.last { !$0.item.isOwn }?.senderName
            ?? session.rooms.selectedName ?? "Agent"
    }

    private func scrollToBottom(_ proxy: ScrollViewProxy) {
        guard let last = timeline.items.last?.item.id else { return }
        withAnimation(.easeOut(duration: 0.18)) {
            proxy.scrollTo(last, anchor: .bottom)
        }
    }
}

/// What one scroll observation tells the timeline.
///
/// Both distances come off the same `ScrollGeometry`, and reading them
/// together in one `onScrollGeometryChange` keeps the two decisions they drive
/// — fetch older history, stay pinned to the newest message — measured against
/// the same instant rather than two nearby ones.
private struct ScrollMetrics: Equatable {
    /// Points scrolled back into history. Zero is the oldest loaded row.
    var fromTop: CGFloat
    /// Points below the fold. Zero is the newest message.
    var fromBottom: CGFloat
}
