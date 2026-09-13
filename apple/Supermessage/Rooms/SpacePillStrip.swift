import SupermessageKit
import SwiftUI

/// The space filter, on a phone.
///
/// A horizontal strip rather than the desktop's 52pt rail: there is no room
/// for a rail beside a phone-width list. It sits **inside** the list's scroll
/// content, so it is present the moment the reader arrives and gives its ~40pt
/// back the moment they start scrolling. The current space also names the
/// navigation title, so scope stays legible once the strip has gone.
struct SpacePillStrip: View {
    let spaces: SpacesStore
    /// How many rooms "All" holds. Not the store's business: the roster is
    /// what "All" means, and only the roster knows how long it is.
    let allCount: Int

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                pill(
                    label: "All", count: allCount, isSelected: spaces.selectedId == nil,
                    isInvitation: false
                ) {
                    Task { await spaces.select(nil) }
                }
                ForEach(spaces.spaces, id: \.id) { space in
                    pill(
                        label: space.identity.name,
                        // An invitation has no count to show — see the pill
                        // itself for why one would be invented.
                        count: spaces.isInvitation(space) ? nil : Int(space.childCount),
                        isSelected: spaces.selectedId == space.id,
                        isInvitation: spaces.isInvitation(space)
                    ) {
                        Task { await spaces.select(space.id) }
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 6)
        }
        .scrollClipDisabled()
    }

    private func pill(
        label: String, count: Int?, isSelected: Bool, isInvitation: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 4) {
                // Truncated in the *middle*, and capped. A host reads as
                // `Rakesh's MacBook Pro` and a provisioned runtime as
                // `9247e5…`; trimming the tail would keep the padding and
                // throw away the half that distinguishes one from another.
                Text(label)
                    .lineLimit(1)
                    .truncationMode(.middle)
                    .frame(maxWidth: 130)
                    .fixedSize(horizontal: true, vertical: false)
                // On every pill, not only the selected one. A filter that
                // says how much it holds can be chosen without being tried;
                // one that does not has to be tapped to find out, and an
                // empty space looks the same as a full one until then.
                if let count {
                    Text("\(count)")
                        .monospacedDigit()
                        .foregroundStyle(isSelected ? Theme.accent : Theme.contentMuted)
                }
                if isInvitation {
                    // An invitation is not a filter — the account cannot see
                    // into a space it has not joined, so a count would be
                    // invented. Marking it is what lets a tap offer Accept.
                    Image(systemName: "envelope").imageScale(.small)
                }
            }
            .font(.footnote)
            .padding(.horizontal, 11)
            .padding(.vertical, 5)
            .background(isSelected ? Theme.accent.opacity(0.14) : Color.clear, in: Capsule())
            .overlay(
                Capsule().stroke(
                    isSelected ? Theme.accent : Theme.border,
                    lineWidth: isSelected ? 1.5 : 1))
        }
        .buttonStyle(.plain)
    }
}

#if DEBUG
// The strip with three spaces, one of them an invitation.
//
// Wrapped in `PreviewSeeded` because `SpacesStore` has no envelope route —
// only `refresh()`, which is an `await`. So this is the one component whose
// preview goes through the same asynchronous path the app does on launch,
// and it is worth knowing that: the frame before the seed lands is a strip
// holding nothing but "All", which is also what a reader with no spaces sees.
#Preview("Three spaces") {
    let spaces = PreviewFixtures.spacesStore()
    return PreviewSeeded(seed: { await spaces.refresh() }) {
        PreviewGround { SpacePillStrip(spaces: spaces, allCount: 5) }
    }
}

// No spaces at all, which is most accounts: "All" alone.
//
// The question this answers is whether the strip should be there at all in
// that case — it costs ~40pt of a phone's first screen to say nothing.
#Preview("No spaces") {
    let spaces = PreviewFixtures.spacesStore(.empty)
    return PreviewSeeded(seed: { await spaces.refresh() }) {
        PreviewGround { SpacePillStrip(spaces: spaces, allCount: 5) }
    }
}
#endif
