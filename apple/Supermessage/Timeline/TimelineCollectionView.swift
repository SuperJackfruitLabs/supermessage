import SupermessageFFI
import SupermessageKit
import SwiftUI
import UIKit

/// The timeline's scroll container: an **inverted** `UICollectionView`.
///
/// ## Why this is not SwiftUI
///
/// It was, and it did not hold. A `ScrollView` + `LazyVStack` in natural order
/// needs three separate mechanisms to behave like a conversation —
/// `.defaultScrollAnchor(.bottom)` to open at the newest message,
/// `.scrollPosition(id:)` to hold position when history is prepended, and a
/// `ScrollViewReader` to follow new arrivals — and nothing arbitrates between
/// them. Element X iOS reached the same place and dropped to UIKit for this
/// one screen. This follows it.
///
/// ## What inversion buys
///
/// `transform = CGAffineTransform(scaleX: 1, y: -1)` on the collection view,
/// with the same flip applied to each cell's SwiftUI content to turn the rows
/// back the right way up. The list is fed **newest first**, so what the reader
/// sees at the bottom is the head of the data.
///
/// Three problems stop being problems rather than being managed:
///
/// - *Am I at the bottom?* becomes `contentOffset.y <= 0`. Exact, not a
///   threshold with a tolerance to tune.
/// - *A new message arrives.* It goes in at index 0, off the far end of the
///   scroll. Nothing on screen moves, so there is nothing to correct.
/// - *Older history is prepended.* It appends to the tail, also off the far
///   end. The reading position is untouched.
///
/// **And a room opens at its newest message by construction**, because that is
/// where a fresh scroll view already rests. No scroll-to-bottom on load, no
/// anchor to reset on a room switch, and nothing to land wrongly.
///
/// ## A `UIViewRepresentable`, deliberately
///
/// The first attempt wrapped a `UIViewController`, and SwiftUI lays a hosted
/// controller's view out over the **whole window** — including behind the
/// navigation bar, and *after* it among its siblings. That container answered
/// for every touch in its bounds and swallowed the entire navigation bar: both
/// the room-info button and the sidebar toggle reported `isHittable == false`.
/// Overriding `hitTest` on the controller's own root view did not help,
/// because the view claiming the touches was SwiftUI's wrapper rather than
/// mine.
///
/// A plain view has no such wrapper: SwiftUI sizes this collection view to the
/// frame it proposes, and nothing of it extends under the bar.
///
/// Rows stay SwiftUI — each cell hosts `TimelineRowView` through
/// `UIHostingConfiguration` — so nothing about how a message *looks* moves to
/// UIKit. Only the scrolling does.
struct TimelineCollectionView: UIViewRepresentable {
    let session: Session
    let timeline: TimelineStore

    func makeCoordinator() -> Coordinator {
        Coordinator(session: session, timeline: timeline)
    }

    func makeUIView(context: Context) -> UICollectionView {
        var configuration = UICollectionLayoutListConfiguration(appearance: .plain)
        configuration.showsSeparators = false
        configuration.backgroundColor = .clear

        let view = UICollectionView(
            frame: .zero,
            collectionViewLayout: UICollectionViewCompositionalLayout.list(using: configuration))
        view.backgroundColor = .clear
        view.delegate = context.coordinator
        view.alwaysBounceVertical = true
        // The inversion itself. Everything else in this file follows from it.
        view.transform = CGAffineTransform(scaleX: 1, y: -1)
        // It would otherwise run down the leading edge and travel backwards.
        view.showsVerticalScrollIndicator = false
        // Room under the newest message. `top` because the view is inverted,
        // so the head of the content is what sits at the bottom of the screen:
        // without this the last line of a conversation rests flush against the
        // composer and reads as cut off.
        view.contentInset.top = 12

        context.coordinator.attach(to: view)
        return view
    }

    func updateUIView(_ view: UICollectionView, context: Context) {
        context.coordinator.apply(rows: timeline.items, isPaginating: timeline.isPaginating)
    }

    /// What the list holds. Not only rows: the pagination spinner and the live
    /// turn occupy positions in the same scroll, so they are entries too rather
    /// than something layered on top with its own coordinate problems.
    enum Entry: Hashable {
        /// A message, keyed by the row's **identity** — the SDK's
        /// `unique_id()` — which holds still across the local-echo-to-confirmed
        /// transition. Keyed by event id instead, every message would leave and
        /// rejoin the list at the moment it was confirmed. See
        /// `TimelineItemDto`'s field docs.
        case row(String)
        /// The agent's in-progress turn, pinned to the newest end.
        case liveTurn
        /// Shown at the oldest end while a page of history is in flight.
        case paginating
    }

    @MainActor
    final class Coordinator: NSObject, UICollectionViewDelegate {
        private let session: Session
        private let timeline: TimelineStore
        private var dataSource: UICollectionViewDiffableDataSource<Int, Entry>?

        /// The rows behind the identifiers in the snapshot, and whether each
        /// continues a run. Grouping is resolved once here rather than per
        /// cell, because a cell knows only itself and grouping is a question
        /// about neighbours.
        private var rowsById: [String: (row: TimelineRow, continuesRun: Bool)] = [:]
        private var writerName = "Agent"

        init(session: Session, timeline: TimelineStore) {
            self.session = session
            self.timeline = timeline
        }

        func attach(to view: UICollectionView) {
            let cell = UICollectionView.CellRegistration<UICollectionViewListCell, Entry> {
                [weak self] cell, _, entry in
                guard let self else { return }
                cell.backgroundConfiguration = .clear()

                switch entry {
                case let .row(id):
                    guard let found = self.rowsById[id] else { return }
                    cell.contentConfiguration = UIHostingConfiguration {
                        TimelineRowView(row: found.row, continuesRun: found.continuesRun)
                            // Turn the row back the right way up. Applied to
                            // the content rather than to `cell.contentView`,
                            // because `UIHostingConfiguration` replaces that
                            // view when assigned — a transform set on it
                            // beforehand is discarded, which showed up as
                            // reused cells rendering upside down while freshly
                            // created ones were fine.
                            .scaleEffect(x: 1, y: -1)
                            // The reading column, unchanged: one centred
                            // measure so a phone and an iPad detail pane read
                            // the same way, and prose never set flush to an
                            // edge.
                            .padding(.horizontal, 16)
                            .frame(maxWidth: 712, alignment: .leading)
                            .frame(maxWidth: .infinity, alignment: .center)
                    }
                    .margins(.all, 0)

                case .liveTurn:
                    cell.contentConfiguration = UIHostingConfiguration {
                        LiveTurnView(live: self.session.live, writerName: self.writerName)
                            .scaleEffect(x: 1, y: -1)
                            .padding(.horizontal, 16)
                            .frame(maxWidth: 712, alignment: .leading)
                            .frame(maxWidth: .infinity, alignment: .center)
                    }
                    .margins(.all, 0)

                case .paginating:
                    cell.contentConfiguration = UIHostingConfiguration {
                        ProgressView()
                            .scaleEffect(x: 1, y: -1)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                    }
                    .margins(.all, 0)
                }
            }

            dataSource = UICollectionViewDiffableDataSource<Int, Entry>(collectionView: view) {
                view, indexPath, entry in
                view.dequeueConfiguredReusableCell(using: cell, for: indexPath, item: entry)
            }
        }

        /// Hand the list a new set of rows.
        ///
        /// Called from `updateUIView`, so it runs on every SwiftUI update. The
        /// diffable data source is what makes that cheap — and what makes an
        /// unchanged identity an update rather than a delete and an insert.
        func apply(rows: [TimelineRow], isPaginating: Bool) {
            guard let dataSource else { return }

            writerName = rows.last { !$0.item.isOwn }?.senderName
                ?? session.rooms.selectedName ?? "Agent"

            var byId: [String: (row: TimelineRow, continuesRun: Bool)] = [:]
            byId.reserveCapacity(rows.count)
            for (index, row) in rows.enumerated() {
                // `continuesRun` asks about the row before this one in reading
                // order — a fact about the conversation, unaffected by the
                // inversion, which is a fact about the scroll.
                let previous = index > 0 ? rows[index - 1] : nil
                byId[row.item.id] = (row, TimelineGrouping.continuesRun(row, after: previous))
            }
            rowsById = byId

            var snapshot = NSDiffableDataSourceSnapshot<Int, Entry>()
            snapshot.appendSections([0])
            // Newest first, because the view is inverted. Index 0 is what the
            // reader sees at the bottom of the screen.
            if session.live.isLive { snapshot.appendItems([.liveTurn]) }
            snapshot.appendItems(rows.reversed().map { .row($0.item.id) })
            if isPaginating { snapshot.appendItems([.paginating]) }

            // Reconfigure rather than reload: an identity that survived should
            // update in place, which is the point of the identity this list is
            // keyed on.
            let existing = Set(dataSource.snapshot().itemIdentifiers)
            let carried = snapshot.itemIdentifiers.filter { existing.contains($0) }
            if !carried.isEmpty { snapshot.reconfigureItems(carried) }

            dataSource.apply(snapshot, animatingDifferences: false)
        }

        nonisolated func scrollViewDidScroll(_ scrollView: UIScrollView) {
            MainActor.assumeIsolated {
                // Distance from the oldest loaded message. In an inverted view
                // that is measured from the far end of the content.
                let distanceFromTop =
                    scrollView.contentSize.height - scrollView.contentOffset.y
                    - scrollView.bounds.height

                guard
                    TimelineFollow.wantsOlderHistory(
                        distanceFromTop: distanceFromTop,
                        canPaginate: timeline.canPaginate,
                        isPaginating: timeline.isPaginating,
                        // Inversion removes the reason the settle gate existed:
                        // arriving content cannot drag the offset toward the
                        // trigger, because it lands off the far end.
                        hasSettled: true)
                else { return }

                Task { await timeline.paginateBack() }
            }
        }
    }
}
