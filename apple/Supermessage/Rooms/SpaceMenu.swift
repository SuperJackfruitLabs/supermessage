import SupermessageKit
import SwiftUI

/// The space filter, on a phone: the list's title, as a menu.
///
/// It was a strip of pills above a second strip of filter chips. The two
/// looked alike, both began with "All", and spaces were then named a third
/// time by the section headings (2026-09-24). A space is a *scope*, not a
/// filter, so it is chosen the way Slack chooses a workspace and Mail a
/// mailbox: from the title. The chips below are left with the one job of
/// narrowing, and the first room moves up by a row.
struct SpaceMenu: View {
    let spaces: SpacesStore
    /// How many rooms "All spaces" holds. The roster is what "All" means, and
    /// only the roster knows how long it is.
    let allCount: Int

    private var title: String {
        spaces.selectedName.map(SpaceNames.display) ?? "Chats"
    }

    private var selection: Binding<String?> {
        Binding(
            get: { spaces.selectedId },
            set: { id in Task { await spaces.select(id) } })
    }

    var body: some View {
        if spaces.spaces.isEmpty {
            // Most accounts have no spaces: then there is nothing to choose,
            // and the title is only a title.
            Text("Chats").font(.headline)
        } else {
            Menu {
                Picker("Space", selection: selection) {
                    Text("All spaces · \(allCount)").tag(String?.none)
                    ForEach(spaces.spaces, id: \.id) { space in
                        let name = SpaceNames.display(space.identity.name)
                        if spaces.isInvitation(space) {
                            // No count: the account cannot see into a space
                            // it has not joined, so one would be invented.
                            Label("\(name) · Invitation", systemImage: "envelope")
                                .tag(Optional(space.id))
                        } else {
                            Text("\(name) · \(space.childCount)").tag(Optional(space.id))
                        }
                    }
                }
            } label: {
                HStack(spacing: 4) {
                    Text(title)
                        .font(.headline)
                        .lineLimit(1)
                        .truncationMode(.middle)
                    Image(systemName: "chevron.down")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(Theme.contentMuted)
                }
                .foregroundStyle(Theme.content)
                .frame(maxWidth: 220)
            }
            .accessibilityLabel("Space: \(title)")
            .accessibilityHint("Shows the rooms of one space")
        }
    }
}

#if DEBUG
// The title with three spaces, one of them an invitation.
//
// Wrapped in `PreviewSeeded` because `SpacesStore` has no envelope route —
// only `refresh()`, which is an `await`.
#Preview("Three spaces") {
    let spaces = PreviewFixtures.spacesStore()
    return PreviewSeeded(seed: { await spaces.refresh() }) {
        PreviewGround { SpaceMenu(spaces: spaces, allCount: 5) }
    }
}

// No spaces at all, which is most accounts: a plain title.
#Preview("No spaces") {
    let spaces = PreviewFixtures.spacesStore(.empty)
    return PreviewSeeded(seed: { await spaces.refresh() }) {
        PreviewGround { SpaceMenu(spaces: spaces, allCount: 5) }
    }
}
#endif
