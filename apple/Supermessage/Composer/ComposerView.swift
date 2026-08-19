import PhotosUI
import SupermessageFFI
import SupermessageKit
import SwiftUI

/// Where a message is written.
///
/// `TextField(axis: .vertical)` inside a `.safeAreaInset(edge: .bottom)`, and
/// that single line deletes the largest risk `tech-stack.md` used to carry:
/// roughly two hundred lines of objc2 budgeted for resizing a WKWebView that
/// the iOS keyboard would not resize, called out there as the number one "web
/// tell" in a chat app. SwiftUI does keyboard avoidance natively, and the 16px
/// focus-zoom rule that broke the Tauri build does not exist outside a webview.
struct ComposerView: View {
    let session: Session
    let roomId: String

    @State private var text = ""
    @State private var sending = false
    @State private var photo: PhotosPickerItem?
    @State private var showsFileImporter = false
    @State private var failure: String?

    @FocusState private var focused: Bool

    private var pendingReply: ReplyTarget.Pending? {
        session.replies.pending(for: roomId)
    }

    var body: some View {
        VStack(spacing: 0) {
            if let pendingReply {
                ReplyStrip(pending: pendingReply) { session.replies.cancel(roomId) }
            }
            if let staged = session.staged.file {
                AttachmentChip(staged: staged) { Task { await session.staged.discard() } }
            }
            if let failure {
                Text(failure)
                    .font(Theme.meta)
                    .foregroundStyle(Theme.danger)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.top, 4)
            }

            HStack(alignment: .bottom, spacing: 10) {
                Menu {
                    PhotosPicker("Photo", selection: $photo, matching: .images)
                    Button("File") { showsFileImporter = true }
                } label: {
                    Image(systemName: "plus.circle").imageScale(.large)
                }

                TextField("Message…", text: $text, axis: .vertical)
                    .lineLimit(1...6)
                    .textFieldStyle(.plain)
                    .focused($focused)
                    .onChange(of: text) { _, next in
                        session.drafts.set(next, for: roomId)
                        Task { await session.setTyping(!next.isEmpty, in: roomId) }
                    }

                Button {
                    Task { await send() }
                } label: {
                    Image(systemName: "arrow.up.circle.fill").imageScale(.large)
                }
                .disabled(sending || !canSend)
                .tint(Theme.accent)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
        }
        .background(.bar)
        .task(id: roomId) { text = session.drafts.draft(for: roomId) }
        .onChange(of: photo) { _, item in
            guard let item else { return }
            Task { await stage(item) }
        }
        .fileImporter(isPresented: $showsFileImporter, allowedContentTypes: [.item]) { result in
            guard case let .success(url) = result else { return }
            Task { await stage(url) }
        }
    }

    private var canSend: Bool {
        !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            || session.staged.file != nil
    }

    private func send() async {
        sending = true
        defer { sending = false }
        failure = await session.send(text: text, in: roomId)
        if failure == nil {
            text = ""
            session.drafts.clear(roomId)
        }
    }

    /// A picked photo has no path until its bytes are written somewhere.
    ///
    /// This is why `FilePicker` never crossed the FFI boundary: the host picks
    /// and produces a path, and the core takes it from there — sniffing the
    /// mime from content, reading dimensions from the header, bounding the
    /// size. None of that is repeated here.
    private func stage(_ item: PhotosPickerItem) async {
        guard let data = try? await item.loadTransferable(type: Data.self) else { return }
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
        guard (try? data.write(to: url)) != nil else { return }
        failure = await session.staged.stage(path: url.path, in: roomId)
        photo = nil
    }

    private func stage(_ url: URL) async {
        // A security-scoped URL from the document picker has to be opened
        // before it can be read, and closed after — the core only ever sees
        // the path, and reads it on its own thread.
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        failure = await session.staged.stage(path: url.path, in: roomId)
    }
}

/// "Replying to …", with the excerpt the core bounded.
private struct ReplyStrip: View {
    let pending: ReplyTarget.Pending
    let cancel: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            Rectangle().fill(Theme.accent).frame(width: 2, height: 28)
            VStack(alignment: .leading, spacing: 1) {
                Text("Replying to \(pending.sender)")
                    .font(Theme.meta)
                    .textCase(.uppercase)
                if let excerpt = pending.excerpt {
                    Text(excerpt).font(.footnote).lineLimit(1).foregroundStyle(.secondary)
                }
            }
            Spacer()
            Button(action: cancel) { Image(systemName: "xmark") }
                .buttonStyle(.plain)
                .foregroundStyle(.secondary)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 6)
    }
}

/// A file waiting to be sent.
private struct AttachmentChip: View {
    let staged: StagedFile
    let discard: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "paperclip")
            Text(staged.filename).font(.footnote).lineLimit(1)
            Text(ByteCountFormatter.string(fromByteCount: Int64(staged.sizeBytes), countStyle: .file))
                .font(Theme.meta)
                .foregroundStyle(.secondary)
            Spacer()
            Button(action: discard) { Image(systemName: "xmark") }
                .buttonStyle(.plain)
                .foregroundStyle(.secondary)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 6)
    }
}
