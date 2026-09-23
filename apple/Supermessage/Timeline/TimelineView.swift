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

    @State private var isAwayFromNewest = false
    /// Who is in the room: its agents, and who the header names (D11).
    @State private var cast = RoomCast()

    /// What re-marks the room read: a change to the history, or the reader
    /// coming back to the newest end after being away.
    private var readMarker: String {
        "\(timeline.roomId ?? "")-\(timeline.revision)-\(isAwayFromNewest)"
    }

    var body: some View {
        // Everything that used to be here — the ScrollView, the LazyVStack,
        // the scroll-position binding, the ScrollViewReader and the geometry
        // observer — is now `TimelineCollectionView`, whose doc comment
        // explains why. What is left is what was never the problem: marking
        // the room read, and the typing line.
        TimelineCollectionView(
            session: session, timeline: timeline, isAwayFromNewest: $isAwayFromNewest)
            .task(id: timeline.roomId) {
                await timeline.markRead()
            }
            // **And again whenever something arrives while you are reading.**
            // Marking on entry alone meant a message that landed while the
            // room was open on screen stayed unread — you read it, went back
            // to the roster, and the room was still bold, which is the app
            // disagreeing with what you just did.
            //
            // Gated on being at the newest end: scrolled up in history, the
            // newest message genuinely has not been read, and saying it has
            // would lose it. `mark_as_read` is a no-op at the homeserver when
            // the receipt already points at the latest event, so firing this
            // per arrival costs nothing when there is nothing to say.
            .task(id: readMarker) {
                guard !isAwayFromNewest else { return }
                await timeline.markRead()
            }
            .overlay(alignment: .bottomTrailing) {
                // A way back, and only when there is somewhere to go back
                // from. Scrolling through history with no route home is the
                // thing that makes a long room feel like a trap.
                //
                // The animation is scoped to this button. It used to hang off
                // the whole timeline (`.animation(value: isAwayFromNewest)`
                // after the overlay), and the flag flips again and again
                // during an ordinary scroll: every row the list was resizing
                // in that instant was animated with it, so rows slid over one
                // another and gaps opened and closed — the 2026-09-23 screen
                // recording, reproduced with `-fixtureTimeline`.
                ZStack {
                if isAwayFromNewest {
                    Button {
                        NotificationCenter.default.post(name: .scrollTimelineToNewest, object: nil)
                    } label: {
                        Image(systemName: "arrow.down")
                            .font(.system(size: 15, weight: .semibold))
                            .frame(width: 36, height: 36)
                            .background(.regularMaterial, in: Circle())
                            .overlay(Circle().stroke(Theme.border, lineWidth: 1))
                            // The disc stays 36; the target becomes 44.
                            //
                            // This is the route home from a long scrollback,
                            // and it was eight points under the minimum on
                            // every axis. `contentShape(Circle())` rather
                            // than a rectangle so the corners of the 44pt box
                            // do not steal taps meant for the timeline behind
                            // it.
                            .frame(width: 44, height: 44)
                            .contentShape(Circle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Jump to newest")
                    // Clear of the conversation rather than on top of it: at
                    // twelve points it sat over a reaction chip, which is a
                    // control covering another control.
                    //
                    // Eight and sixteen, not twelve and twenty: the 44pt
                    // target adds four points on every side, so the padding
                    // gives four back and the disc sits exactly where it did.
                    .padding(.trailing, 8)
                    .padding(.bottom, 16)
                    .transition(.scale.combined(with: .opacity))
                    .accessibilityLabel("Jump to newest")
                }
                }
                .animation(.snappy(duration: 0.2), value: isAwayFromNewest)
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                // Above the composer: the acknowledgement for an agent, the
                // typing line for people. Both are pills that float, like the
                // capsule under them, rather than a grey bar across the page.
                VStack(alignment: .leading, spacing: 4) {
                    if let acknowledgement {
                        AcknowledgementDock(acknowledgement: acknowledgement)
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                    }
                    if let line = session.typing.line {
                        TypingPill(line: line)
                            .transition(.opacity)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 12)
                .padding(.bottom, acknowledgement == nil && session.typing.line == nil ? 0 : 4)
                .animation(.snappy(duration: 0.22), value: acknowledgement)
                .animation(.snappy(duration: 0.22), value: session.typing.line)
            }
            .task(id: timeline.roomId) { await loadCast() }
            // Success when a turn finishes in the room on screen (M2) — only
            // on the change to finished, never when a room opens onto a turn
            // that had already ended.
            .sensoryFeedback(.success, trigger: session.live.finished) { was, now in !was && now }
    }

    /// What the dock says: "Sent to Atlas" → "Atlas is on it…" → nothing.
    /// The rules are `Acknowledgement`'s; this only gathers the facts.
    private var acknowledgement: Acknowledgement? {
        guard let roomId = timeline.roomId else { return nil }
        let known = cast.roomId == roomId
        return Acknowledgement.state(
            rows: timeline.items, addressee: known ? cast.addressee : nil,
            agentIds: known ? cast.agentIds : [],
            sentThisSession: session.typing.hasSent(in: roomId),
            typingAgents: session.typing.typingAgents,
            turnInProgress: session.live.inProgress)
    }

    private func loadCast() async {
        guard let roomId = timeline.roomId else { return }
        let row = session.rooms.row(for: roomId)
        await cast.load(
            roomId: roomId, headerName: row?.identity.name, isAgentRoom: row?.room.runtime != nil,
            people: { await session.people() },
            memberIds: { try? await session.roomInfo(roomId).members.map(\.userId) })
        // The same name the header uses, on the typing line and on the live
        // turn — one agent, one name (D11).
        session.typing.recognise(cast.cast, in: roomId)
        session.live.setAgentName(cast.counterpart?.name, for: roomId)
    }
}

/// "Sent to Atlas", then "Atlas is on it…" (A3).
///
/// It replaces "Atlas is typing…" for an agent: an agent that is typing is
/// working, and saying so is the more useful sentence.
private struct AcknowledgementDock: View {
    let acknowledgement: Acknowledgement

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.rendersStill) private var rendersStill

    private var isWorking: Bool {
        if case .onIt = acknowledgement { return true }
        return false
    }

    var body: some View {
        HStack(spacing: 7) {
            if isWorking {
                Image(systemName: "ellipsis")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(Theme.accent)
                    .symbolEffect(.variableColor.iterative, isActive: !reduceMotion && !rendersStill)
            } else {
                Image(systemName: "checkmark")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(Theme.ok)
            }
            Text(acknowledgement.text)
                .metaFace()
                .foregroundStyle(Theme.content)
                .lineLimit(1)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 7)
        .floatingCapsule()
        .contentTransition(.opacity)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.updatesFrequently)
    }
}

/// Someone — a person — typing, as a quiet pill.
private struct TypingPill: View {
    let line: String

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: "ellipsis.bubble")
                .font(.caption)
                .foregroundStyle(Theme.contentFaint)
            Text(line)
                .metaFace()
                .foregroundStyle(Theme.contentMuted)
                .lineLimit(1)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .background(Theme.surfaceSunken, in: Capsule())
        .accessibilityElement(children: .combine)
    }
}

#if DEBUG
// A room's history, in the collection view that actually draws it.
//
// `TimelineView` is thin by design — everything that used to be here is now
// `TimelineCollectionView` — so this preview is mostly a test of that seam:
// whether the read marker, the jump-to-newest affordance and the list agree
// about where the newest end is.
#Preview("A conversation") {
    let session = PreviewFixtures.session()
    return TimelineView(session: session, timeline: session.timeline)
        .previewChrome()
}

// A room with nothing in it yet.
#Preview("Empty room") {
    let session = PreviewFixtures.session(.empty)
    return TimelineView(session: session, timeline: session.timeline)
        .previewChrome()
}

// The dock's two sentences, and the typing pill beneath them.
#Preview("Acknowledgement") {
    PreviewGround {
        VStack(alignment: .leading, spacing: 8) {
            AcknowledgementDock(acknowledgement: .sent(to: "Atlas"))
            AcknowledgementDock(acknowledgement: .onIt(["Atlas"]))
            TypingPill(line: "Krishna is typing…")
        }
    }
    .environment(\.rendersStill, true)
}
#endif
