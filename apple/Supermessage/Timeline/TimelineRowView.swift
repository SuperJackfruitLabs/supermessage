import SupermessageFFI
import SupermessageKit
import SwiftUI

/// One timeline row, drawn from the decision the core made about it.
///
/// The switch is over `ItemView`, which is `core::item_view`'s classification
/// of a Matrix event — whether an `m.room.name` change is a visible row,
/// whether an undecryptable event says something specific. This view never
/// makes that call; it draws the answer.
/// Approval, refusal, attention, thanks — the working vocabulary of a room
/// whose other occupants are agents, and what these rooms are observably
/// using: ✅ and 👀 appear all over them, 🎉 and 🙏 do not.
///
/// **Four, not six.** Partly because these are the ones reached for, and
/// partly because iOS lays a `.small` menu group out four to a row: six meant
/// four in a strip and then two stranded on full-width rows of their own,
/// which read as a mistake because it was one.
///
/// Matches the desktop's `QUICK_REACTIONS` deliberately — two clients offering
/// different quick reactions is two different apps.
let quickReactions = ["✅", "👍", "❌", "👀"]

struct TimelineRowView: View {
    let row: TimelineRow
    /// Whether the row above already carries this sender's header.
    var continuesRun: Bool = false
    /// Whether this is the last row of its run — where your own messages
    /// carry their one timestamp.
    var endsRun: Bool = true
    /// Who to name, already chosen: the full attribution in a room where
    /// several agents speak, the bare name where one does. Chosen by the list,
    /// which can see every row; a single row cannot.
    var attribution: String = ""
    let media: MediaCache
    /// Senders' faces, keyed by `mxc:` URI.
    let faces: AvatarCache
    /// Whether the reply quote only repeats the row directly above, and so
    /// is left off (D6). Decided by the list, which can see that row.
    var hidesQuote: Bool = false
    /// Who has read up to this message, with the faces the room has shown
    /// for them. Resolved by the list, which has seen their messages.
    var readers: [ReaderFace] = []
    /// Briefly true after the reader jumped here from a reply quote (T5).
    var highlighted: Bool = false
    /// Start a reply to this row. `nil` in contexts with no composer.
    var onReply: (() -> Void)?
    /// Add or remove one of this account's reactions.
    var onReact: ((String) -> Void)?
    /// Jump to the message this one quotes. `nil` where there is nowhere to
    /// jump — a preview, a context-menu lift.
    var onQuoteTap: (() -> Void)?
    /// Answering a decision on a suite event. Separate from `onReact` because
    /// a reaction annotates an event and a decision resolves something on
    /// another plane — the row cannot supply the latter's subject, so the card
    /// hands it back up.
    var onDecide: ((GateAnswer) async -> Bool)?
    /// Drawn between the sender's name and what they said: the reasoning
    /// and steps that led to an agent's answer. See `WhatIDidFooter`.
    var prelude: AnyView?

    private var item: TimelineItemDto { row.item }

    /// The attribution the list chose, falling back to the full one so a row
    /// built without an opinion still names its sender.
    private var named: String { attribution.isEmpty ? row.senderName : attribution }

    /// The day a divider names — "Today", "Yesterday", "15 September" — in
    /// sentence case. See `TimelineDay`.
    static func day(_ ms: UInt64?) -> String {
        TimelineDay.label(ms)
    }

    var body: some View {
        content
            // A wash behind the row the reader just jumped to from a quote,
            // which fades on its own: it answers "which one?" and then gets
            // out of the way.
            .background {
                RoundedRectangle(cornerRadius: 12)
                    .fill(Theme.accentSoft)
                    .padding(.horizontal, -8)
                    .opacity(highlighted ? 1 : 0)
                    .animation(.easeOut(duration: highlighted ? 0.15 : 0.8), value: highlighted)
            }
    }

    @ViewBuilder private var content: some View {
        switch row.view {
        case let .bubble(muted, blocks):
            MessageBlock(
                row: row, named: named, muted: muted, blocks: blocks,
                continuesRun: continuesRun, endsRun: endsRun, hidesQuote: hidesQuote,
                readers: readers, faces: faces, onReact: onReact, onQuoteTap: onQuoteTap,
                prelude: prelude
            )

        case .emote:
            // Centred italic: an emote is prose *about* its sender rather
            // than something they said.
            Text("\(named) \(item.body ?? "")")
                .font(Theme.body.italic())
                .foregroundStyle(Theme.contentMuted)
                .frame(maxWidth: .infinity, alignment: .center)
                .padding(.vertical, 4)

        // `kind` ignored here, and that is the correct reading of it: this
        // host renders the English the core already composed. The field
        // exists for a host that wants the line in another language — see
        // docs/i18n.md §2.2 — and this one does not yet.
        case let .system(_, text):
            SystemLine(text: text)

        case .dateDivider:
            // A hairline with the date on it. Formatted here rather than in
            // the core because it reads a clock and a locale — the core sends
            // the timestamp, which is all it can honestly know.
            HStack(spacing: 10) {
                VStack { Divider().overlay(Theme.border) }
                Text(Self.day(item.timestampMs))
                    .font(ThemeType.meta.weight(.medium))
                    .foregroundStyle(Theme.contentMuted)
                    .fixedSize()
                VStack { Divider().overlay(Theme.border) }
            }
            .padding(.top, 14)
            .padding(.bottom, 6)
            .accessibilityElement(children: .combine)

        case .unreadMarker:
            // Labelled, briefly. It was a bare accent line on the theory that
            // the line says it; read cold it said nothing, and looked like a
            // stray divider between a message and its reply (2026-09-24).
            // One word at the trailing end, as Slack does — enough to name
            // the line without a banner across the conversation.
            HStack(spacing: 8) {
                VStack { Divider().overlay(Theme.accent) }
                Text("New")
                    .metaFace()
                    .foregroundStyle(Theme.accent)
                    .fixedSize()
            }
            .padding(.vertical, 10)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("New messages")

        // A message this device holds no key for is still a *message*: it was
        // sent by someone, at a time, into this conversation. Drawn as a
        // centred grey line it read like "Krishna joined the room" — an event
        // about the room rather than a gap in it — and with encryption on by
        // default it is the placeholder a reader meets most.
        case let .placeholder(.unableToDecrypt, text):
            UndecryptableRow(
                row: row, named: named, text: text, continuesRun: continuesRun, faces: faces)

        // `kind` ignored, like `.system` above and for the same reason.
        case let .placeholder(_, text):
            SystemLine(text: text)

        case let .image(alt, width, height):
            ImageRow(
                row: row, named: named, alt: alt, width: width, height: height, media: media)

        case let .mediaFile(label, filename, size, _):
            MediaFileRow(label: label, filename: filename, size: size)

        case let .customEvent(view, label, eventType):
            CustomEventCard(
                view: view, label: label, eventType: eventType, senderName: named,
                onDecide: onDecide)

        case .none:
            EmptyView()
        }
    }
}

/// "edited", beside a message the SDK has folded an `m.replace` into.
///
/// iOS showed nothing at all here while the desktop always has, so an agent
/// revising a message it had already sent was invisible on a phone.
private struct EditedMark: View {
    var body: some View {
        Text("edited")
            .metaFace()
            .foregroundStyle(Theme.contentFaint)
            .accessibilityLabel("Edited")
    }
}

/// A message, peer or own.
///
/// **A peer's message is a card** (T1): a soft raised surface at a readable
/// measure, under a header that names the sender, shows their face, and says
/// **Agent** when they are one. It used to be bare prose on the page, which
/// made an agent's report and a colleague's one-liner the same object, and
/// said nothing about which of them was a delegated participant.
///
/// **Your own is a tinted bubble** on the trailing side, as before.
///
/// Reactions hang off the bubble's bottom corner, overlapping it slightly, so
/// they read as belonging to *this* message rather than as a row of controls
/// under the timestamp (D8).
private struct MessageBlock: View {
    let row: TimelineRow
    /// Chosen by the list — see `TimelineRowView.attribution`.
    let named: String
    let muted: Bool
    let blocks: [RichBlock]
    let continuesRun: Bool
    let endsRun: Bool
    let hidesQuote: Bool
    let readers: [ReaderFace]
    let faces: AvatarCache
    var onReact: ((String) -> Void)?
    var onQuoteTap: (() -> Void)?
    var prelude: AnyView?

    @State private var reading = false

    private var isOwn: Bool { row.item.isOwn }
    private var sendState: SendState { SendState(row.item.sendState) }
    private var isLong: Bool { TimelineGrouping.isLongRead(row) }

    var body: some View {
        VStack(alignment: isOwn ? .trailing : .leading, spacing: 4) {
            if !isOwn && !continuesRun {
                header
            }

            // An agent reasons, then answers, so the record of its reasoning
            // reads first — under its name, above what it said — as it does
            // in ChatGPT, Claude and assistant-ui. It used to be a footnote
            // under the answer, which put the conclusion before the working.
            if let prelude {
                prelude
            }

            // The bubble, with its reactions pulled up over its bottom edge.
            // Negative spacing rather than an overlay, so the chips still take
            // their height in the row and never overlap the next message.
            VStack(alignment: isOwn ? .trailing : .leading, spacing: -8) {
                bubble
                if !row.item.reactions.isEmpty {
                    ReactionRow(reactions: row.item.reactions, onReact: onReact)
                        .padding(.horizontal, 10)
                }
            }

            // A continued row has no header to carry the mark, so it goes
            // under the text instead. An agent quietly rewriting what it said
            // is exactly what a reader must be able to see.
            if !isOwn && continuesRun && row.item.edited {
                EditedMark()
            }

            // Your own side of the conversation, which carried no time and no
            // send state at all — three identical messages were
            // indistinguishable, and a message that never left the phone
            // looked exactly like one that landed.
            //
            // One time per run, not one per bubble: ten messages sent in a
            // minute carried ten identical timestamps. The last of the run
            // keeps it; a send state or an edit always speaks.
            //
            // Receipts sit on the same line as a few small faces (D7). Only
            // under your own messages, and only where a receipt actually
            // points: a receipt names the latest event a member has read, so
            // this lands on the newest thing they have seen and stays off
            // everything older.
            if isOwn, endsRun || sendState.isWorthShowing || row.item.edited || !readers.isEmpty {
                HStack(spacing: 5) {
                    if let label = sendState.label {
                        if sendState == .failed {
                            Image(systemName: "exclamationmark.circle")
                        }
                        Text(label)
                    }
                    if row.item.edited {
                        Text("edited")
                    }
                    if endsRun || sendState.isWorthShowing, let timestamp = row.item.timestampMs {
                        Text(TimelineTime.short(timestamp))
                    }
                    if !readers.isEmpty {
                        ReaderStack(readers: readers, faces: faces)
                    }
                }
                .metaFace()
                // Failure is the one state that may speak up. Everything else
                // here is a quiet timestamp.
                .foregroundStyle(sendState == .failed ? AnyShapeStyle(Theme.danger) : AnyShapeStyle(Theme.contentFaint))
            }
        }
        .frame(maxWidth: .infinity, alignment: isOwn ? .trailing : .leading)
        // The rhythm (T2): two points inside a run, twelve between turns. The
        // gap is what says "same turn" once the header is gone.
        .padding(.top, continuesRun ? 2 : 12)
        .sheet(isPresented: $reading) {
            LongReadSheet(title: named, blocks: blocks)
        }
    }

    private var header: some View {
        HStack(spacing: 6) {
            // A face, where the sender has one. In a room with four agents,
            // four near-identical grey headers made a reader scanning back
            // for who said what read rather than glance.
            //
            // `row.senderInitial`, not `named.first`. For an agent the first
            // character of the attribution is the glyph, so taking it here
            // drew the symbol in the disc and left it in the name beside it:
            // `✳ ✳ Atlas — Platform`, under every message. The core hands over
            // the glyph and a name without it.
            SenderFace(
                mxcUri: row.item.senderAvatar, initial: row.senderInitial, faces: faces, size: 22)
            Text(named).nameFace().lineLimit(1)
            if TimelineGrouping.isAgent(row) {
                AgentLabel()
            }
            if let timestamp = row.item.timestampMs {
                Text(TimelineTime.short(timestamp)).metaFace().foregroundStyle(Theme.contentFaint)
            }
            if row.item.edited {
                EditedMark()
            }
        }
        .accessibilityElement(children: .combine)
    }

    private var bubble: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let quote = row.replyQuote, !hidesQuote {
                ReplyQuote(quote: quote, onTap: onQuoteTap)
            }

            if isLong {
                // The card shows the opening of a report and fades; the whole
                // of it is one tap away, set for reading. A 4,000-word report
                // drawn in full in the timeline is a room nobody can scroll
                // past.
                //
                // `fixedSize` first: the text lays out at its full height and
                // the frame then *cuts* it. Without it the 320pt frame was a
                // height offer, and every paragraph shrank to fit it — each
                // one truncated to a line or two with "…", which read as a
                // broken message rather than the opening of a long one.
                text
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxHeight: 320, alignment: .top)
                    .clipped()
                    .mask {
                        LinearGradient(
                            stops: [
                                .init(color: .black, location: 0),
                                .init(color: .black, location: 0.72),
                                .init(color: .clear, location: 1),
                            ], startPoint: .top, endPoint: .bottom)
                    }
                Button {
                    reading = true
                } label: {
                    Label("Read", systemImage: "book")
                        .font(ThemeType.ui.weight(.semibold))
                }
                .buttonStyle(.borderless)
                .tint(Theme.accent)
                .accessibilityHint("Opens the whole message in a reading view")
            } else {
                text
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(
            isOwn ? AnyShapeStyle(Theme.accent.opacity(0.13)) : AnyShapeStyle(Theme.surfaceRaised),
            in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .frame(maxWidth: isOwn ? MessageMeasure.own : MessageMeasure.card, alignment: isOwn ? .trailing : .leading)
    }

    private var text: some View {
        RichTextView(blocks: blocks)
            // Own messages arrive from the core verbatim — never parsed as
            // markdown, because "you type, they write": a stray asterisk
            // must not change what you appear to have said.
            .font(isOwn ? Theme.own : Theme.body)
            .foregroundStyle(muted && !isOwn ? AnyShapeStyle(Theme.contentMuted) : AnyShapeStyle(Theme.content))
    }
}

/// The quoted parent of a reply, as the core resolved it.
///
/// Tappable when the list can find the parent (T5): the timeline scrolls to it
/// and lights it briefly. When the parent is not loaded the tap does nothing —
/// there is nowhere to go, and a jump that lands on the wrong message is worse
/// than one that does not happen.
private struct ReplyQuote: View {
    let quote: ReplyQuoteView
    var onTap: (() -> Void)?

    var body: some View {
        // The rule is an overlay, not a sibling in the HStack. `Rectangle` is a
        // `Shape` and so is infinitely flexible on BOTH axes; pinning only its
        // width leaves it free to take every point of height the row is
        // offered. Inside a self-sizing `UIHostingConfiguration` cell that is
        // the whole proposed height, so a one-line quote rendered several lines
        // tall and the body below it was squeezed until it truncated with an
        // ellipsis — a message arriving complete and displaying cut off.
        // An overlay is measured against its content, so the rule can only ever
        // be exactly as tall as the quote it marks.
        Group {
            switch quote {
            case .unavailable:
                // The core folds Unavailable/Pending/Error together, so this is
                // the one shape to handle — and it renders as a sentence rather
                // than an empty quote or a spinner that will never resolve.
                Text("Original message unavailable")
                    .metaFace()
                    .foregroundStyle(Theme.contentFaint)
            case let .available(sender, excerpt, label):
                VStack(alignment: .leading, spacing: 1) {
                    Text(sender).metaFace().foregroundStyle(Theme.contentMuted)
                    if let excerpt {
                        Text(excerpt).font(.footnote).lineLimit(2)
                            .foregroundStyle(Theme.contentMuted)
                    } else if let label {
                        // A ready parent with nothing to quote — redacted, a
                        // sticker, undecryptable. The label says which, in the
                        // same words a top-level item of that kind would use.
                        Text(label).font(.footnote).foregroundStyle(Theme.contentFaint)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.leading, 10)
        .overlay(alignment: .leading) {
            Rectangle().fill(Theme.accent.opacity(0.6)).frame(width: 2)
        }
        .padding(.vertical, 2)
        .contentShape(Rectangle())
        .onTapGesture { onTap?() }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(onTap == nil ? [] : [.isButton])
        .accessibilityHint(onTap == nil ? "" : "Shows the original message")
    }
}

private struct ReactionRow: View {
    let reactions: [ReactionDto]
    var onReact: ((String) -> Void)?

    /// Which chip the reader is asking about. A chip says how many; only
    /// asking says who, because a row of names is wider than the message it
    /// hangs under.
    @State private var asking: ReactionQuery?
    /// Bumped on every tap, so the selection haptic fires once per toggle
    /// (M2) rather than on every redraw.
    @State private var taps = 0

    var body: some View {
        HStack(spacing: 4) {
            ForEach(reactions, id: \.key) { reaction in
                Button {
                    taps += 1
                    onReact?(reaction.key)
                } label: {
                    chip(reaction)
                }
                .buttonStyle(.plain)
                .disabled(onReact == nil)
                // `key`, not `displayKey`: the wire value is what the
                // homeserver matches against what everyone else sent, and
                // `displayKey` is bounded for showing. Reacting with the
                // display form would land a *different* reaction beside the
                // one the reader meant to join.
                .accessibilityLabel(
                    "\(reaction.displayKey), \(reaction.count)"
                        + (reaction.byMe ? ", including yours" : ""))
                .accessibilityAddTraits(reaction.byMe ? [.isSelected] : [])
                .accessibilityHint(who(reaction))
                .onLongPressGesture { asking = ReactionQuery(reaction: reaction) }
            }
        }
        .sensoryFeedback(.selection, trigger: taps)
        .popover(item: $asking) { query in
            VStack(alignment: .leading, spacing: 4) {
                Text(query.reaction.displayKey).font(.title3)
                Text(who(query.reaction)).metaFace().foregroundStyle(Theme.contentMuted)
            }
            .padding(12)
            // Without this a popover on iPhone arrives as a half-height
            // sheet — far too much furniture for one line of names.
            .presentationCompactAdaptation(.popover)
        }
    }

    /// Who reacted, named by the core so both hosts say it the same way.
    private func who(_ reaction: ReactionDto) -> String {
        peopleLabel(userIds: reaction.senders)
    }

    @ViewBuilder private func chip(_ reaction: ReactionDto) -> some View {
        HStack(spacing: 3) {
            // `displayKey`, never `key`: they usually look alike, but `key` is
            // wire data compared byte-for-byte against what other clients
            // sent, and this one is bounded for display.
            Text(reaction.displayKey).font(.caption)
            // One reaction is already fully described by the emoji; printing
            // "1" beside it is a count of nothing anyone wondered about.
            if reaction.count > 1 {
                Text("\(reaction.count)")
                    .metaFace()
                    .monospacedDigit()
                    .foregroundStyle(reaction.byMe ? Theme.accent : Theme.contentMuted)
            }
        }
        .padding(.horizontal, 6)
        .padding(.vertical, 2)
        .contentShape(Capsule())
        // A fill rather than an outline: a dozen stroked capsules under a
        // message read as a toolbar. Only the reader's own reaction is drawn
        // with an edge, because that is the one distinction a chip must make.
        .background(
            reaction.byMe ? Theme.accentSoft : Theme.surfaceRaised,
            in: Capsule())
        .overlay(
            Capsule().strokeBorder(
                reaction.byMe ? Theme.accent.opacity(0.55) : .clear, lineWidth: 1))
        // A ring of the page around each chip, so where it overlaps the
        // bubble's corner the two stay separate shapes.
        .padding(2)
        .background(Theme.surface, in: Capsule())
    }
}

/// A chip the reader has asked about, identified by its wire key.
private struct ReactionQuery: Identifiable {
    let reaction: ReactionDto
    var id: String { reaction.key }
}

/// A message that arrived encrypted for keys this device does not have.
private struct UndecryptableRow: View {
    let row: TimelineRow
    let named: String
    let text: String
    let continuesRun: Bool
    let faces: AvatarCache

    var body: some View {
        VStack(alignment: row.item.isOwn ? .trailing : .leading, spacing: 4) {
            if !row.item.isOwn && !continuesRun {
                HStack(spacing: 6) {
                    SenderFace(
                        mxcUri: row.item.senderAvatar, initial: row.senderInitial, faces: faces,
                        size: 22)
                    Text(named).nameFace()
                    if let timestamp = row.item.timestampMs {
                        Text(TimelineTime.short(timestamp)).metaFace()
                            .foregroundStyle(Theme.contentFaint)
                    }
                }
            }
            Label {
                VStack(alignment: .leading, spacing: 2) {
                    Text(text)
                    Text("Encryption recovery in your account can restore older messages.")
                        .font(.footnote)
                        .foregroundStyle(Theme.contentFaint)
                }
            } icon: {
                Image(systemName: "lock.fill")
            }
            .font(.subheadline.italic())
            .foregroundStyle(Theme.contentMuted)
            .padding(10)
            .background(Theme.surfaceRaised, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .frame(maxWidth: .infinity, alignment: row.item.isOwn ? .trailing : .leading)
        .padding(.top, continuesRun ? 2 : 12)
        .accessibilityElement(children: .combine)
    }
}

/// A quiet line about the room rather than in it — a membership change, a
/// placeholder, a collapsed run.
///
/// **One line, four points above and below** (D5, T2). These were drawn
/// roughly a hundred and thirty points apart, which made a room's membership
/// churn the tallest thing in it. At the accessibility sizes the line may
/// wrap rather than truncate, because a sentence cut in half says nothing.
struct SystemLine: View {
    let text: String
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        Text(text)
            .metaFace()
            .foregroundStyle(Theme.contentFaint)
            .lineLimit(typeSize.isAccessibilitySize ? nil : 1)
            .truncationMode(.middle)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity, alignment: .center)
            .padding(.vertical, 4)
    }
}

private struct ImageRow: View {
    let row: TimelineRow
    /// Chosen by the list — see `TimelineRowView.attribution`.
    let named: String
    let alt: String
    let width: UInt64?
    let height: UInt64?
    let media: MediaCache

    /// The picture, once it arrives. `nil` while loading *and* when there is
    /// nothing to show — `media.hasFailed` is what separates those.
    private var image: UIImage? {
        guard let eventId = row.item.eventId else { return nil }
        return media.image(for: eventId)
    }

    private var failed: Bool {
        // A local echo has no event to fetch against, which is not a failure —
        // it is a picture that has not landed on the server yet.
        guard let eventId = row.item.eventId else { return false }
        return media.hasFailed(eventId)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(named).nameFace()
            Group {
                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFit()
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                } else {
                    RoundedRectangle(cornerRadius: 8)
                        .fill(Theme.surfaceRaised)
                        // The box is reserved from the sender's reported
                        // dimensions *before* any bytes are asked for, so the
                        // list does not reflow when they land.
                        .aspectRatio(aspect, contentMode: .fit)
                        .overlay {
                            // Never a broken-image glyph. A picture that is
                            // still arriving and one that cannot be shown are
                            // different states and read differently.
                            if failed {
                                Image(systemName: "photo").foregroundStyle(Theme.contentMuted)
                            } else {
                                ProgressView()
                            }
                        }
                }
            }
            .frame(maxWidth: 320)
            .accessibilityLabel(alt)
        }
        .padding(.vertical, 6)
    }

    private var aspect: CGFloat {
        guard let width, let height, height > 0 else { return 4.0 / 3.0 }
        return CGFloat(width) / CGFloat(height)
    }
}

private struct MediaFileRow: View {
    let label: MediaFileLabel
    let filename: String
    let size: UInt64?

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: icon)
            VStack(alignment: .leading, spacing: 1) {
                Text(filename).font(.subheadline).lineLimit(1)
                Text(caption).metaFace().foregroundStyle(Theme.contentMuted)
            }
        }
        .padding(10)
        .background(Theme.surfaceRaised, in: RoundedRectangle(cornerRadius: 8))
        .padding(.vertical, 6)
    }

    private var icon: String {
        switch label {
        case .file: return "doc"
        case .audio: return "waveform"
        case .video: return "film"
        }
    }

    /// `label` is display text the core chose — printed, not switched on for
    /// wording.
    private var caption: String {
        let kind = String(describing: label).capitalized
        guard let size else { return kind }
        let formatted = ByteCountFormatter.string(fromByteCount: Int64(size), countStyle: .file)
        return "\(kind) · \(formatted)"
    }
}

#if DEBUG
// The whole timeline vocabulary in one frame.
//
// Previewed as a list rather than one row at a time, because almost every
// decision this view makes is about its *neighbours*: the run grouping, the
// day divider's separation, whether a system line reads as belonging to the
// message above it. A row alone cannot show any of that.
#Preview("Everything") {
    let media = PreviewFixtures.mediaCache()
    let faces = PreviewFixtures.faceCache()
    return ScrollView {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(PreviewFixtures.history.enumerated()), id: \.offset) { _, row in
                TimelineRowView(row: row, media: media, faces: faces)
            }
        }
        .padding(.horizontal, 12)
    }
    .background(Theme.surface)
}

// A second message from the same sender, moments later.
//
// `continuesRun` defaults to `false`, so every other preview here shows the
// attributed form. This is the pair: the first row names its sender, the
// second does not, and the vertical gap between them is what says they are
// one turn rather than two.
#Preview("Sender run") {
    let media = PreviewFixtures.mediaCache()
    let faces = PreviewFixtures.faceCache()
    return VStack(alignment: .leading, spacing: 0) {
        TimelineRowView(
            row: PreviewFixtures.message, attribution: "Atlas — Platform", media: media,
            faces: faces)
        TimelineRowView(
            row: PreviewFixtures.noticed, continuesRun: true, media: media, faces: faces)
    }
    .padding(.horizontal, 12)
    .frame(maxWidth: .infinity, alignment: .leading)
    .background(Theme.surface)
}

// Own messages, and the two send states that are not "sent".
//
// A failed send is the one row in the timeline that is asking the reader for
// something, and it may not use amber to do it — amber means a pending
// decision. `danger` is the role that belongs here.
#Preview("Sending and failed") {
    let media = PreviewFixtures.mediaCache()
    let faces = PreviewFixtures.faceCache()
    return VStack(alignment: .leading, spacing: 0) {
        TimelineRowView(row: PreviewFixtures.ownSending, media: media, faces: faces)
        TimelineRowView(row: PreviewFixtures.ownFailed, media: media, faces: faces)
    }
    .padding(.horizontal, 12)
    .frame(maxWidth: .infinity, alignment: .leading)
    .background(Theme.surface)
}

// A reply whose parent is there, and one whose parent is gone.
//
// `ReplyQuoteView.unavailable` is a real and common state — the parent was
// redacted, or has not been paginated in — and the quote has to say so
// without looking like a failure of this app.
#Preview("Replies") {
    let media = PreviewFixtures.mediaCache()
    let faces = PreviewFixtures.faceCache()
    return VStack(alignment: .leading, spacing: 0) {
        TimelineRowView(row: PreviewFixtures.reply, media: media, faces: faces)
        TimelineRowView(row: PreviewFixtures.replyToNothing, media: media, faces: faces)
    }
    .padding(.horizontal, 12)
    .frame(maxWidth: .infinity, alignment: .leading)
    .background(Theme.surface)
}

// Reactions, including a key that is not an emoji.
//
// `ReactionDto.key` is arbitrary sender-controlled text. A row previewed with
// nothing but emoji would never show what a word-length key does to the
// chip row's wrapping.
#Preview("Reactions") {
    PreviewGround(width: 360) {
        TimelineRowView(
            row: PreviewFixtures.withReactions, media: PreviewFixtures.mediaCache(),
            faces: PreviewFixtures.faceCache())
    }
}

// Media, with no bytes behind it.
//
// The stub's `mediaFetch` returns `nil`, which is deliberately the
// interesting case: this is the frame a reader sees before an image arrives,
// or permanently when it never does. `ItemView.image` carries the sender's
// own pixel dimensions so the box can be reserved before the bytes are
// requested, and whether that reservation is honoured is exactly what this
// preview shows.
#Preview("Media without bytes") {
    // Failed up front, rather than a cache that will get there on its own.
    //
    // Five renders of this frame disagreed by up to 2,311 pixels in a 58×45
    // box — the placeholder flipping between the spinner and the
    // permanent-absence glyph, because `hasFailed` becomes true only once the
    // fetch this preview can never satisfy has given up. Both states are
    // real; which one the shutter caught was a race.
    //
    // "Without bytes" is the settled one, so the fixture starts there.
    // `markFailed` also stops `image(for:)` asking again, so there is no
    // outstanding Task left to land mid-capture.
    let media = PreviewFixtures.mediaCache(
        failed: [PreviewFixtures.image, PreviewFixtures.attachment]
            .compactMap(\.item.eventId))
    let faces = PreviewFixtures.faceCache()
    return VStack(alignment: .leading, spacing: 0) {
        TimelineRowView(row: PreviewFixtures.image, media: media, faces: faces)
        TimelineRowView(row: PreviewFixtures.attachment, media: media, faces: faces)
    }
    .padding(.horizontal, 12)
    .frame(maxWidth: .infinity, alignment: .leading)
    .background(Theme.surface)
}

// The rows that are not bubbles at all: a day divider, a membership line, and
// a type this build cannot render.
//
// `ItemView.dateDivider` exists as a variant because it used to be a contract
// in a comment — and iOS was the host that missed it and put "Unsupported
// event (dateDivider)" in the middle of a conversation. This preview is where
// that regression would be visible without a device.
#Preview("Not a message") {
    let media = PreviewFixtures.mediaCache()
    let faces = PreviewFixtures.faceCache()
    return VStack(alignment: .leading, spacing: 0) {
        TimelineRowView(row: PreviewFixtures.dayDivider, media: media, faces: faces)
        TimelineRowView(row: PreviewFixtures.membership, media: media, faces: faces)
        TimelineRowView(row: PreviewFixtures.encrypted, media: media, faces: faces)
    }
    .padding(.horizontal, 12)
    .frame(maxWidth: .infinity, alignment: .leading)
    .background(Theme.surface)
}

// A 104-character run with no break in it, at a phone's width.
#Preview("Unbreakable body") {
    PreviewGround(width: 360) {
        TimelineRowView(
            row: PreviewFixtures.unbreakable, media: PreviewFixtures.mediaCache(),
            faces: PreviewFixtures.faceCache())
    }
}

// A card inside a timeline row, which is how a reader actually meets one.
//
// The card has its own previews in `DecisionCard.swift`; this one is about
// the seam around it — whether the row's horizontal inset and the card's own
// frame agree at a phone's width.
#Preview("Card in a row") {
    PreviewGround(width: 360) {
        TimelineRowView(
            row: PreviewFixtures.card, media: PreviewFixtures.mediaCache(),
            faces: PreviewFixtures.faceCache(), onDecide: { _ in true })
    }
}

// An agent's card, the same agent continuing, and a colleague (T1, T2).
//
// The badge is on the agent and not on Krishna; the second agent message has
// no header and sits two points under the first; Krishna's turn opens twelve
// points further down.
#Preview("Agent cards") {
    let media = PreviewFixtures.mediaCache()
    let faces = PreviewFixtures.faceCache()
    return PreviewGround(width: 390) {
        VStack(alignment: .leading, spacing: 0) {
            TimelineRowView(
                row: PreviewFixtures.agentMessage, attribution: "Atlas", media: media, faces: faces)
            TimelineRowView(
                row: PreviewFixtures.agentFollowUp, continuesRun: true, attribution: "Atlas",
                media: media, faces: faces)
            TimelineRowView(row: PreviewFixtures.colleagueMessage, media: media, faces: faces)
        }
    }
}

// A report past six hundred characters: the card's opening fades out and a
// Read button opens the whole of it in the serif long-read view.
#Preview("Long report") {
    PreviewGround(width: 390) {
        TimelineRowView(
            row: PreviewFixtures.agentReport, attribution: "Atlas",
            media: PreviewFixtures.mediaCache(), faces: PreviewFixtures.faceCache())
    }
}

// Receipts as faces beside the time (D7), not "Read by …" sentences, and own
// reactions hung on the bubble's corner (D8).
#Preview("Receipts and corner reactions") {
    let media = PreviewFixtures.mediaCache()
    let faces = PreviewFixtures.faceCache()
    return PreviewGround(width: 390) {
        VStack(alignment: .leading, spacing: 0) {
            TimelineRowView(row: PreviewFixtures.ownWithReactions, media: media, faces: faces)
            TimelineRowView(
                row: PreviewFixtures.ownRead, media: media, faces: faces,
                readers: PreviewFixtures.readers)
            TimelineRowView(row: PreviewFixtures.withReactions, media: media, faces: faces)
        }
    }
}

// A reply directly under its parent drops the quote (D6); the same reply with
// something between them keeps it. And the row a quote tap lands on, lit.
#Preview("Reply beside its parent") {
    let media = PreviewFixtures.mediaCache()
    let faces = PreviewFixtures.faceCache()
    return PreviewGround(width: 390) {
        VStack(alignment: .leading, spacing: 0) {
            TimelineRowView(
                row: PreviewFixtures.replyParent, media: media, faces: faces, highlighted: true)
            TimelineRowView(row: PreviewFixtures.reply, media: media, faces: faces, hidesQuote: true)
            TimelineRowView(row: PreviewFixtures.noticed, media: media, faces: faces)
            TimelineRowView(row: PreviewFixtures.reply, media: media, faces: faces, onQuoteTap: {})
        }
    }
}

// Membership churn collapsed to one line each, four points apart (D5), and a
// day divider in sentence case (D7).
#Preview("System lines") {
    PreviewGround(width: 390) {
        VStack(spacing: 0) {
            TimelineRowView(
                row: PreviewFixtures.dayDivider, media: PreviewFixtures.mediaCache(),
                faces: PreviewFixtures.faceCache())
            ForEach(PreviewFixtures.churnLines, id: \.self) { line in
                SystemLine(text: line)
            }
            TimelineRowView(
                row: PreviewFixtures.agentMessage, attribution: "Atlas",
                media: PreviewFixtures.mediaCache(), faces: PreviewFixtures.faceCache())
        }
    }
}

// The rows mid-swipe, times out at the trailing edge (T4).
#Preview("Times revealed") {
    let reveal = TimeReveal()
    reveal.offset = TimeReveal.width
    let media = PreviewFixtures.mediaCache()
    let faces = PreviewFixtures.faceCache()
    return PreviewGround(width: 390) {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(
                [PreviewFixtures.agentMessage, PreviewFixtures.ownRead, PreviewFixtures.colleagueMessage],
                id: \.item.id
            ) { row in
                RevealsTime(reveal: reveal, time: row.item.timestampMs.map(TimelineTime.short)) {
                    TimelineRowView(row: row, attribution: row.senderShort, media: media, faces: faces)
                }
            }
        }
    }
}

// The long-read sheet on its own: New York, a reading measure.
#Preview("Long read") {
    LongReadSheet(title: "Atlas", blocks: PreviewFixtures.reportBlocks)
        .previewChrome()
}
#endif
