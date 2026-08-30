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
    /// Raised while the reader is away from the newest message, so the view
    /// above can offer a way back. Written from scroll callbacks.
    @Binding var isAwayFromNewest: Bool

    func makeCoordinator() -> Coordinator {
        Coordinator(session: session, timeline: timeline)
    }

    func makeUIView(context: Context) -> UICollectionView {
        var configuration = UICollectionLayoutListConfiguration(appearance: .plain)
        configuration.showsSeparators = false
        configuration.backgroundColor = .clear
        // Swipe to reply, the gesture an iOS reader already has in their
        // hands. It has to come from the list configuration: `.swipeActions`
        // in SwiftUI only does anything inside a `List`, so applied to a cell's
        // hosted content it was silently inert.
        configuration.leadingSwipeActionsConfigurationProvider = {
            [weak coordinator = context.coordinator] indexPath in
            coordinator?.swipeToReply(at: indexPath)
        }

        let view = UICollectionView(
            frame: .zero,
            collectionViewLayout: UICollectionViewCompositionalLayout.list(using: configuration))
        view.backgroundColor = .clear
        view.delegate = context.coordinator
        view.alwaysBounceVertical = true
        // Drag the conversation to put the keyboard away, the way Messages
        // does. There was no way to dismiss it at all: it took half the
        // screen and stayed there, so a reader who tapped the composer to
        // write and then changed their mind had to leave the room.
        //
        // `.interactive` rather than `.onDrag` because the list is inverted —
        // dragging *down* moves toward the newest message, which is the same
        // gesture that should bring the keyboard down with it, and
        // interactive tracking is what keeps the two moving together instead
        // of the keyboard snapping away on the first pixel.
        view.keyboardDismissMode = .interactive
        // The inversion itself. Everything else in this file follows from it.
        view.transform = CGAffineTransform(scaleX: 1, y: -1)
        // It would otherwise run down the leading edge and travel backwards.
        view.showsVerticalScrollIndicator = false
        // Room under the newest message. `top` because the view is inverted,
        // so the head of the content is what sits at the bottom of the screen:
        // without this the last line of a conversation rests flush against the
        // composer and reads as cut off.
        view.contentInset.top = 16

        context.coordinator.attach(to: view)
        // Posted by the jump-to-newest button, which lives in the SwiftUI view
        // above and has no other way to reach this scroll view.
        NotificationCenter.default.addObserver(
            forName: .scrollTimelineToNewest, object: nil, queue: .main
        ) { [weak view] _ in
            guard let view else { return }
            MainActor.assumeIsolated { Self.scrollToNewest(view) }
        }
        return view
    }

    func updateUIView(_ view: UICollectionView, context: Context) {
        context.coordinator.onDistanceChanged = { away in
            // Guarded: SwiftUI forbids mutating state during an update, and
            // the scroll callbacks that drive this can land inside one.
            if isAwayFromNewest != away {
                DispatchQueue.main.async { isAwayFromNewest = away }
            }
        }
        context.coordinator.apply(
            rows: timeline.items, revision: timeline.revision,
            isPaginating: timeline.isPaginating, isLive: session.live.isLive,
            isFinished: session.live.finished)
    }

    /// Bring the newest message back into view.
    ///
    /// Trivial in an inverted list: the newest message is the origin, so this
    /// is a scroll to zero rather than a search for the last row.
    static func scrollToNewest(_ view: UICollectionView) {
        view.setContentOffset(CGPoint(x: 0, y: -view.contentInset.top), animated: true)
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
        /// A collapsed run of membership changes, keyed on the first item in
        /// the run so a run that is still growing keeps the same identity.
        case membershipRun(String)
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
        /// Weak, and only for presenting from — the list owns the coordinator.
        private weak var list: UICollectionView?

        /// The rows behind the identifiers in the snapshot, and whether each
        /// continues a run. Grouping is resolved once here rather than per
        /// cell, because a cell knows only itself and grouping is a question
        /// about neighbours.
        private var rowsById: [String: (row: TimelineRow, continuesRun: Bool)] = [:]
        /// The sentence for each collapsed membership run, by its id.
        private var runsById: [String: String] = [:]
        /// The history's entry list from the last full pass, so an update
        /// that only toggles the live turn can be applied without redoing
        /// the grouping.
        private var displayEntries: [Entry] = []
        /// What the last pass was given, so the next one can tell what — if
        /// anything — actually changed. See `apply`.
        private var appliedRevision: UInt64 = 0
        private var appliedPaginating = false
        private var appliedLive = false
        private var appliedFinished = false
        private var hasApplied = false
        /// Whether one agent does all the talking, so the runtime suffix can
        /// come off every attribution in the room.
        private var singleSpeaker = true
        private var writerName = "Agent"
        /// Told when the reader moves away from, or back to, the newest
        /// message. Exact in an inverted list, where the bottom is the origin.
        var onDistanceChanged: ((Bool) -> Void)?
        private var wasAway = false

        init(session: Session, timeline: TimelineStore) {
            self.session = session
            self.timeline = timeline
        }

        func attach(to view: UICollectionView) {
            list = view
            let cell = UICollectionView.CellRegistration<UICollectionViewListCell, Entry> {
                [weak self] cell, _, entry in
                guard let self else { return }
                cell.backgroundConfiguration = .clear()

                switch entry {
                case let .row(id):
                    guard let found = self.rowsById[id] else { return }
                    cell.contentConfiguration = UIHostingConfiguration {
                        TimelineRowView(
                            row: found.row,
                            continuesRun: found.continuesRun,
                            attribution: self.singleSpeaker
                                ? found.row.senderShort : found.row.senderName,
                            media: self.session.media,
                            faces: self.session.faces,
                            onReply: { self.startReply(found.row) },
                            onReact: { key in self.react(found.row, key) },
                            onDecide: { answer in await self.decide(found.row, answer) }
                        )
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

                case let .membershipRun(id):
                    guard let text = self.runsById[id] else { return }
                    cell.contentConfiguration = UIHostingConfiguration {
                        SystemLine(text: text)
                            .scaleEffect(x: 1, y: -1)
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
        /// Called from `updateUIView`, so it runs on **every** SwiftUI update
        /// — and while an agent is writing, that is many times a second. So
        /// the first thing it does is work out how much of itself it can
        /// skip:
        ///
        /// - Nothing changed at all: return before touching anything.
        /// - Only the live turn or the pagination spinner changed: the
        ///   history is the same, so reuse the entry list computed last time
        ///   and re-apply. No grouping pass, no reconfigure.
        /// - The history changed: recompute, and reconfigure only the rows
        ///   whose content actually differs.
        ///
        /// The version this replaced did the full pass every time and called
        /// `reconfigureItems` on *every* carried identifier, which threw away
        /// and rebuilt the `UIHostingConfiguration` of every visible cell on
        /// every streaming token. That is what the jitter was: not a missing
        /// animation, but every row on screen being re-measured and laid out
        /// again several times a second.
        func apply(
            rows: [TimelineRow], revision: UInt64, isPaginating: Bool, isLive: Bool,
            isFinished: Bool
        ) {
            guard let dataSource else { return }

            let historyChanged = revision != appliedRevision || !hasApplied
            if !historyChanged, isPaginating == appliedPaginating, isLive == appliedLive,
                isFinished == appliedFinished
            {
                return
            }

            if !historyChanged {
                appliedPaginating = isPaginating
                appliedLive = isLive
                // One row appearing or disappearing at the newest end — the
                // "writing…" card, or the pagination spinner. Always worth
                // animating when the reader is looking at it.
                appliedFinished = isFinished
                dataSource.apply(
                    snapshot(
                        entries: displayEntries, isPaginating: isPaginating, isLive: isLive,
                        isFinished: isFinished),
                    animatingDifferences: !wasAway)
                return
            }

            appliedRevision = revision
            appliedPaginating = isPaginating
            appliedLive = isLive
            appliedFinished = isFinished
            // Set *after* this pass, so `animates` can tell a first fill —
            // a room switch — from an arrival into a room already on screen.
            defer { hasApplied = true }

            writerName = rows.last { !$0.item.isOwn }?.senderName
                ?? session.rooms.selectedName ?? "Agent"

            // Collapse membership churn first, so `continuesRun` compares a
            // row against the row *displayed* before it rather than against a
            // membership line that is no longer drawn on its own.
            let display = TimelineGrouping.collapseMembershipRuns(rows)

            var byId: [String: (row: TimelineRow, continuesRun: Bool)] = [:]
            var runs: [String: String] = [:]
            byId.reserveCapacity(rows.count)
            var previous: TimelineRow?
            for entry in display {
                switch entry {
                case let .row(row):
                    byId[row.item.id] = (row, TimelineGrouping.continuesRun(row, after: previous))
                    previous = row
                case let .membershipRun(id, text, _):
                    runs[id] = text
                    previous = nil
                }
            }
            let previousRows = rowsById
            let previousRuns = runsById
            let previousSingleSpeaker = singleSpeaker

            rowsById = byId
            runsById = runs
            singleSpeaker = TimelineGrouping.hasSingleSpeaker(rows)

            // Newest first, because the view is inverted. Index 0 is what the
            // reader sees at the bottom of the screen.
            displayEntries = display.reversed().map { entry in
                switch entry {
                case let .row(row): return Entry.row(row.item.id)
                case let .membershipRun(id, _, _): return Entry.membershipRun(id)
                }
            }

            var snap = snapshot(
                entries: displayEntries, isPaginating: isPaginating, isLive: isLive,
                isFinished: isFinished)

            // Reconfigure rather than reload: an identity that survived should
            // update in place, which is the point of the identity this list is
            // keyed on.
            //
            // **Only the ones that changed.** Reconfiguring every carried
            // identifier rebuilds the hosting configuration of every visible
            // cell, so a single edited message re-laid-out the whole screen.
            // A row is worth reconfiguring when its content differs, when its
            // place in a run changed, or when the attribution rule flipped —
            // `singleSpeaker` decides which name every row shows, so a change
            // there is a change to all of them.
            let existing = Set(dataSource.snapshot().itemIdentifiers)
            let changed = snap.itemIdentifiers.filter { entry in
                guard existing.contains(entry) else { return false }
                switch entry {
                case let .row(id):
                    if previousSingleSpeaker != singleSpeaker { return true }
                    guard let before = previousRows[id], let after = byId[id] else { return true }
                    return before.row != after.row || before.continuesRun != after.continuesRun
                case let .membershipRun(id):
                    return previousRuns[id] != runs[id]
                // The live turn redraws itself: `LiveTurnView` reads the
                // observable store directly, so its cell never needs telling.
                case .liveTurn, .paginating:
                    return false
                }
            }
            if !changed.isEmpty { snap.reconfigureItems(changed) }

            let arrived = snap.itemIdentifiers.filter { !existing.contains($0) }.count
            dataSource.apply(
                snap, animatingDifferences: animates(arrived: arrived, had: existing.count))
        }

        /// Whether an update to the history should animate.
        ///
        /// **A message arriving, and nothing else.** That is the one change
        /// where the movement carries information: the conversation makes
        /// room for something new while the reader is watching the place it
        /// appears.
        ///
        /// Everything else is excluded, each for its own reason:
        ///
        /// - *Away from the newest end*: the reader is reading something, and
        ///   animating moves it under them.
        /// - *A room with nothing in it yet*: the first fill is not an
        ///   arrival, it is the room appearing, and animating every row of it
        ///   is a screenful of movement that says nothing.
        /// - *More than a handful at once*: that is a page of history, or a
        ///   resync. A conversation does not gain eight messages in one
        ///   moment, so if it looks like it did, this is not an arrival.
        private func animates(arrived: Int, had: Int) -> Bool {
            guard hasApplied, !wasAway, had > 0 else { return false }
            return arrived > 0 && arrived <= 3
        }

        /// The snapshot for a given entry list, with the two transient rows
        /// that bracket it.
        ///
        /// **Where the turn card goes depends on whether it has finished.**
        /// Index 0 is the bottom of the screen, because the list is inverted.
        ///
        /// A turn *in progress* belongs at the bottom: it is the newest thing
        /// in the room, still being written, and the message it becomes has
        /// not arrived.
        ///
        /// A turn that has *finished* belongs above the message it produced.
        /// The reasoning and the tool calls happened before the answer, and
        /// drawing them under it says they happened after — which is what the
        /// room looked like: an answer, and then, below it, the thinking that
        /// led to it.
        private func snapshot(
            entries: [Entry], isPaginating: Bool, isLive: Bool, isFinished: Bool
        ) -> NSDiffableDataSourceSnapshot<Int, Entry> {
            var snapshot = NSDiffableDataSourceSnapshot<Int, Entry>()
            snapshot.appendSections([0])
            if isLive, !isFinished { snapshot.appendItems([.liveTurn]) }
            if isLive, isFinished, let newest = entries.first {
                // The newest message stays at the bottom; the record of the
                // turn sits immediately older than it.
                snapshot.appendItems([newest])
                snapshot.appendItems([.liveTurn])
                snapshot.appendItems(Array(entries.dropFirst()))
            } else {
                snapshot.appendItems(entries)
            }
            if isPaginating { snapshot.appendItems([.paginating]) }
            return snapshot
        }

        /// Start a reply to `row`, for the composer to pick up.
        ///
        /// The room comes from the timeline store rather than being captured:
        /// a cell can outlive a room switch, and a reply filed against the
        /// room the reader has left is a message sent to the wrong place.
        fileprivate func startReply(_ row: TimelineRow) {
            guard let roomId = timeline.roomId else { return }
            session.replies.start(row, in: roomId)
        }

        /// Put a message back into the composer to be rewritten.
        ///
        /// The room comes from the timeline store rather than being captured,
        /// for the same reason `startReply` does it: a cell can outlive a room
        /// switch, and an edit filed against a room the reader has left would
        /// rewrite a message they are no longer looking at.
        fileprivate func startEdit(_ row: TimelineRow) {
            guard let roomId = timeline.roomId else { return }
            session.edits.start(row, in: roomId)
        }

        /// Ask before deleting. A redaction is permanent and public, which is
        /// exactly the shape of action that should not happen on one tap of a
        /// menu the reader opened by long-pressing.
        fileprivate func confirmDelete(_ row: TimelineRow) {
            guard let roomId = timeline.roomId, let view = list else { return }
            let sheet = UIAlertController(
                title: "Delete message?",
                message: "This removes it for everyone in the room.",
                preferredStyle: .actionSheet)
            sheet.addAction(
                UIAlertAction(title: "Delete", style: .destructive) { _ in
                    Task { await self.session.delete(row.item.eventId, in: roomId) }
                })
            sheet.addAction(UIAlertAction(title: "Cancel", style: .cancel))
            // iPad presents an action sheet as a popover and requires an
            // anchor; without one it traps rather than falling back.
            sheet.popoverPresentationController?.sourceView = view
            sheet.popoverPresentationController?.sourceRect = CGRect(
                x: view.bounds.midX, y: view.bounds.midY, width: 0, height: 0)
            view.window?.rootViewController?.topmostPresented.present(sheet, animated: true)
        }

        fileprivate func react(_ row: TimelineRow, _ key: String) {
            guard let roomId = timeline.roomId else { return }
            Task { await session.toggleReaction(row.item.eventId, key: key, in: roomId) }
        }

        /// Answer a decision on a suite event.
        ///
        /// The event id is the gate's own, which the decision references; the
        /// card supplies what it resolves. Both are needed and neither side
        /// has both, which is why this meets in the middle here.
        /// Returns whether the answer landed, so the card can stop offering
        /// the choice — and keep offering it when the send failed.
        @discardableResult
        fileprivate func decide(_ row: TimelineRow, _ answer: GateAnswer) async -> Bool {
            guard let roomId = timeline.roomId else { return false }
            return await session.answerGate(
                row.item.eventId, gateId: answer.subject, optionId: answer.optionId,
                comment: answer.comment, prompt: answer.prompt, in: roomId)
        }

        /// Long press a message to act on it.
        ///
        /// Built here rather than with SwiftUI's `.contextMenu` on the cell's
        /// content: the collection view's own gestures win, and the menu never
        /// appeared. This is the list's own mechanism, so it also gets the
        /// lift-and-preview a reader expects.
        nonisolated func collectionView(
            _ collectionView: UICollectionView,
            contextMenuConfigurationForItemAt indexPath: IndexPath,
            point: CGPoint
        ) -> UIContextMenuConfiguration? {
            MainActor.assumeIsolated {
                guard let row = row(at: indexPath) else { return nil }
                return UIContextMenuConfiguration(
                    identifier: nil,
                    // **A preview of our own, not the cell's.** The default
                    // lifts a snapshot of the cell, and every cell here
                    // carries the list's inversion — the lift came up as a
                    // one-pixel-wide sliver. Rendering the row again, the
                    // right way up, is what a reader should see held above the
                    // conversation.
                    previewProvider: { [weak self] in
                        guard let self else { return nil }
                        let host = UIHostingController(
                            rootView: TimelineRowView(
                                row: row, continuesRun: false,
                                media: self.session.media, faces: self.session.faces
                            )
                            .padding(.horizontal, 16)
                            .frame(maxWidth: 360, alignment: .leading))
                        host.view.backgroundColor = .clear
                        host.preferredContentSize = host.sizeThatFits(
                            in: CGSize(width: 360, height: CGFloat.greatestFiniteMagnitude))
                        return host
                    }
                ) { [weak self] _ in
                    guard let self else { return nil }
                    var actions: [UIMenuElement] = []

                    // Nothing is offered against a message the server has not
                    // acknowledged: a reply or a reaction addresses an event
                    // and there is no event yet. The core decides that — see
                    // `can_reply_or_react`.
                    if row.canReplyOrReact {
                        // One horizontal strip, the shape Messages uses — not
                        // six full-width rows, which is what an inline menu
                        // gives by default and which read as a list of
                        // commands that happened to be emoji.
                        let reactions = UIMenu(
                            title: "", options: .displayInline,
                            children: quickReactions.map { emoji in
                                UIAction(title: emoji) { _ in self.react(row, emoji) }
                            })
                        reactions.preferredElementSize = .small
                        actions.append(reactions)
                        actions.append(
                            UIAction(
                                title: "Reply",
                                image: UIImage(systemName: "arrowshape.turn.up.left")
                            ) { _ in self.startReply(row) })
                    }
                    if let body = row.item.body, !body.isEmpty {
                        actions.append(
                            UIAction(title: "Copy", image: UIImage(systemName: "doc.on.doc")) {
                                _ in UIPasteboard.general.string = body
                            })
                    }
                    // `editable` is the SDK's answer, not `isOwn`: a state
                    // event of your own is not editable and neither is a
                    // redacted one, so inferring it from ownership would
                    // offer an Edit the homeserver refuses.
                    if row.item.editable {
                        actions.append(
                            UIAction(title: "Edit", image: UIImage(systemName: "pencil")) { _ in
                                self.startEdit(row)
                            })
                    }
                    // Deletion is offered only for this account's own
                    // messages. Redacting someone else's needs a power level
                    // this app does not check, and an action that fails at
                    // the homeserver reads as the app being broken.
                    if row.item.isOwn, row.canReplyOrReact {
                        actions.append(
                            UIAction(
                                title: "Delete", image: UIImage(systemName: "trash"),
                                attributes: .destructive
                            ) { _ in self.confirmDelete(row) })
                    }
                    return actions.isEmpty ? nil : UIMenu(children: actions)
                }
            }
        }

        func swipeToReply(at indexPath: IndexPath) -> UISwipeActionsConfiguration? {
            guard let row = row(at: indexPath), row.canReplyOrReact else { return nil }
            let reply = UIContextualAction(style: .normal, title: "Reply") {
                [weak self] _, _, done in
                self?.startReply(row)
                done(true)
            }
            reply.image = UIImage(systemName: "arrowshape.turn.up.left")
            reply.backgroundColor = .tintColor
            return UISwipeActionsConfiguration(actions: [reply])
        }

        private func row(at indexPath: IndexPath) -> TimelineRow? {
            guard case let .row(id)? = dataSource?.itemIdentifier(for: indexPath) else {
                return nil
            }
            return rowsById[id]?.row
        }

        nonisolated func scrollViewDidScroll(_ scrollView: UIScrollView) {
            MainActor.assumeIsolated {
                // **Being at the newest message is exactly `contentOffset.y <= 0`.**
                // That exactness is the whole argument for the inversion: in a
                // natural-order list this is a comparison of three numbers with
                // a tolerance to tune.
                let away = scrollView.contentOffset.y > scrollView.bounds.height / 2
                if away != wasAway {
                    wasAway = away
                    onDistanceChanged?(away)
                }

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

extension Notification.Name {
    /// Raised by the jump-to-newest button. A notification rather than a
    /// binding because the button is SwiftUI and the scroll view is UIKit, and
    /// a one-shot command is not state either of them owns.
    static let scrollTimelineToNewest = Notification.Name("dev.supermessage.scrollTimelineToNewest")
}

extension UIViewController {
    /// The controller actually on screen, so a sheet is presented from it
    /// rather than from a root that is already covered by something else —
    /// presenting on a covered controller silently does nothing.
    var topmostPresented: UIViewController {
        presentedViewController?.topmostPresented ?? self
    }
}
