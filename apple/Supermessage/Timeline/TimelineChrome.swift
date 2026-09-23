import SupermessageFFI
import SupermessageKit
import SwiftUI

// The pieces of the timeline that are about a row rather than inside it: the
// times a leftward swipe reveals, the spring a new message arrives on, the
// faces that say who has read what, the badge that says who is an agent, and
// the long-read view a report opens into.

// MARK: - Swipe to reveal times (T4)

/// How far the reader has pulled the timeline left to see its times.
///
/// One value for the whole list, not per row: the gesture moves every message
/// at once, the way Messages does, and a row that did not move with the
/// others would read as stuck. Observable so only the rows on screen — the
/// only ones that exist — redraw while a finger is down.
@MainActor
@Observable
final class TimeReveal {
    /// How far a row travels when the times are fully out. Wide enough for
    /// "12:59 PM" at the default size; a larger size simply clips the
    /// leading edge of the time, which is still legible.
    static let width: CGFloat = 68
    var offset: CGFloat = 0
}

/// A row that slides left under a reveal, with its time waiting at the
/// trailing edge.
///
/// `time` is `nil` for rows that have no time to show — a day divider, the
/// unread line — and those stay where they are: they are about the list, not
/// in it.
struct RevealsTime<Content: View>: View {
    let reveal: TimeReveal
    let time: String?
    @ViewBuilder var content: Content

    var body: some View {
        let offset = time == nil ? 0 : reveal.offset
        content
            .offset(x: -offset)
            .overlay(alignment: .trailing) {
                if let time, offset > 0 {
                    Text(time)
                        .metaFace()
                        .foregroundStyle(Theme.contentFaint)
                        .fixedSize()
                        .offset(x: TimeReveal.width - offset)
                        .opacity(Double(min(1, offset / TimeReveal.width)))
                        // Every message already says its time to VoiceOver
                        // through its header or its own-message footer; this
                        // is the sighted reader's shortcut to the same thing.
                        .accessibilityHidden(true)
                }
            }
    }
}

// MARK: - Arrival (M1)

/// A new message springs in from below, or simply appears under Reduce
/// Motion.
///
/// `active` only for a row that has just arrived into a room already on
/// screen — never for history, a room switch or a page of backfill, which the
/// list decides (see `Coordinator.animates`). Everything else renders settled
/// from its first frame.
struct SpringIn: ViewModifier {
    let active: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var settled = false

    func body(content: Content) -> some View {
        let hidden = active && !settled
        content
            .opacity(hidden ? 0 : 1)
            .offset(y: hidden && !reduceMotion ? 16 : 0)
            .scaleEffect(hidden && !reduceMotion ? 0.97 : 1, anchor: .bottom)
            .onAppear {
                guard active, !settled else { return }
                withAnimation(
                    reduceMotion
                        ? .easeOut(duration: 0.15)
                        : .spring(response: 0.38, dampingFraction: 0.78)
                ) { settled = true }
            }
    }
}

extension View {
    func springsIn(_ active: Bool) -> some View { modifier(SpringIn(active: active)) }
}

// MARK: - Faces

/// A face, where the person has one, or the initial the core chose for them.
///
/// Small by default — 18pt, the cap height of the name it sits beside —
/// because this is an aid to scanning, not a portrait. A face that competes
/// with the message makes the timeline a contact list.
struct SenderFace: View {
    let mxcUri: String?
    let initial: String
    let faces: AvatarCache
    var size: CGFloat = 18

    var body: some View {
        ZStack {
            if let mxcUri, let uri = faces.uri(for: mxcUri),
                let image = RoomRowView.image(from: uri)
            {
                image.resizable().scaledToFill()
            } else {
                Circle().fill(Theme.surfaceRaised)
                if let letter {
                    Text(letter)
                        .font(.system(size: size * 0.56, weight: .medium))
                        .foregroundStyle(Theme.contentMuted)
                } else {
                    // Someone this room has not shown a message from yet, so
                    // there is no initial to hand. A person, not a guess made
                    // from their user id.
                    Image(systemName: "person.fill")
                        .font(.system(size: size * 0.5))
                        .foregroundStyle(Theme.contentFaint)
                }
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
        .task(id: mxcUri) {
            guard let mxcUri else { return }
            await faces.load(mxcUri)
        }
    }

    /// What the disc shows.
    ///
    /// Still reduced to one character here rather than trusted whole: the
    /// core's `sender_initial` is already a single glyph or letter, but a
    /// glyph can be a multi-code-point cluster and this frame is small. Taking
    /// the first `Character` — a grapheme cluster in Swift, not a scalar —
    /// keeps a ZWJ sequence intact instead of rendering half of it.
    private var letter: String? {
        initial.first.map { String($0) }
    }
}

/// Someone whose read receipt points at a message, with whatever face the
/// room has already shown for them.
struct ReaderFace: Hashable {
    let userId: String
    let mxcUri: String?
    /// `nil` when they have sent nothing this device has seen, so the core has
    /// never handed over an initial for them.
    let initial: String?
}

/// "Read by …" as a few small faces rather than a sentence (D7, T3).
///
/// The sentence is still there — as the accessibility label, where a list of
/// names is what VoiceOver should say, and named by the core so it reads the
/// same everywhere.
struct ReaderStack: View {
    let readers: [ReaderFace]
    let faces: AvatarCache

    private static let shown = 3

    var body: some View {
        HStack(spacing: -4) {
            ForEach(readers.prefix(Self.shown), id: \.userId) { reader in
                SenderFace(
                    mxcUri: reader.mxcUri, initial: reader.initial ?? "", faces: faces, size: 14
                )
                // A ring of the page between overlapping faces, so three read
                // as three rather than one blot.
                .padding(1.5)
                .background(Theme.surface, in: Circle())
            }
            if readers.count > Self.shown {
                Text("+\(readers.count - Self.shown)")
                    .font(ThemeType.label)
                    .monospacedDigit()
                    .foregroundStyle(Theme.contentFaint)
                    .padding(.leading, 6)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Read by \(peopleLabel(userIds: readers.map(\.userId)))")
    }
}

// MARK: - Agents (T1)

/// The word that says a participant is an agent. Never a raw `@agent_…` id.
struct AgentLabel: View {
    var body: some View {
        Text("Agent")
            .font(ThemeType.label.weight(.semibold))
            .foregroundStyle(Theme.accent)
            .padding(.horizontal, 6)
            .padding(.vertical, 1)
            .background(Theme.accentSoft, in: Capsule())
            .fixedSize()
    }
}

/// An agent's report, set for reading: New York, a comfortable measure, and
/// the whole thing rather than the card's first screenful.
///
/// The one place the serif survives (`ThemeType.longread`), because this is
/// the one place a reader settles in to read rather than scan.
struct LongReadSheet: View {
    let title: String
    let blocks: [RichBlock]
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                RichTextView(blocks: blocks, longread: true)
                    .font(Theme.longread)
                    .foregroundStyle(Theme.content)
                    .lineSpacing(4)
                    .frame(maxWidth: 640, alignment: .leading)
                    .padding(.horizontal, 20)
                    .padding(.vertical, 16)
                    .frame(maxWidth: .infinity)
            }
            .background(Theme.surface)
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .presentationBackground(Theme.surface)
    }
}

// MARK: - Time

enum TimelineTime {
    /// A message's clock time, in the reader's locale.
    static func short(_ ms: UInt64) -> String {
        let date = Date(timeIntervalSince1970: Double(ms) / 1000)
        return date.formatted(date: .omitted, time: .shortened)
    }
}
