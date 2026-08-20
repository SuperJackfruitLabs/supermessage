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

    private var pendingEdit: EditTarget.Pending? {
        session.edits.pending(for: roomId)
    }

    var body: some View {
        VStack(spacing: 0) {
            if pendingEdit != nil {
                EditStrip { cancelEdit() }
            } else if let pendingReply {
                ReplyStrip(pending: pendingReply) { session.replies.cancel(roomId) }
            }
            if let staged = session.staged.file {
                AttachmentChip(staged: staged) { Task { await session.staged.discard() } }
            }
            if let failure {
                Text(failure)
                    .metaFace()
                    .foregroundStyle(Theme.danger)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.top, 4)
            }

            // The shape iOS readers already know: a `+` outside on the left,
            // and the field and its send button together inside one capsule.
            // Getting this wrong is the kind of thing that makes an app feel
            // foreign even when every pixel of the content is right.
            HStack(alignment: .bottom, spacing: 8) {
                Menu {
                    PhotosPicker("Photo", selection: $photo, matching: .images)
                    Button("File") { showsFileImporter = true }
                } label: {
                    Image(systemName: "plus")
                        .font(.system(size: 20, weight: .medium))
                        .frame(width: 32, height: 32)
                        .foregroundStyle(.secondary)
                }

                HStack(alignment: .bottom, spacing: 6) {
                    TextField(
                        pendingEdit == nil ? "Message" : "Edit message", text: $text,
                        axis: .vertical
                    )
                        .lineLimit(1...6)
                        .textFieldStyle(.plain)
                        .focused($focused)
                        .padding(.leading, 12)
                        .padding(.vertical, 7)
                        .onChange(of: text) { _, next in
                            // Not while editing: the composer is holding an
                            // existing message, and writing that over the
                            // draft would destroy whatever was being written
                            // before the edit began.
                            if pendingEdit == nil {
                                session.drafts.set(next, for: roomId)
                            }
                            Task { await session.setTyping(!next.isEmpty, in: roomId) }
                        }

                    // Inside the capsule, and only once there is something to
                    // send — Messages hides it rather than dimming it, which
                    // keeps the empty field looking like an invitation rather
                    // than a disabled control.
                    if canSend {
                        Button {
                            Task { await send() }
                        } label: {
                            // A tick rather than an arrow while editing:
                            // nothing is being sent to anyone, an existing
                            // message is being replaced.
                            Image(
                                systemName: pendingEdit == nil
                                    ? "arrow.up.circle.fill" : "checkmark.circle.fill"
                            )
                            .font(.system(size: 27))
                            .foregroundStyle(.white, Theme.accent)
                        }
                        .disabled(sending)
                        .padding(.trailing, 3)
                        .padding(.bottom, 2)
                        .transition(.scale.combined(with: .opacity))
                    }
                }
                .padding(.trailing, canSend ? 0 : 12)
                .overlay(
                    RoundedRectangle(cornerRadius: 19, style: .continuous)
                        .stroke(.secondary.opacity(0.35), lineWidth: 1)
                )
                .animation(.snappy(duration: 0.18), value: canSend)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
        }
        .background(.bar)
        .task(id: roomId) { text = session.drafts.draft(for: roomId) }
        // An edit begun from the timeline arrives here, not the other way
        // round: the composer is what holds the text.
        .onChange(of: pendingEdit) { _, next in
            guard let next else { return }
            text = next.body
            focused = true
        }
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

        if let pendingEdit {
            // The reader's text stays in the composer when this fails: an
            // edit that vanished into an error would have silently discarded
            // what they wrote.
            guard await session.edit(pendingEdit.eventId, body: text, in: roomId) else {
                failure = "Could not save the edit."
                return
            }
            failure = nil
            session.edits.cancel(roomId)
            text = session.drafts.draft(for: roomId)
            return
        }

        failure = await session.send(text: text, in: roomId)
        if failure == nil {
            text = ""
            session.drafts.clear(roomId)
        }
    }

    /// Abandon an edit, putting back whatever was being written before it.
    ///
    /// The draft was never cleared when the edit began, so what the reader had
    /// half-typed is still there — dropping them back into an empty composer
    /// would lose it.
    private func cancelEdit() {
        session.edits.cancel(roomId)
        text = session.drafts.draft(for: roomId)
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

/// "Editing message", with a way out.
///
/// No excerpt: the message being edited is already in the composer, in full,
/// and showing it twice would be the same text stacked on itself.
private struct EditStrip: View {
    let cancel: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "pencil").font(.footnote).foregroundStyle(Theme.accent)
            Text("Editing message").metaFace().textCase(.uppercase)
            Spacer()
            Button(action: cancel) { Image(systemName: "xmark") }
                .buttonStyle(.plain)
                .foregroundStyle(.secondary)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 6)
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
                    .metaFace()
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
                .metaFace()
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
