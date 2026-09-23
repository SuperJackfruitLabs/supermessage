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
///
/// ## Under the navigation bar (D4)
///
/// The list used to stop at the bar's lower edge, so a message scrolling up
/// was cut off mid-line against it with no edge effect at all. It now runs
/// **under** the bar — the glass on iOS 26, the translucent bar before it —
/// with the content inset keeping the oldest row clear of it, and a fade in
/// the page's own colour where the two meet. UIKit's own scroll-edge effect is
/// turned off rather than trusted: it draws at the scroll view's top, which in
/// an inverted list is the bottom of the screen.
///
/// The bar keeps its touches: it is drawn above this content by the
/// navigation container, which is not the arrangement that swallowed them
/// (see below).
struct TimelineCollectionView: View {
    let session: Session
    let timeline: TimelineStore
    /// Raised while the reader is away from the newest message, so the view
    /// above can offer a way back. Written from scroll callbacks.
    @Binding var isAwayFromNewest: Bool

    var body: some View {
        // **Inside the safe area, not under the bar.** The list used to run
        // under the navigation bar so text could fade beneath it (D4). But a
        // hosted cell counts whatever part of it lies under the bar as safe-
        // area inset and grows by that much: every row swelled as it scrolled
        // under the bar and shrank as it left, the list re-laid its
        // neighbours on every frame, and rows slid over one another — the
        // 2026-09-23 recording, measured with `-fixtureTimeline` as a day
        // divider going 37 → 138 → 37pt with scroll position. Kept below the
        // bar, content never reaches it, and the fade sits at the list's own
        // top edge instead.
        GeometryReader { proxy in
            let obscured = proxy.safeAreaInsets.top
            TimelineList(
                session: session, timeline: timeline, isAwayFromNewest: $isAwayFromNewest,
                topObscured: obscured
            )
            .overlay(alignment: .top) {
                // The fade the bar sits on: opaque page colour under the bar
                // itself, easing to nothing a line below it, so text slides
                // out of view instead of being sliced by an edge.
                LinearGradient(
                    stops: [
                        .init(color: Theme.surface, location: 0),
                        .init(color: Theme.surface.opacity(0.85), location: obscured > 0 ? 0.55 : 0),
                        .init(color: Theme.surface.opacity(0), location: 1),
                    ], startPoint: .top, endPoint: .bottom
                )
                .frame(height: obscured + 28)
                .allowsHitTesting(false)
                .accessibilityHidden(true)
            }
        }
        .background(Theme.surface)
    }
}

/// The collection view itself. See `TimelineCollectionView`.
struct TimelineList: UIViewRepresentable {
    let session: Session
    let timeline: TimelineStore
    @Binding var isAwayFromNewest: Bool
    /// How much of the top of this view is under the navigation bar, so the
    /// oldest row can come to rest below it.
    var topObscured: CGFloat = 0

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
        // The page's own colour, never the system's (D9): a clear list over a
        // container that fell back to `systemBackground` was pure black in
        // dark mode.
        view.backgroundColor = UIColor(Theme.surface)
        // The insets are set by hand, from what SwiftUI says is obscured.
        // UIKit's automatic adjustment reads the safe area in the view's own
        // coordinates, and in an inverted view those are upside down.
        view.contentInsetAdjustmentBehavior = .never
        Self.disableSystemEdgeEffects(view)
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
        context.coordinator.installTimeReveal(on: view)
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
        // The far end of the inverted content is the top of the screen, so
        // the room under the bar is the *bottom* inset.
        let bottom = topObscured + 8
        if view.contentInset.bottom != bottom {
            view.contentInset.bottom = bottom
            view.verticalScrollIndicatorInsets.bottom = topObscured
        }
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

    /// Turn off UIKit's scroll-edge effect on iOS 26.
    ///
    /// It is drawn at the scroll view's top and bottom in the scroll view's
    /// own coordinates, so in an inverted list the blur meant for the bar
    /// lands over the composer, and the bar gets the one meant for the
    /// bottom. The fade in `TimelineCollectionView` replaces it. Compiled only
    /// by an SDK that has the API: CI builds with iOS 26's, a local Xcode 16
    /// with iOS 18's.
    static func disableSystemEdgeEffects(_ view: UIScrollView) {
        #if compiler(>=6.2)
        if #available(iOS 26.0, *) {
            view.topEdgeEffect.isHidden = true
            view.bottomEdgeEffect.isHidden = true
        }
        #endif
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
        /// A finished turn's record — "What I did · N steps" — under the
        /// agent's newest message (T6).
        case turnRecord
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

        /// Everything the list decided about one row by looking at its
        /// neighbours — a cell knows only itself, and every one of these is a
        /// question about the rows around it.
        struct Placement: Equatable {
            let row: TimelineRow
            /// The row above carries this sender's header.
            var continuesRun: Bool
            /// The last of its run, where own messages carry their time.
            var endsRun: Bool
            /// The quote only repeats the row directly above (D6).
            var hidesQuote: Bool
            /// Whose read receipts point here, with faces (D7).
            var readers: [ReaderFace]
        }

        /// The rows behind the identifiers in the snapshot. Grouping is
        /// resolved once here rather than per cell.
        private var rowsById: [String: Placement] = [:]
        /// Row identity by event id, so a reply quote can find its parent
        /// (T5). Only rows this device has loaded are in it, which is the
        /// point: a parent that is not here is not jumped to.
        private var idByEventId: [String: String] = [:]
        /// The sentence for each collapsed membership run, by its id.
        private var runsById: [String: String] = [:]
        /// Stretches of membership churn the reader has tapped open.
        private var expandedStretches: Set<String> = []
        /// The last history applied, so opening a stretch can regroup it
        /// without waiting for the next update.
        private var lastApply: (rows: [TimelineRow], isPaginating: Bool, isLive: Bool, isFinished: Bool)?
        private var forceRegroup = false
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
        /// Entries that arrived in the last animated pass and have not been
        /// drawn yet — the only ones that spring in (M1).
        private var arriving: Set<Entry> = []
        /// The row a quote tap just landed on, lit until the timer clears it.
        private var highlightedId: String?
        private var highlightTask: Task<Void, Never>?
        /// The leftward swipe that shows every message's time (T4).
        let reveal = TimeReveal()
        private var revealPan: UIPanGestureRecognizer?
        fileprivate let selection = UISelectionFeedbackGenerator()

        init(session: Session, timeline: TimelineStore) {
            self.session = session
            self.timeline = timeline
        }

        /// A cell's content in the reading column, the right way up.
        ///
        /// The flip is applied to the content rather than to
        /// `cell.contentView`, because `UIHostingConfiguration` replaces that
        /// view when assigned — a transform set on it beforehand is
        /// discarded, which showed up as reused cells rendering upside down
        /// while freshly created ones were fine.
        ///
        /// `.minSize(height: 0)` because a list cell's default minimum is
        /// 44pt: a one-line membership row was drawn in a 44pt cell, and a few
        /// of them stacked were most of the "130pt apart" in D5.
        private func column<Content: View>(
            _ entry: Entry, @ViewBuilder _ content: () -> Content
        ) -> UIHostingConfiguration<some View, EmptyView> {
            return UIHostingConfiguration {
                content()
                    .scaleEffect(x: 1, y: -1)
                    // The reading column, unchanged: one centred measure so a
                    // phone and an iPad detail pane read the same way, and
                    // prose never set flush to an edge.
                    .padding(.horizontal, 16)
                    .frame(maxWidth: 712, alignment: .leading)
                    .frame(maxWidth: .infinity, alignment: .center)
                    .ignoresSafeArea()
            }
            .margins(.all, 0)
            .minSize(width: 0, height: 0)
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
                    let row = found.row
                    let parent = row.item.replyTo?.eventId
                    let canJump = parent.flatMap { self.idByEventId[$0] } != nil
                    cell.contentConfiguration = self.column(entry) {
                        RevealsTime(reveal: self.reveal, time: Self.revealedTime(row)) {
                            TimelineRowView(
                                row: row,
                                continuesRun: found.continuesRun,
                                endsRun: found.endsRun,
                                attribution: self.singleSpeaker ? row.senderShort : row.senderName,
                                media: self.session.media,
                                faces: self.session.faces,
                                hidesQuote: found.hidesQuote,
                                readers: found.readers,
                                highlighted: self.highlightedId == id,
                                onReply: { self.startReply(row) },
                                onReact: { key in self.react(row, key) },
                                onQuoteTap: canJump ? { self.jumpToParent(of: row) } : nil,
                                onDecide: { answer in await self.decide(row, answer) }
                            )
                        }
                    }

                case let .membershipRun(id):
                    guard let text = self.runsById[id] else { return }
                    if TimelineGrouping.isStretch(id) {
                        // A collapsed stretch: tap to see every line.
                        cell.contentConfiguration = self.column(entry) {
                            Button { self.expandStretch(id) } label: {
                                HStack(spacing: 4) {
                                    SystemLine(text: text).fixedSize(horizontal: false, vertical: true)
                                    Image(systemName: "chevron.down")
                                        .imageScale(.small)
                                        .foregroundStyle(Theme.contentFaint)
                                }
                                .frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.plain)
                            .accessibilityHint("Shows each change")
                        }
                    } else {
                        cell.contentConfiguration = self.column(entry) { SystemLine(text: text) }
                    }

                case .liveTurn:
                    cell.contentConfiguration = self.column(entry) {
                        LiveTurnView(live: self.session.live, writerName: self.writerName)
                    }

                case .turnRecord:
                    cell.contentConfiguration = self.column(entry) {
                        WhatIDidFooter(live: self.session.live)
                    }

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

        /// The time a leftward swipe shows beside a row, or `nil` for a row
        /// that is about the list rather than in it.
        static func revealedTime(_ row: TimelineRow) -> String? {
            switch row.view {
            case .dateDivider, .unreadMarker, .system, .none: return nil
            default: return row.item.timestampMs.map(TimelineTime.short)
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
            lastApply = (rows, isPaginating, isLive, isFinished)

            let historyChanged = revision != appliedRevision || !hasApplied || forceRegroup
            forceRegroup = false
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
            let display = TimelineGrouping.collapseMembershipRuns(rows, expanded: expandedStretches)

            // Faces for readers, from the messages they have sent here. A
            // receipt carries only a user id; the room has usually already
            // shown that person's face beside something they said.
            var facesBySender: [String: ReaderFace] = [:]
            for row in rows {
                guard let sender = row.item.sender, facesBySender[sender] == nil,
                    row.item.kind == "message"
                else { continue }
                facesBySender[sender] = ReaderFace(
                    userId: sender, mxcUri: row.item.senderAvatar, initial: row.senderInitial)
            }

            var byId: [String: Placement] = [:]
            var byEventId: [String: String] = [:]
            var runs: [String: String] = [:]
            byId.reserveCapacity(rows.count)
            var previous: TimelineRow?
            for entry in display {
                switch entry {
                case let .row(row):
                    let continues = TimelineGrouping.continuesRun(row, after: previous)
                    // The row above is no longer the last of its run.
                    if continues, let above = previous, var placement = byId[above.item.id] {
                        placement.endsRun = false
                        byId[above.item.id] = placement
                    }
                    let readers = row.item.readBy.map {
                        facesBySender[$0] ?? ReaderFace(userId: $0, mxcUri: nil, initial: nil)
                    }
                    byId[row.item.id] = Placement(
                        row: row, continuesRun: continues, endsRun: true,
                        hidesQuote: TimelineGrouping.quoteRepeatsPrevious(row, after: previous),
                        readers: readers)
                    if let eventId = row.item.eventId { byEventId[eventId] = row.item.id }
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
            idByEventId = byEventId
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
                    return before != after
                case let .membershipRun(id):
                    return previousRuns[id] != runs[id]
                // The live turn redraws itself: `LiveTurnView` reads the
                // observable store directly, so its cell never needs telling.
                case .liveTurn, .turnRecord, .paginating:
                    return false
                }
            }
            if !changed.isEmpty { snap.reconfigureItems(changed) }

            let arrivals = snap.itemIdentifiers.filter { !existing.contains($0) }
            let animated = animates(arrived: arrivals.count, had: existing.count)
            // Only messages spring in; a spinner or a turn card arriving is
            // not news in the same way.
            arriving = animated ? Set(arrivals.filter { if case .row = $0 { true } else { false } }) : []
            dataSource.apply(snap, animatingDifferences: animated)
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

        /// The snapshot for a given entry list, with the transient rows that
        /// bracket it.
        ///
        /// **Where the turn goes depends on whether it has finished.** Index
        /// 0 is the bottom of the screen, because the list is inverted.
        ///
        /// A turn *in progress* belongs at the bottom: it is the newest thing
        /// in the room, still being written, and the message it becomes has
        /// not arrived.
        ///
        /// A turn that has *finished* is a footnote to the answer it
        /// produced — "What I did · 4 steps" — directly under the agent's
        /// newest message (T6). It used to be a whole card above that
        /// message, which put the working ahead of the conclusion and read as
        /// a second reply.
        private func snapshot(
            entries: [Entry], isPaginating: Bool, isLive: Bool, isFinished: Bool
        ) -> NSDiffableDataSourceSnapshot<Int, Entry> {
            var snapshot = NSDiffableDataSourceSnapshot<Int, Entry>()
            snapshot.appendSections([0])
            if isLive, !isFinished { snapshot.appendItems([.liveTurn]) }
            if isLive, isFinished {
                // Under the newest message from someone else — the answer.
                // Newest first, so "under" is the index before it.
                let anchor = entries.firstIndex { entry in
                    guard case let .row(id) = entry, let row = rowsById[id]?.row else {
                        return false
                    }
                    guard case .bubble = row.view else { return false }
                    return !row.item.isOwn
                } ?? 0
                var ordered = entries
                ordered.insert(.turnRecord, at: anchor)
                snapshot.appendItems(ordered)
            } else {
                snapshot.appendItems(entries)
            }
            if isPaginating { snapshot.appendItems([.paginating]) }
            return snapshot
        }

        /// Scroll to the message a reply quotes and light it briefly (T5).
        ///
        /// Does nothing when the parent is not loaded — the quote then offers
        /// no tap at all, so this is only a guard against a row that left
        /// between the tap and now.
        fileprivate func jumpToParent(of row: TimelineRow) {
            guard let parent = row.item.replyTo?.eventId, let id = idByEventId[parent],
                let list, let dataSource,
                let indexPath = dataSource.indexPath(for: .row(id))
            else { return }
            let reduceMotion = UIAccessibility.isReduceMotionEnabled
            list.scrollToItem(at: indexPath, at: .centeredVertically, animated: !reduceMotion)
            setHighlight(id)
            highlightTask?.cancel()
            highlightTask = Task { @MainActor [weak self] in
                try? await Task.sleep(for: .seconds(1.4))
                guard !Task.isCancelled else { return }
                self?.setHighlight(nil)
            }
        }

        private func setHighlight(_ id: String?) {
            guard let dataSource else { return }
            // Deduplicated: the same id twice — a second tap on the same
            // quote — would be a non-unique reconfigure, which traps.
            let changed = Set([highlightedId, id].compactMap { $0 }).map(Entry.row)
            highlightedId = id
            var snap = dataSource.snapshot()
            let present = changed.filter { snap.indexOfItem($0) != nil }
            guard !present.isEmpty else { return }
            snap.reconfigureItems(present)
            dataSource.apply(snap, animatingDifferences: false)
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
            guard let gateId = answer.subject else {
                // A permission request: the option, as a plain reply.
                return await session.answerPermission(optionId: answer.optionId, in: roomId)
            }
            return await session.answerGate(
                row.item.eventId, gateId: gateId, optionId: answer.optionId,
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
                                UIAction(title: emoji) { _ in
                                    // A reaction is a selection (M2). The
                                    // chips under a message fire their own
                                    // through `.sensoryFeedback`; this is the
                                    // menu's strip, which is UIKit's.
                                    self.selection.selectionChanged()
                                    self.react(row, emoji)
                                }
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

        /// Open a collapsed stretch of membership changes in place.
        private func expandStretch(_ id: String) {
            guard let last = lastApply else { return }
            expandedStretches.insert(id)
            forceRegroup = true
            selection.selectionChanged()
            apply(
                rows: last.rows, revision: appliedRevision, isPaginating: last.isPaginating,
                isLive: last.isLive, isFinished: last.isFinished)
        }

        // MARK: Arrival (M1)

        /// A message that has just arrived rises into place.
        ///
        /// On the cell, per display, rather than in SwiftUI. The SwiftUI
        /// version hid a row in `@State` until `onAppear` revealed it — and a
        /// recycled cell keeps its hosted view's state and does not appear
        /// again, so a reused row could stay invisible: the blank gaps in the
        /// scrolling recording of 2026-09-23. This runs fresh for every
        /// display and puts every other cell back to rest.
        nonisolated func collectionView(
            _ collectionView: UICollectionView, willDisplay cell: UICollectionViewCell,
            forItemAt indexPath: IndexPath
        ) {
            MainActor.assumeIsolated {
                let view = cell.contentView
                view.layer.removeAllAnimations()
                guard let entry = dataSource?.itemIdentifier(for: indexPath),
                    arriving.remove(entry) != nil
                else {
                    view.alpha = 1
                    view.transform = .identity
                    return
                }
                let reduceMotion = UIAccessibility.isReduceMotionEnabled
                view.alpha = 0
                // The list is flipped, so "from below" is a negative y here.
                view.transform = reduceMotion ? .identity : CGAffineTransform(translationX: 0, y: -16)
                UIView.animate(
                    withDuration: reduceMotion ? 0.15 : 0.38, delay: 0,
                    usingSpringWithDamping: reduceMotion ? 1 : 0.78, initialSpringVelocity: 0,
                    options: [.allowUserInteraction, .beginFromCurrentState]
                ) {
                    view.alpha = 1
                    view.transform = .identity
                }
            }
        }

        // MARK: Swipe left for times (T4)

        /// A leftward pan anywhere on the list slides every message over and
        /// shows its time at the trailing edge, the way Messages does, and
        /// springs back on release.
        ///
        /// **Leftward only, and only when mostly horizontal.** The list's own
        /// swipe-to-reply is a *rightward* swipe on a cell, and scrolling is
        /// vertical; this declines both before it begins, so neither has
        /// anything to arbitrate. The scroll view's pan waits for this one to
        /// decline, which it does at the same movement threshold at which it
        /// would begin — a vertical drag is not held up by it.
        func installTimeReveal(on view: UICollectionView) {
            let pan = UIPanGestureRecognizer(target: self, action: #selector(revealPanned(_:)))
            pan.delegate = self
            pan.cancelsTouchesInView = true
            view.addGestureRecognizer(pan)
            // Deliberately *not* `panGestureRecognizer.require(toFail: pan)`.
            // That made every scroll wait for this gesture to decline first,
            // so a drag stuck and then lurched a screen at a time, and a flick
            // that curved left was taken for a reveal mid-scroll. The two are
            // exclusive by default; whichever begins first wins, and this one
            // begins only on a clearly horizontal drag.
            revealPan = pan
        }

        @objc private func revealPanned(_ pan: UIPanGestureRecognizer) {
            let width = TimeReveal.width
            switch pan.state {
            case .began:
                reveal.tracking = true
            case .changed:
                let pulled = max(0, -pan.translation(in: pan.view).x)
                // Rubber-banded past the full width, so the edge is felt
                // rather than hit.
                reveal.offset = pulled <= width ? pulled : width + (pulled - width) * 0.2
            case .ended, .cancelled, .failed:
                // The spring lives on the offset itself (`RevealsTime`).
                reveal.tracking = false
                reveal.offset = 0
            default:
                break
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

extension TimelineList.Coordinator: UIGestureRecognizerDelegate {
    /// Begin only for a leftward, mostly horizontal drag. Anything else is a
    /// scroll or a swipe to reply, and belongs to those.
    nonisolated func gestureRecognizerShouldBegin(_ gesture: UIGestureRecognizer) -> Bool {
        MainActor.assumeIsolated {
            guard let pan = gesture as? UIPanGestureRecognizer, pan.view != nil else { return true }
            // Leftward and clearly horizontal — three times as much sideways
            // as vertical — measured on both the velocity and the distance
            // so far, so the curve of a thumb flicking up the list is never
            // read as a reveal.
            let velocity = pan.velocity(in: pan.view)
            let moved = pan.translation(in: pan.view)
            return velocity.x < 0 && abs(velocity.x) > abs(velocity.y) * 3
                && moved.x < 0 && abs(moved.x) > abs(moved.y) * 3
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

#if DEBUG
// The list itself, without `TimelineView` around it.
//
// Worth previewing separately from `TimelineView` because this is a
// `UIViewRepresentable`: its cells are `UIHostingController`s inside
// `UICollectionViewListCell`s, and the two places that arrangement goes wrong
// — a cell that will not size itself to its content, and a separator drawn
// where the design has none — are both invisible in a SwiftUI-only preview of
// the parent.
#Preview("A conversation") {
    @Previewable @State var isAwayFromNewest = false
    let session = PreviewFixtures.session()
    return TimelineCollectionView(
        session: session, timeline: session.timeline, isAwayFromNewest: $isAwayFromNewest)
}
#endif

