import SupermessageFFI
import SupermessageKit
import SwiftUI

/// One timeline row, drawn from the decision the core made about it.
///
/// The switch is over `ItemView`, which is `core::item_view`'s classification
/// of a Matrix event — whether an `m.room.name` change is a visible row,
/// whether an undecryptable event says something specific. This view never
/// makes that call; it draws the answer.
struct TimelineRowView: View {
    let row: TimelineRow

    private var item: TimelineItemDto { row.item }

    var body: some View {
        switch row.view {
        case let .bubble(muted, blocks):
            MessageBlock(row: row, muted: muted, blocks: blocks)

        case .emote:
            // Centred serif italic: an emote is prose *about* its sender
            // rather than something they said.
            Text("\(row.senderName) \(item.body ?? "")")
                .font(Theme.body.italic())
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .center)
                .padding(.vertical, 6)

        case let .system(text):
            SystemLine(text: text)

        case .unreadMarker:
            // No label. The divider says it, and a caption repeated at every
            // scroll position would be chrome pretending to be content.
            Divider()
                .overlay(Theme.accent)
                .padding(.vertical, 10)

        case let .placeholder(text):
            SystemLine(text: text)

        case let .image(alt, width, height):
            ImageRow(row: row, alt: alt, width: width, height: height)

        case let .mediaFile(label, filename, size, _):
            MediaFileRow(label: label, filename: filename, size: size)

        case let .customEvent(view, eventType):
            CustomEventCard(view: view, eventType: eventType, senderName: row.senderName)

        case .none:
            EmptyView()
        }
    }
}

/// A message, peer or own.
private struct MessageBlock: View {
    let row: TimelineRow
    let muted: Bool
    let blocks: [RichBlock]

    private var isOwn: Bool { row.item.isOwn }

    var body: some View {
        VStack(alignment: isOwn ? .trailing : .leading, spacing: 4) {
            if !isOwn {
                HStack(spacing: 6) {
                    Text(row.senderName).font(Theme.name)
                    if let timestamp = row.item.timestampMs {
                        Text(Self.time(timestamp)).font(Theme.meta).foregroundStyle(.tertiary)
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

            if !row.item.reactions.isEmpty {
                ReactionRow(reactions: row.item.reactions)
            }
        }
        .frame(maxWidth: .infinity, alignment: isOwn ? .trailing : .leading)
        .padding(.vertical, 6)
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
                    .font(Theme.meta)
                    .foregroundStyle(.tertiary)
            case let .available(sender, excerpt, label):
                VStack(alignment: .leading, spacing: 1) {
                    Text(sender).font(Theme.meta).textCase(.uppercase)
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

    var body: some View {
        HStack(spacing: 6) {
            ForEach(reactions, id: \.key) { reaction in
                HStack(spacing: 3) {
                    // `displayKey`, never `key`: they usually look alike, but
                    // `key` is wire data compared byte-for-byte against what
                    // other clients sent, and this one is bounded for display.
                    Text(reaction.displayKey)
                    Text("\(reaction.count)").font(Theme.meta)
                }
                .padding(.horizontal, 7)
                .padding(.vertical, 2)
                .overlay(
                    Capsule().stroke(
                        reaction.byMe ? Theme.accent : Color.secondary.opacity(0.4),
                        lineWidth: reaction.byMe ? 1.5 : 1))
            }
        }
    }
}

private struct SystemLine: View {
    let text: String

    var body: some View {
        Text(text)
            .font(Theme.meta)
            .foregroundStyle(.tertiary)
            .frame(maxWidth: .infinity, alignment: .center)
            .padding(.vertical, 6)
    }
}

private struct ImageRow: View {
    let row: TimelineRow
    let alt: String
    let width: UInt64?
    let height: UInt64?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(row.senderName).font(Theme.name)
            RoundedRectangle(cornerRadius: 8)
                .fill(.quaternary)
                // The box is reserved from the sender's reported dimensions
                // *before* any bytes are asked for, so the lazy stack does not
                // reflow when they land.
                .aspectRatio(aspect, contentMode: .fit)
                .frame(maxWidth: 320)
                .overlay(Image(systemName: "photo").foregroundStyle(.secondary))
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
                Text(caption).font(Theme.meta).foregroundStyle(.secondary)
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
