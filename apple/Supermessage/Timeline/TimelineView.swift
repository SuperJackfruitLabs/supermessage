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
                if isAwayFromNewest {
                    Button {
                        NotificationCenter.default.post(name: .scrollTimelineToNewest, object: nil)
                    } label: {
                        Image(systemName: "arrow.down")
                            .font(.system(size: 15, weight: .semibold))
                            .frame(width: 36, height: 36)
                            .background(.regularMaterial, in: Circle())
                            .overlay(Circle().stroke(.secondary.opacity(0.25), lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                    // Clear of the conversation rather than on top of it: at
                    // twelve points it sat over a reaction chip, which is a
                    // control covering another control.
                    .padding(.trailing, 12)
                    .padding(.bottom, 20)
                    .transition(.scale.combined(with: .opacity))
                    .accessibilityLabel("Jump to newest")
                }
            }
            .animation(.snappy(duration: 0.2), value: isAwayFromNewest)
            .safeAreaInset(edge: .bottom, spacing: 0) {
                if let line = session.typing.line {
                    Text(line)
                        .metaFace()
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 6)
                        .background(.bar)
                }
            }
    }
}
