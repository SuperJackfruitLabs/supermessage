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
    /// Who to name, already chosen: the full attribution in a room where
    /// several agents speak, the bare name where one does. Chosen by the list,
    /// which can see every row; a single row cannot.
    var attribution: String = ""
    let media: MediaCache
    /// Start a reply to this row. `nil` in contexts with no composer.
    var onReply: (() -> Void)?
    /// Add or remove one of this account's reactions.
    var onReact: ((String) -> Void)?

    private var item: TimelineItemDto { row.item }

    /// The attribution the list chose, falling back to the full one so a row
    /// built without an opinion still names its sender.
    private var named: String { attribution.isEmpty ? row.senderName : attribution }

    /// The day a divider names — "Today" and "Yesterday" where those apply,
    /// because a date is harder to place than a word.
    static func day(_ ms: UInt64?) -> String {
        guard let ms else { return "" }
        let date = Date(timeIntervalSince1970: Double(ms) / 1000)
        let formatter = DateFormatter()
        formatter.doesRelativeDateFormatting = true
        formatter.dateStyle = .medium
        formatter.timeStyle = .none
        return formatter.string(from: date)
    }

    var body: some View {
        switch row.view {
        case let .bubble(muted, blocks):
            MessageBlock(
                row: row, named: named, muted: muted, blocks: blocks,
                continuesRun: continuesRun, onReact: onReact
            )

        case .emote:
            // Centred serif italic: an emote is prose *about* its sender
            // rather than something they said.
            Text("\(named) \(item.body ?? "")")
                .font(Theme.body.italic())
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .center)
                .padding(.vertical, 6)

        case let .system(text):
            SystemLine(text: text)

        case .dateDivider:
            // A hairline with the date on it. Formatted here rather than in
            // the core because it reads a clock and a locale — the core sends
            // the timestamp, which is all it can honestly know.
            HStack(spacing: 10) {
                VStack { Divider() }
                Text(Self.day(item.timestampMs))
                    .metaFace()
                    .textCase(.uppercase)
                    .foregroundStyle(.secondary)
                    .fixedSize()
                VStack { Divider() }
            }
            .padding(.vertical, 10)

        case .unreadMarker:
            // No label. The divider says it, and a caption repeated at every
            // scroll position would be chrome pretending to be content.
            Divider()
                .overlay(Theme.accent)
                .padding(.vertical, 10)

        case let .placeholder(text):
            SystemLine(text: text)

        case let .image(alt, width, height):
            ImageRow(
                row: row, named: named, alt: alt, width: width, height: height, media: media)

        case let .mediaFile(label, filename, size, _):
            MediaFileRow(label: label, filename: filename, size: size)

        case let .customEvent(view, eventType):
            CustomEventCard(view: view, eventType: eventType, senderName: named)

        case .none:
            EmptyView()
        }
    }
}

/// A message, peer or own.
private struct MessageBlock: View {
    let row: TimelineRow
    /// Chosen by the list — see `TimelineRowView.attribution`.
    let named: String
    let muted: Bool
    let blocks: [RichBlock]
    let continuesRun: Bool
    var onReact: ((String) -> Void)?

    private var isOwn: Bool { row.item.isOwn }
    private var sendState: SendState { SendState(row.item.sendState) }

    var body: some View {
        VStack(alignment: isOwn ? .trailing : .leading, spacing: 4) {
            if !isOwn && !continuesRun {
                HStack(spacing: 6) {
                    Text(named).nameFace()
                    if let timestamp = row.item.timestampMs {
                        Text(Self.time(timestamp)).metaFace().foregroundStyle(.tertiary)
                    }
                }
            }

            if let quote = row.replyQuote {
                ReplyQuote(quote: quote)
            }

            RichTextView(blocks: blocks)
                // Own messages arrive from the core verbatim — never parsed as
                // markdown, because "you type, they write": a stray asterisk
                // must not change what you appear to have said.
                .font(isOwn ? Theme.own : Theme.body)
                .foregroundStyle(muted && !isOwn ? AnyShapeStyle(.secondary) : AnyShapeStyle(.primary))
                .padding(isOwn ? 10 : 0)
                .background(isOwn ? Theme.accent.opacity(0.13) : .clear, in: RoundedRectangle(cornerRadius: 12))

            // Your own side of the conversation, which carried no time and no
            // send state at all — three identical messages were
            // indistinguishable, and a message that never left the phone
            // looked exactly like one that landed.
            if isOwn {
                HStack(spacing: 5) {
                    if let label = sendState.label {
                        if sendState == .failed {
                            Image(systemName: "exclamationmark.circle")
                        }
                        Text(label)
                    }
                    if let timestamp = row.item.timestampMs {
                        Text(Self.time(timestamp))
                    }
                }
                .metaFace()
                // Failure is the one state that may speak up. Everything else
                // here is a quiet timestamp.
                .foregroundStyle(sendState == .failed ? AnyShapeStyle(Theme.danger) : AnyShapeStyle(.tertiary))
            }

            if !row.item.reactions.isEmpty {
                ReactionRow(reactions: row.item.reactions, onReact: onReact)
            }
        }
        .frame(maxWidth: .infinity, alignment: isOwn ? .trailing : .leading)
        // A continued row sits closer to the one above it: the gap is what
        // says "same turn" once the header is gone.
        .padding(.top, continuesRun ? 2 : 8)
        .padding(.bottom, 2)
    }

    static func time(_ ms: UInt64) -> String {
        let date = Date(timeIntervalSince1970: Double(ms) / 1000)
        return date.formatted(date: .omitted, time: .shortened)
    }
}

/// The quoted parent of a reply, as the core resolved it.
private struct ReplyQuote: View {
    let quote: ReplyQuoteView

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Rectangle().fill(Theme.accent.opacity(0.6)).frame(width: 2)
            switch quote {
            case .unavailable:
                // The core folds Unavailable/Pending/Error together, so this is
                // the one shape to handle — and it renders as a sentence rather
                // than an empty quote or a spinner that will never resolve.
                Text("Original message unavailable")
                    .metaFace()
                    .foregroundStyle(.tertiary)
            case let .available(sender, excerpt, label):
                VStack(alignment: .leading, spacing: 1) {
                    Text(sender).metaFace().textCase(.uppercase)
                    if let excerpt {
                        Text(excerpt).font(.footnote).lineLimit(2)
                    } else if let label {
                        // A ready parent with nothing to quote — redacted, a
                        // sticker, undecryptable. The label says which, in the
                        // same words a top-level item of that kind would use.
                        Text(label).font(.footnote).foregroundStyle(.tertiary)
                    }
                }
            }
        }
        .padding(.vertical, 2)
    }
}

private struct ReactionRow: View {
    let reactions: [ReactionDto]
    var onReact: ((String) -> Void)?

    var body: some View {
        HStack(spacing: 6) {
            ForEach(reactions, id: \.key) { reaction in
                Button { onReact?(reaction.key) } label: {
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
            }
        }
    }

    @ViewBuilder private func chip(_ reaction: ReactionDto) -> some View {
        HStack(spacing: 3) {
            // `displayKey`, never `key`: they usually look alike, but `key` is
            // wire data compared byte-for-byte against what other clients
            // sent, and this one is bounded for display.
            Text(reaction.displayKey)
            Text("\(reaction.count)").metaFace()
        }
        .padding(.horizontal, 7)
        .padding(.vertical, 4)
        .contentShape(Capsule())
        .overlay(
            Capsule().stroke(
                reaction.byMe ? Theme.accent : Color.secondary.opacity(0.4),
                lineWidth: reaction.byMe ? 1.5 : 1))
    }
}

/// A quiet line about the room rather than in it — a membership change, a
/// placeholder, a collapsed run.
struct SystemLine: View {
    let text: String

    var body: some View {
        Text(text)
            .metaFace()
            .foregroundStyle(.tertiary)
            .frame(maxWidth: .infinity, alignment: .center)
            .padding(.vertical, 6)
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
                        .fill(.quaternary)
                        // The box is reserved from the sender's reported
                        // dimensions *before* any bytes are asked for, so the
                        // list does not reflow when they land.
                        .aspectRatio(aspect, contentMode: .fit)
                        .overlay {
                            // Never a broken-image glyph. A picture that is
                            // still arriving and one that cannot be shown are
                            // different states and read differently.
                            if failed {
                                Image(systemName: "photo").foregroundStyle(.secondary)
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
                Text(caption).metaFace().foregroundStyle(.secondary)
            }
        }
        .padding(10)
        .background(.quaternary.opacity(0.4), in: RoundedRectangle(cornerRadius: 8))
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
