#if DEBUG
import SupermessageKit
import SwiftUI

/// The same screens a reader has turned their text up for.
///
/// Every frame in the catalogue was rendered at one text size, and it is the
/// default one. A reader at an accessibility size is using an application
/// nobody has looked at, and what lives there is layout: a name truncated to
/// nothing, a timestamp pushed off an edge, buttons that stop fitting side by
/// side.
///
/// Android's equivalent found exactly that on its first render — the roster
/// read `Kaa…` at twice the text size, because the name was splitting the
/// leftover space evenly with a `Spacer`. These are the frames that would
/// show the same thing here.
///
/// **`.accessibility1` and `.accessibility3`**, not the whole ladder.
/// `DynamicTypeSize` has twelve steps and they find the same bugs; one step
/// into the accessibility range and one near the top of it is where
/// two-column rows start to fight and then lose.

// The roster row: a glyph, a name, a state word, a runtime, a time and an
// unread badge, all competing for one line.
#Preview("Roster, accessibility1") {
    List {
        RoomRowView(row: PreviewFixtures.roomNeedsYou, avatarURI: nil, state: .needsYou, when: "2m")
        RoomRowView(row: PreviewFixtures.roomActive, avatarURI: nil, state: .active, when: "14m")
        RoomRowView(row: PreviewFixtures.roomInvitation, avatarURI: nil, state: .idle, when: "")
    }
    .listStyle(.plain)
    .environment(\.dynamicTypeSize, .accessibility1)
    .previewChrome()
}

#Preview("Roster, accessibility3") {
    List {
        RoomRowView(row: PreviewFixtures.roomNeedsYou, avatarURI: nil, state: .needsYou, when: "2m")
        RoomRowView(row: PreviewFixtures.roomActive, avatarURI: nil, state: .active, when: "14m")
    }
    .listStyle(.plain)
    .environment(\.dynamicTypeSize, .accessibility3)
    .previewChrome()
}

// Three buttons that have to fit a row, or stop trying to.
#Preview("Card pending, accessibility3") {
    PreviewGround {
        CustomEventCard(
            view: PreviewFixtures.cardPending, label: "Gate",
            eventType: "dev.superpipeline.gate.v1", senderName: "Superpipeline — Delivery",
            onDecide: { _ in true })
    }
    .environment(\.dynamicTypeSize, .accessibility3)
    .previewChrome()
}

// A table, a code block and a quote, none of which wrap like a paragraph.
#Preview("Rich text, accessibility3") {
    ScrollView { PreviewGround { RichTextView(blocks: PreviewFixtures.richBlocks) } }
        .environment(\.dynamicTypeSize, .accessibility3)
        .previewChrome()
}

// A message with its sender, time and reactions in one line's worth.
#Preview("Timeline row, accessibility3") {
    let media = PreviewFixtures.mediaCache()
    let faces = PreviewFixtures.faceCache()
    return VStack(alignment: .leading, spacing: 0) {
        TimelineRowView(
            row: PreviewFixtures.message, attribution: "Atlas — Platform", media: media,
            faces: faces)
        TimelineRowView(row: PreviewFixtures.withReactions, media: media, faces: faces)
    }
    .padding(.horizontal, 12)
    .frame(maxWidth: .infinity, alignment: .leading)
    .background(Theme.surface)
    .environment(\.dynamicTypeSize, .accessibility3)
    .previewChrome()
}
#endif
