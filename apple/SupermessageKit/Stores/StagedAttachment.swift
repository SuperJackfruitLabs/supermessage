import Foundation
import Observation
import SupermessageFFI

/// The one file waiting to be sent.
///
/// One, not many: multiple attachments in a single send are out of scope, and
/// the strip shows a single chip. A second pick replaces the first rather than
/// queueing, so what is on screen is always what will be sent.
///
/// ## A failure belongs to the chip
///
/// A refusal — at staging or at send — is recorded here, as ``failure``, and
/// drawn on the chip it is about. It used to be a loose line of red text above
/// the composer, which read as a failure of the *message*, and the message's
/// text had often already gone without its attachment.
///
/// ## The token after a failed send
///
/// The core owns this: a send that fails after it took the token comes back as
/// `.failed(error:restaged:)`, with the same path already staged again behind a
/// fresh token (and a voice mark re-applied), or `restaged == nil` when the file
/// is gone. The chip simply holds whatever the core hands back — a sendable
/// chip carrying the error, or a *failed* chip with nothing behind it that the
/// reader removes or replaces, never silently dropped.
///
/// A send refused *before* the take throws instead: `UnknownAttachment` means
/// the core no longer holds the token (swept, or the room changed), so the chip
/// becomes a failed one; any other refusal (`RoomChanged`, ...) leaves the
/// token live and the chip sendable.
@MainActor
@Observable
public final class StagedAttachment {
    /// What the chip is about when the attachment could not be staged or sent.
    public struct Failure: Equatable, Sendable {
        public let filename: String
        public let message: String

        public init(filename: String, message: String) {
            self.filename = filename
            self.message = message
        }
    }

    /// The staged file, with a live token. `nil` when there is nothing that
    /// could be sent.
    public private(set) var file: StagedFile?
    /// Where ``file`` was staged from, so the chip can draw a thumbnail of an
    /// image.
    public private(set) var path: String?
    /// The room it was staged for. The core binds a token to one room, and a
    /// chip that followed the reader into another room would be a chip that
    /// cannot be sent there.
    public private(set) var roomId: String?
    /// Why the last stage or send did not work, drawn on the chip.
    public private(set) var failure: Failure?
    /// The staged file is a recording, sent as a voice message: its length
    /// and waveform.
    public private(set) var voice: VoiceMark?

    public struct VoiceMark: Equatable, Sendable {
        public let durationMs: UInt64
        public let waveform: [Float]
        public init(durationMs: UInt64, waveform: [Float]) {
            self.durationMs = durationMs
            self.waveform = waveform
        }
    }

    private let client: any AttachmentStaging

    public init(client: any AttachmentStaging) {
        self.client = client
    }

    /// Whether there is a chip to draw in `roomId` at all — a file, a
    /// failure, or both.
    public func isPresent(in roomId: String) -> Bool {
        self.roomId == roomId && (file != nil || failure != nil)
    }

    /// Whether the chip in `roomId` stands for an attachment that cannot go.
    ///
    /// The composer must not send the text on its own while this is true: the
    /// reader asked for text *and* a file, and a message that arrives without
    /// the file it describes ("here's the screenshot") is the defect D12 is.
    public func blocksSend(in roomId: String) -> Bool {
        self.roomId == roomId && file == nil && failure != nil
    }

    /// Hand the core a path and keep the token it returns.
    ///
    /// The core does the rest — sniffing the mime from the file's *content*
    /// rather than trusting its extension, reading dimensions from the header,
    /// bounding the size. Returns a message when it refuses, or `nil`; the
    /// same message is kept as ``failure`` for the chip.
    @discardableResult
    public func stage(path: String, in roomId: String) async -> String? {
        // Replacing rather than queueing: discard whatever was staged first,
        // so a token cannot be orphaned in the core.
        await discard()
        self.roomId = roomId
        do {
            file = try await client.attachmentStagePath(roomId: roomId, path: path)
            self.path = path
            return nil
        } catch {
            let message = Self.message(for: error, otherwise: "Couldn't attach that file.")
            failure = Failure(filename: Self.filename(of: path), message: message)
            return message
        }
    }

    /// Mark what is staged in `roomId` as a voice message.
    ///
    /// Sent without it, a recording is a plain audio file: other clients draw
    /// a file row, and Hermes — which transcribes only messages flagged as
    /// voice — never transcribes it (2026-09-26). A failed mark is not a
    /// failed attachment: the recording still sends, as a file.
    public func markVoice(_ mark: VoiceMark, in roomId: String) async {
        guard self.roomId == roomId, let file else { return }
        voice = mark
        try? await client.attachmentMarkVoice(
            roomId: roomId, token: file.token, durationMs: mark.durationMs, waveform: mark.waveform)
    }

    /// Record a refusal that happened before the core was ever asked — the
    /// picker's bytes could not be read, or copied.
    public func refuse(filename: String, message: String, in roomId: String) async {
        await discard()
        self.roomId = roomId
        failure = Failure(filename: filename, message: message)
    }

    /// Send it, consuming the token. Returns a message on refusal.
    ///
    /// A chip that is a failure with no file behind it refuses without asking
    /// the core anything: there is nothing to send, and the caller must not
    /// send the text without it either.
    public func send(in roomId: String, caption: String? = nil) async -> String? {
        guard let file else { return failure?.message }
        do {
            switch try await client.attachmentSend(roomId: roomId, token: file.token, caption: caption) {
            case .sent:
                self.file = nil
                path = nil
                failure = nil
                voice = nil
                self.roomId = nil
                return nil
            case let .failed(error, restaged):
                let message = ErrorPresenter.message(for: error)
                self.file = restaged
                failure = Failure(filename: file.filename, message: message)
                return message
            }
        } catch {
            // Refused before the core took the token.
            let message = Self.message(for: error, otherwise: "Couldn't send that file.")
            if case .UnknownAttachment? = error as? FfiError {
                // The core no longer holds the token and has no path to stage
                // again: nothing can be sent from this chip.
                self.file = nil
            }
            failure = Failure(filename: file.filename, message: message)
            return message
        }
    }

    public func discard() async {
        if let file {
            await client.attachmentDiscard(token: file.token)
        }
        file = nil
        path = nil
        failure = nil
        voice = nil
        roomId = nil
    }

    private static func message(for error: any Error, otherwise fallback: String) -> String {
        if let error = error as? FfiError { return ErrorPresenter.message(for: error) }
        return fallback
    }

    private static func filename(of path: String) -> String {
        let name = (path as NSString).lastPathComponent
        return name.isEmpty ? "Attachment" : name
    }
}
