import SupermessageFFI
import SupermessageKit
import SwiftUI

/// Who is in a room, and what it is called.
struct RoomInfoPanel: View {
    let session: Session
    let roomId: String
    let onClose: () -> Void

    @State private var info: RoomInfoDto?
    @State private var failure: String?
    /// This account's user id, so it can be left out of the member list.
    @State private var account: String?
    @State private var showsAvatar = false

    /// The room's picture as a `data:` URI, from the same cache the roster
    /// reads — so opening this panel does not re-fetch what is already held.
    private var avatarURI: String? { session.avatars.uri(for: roomId) }

    var body: some View {
        NavigationStack {
            Group {
                if let info {
                    List {
                        Section {
                            HStack(spacing: 12) {
                                // The room's actual picture, where it has one.
                                // This panel showed a grey initial even for
                                // rooms whose avatar the roster was already
                                // drawing three rows away.
                                Button {
                                    guard avatarURI != nil else { return }
                                    showsAvatar = true
                                } label: {
                                    ZStack {
                                        Circle().fill(.quaternary)
                                        if let avatarURI,
                                            let image = RoomRowView.image(from: avatarURI)
                                        {
                                            image.resizable().scaledToFill().clipShape(Circle())
                                        } else {
                                            // The initial the core derived
                                            // from the *parsed* name, never
                                            // the raw string's first
                                            // character — for a structured
                                            // room that is the glyph.
                                            Text(info.identity.initial)
                                        }
                                    }
                                    .frame(width: 44, height: 44)
                                }
                                .buttonStyle(.plain)
                                .disabled(avatarURI == nil)
                                .accessibilityLabel(
                                    avatarURI == nil
                                        ? "\(info.identity.name)"
                                        : "\(info.identity.name), tap to view the picture")
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(info.identity.name).font(.headline)
                                    if let role = info.identity.role {
                                        Text(role).metaFace().foregroundStyle(.secondary)
                                    }
                                }
                            }
                            // The runtime, when this room is an agent's — the
                            // thing you open this panel to find. The core has
                            // already read it out of the topic and suppressed
                            // the raw line, so there is nothing to decide here.
                            if let runtime = info.runtime {
                                LabeledContent("Harness", value: runtime.harness)
                                LabeledContent("Machine", value: runtime.host)
                            }
                            if let topic = info.topic, !topic.isEmpty {
                                Text(topic).font(.callout)
                            }
                        }

                        // Above the member list, so the two settings people
                        // actually reach for are on screen at the medium
                        // detent rather than below a list of two.
                        Section("Notifications") {
                            Toggle("Mute", isOn: muted)
                            Picker("Notify me about", selection: notifications) {
                                Text("Everything").tag(NotificationMode.allMessages)
                                Text("Mentions only").tag(NotificationMode.mentionsOnly)
                                // Named for what it does, not for what it is:
                                // "Default" is a word about the settings
                                // system, "Account default" is about the
                                // reader's account.
                                Text("Account default").tag(NotificationMode.default)
                                Text("Nothing").tag(NotificationMode.muted)
                            }
                            .pickerStyle(.menu)
                            Toggle("Pin to top", isOn: pinned)
                        }

                        // A room with one agent and you in it does not need a
                        // list — it needs the *other* participant named, and
                        // the count for anything larger. The list is the
                        // answer to "who is in here", which is only a question
                        // once there is more than one of them.
                        if others(info).count > 1 {
                            Section("Members (\(info.activeMemberCount))") {
                                ForEach(info.members, id: \.userId) { member in
                                    MemberRow(member: member)
                                }
                            }
                        } else if let sole = others(info).first {
                            Section("Members") {
                                MemberRow(member: sole)
                            }
                        }

                        // The room's own address. Last, because it is what you
                        // come here for when something is wrong rather than
                        // when something is normal — and copyable, because the
                        // only use for it is pasting it somewhere else.
                        Section("Address") {
                            if let alias = info.canonicalAlias {
                                CopyableRow(label: "Alias", value: alias)
                            }
                            CopyableRow(label: "Room id", value: info.roomId)
                        }

                        Section {
                            Button("Leave room", role: .destructive) {
                                Task {
                                    _ = await session.leaveRoom(roomId)
                                    onClose()
                                }
                            }
                        }
                    }
                } else if let failure {
                    ContentUnavailableView(
                        "Couldn't load", systemImage: "exclamationmark.triangle",
                        description: Text(failure))
                } else {
                    ProgressView()
                }
            }
            .navigationTitle("Room info")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done", action: onClose)
                }
            }
        }
        .task(id: roomId) {
            account = await session.account()?.userId
            await session.avatars.load(roomId)
            await load()
        }
        .fullScreenCover(isPresented: $showsAvatar) {
            if let avatarURI, let image = RoomRowView.image(from: avatarURI) {
                AvatarViewer(image: image, title: info?.identity.name ?? "") {
                    showsAvatar = false
                }
            }
        }
    }

    private func load() async {
        do { info = try await session.roomInfo(roomId) } catch { failure = "\(error)" }
    }

    /// Everyone in the room who is not this account.
    ///
    /// The reader knows they are in the room they are reading; listing them
    /// back to themselves is half of a two-member list saying nothing.
    private func others(_ info: RoomInfoDto) -> [RoomMemberDto] {
        info.members.filter { $0.userId != account }
    }

    /// Mute is the setting people reach for first, so it gets a switch of its
    /// own rather than being the fourth item in a menu. Turning it off
    /// restores the account default rather than picking a level on the
    /// reader's behalf.
    // Every binding below reads `self.info` rather than a passed-in copy, so
    // the optimistic write is the value the control reads back. A control
    // that waits for a homeserver round trip before moving reads as a broken
    // control, and one that moves against a stale copy snaps back.
    private var muted: Binding<Bool> {
        Binding(
            get: { info?.notifications == .muted },
            // Turning mute off restores the account default rather than
            // picking a level on the reader's behalf.
            set: { apply($0 ? .muted : .default) })
    }

    private var notifications: Binding<NotificationMode> {
        Binding(get: { info?.notifications ?? .default }, set: { apply($0) })
    }

    private var pinned: Binding<Bool> {
        Binding(
            get: { info?.pinned ?? false },
            set: { next in
                info?.pinned = next
                Task {
                    _ = await session.setPinned(next, in: roomId)
                    await load()
                }
            })
    }

    private func apply(_ mode: NotificationMode) {
        info?.notifications = mode
        Task {
            _ = await session.setNotifications(mode, in: roomId)
            await load()
        }
    }
}

/// One member: their name, and the id beneath it.
private struct MemberRow: View {
    let member: RoomMemberDto

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(member.displayName ?? member.userId)
            if member.displayName != nil {
                // One line, truncated in the middle: an agent id is
                // `@agent_<host>_<harness>-<name>:<server>` and both ends
                // carry meaning, so trimming the tail would throw away the
                // server and keep the padding.
                Text(member.userId)
                    .metaFace()
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
        }
    }
}

/// A label and a value you can take away with you.
private struct CopyableRow: View {
    let label: String
    let value: String

    var body: some View {
        LabeledContent(label) {
            Text(value).lineLimit(1).truncationMode(.middle)
        }
        .contextMenu {
            Button("Copy") { UIPasteboard.general.string = value }
        }
    }
}

/// A room's picture, full size.
///
/// Black ground and no chrome but a Done button: the picture is the whole
/// content, and anything else on screen is competing with it. Pinch to zoom,
/// drag to pan, and a drag down when unzoomed dismisses — the gestures a
/// reader already expects from Photos.
private struct AvatarViewer: View {
    let image: Image
    let title: String
    let onClose: () -> Void

    @State private var zoom: CGFloat = 1
    @State private var committedZoom: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var committedOffset: CGSize = .zero

    var body: some View {
        NavigationStack {
            GeometryReader { geometry in
                image
                    .resizable()
                    .scaledToFit()
                    .scaleEffect(zoom)
                    .offset(offset)
                    .frame(width: geometry.size.width, height: geometry.size.height)
                    .contentShape(Rectangle())
                    .gesture(
                        MagnifyGesture()
                            .onChanged { value in
                                // Never below 1: a picture smaller than the
                                // screen it is being viewed on is not a view
                                // of it.
                                zoom = max(1, committedZoom * value.magnification)
                            }
                            .onEnded { _ in
                                committedZoom = zoom
                                if zoom <= 1 { resetPan() }
                            }
                    )
                    .simultaneousGesture(
                        DragGesture()
                            .onChanged { value in
                                guard zoom > 1 else { return }
                                offset = CGSize(
                                    width: committedOffset.width + value.translation.width,
                                    height: committedOffset.height + value.translation.height)
                            }
                            .onEnded { value in
                                if zoom > 1 {
                                    committedOffset = offset
                                } else if value.translation.height > 80 {
                                    onClose()
                                }
                            }
                    )
                    .onTapGesture(count: 2) {
                        // Double tap toggles between fit and 2×, which is
                        // faster than pinching for the one thing anyone wants
                        // to do to a small round picture.
                        withAnimation(.snappy(duration: 0.2)) {
                            zoom = zoom > 1 ? 1 : 2
                            committedZoom = zoom
                            if zoom == 1 { resetPan() }
                        }
                    }
            }
            .background(Color.black.ignoresSafeArea())
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("Done", action: onClose) }
            }
        }
    }

    private func resetPan() {
        offset = .zero
        committedOffset = .zero
    }
}
