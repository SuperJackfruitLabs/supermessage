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

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                pill(label: "All", isSelected: spaces.selectedId == nil, isInvitation: false) {
                    Task { await spaces.select(nil) }
                }
                ForEach(spaces.spaces, id: \.id) { space in
                    pill(
                        label: space.identity.name,
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
        label: String, isSelected: Bool, isInvitation: Bool, action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 4) {
                Text(label).lineLimit(1)
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
                    isSelected ? Theme.accent : Color.secondary.opacity(0.4),
                    lineWidth: isSelected ? 1.5 : 1))
        }
        .buttonStyle(.plain)
    }
}
