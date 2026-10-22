import SupermessageFFI
import SupermessageKit
import SwiftUI

/// All · Unread, above the Chats list.
///
/// Agents and Needs you were chips too, and both are tabs one tap away, so
/// the chip row repeated the tab bar (2026-09-24). What is left narrows the
/// list by something no tab covers. Mentions joins when the core exposes it.
///
/// A chip narrows what the core arranged; it never re-sorts it — see
/// `rosterFilterSections`. Each chip says how many rooms it would leave, so it
/// can be chosen without being tried.
struct RosterFilterChips: View {
    @Binding var selection: RosterFilter
    /// The chips the core offers, each with how many rooms it would show.
    let chips: [RosterFilterChip]

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(chips, id: \.filter) { chip in
                    self.chip(chip)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 6)
        }
        .scrollClipDisabled()
        .sensoryFeedback(.selection, trigger: selection)
    }

    private func chip(_ chip: RosterFilterChip) -> some View {
        let filter = chip.filter
        let selected = selection == filter
        return Button {
            selection = filter
        } label: {
            HStack(spacing: 4) {
                Text(chip.title)
                if filter != .all, chip.count > 0 {
                    Text("\(chip.count)")
                        .monospacedDigit()
                        .foregroundStyle(selected ? Theme.accentContent : Theme.contentMuted)
                }
            }
            .font(.footnote.weight(selected ? .semibold : .regular))
            .foregroundStyle(selected ? Theme.accentContent : Theme.content)
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(selected ? Theme.accent : Theme.surfaceRaised, in: Capsule())
            .overlay(Capsule().stroke(selected ? Color.clear : Theme.border, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityLabel("\(chip.title), \(chip.count)")
    }
}

#if DEBUG
#Preview("Filter chips") {
    @Previewable @State var filter = RosterFilter.all
    PreviewGround {
        RosterFilterChips(
            selection: $filter,
            chips: [
                RosterFilterChip(filter: .all, title: "All", count: 5),
                RosterFilterChip(filter: .unread, title: "Unread", count: 2),
            ])
    }
}
#endif
