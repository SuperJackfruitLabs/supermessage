import ImageIO
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
///
/// ## The capsule (C1)
///
/// One floating capsule holds everything: the `+`, the field, and a trailing
/// control that is **send** when there is something to send and **mic** when
/// there is not. The reply/edit strip and the attachment chip grow the capsule
/// upward rather than stacking loose rows above it, so whatever the next send
/// will carry is inside the thing that sends it.
///
/// ## Failures (D12)
///
/// An attachment's failure is drawn **on its chip** (`StagedAttachment.failure`)
/// — not as a loose red line above the composer, where it read as the whole
/// message failing after the text had in fact already gone. `Outbox` sends
/// the attachment first and the text only if it went, and the draft stays
/// in the field whenever anything did not go.
struct ComposerView: View {
    let session: Session
    let roomId: String

    @State private var text = ""
    @State private var sending = false
    @State private var photo: PhotosPickerItem?
    @State private var showsFileImporter = false
    /// Why the *text* (or an edit) did not go. Attachment failures live on
    /// the chip, never here.
    @State private var failure: String?
    /// Counts sends that reached the room, for the send haptic (M2).
    @State private var delivered = 0
    @State private var cast = RoomCast()
    @State private var recorder = VoiceRecorder()

    @FocusState private var focused: Bool

    private var pendingReply: ReplyTarget.Pending? {
        session.replies.pending(for: roomId)
    }

    private var pendingEdit: EditTarget.Pending? {
        session.edits.pending(for: roomId)
    }

    var body: some View {
        VStack(spacing: 6) {
            if let query = mentionQuery {
                let people = cast.candidates(matching: query.text)
                if !people.isEmpty {
                    MentionPicker(people: people) { person in
                        text = MentionComposing.insert(person, for: query, in: text)
                    }
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }

            VStack(spacing: 0) {
                if pendingEdit != nil {
                    EditStrip { cancelEdit() }
                } else if let pendingReply {
                    ReplyStrip(pending: pendingReply) { session.replies.cancel(roomId) }
                }
                if session.staged.isPresent(in: roomId) {
                    AttachmentChip(
                        file: session.staged.file, path: session.staged.path,
                        failure: session.staged.failure
                    ) { Task { await session.staged.discard() } }
                    .padding(.horizontal, 6)
                    .padding(.top, 6)
                }
                fieldRow
            }
            .floatingSurface(cornerRadius: 22)

            if let message = failure ?? recorder.failure {
                Text(message)
                    .metaFace()
                    .foregroundStyle(Theme.danger)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 14)
            }
        }
        .padding(.horizontal, 10)
        .padding(.top, 4)
        .padding(.bottom, 6)
        // A fade from the page into the composer's strip rather than a bar:
        // the capsule floats, and the conversation is still there behind it.
        .background(alignment: .bottom) {
            LinearGradient(
                colors: [Theme.surface.opacity(0), Theme.surface], startPoint: .top,
                endPoint: .center
            )
            .ignoresSafeArea(edges: .bottom)
            .allowsHitTesting(false)
        }
        .animation(.snappy(duration: 0.18), value: trailing)
        .animation(.snappy(duration: 0.2), value: mentionQuery)
        // Light, and only for a send that reached the room — a haptic that
        // fires on a failure is the phone contradicting the screen. The
        // system's own haptics setting is honoured by `sensoryFeedback`.
        .sensoryFeedback(.impact(weight: .light), trigger: delivered)
        .task(id: roomId) {
            text = session.drafts.draft(for: roomId)
            await loadCast()
        }
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

    // MARK: - The row

    private var fieldRow: some View {
        HStack(alignment: .bottom, spacing: 4) {
            if let startedAt = recorder.startedAt {
                RecordingPill(startedAt: startedAt, held: recorder.isHeld) {
                    recorder.cancel()
                }
                .transition(.opacity)
            } else {
                attachMenu
                TextField(
                    pendingEdit == nil ? "Message" : "Edit message", text: $text, axis: .vertical
                )
                .lineLimit(1...6)
                .textFieldStyle(.plain)
                .focused($focused)
                .padding(.vertical, 11)
                .onChange(of: text) { _, next in
                    // Not while editing: the composer is holding an existing
                    // message, and writing that over the draft would destroy
                    // whatever was being written before the edit began.
                    if pendingEdit == nil {
                        session.drafts.set(next, for: roomId)
                    }
                    Task { await session.setTyping(!next.isEmpty, in: roomId) }
                }
            }
            trailingControl
        }
        .padding(.leading, recorder.isRecording ? 14 : 4)
        .padding(.trailing, 4)
    }

    private var attachMenu: some View {
        Menu {
            PhotosPicker("Photo", selection: $photo, matching: .images)
            Button("File") { showsFileImporter = true }
        } label: {
            Image(systemName: "plus")
                .font(.system(size: 19, weight: .medium))
                .foregroundStyle(Theme.contentMuted)
                .frame(width: 40, height: 44)
                .contentShape(Rectangle())
        }
        .accessibilityLabel("Attach")
    }

    /// Which trailing control is showing. Also the animation's value, so the
    /// morph between send and mic is one spring rather than a cut.
    private enum Trailing: Equatable {
        case mic, send, confirmEdit, stopAndSend
    }

    private var trailing: Trailing {
        // Held: the mic stays exactly where the finger is. Replacing it while
        // it is being pressed would cancel the gesture the recording hangs on.
        if recorder.isHeld { return .mic }
        if recorder.isRecording { return .stopAndSend }
        if pendingEdit != nil { return .confirmEdit }
        let hasText = !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        return hasText || session.staged.isPresent(in: roomId) ? .send : .mic
    }

    @ViewBuilder private var trailingControl: some View {
        switch trailing {
        case .mic:
            MicButton(isRecording: recorder.isRecording) {
                Task { await recorder.start(held: false) }
            } holdBegan: {
                Task { await recorder.start(held: true) }
            } holdEnded: { cancelled in
                if !recorder.isRecording {
                    recorder.abandonPendingStart()
                } else if cancelled {
                    recorder.cancel()
                } else {
                    Task { await sendRecording() }
                }
            }
            .transition(.scale(scale: 0.6).combined(with: .opacity))
        case .send, .confirmEdit, .stopAndSend:
            Button {
                Task {
                    if trailing == .stopAndSend { await sendRecording() } else { await send() }
                }
            } label: {
                // A tick rather than an arrow while editing: nothing is being
                // sent to anyone, an existing message is being replaced.
                Image(systemName: trailing == .confirmEdit ? "checkmark.circle.fill" : "arrow.up.circle.fill")
                    .font(.system(size: 30))
                    .foregroundStyle(Theme.accentContent, canSend ? Theme.accent : Theme.contentFaint)
                    .frame(width: 44, height: 44)
                    .contentShape(Circle())
            }
            .buttonStyle(.plain)
            .disabled(sending || !canSend)
            .accessibilityLabel(
                trailing == .confirmEdit ? "Save edit" : trailing == .stopAndSend ? "Send voice message" : "Send")
            .transition(.scale(scale: 0.6).combined(with: .opacity))
        }
    }

    private var canSend: Bool {
        if recorder.isRecording { return true }
        let hasText = !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        if pendingEdit != nil { return hasText }
        // A failed chip with nothing behind it holds the text back too: the
        // reader asked for both, and one without the other is D12.
        if session.staged.blocksSend(in: roomId) { return false }
        return hasText || session.staged.isPresent(in: roomId)
    }

    // MARK: - Mentions

    private var mentionQuery: MentionComposing.Query? {
        guard focused, !recorder.isRecording else { return nil }
        return MentionComposing.activeQuery(in: text)
    }

    private func loadCast() async {
        let row = session.rooms.row(for: roomId)
        await cast.load(
            roomId: roomId, headerName: row?.identity.name, isAgentRoom: row?.room.runtime != nil,
            people: { await session.people() },
            memberIds: { try? await session.roomInfo(roomId).members.map(\.userId) })
    }

    // MARK: - Sending

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

        switch await session.send(text: text, in: roomId, mentioning: cast.mentionables) {
        case .sent:
            failure = nil
            text = ""
            session.drafts.clear(roomId)
            delivered += 1
        case .nothingToSend:
            break
        case .attachmentFailed:
            // On the chip. The text is untouched and still in the field.
            failure = nil
        case let .textFailed(message):
            failure = message
        }
    }

    /// Stop the recording and send it as a voice message.
    ///
    /// Staged through the path a picked file takes, then marked as a voice
    /// message with its length and waveform — without the mark it went as a
    /// plain audio file that Hermes never transcribed. The field is empty
    /// whenever the mic is offered, so there is no text to go with it.
    private func sendRecording() async {
        guard let recording = recorder.finish() else { return }
        guard await session.staged.stage(path: recording.url.path, in: roomId) == nil else { return }
        await session.staged.markVoice(
            .init(durationMs: recording.durationMs, waveform: recording.waveform), in: roomId)
        sending = true
        defer { sending = false }
        if await session.send(text: "", in: roomId) == .sent {
            delivered += 1
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
        defer { photo = nil }
        guard let data = try? await item.loadTransferable(type: Data.self) else {
            await session.staged.refuse(
                filename: "Photo", message: "Couldn't read that photo.", in: roomId)
            return
        }
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
        guard (try? data.write(to: url)) != nil else {
            await session.staged.refuse(
                filename: "Photo", message: "Couldn't read that photo.", in: roomId)
            return
        }
        await session.staged.stage(path: url.path, in: roomId)
    }

    private func stage(_ url: URL) async {
        // A security-scoped URL from the document picker is readable only
        // between `start…` and `stop…`. Staging the picked path itself worked
        // — the core reads it once to stage — and then failed at *send*,
        // which reads it again after access had been closed: "cannot read
        // that file", shown as "Couldn't read this device's local store", and
        // the text went without the image.
        //
        // So copy it into this app's own temporary directory while access is
        // open, keeping its name (the recipient sees it), and stage the copy.
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let folder = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        let copy = folder.appendingPathComponent(url.lastPathComponent)
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            try FileManager.default.copyItem(at: url, to: copy)
        } catch {
            await session.staged.refuse(
                filename: url.lastPathComponent, message: "Couldn't read that file.", in: roomId)
            return
        }
        await session.staged.stage(path: copy.path, in: roomId)
    }
}

// MARK: - The mic

/// Tap to start recording, or hold to record and release to send.
///
/// One `DragGesture(minimumDistance: 0)` rather than a `Button` plus a long
/// press: a button swallows the press, and a `LongPressGesture` reports when
/// the duration is reached, not when the finger lifts — and the lift is the
/// send. Sliding left past the threshold while holding cancels.
private struct MicButton: View {
    let isRecording: Bool
    let tapped: () -> Void
    let holdBegan: () -> Void
    let holdEnded: (_ cancelled: Bool) -> Void

    init(
        isRecording: Bool, tapped: @escaping () -> Void, holdBegan: @escaping () -> Void,
        holdEnded: @escaping (_ cancelled: Bool) -> Void
    ) {
        self.isRecording = isRecording
        self.tapped = tapped
        self.holdBegan = holdBegan
        self.holdEnded = holdEnded
    }

    /// How far left a held finger slides to throw the recording away.
    static let cancelDistance: CGFloat = -80

    @State private var pressing = false
    @State private var holding = false
    @State private var holdTimer: Task<Void, Never>?

    var body: some View {
        Image(systemName: isRecording ? "mic.fill" : "mic")
            .font(.system(size: 19, weight: .medium))
            .foregroundStyle(isRecording ? Theme.accentContent : Theme.contentMuted)
            .frame(width: 36, height: 36)
            .background(isRecording ? Theme.danger : Color.clear, in: Circle())
            .scaleEffect(isRecording ? 1.15 : 1)
            .frame(width: 44, height: 44)
            .contentShape(Circle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { _ in
                        guard !pressing else { return }
                        pressing = true
                        holdTimer = Task { @MainActor in
                            try? await Task.sleep(for: .milliseconds(300))
                            guard !Task.isCancelled, pressing else { return }
                            holding = true
                            holdBegan()
                        }
                    }
                    .onEnded { value in
                        holdTimer?.cancel()
                        holdTimer = nil
                        pressing = false
                        if holding {
                            holding = false
                            holdEnded(value.translation.width < Self.cancelDistance)
                        } else {
                            tapped()
                        }
                    }
            )
            .accessibilityElement()
            .accessibilityLabel("Record voice message")
            .accessibilityAddTraits(.isButton)
            .accessibilityAction { tapped() }
    }
}

/// What the field becomes while recording: how long, and a way out.
private struct RecordingPill: View {
    let startedAt: Date
    let held: Bool
    /// A fixed "now", for a still rendering; `nil` ticks with the clock.
    var frozenAt: Date? = nil
    let cancel: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.rendersStill) private var rendersStill
    @State private var pulse = false

    var body: some View {
        HStack(spacing: 10) {
            Circle()
                .fill(Theme.danger)
                .frame(width: 9, height: 9)
                .opacity(pulse ? 0.35 : 1)
                .onAppear {
                    guard !reduceMotion, !rendersStill else { return }
                    withAnimation(.easeInOut(duration: 0.8).repeatForever()) { pulse = true }
                }
            if let frozenAt {
                elapsed(at: frozenAt)
            } else {
                // `SwiftUI.` because this module has a `TimelineView` of its own.
                SwiftUI.TimelineView(.periodic(from: startedAt, by: 1)) { context in
                    elapsed(at: context.date)
                }
            }
            Text(held ? "Slide left to cancel" : "Recording")
                .metaFace()
                .foregroundStyle(Theme.contentMuted)
                .lineLimit(1)
            Spacer(minLength: 0)
            if !held {
                Button(action: cancel) {
                    Image(systemName: "trash")
                        .foregroundStyle(Theme.contentMuted)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Discard recording")
            }
        }
        .frame(minHeight: 44)
        .accessibilityElement(children: .contain)
    }

    private func elapsed(at now: Date) -> some View {
        Text(ElapsedTime.label(now.timeIntervalSince(startedAt)))
            .font(.body.monospacedDigit())
            .foregroundStyle(Theme.content)
    }
}

// MARK: - Mentions

/// The `@` picker: the room's people matching what follows the `@`, agents
/// first (the core's order), each agent labelled.
private struct MentionPicker: View {
    let people: [PersonDto]
    let pick: (PersonDto) -> Void

    var body: some View {
        VStack(spacing: 0) {
            ForEach(people, id: \.userId) { person in
                Button { pick(person) } label: {
                    HStack(spacing: 10) {
                        Text(person.initial)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(Theme.accent)
                            .frame(width: 30, height: 30)
                            .background(Theme.accentSoft, in: Circle())
                        VStack(alignment: .leading, spacing: 1) {
                            Text(person.name)
                                .font(.subheadline.weight(.medium))
                                .foregroundStyle(Theme.content)
                                .lineLimit(1)
                            if let runtime = person.runtime {
                                Text("\(runtime.harness) on \(runtime.host)")
                                    .metaFace()
                                    .foregroundStyle(Theme.contentMuted)
                                    .lineLimit(1)
                            }
                        }
                        Spacer(minLength: 8)
                        if person.runtime != nil { AgentTag() }
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 7)
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(person.runtime != nil ? "\(person.name), agent" : person.name)
            }
        }
        .padding(.vertical, 4)
        .floatingSurface(cornerRadius: 16)
    }
}

// MARK: - Strips

/// "Editing message", with a way out.
///
/// No excerpt: the message being edited is already in the composer, in full,
/// and showing it twice would be the same text stacked on itself.
private struct EditStrip: View {
    let cancel: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "pencil").font(.footnote).foregroundStyle(Theme.accent)
            Text("Editing message").metaFace()
            Spacer()
            Button(action: cancel) {
                // The glyph stays small; the target does not. `.plain` around a
                // bare `Image` is hit-testable only where the xmark's own ink
                // is — about 14pt across, a third of the 44pt Apple asks for.
                // `contentShape` is what makes the empty part of the frame
                // count; the frame alone would still only catch the glyph.
                Image(systemName: "xmark")
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel("Cancel")
            .buttonStyle(.plain)
            .foregroundStyle(Theme.contentMuted)
        }
        .padding(.leading, 14)
        .padding(.trailing, 2)
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
                if let excerpt = pending.excerpt {
                    Text(excerpt).font(.footnote).lineLimit(1).foregroundStyle(Theme.contentMuted)
                }
            }
            Spacer()
            Button(action: cancel) {
                // Same 14pt glyph against the same 44pt floor as `EditStrip`.
                Image(systemName: "xmark")
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel("Cancel")
            .buttonStyle(.plain)
            .foregroundStyle(Theme.contentMuted)
        }
        .padding(.leading, 14)
        .padding(.trailing, 2)
        .padding(.top, 4)
    }
}

// MARK: - The chip

/// A file waiting to be sent — or one that could not be, and says why.
///
/// A picture shows itself (C3): a thumbnail from the staged path, so the
/// reader can see *which* screenshot is about to go. Anything else shows
/// what kind of thing it is.
private struct AttachmentChip: View {
    let file: StagedFile?
    let path: String?
    let failure: StagedAttachment.Failure?
    let discard: () -> Void

    private var filename: String { file?.filename ?? failure?.filename ?? "Attachment" }

    var body: some View {
        HStack(spacing: 10) {
            preview
                .frame(width: 44, height: 44)
                .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
            VStack(alignment: .leading, spacing: 2) {
                Text(filename)
                    .font(.footnote.weight(.medium))
                    .foregroundStyle(Theme.content)
                    .lineLimit(1)
                    .truncationMode(.middle)
                if let failure {
                    Label(failure.message, systemImage: "exclamationmark.circle.fill")
                        .labelStyle(.titleAndIcon)
                        .font(.caption)
                        .foregroundStyle(Theme.danger)
                        .lineLimit(3)
                } else if let file {
                    Text(ByteCountFormatter.string(fromByteCount: Int64(file.sizeBytes), countStyle: .file))
                        .metaFace()
                        .foregroundStyle(Theme.contentMuted)
                }
            }
            Spacer(minLength: 4)
            Button(action: discard) {
                // Same 14pt target against the same 44pt floor as the strips.
                Image(systemName: "xmark")
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel("Remove attachment")
            .buttonStyle(.plain)
            .foregroundStyle(Theme.contentMuted)
        }
        .padding(.leading, 6)
        .padding(.vertical, 6)
        .background(Theme.surfaceSunken, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(failure == nil ? Theme.border : Theme.danger, lineWidth: 1)
        )
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder private var preview: some View {
        if let file, file.mime.hasPrefix("image/"), let path {
            Thumbnail(path: path)
        } else {
            Image(systemName: symbol)
                .font(.system(size: 18))
                .foregroundStyle(failure == nil ? Theme.contentMuted : Theme.danger)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Theme.surfaceRaised)
        }
    }

    private var symbol: String {
        guard let mime = file?.mime else { return "exclamationmark.triangle" }
        if mime.hasPrefix("audio/") { return "waveform" }
        if mime.hasPrefix("video/") { return "film" }
        if mime.hasPrefix("image/") { return "photo" }
        return "doc"
    }
}

/// A small decoded copy of a staged image.
///
/// ImageIO's thumbnailer rather than `UIImage(contentsOfFile:)`: a photo
/// from the library is twelve megapixels, and decoding all of it to draw a
/// 44-point square is 48 MB of memory for a picture the size of a stamp.
private struct Thumbnail: View {
    let path: String
    @State private var image: CGImage?
    @Environment(\.displayScale) private var scale

    var body: some View {
        Group {
            if let image {
                Image(decorative: image, scale: scale)
                    .resizable()
                    .scaledToFill()
            } else {
                Theme.surfaceRaised
            }
        }
        .task(id: path) { image = await Self.decode(path, pixels: Int(44 * scale)) }
    }

    nonisolated private static func decode(_ path: String, pixels: Int) async -> CGImage? {
        guard let source = CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL, nil) else {
            return nil
        }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: max(pixels, 1),
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    }
}

#if DEBUG
// The composer at rest: the capsule with its mic.
#Preview("Empty") {
    ComposerView(session: PreviewFixtures.session(), roomId: PreviewFixtures.roomId)
        .background(Theme.surface)
}

// With a reply staged above it.
//
// `ReplyTarget.Pending` carries a snapshot rather than a binding, on purpose:
// the composer keeps whatever it was handed, so a parent that is later
// redacted or scrolls out of the materialised timeline does not make this row
// change or vanish underneath the person writing. This preview is that row.
#Preview("Replying") {
    let session = PreviewFixtures.session()
    // `start` takes the row rather than its parts, and reads `eventId` from
    // it — not `item.id`, because identity is stable across the
    // local-echo-to-confirmed transition and is therefore not something the
    // homeserver has heard of.
    session.replies.start(PreviewFixtures.replyParent, in: PreviewFixtures.roomId)
    return ComposerView(session: session, roomId: PreviewFixtures.roomId)
        .background(Theme.surface)
}

// With an attachment staged.
//
// The staged file has dimensions and a size, and the row has to hold a
// filename that may be much longer than the space for it.
#Preview("Attachment staged") {
    let session = PreviewFixtures.session()
    return PreviewSeeded(seed: {
        _ = await session.staged.stage(path: "/tmp/muster-dark.png", in: PreviewFixtures.roomId)
    }) {
        ComposerView(session: session, roomId: PreviewFixtures.roomId)
            .background(Theme.surface)
    }
}

// An attachment whose send failed, and says so on itself (D12). The file is
// still behind the chip — re-staged after the failed send consumed the old
// token — so the reader can try again or remove it.
#Preview("Chip, send failed") {
    PreviewGround {
        AttachmentChip(
            file: ComposerRevampFixtures.stagedAudio, path: nil,
            failure: ComposerRevampFixtures.sendFailure
        ) {}
    }
}

// A file the core refused at staging, with nothing behind the chip.
#Preview("Attachment refused") {
    let session = PreviewFixtures.session()
    return PreviewSeeded(seed: {
        await session.staged.refuse(
            filename: "build-log-2026-09-23.txt", message: "That file is too large to send.",
            in: PreviewFixtures.roomId)
    }) {
        ComposerView(session: session, roomId: PreviewFixtures.roomId)
            .background(Theme.surface)
    }
}

// The `@` picker, agents first, each labelled.
#Preview("Mention picker") {
    PreviewGround {
        MentionPicker(people: PreviewFixtures.people) { _ in }
    }
}

// Recording a voice message, tapped rather than held.
#Preview("Recording") {
    PreviewGround {
        RecordingPill(
            startedAt: ComposerRevampFixtures.epoch, held: false,
            frozenAt: ComposerRevampFixtures.epoch.addingTimeInterval(14)
        ) {}
            .padding(.horizontal, 14)
            .floatingSurface(cornerRadius: 22)
    }
    .environment(\.rendersStill, true)
}
#endif
