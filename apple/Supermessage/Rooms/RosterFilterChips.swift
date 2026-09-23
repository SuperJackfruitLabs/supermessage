import SupermessageKit
import SwiftUI

/// All · Unread, above the Chats list.
///
/// Agents and Needs you were chips too, and both are tabs one tap away, so
/// the chip row repeated the tab bar (2026-09-24). What is left narrows the
/// list by something no tab covers. Mentions joins when the core exposes it.
///
/// A chip narrows what the core arranged; it never re-sorts it — see
/// `RosterFilter.apply`. Each chip says how many rooms it would leave, so it
/// can be chosen without being tried.
struct RosterFilterChips: View {
    /// The chips a phone offers.
    static let offered: [RosterFilter] = [.all, .unread]

    @Binding var selection: RosterFilter
    /// How many rooms each chip would show.
    let counts: [RosterFilter: Int]

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(Self.offered, id: \.self) { filter in
                    chip(filter)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 6)
        }
        .scrollClipDisabled()
        .sensoryFeedback(.selection, trigger: selection)
    }

    private func chip(_ filter: RosterFilter) -> some View {
        let selected = selection == filter
        return Button {
            selection = filter
        } label: {
            HStack(spacing: 4) {
                Text(filter.title)
                if filter != .all, let count = counts[filter], count > 0 {
                    Text("\(count)")
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
        .accessibilityLabel(
            counts[filter].map { "\(filter.title), \($0)" } ?? filter.title)
    }
}

#if DEBUG
#Preview("Filter chips") {
    @Previewable @State var filter = RosterFilter.all
    PreviewGround {
        RosterFilterChips(
            selection: $filter, counts: [.all: 5, .unread: 2, .agents: 3, .needsYou: 1])
    }
}
#endif
