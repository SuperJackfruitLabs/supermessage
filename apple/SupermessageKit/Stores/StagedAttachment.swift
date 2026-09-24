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
/// The core's `send_staged` **consumes the token before it reads the file**
/// (`take_for_send`, then `stat`, then `read`, then upload). So a send that
/// failed may have left a live token (it failed at the room guard, before the
/// take) or a dead one (it failed at the read or the upload, after it). The
/// host cannot tell which from the error.
///
/// Pretending the chip is still sendable would be dishonest: the retry would
/// fail with "no longer staged" and look like a second, different fault. So on
/// a failed send the old token is discarded — a no-op on the core's side if
/// it was already taken — and the same path is staged again for a fresh one.
/// If that works, the chip is still there, still sendable, and carries the
/// error. If it does not (the file has gone), the chip stays as a *failed*
/// chip with nothing behind it: visible, explained, and something the reader
/// removes or replaces — never silently dropped.
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
    /// Where ``file`` was staged from, so a failed send can stage it again,
    /// and so the chip can draw a thumbnail of an image.
    public private(set) var path: String?
    /// The room it was staged for. The core binds a token to one room, and a
    /// chip that followed the reader into another room would be a chip that
    /// cannot be sent there.
    public private(set) var roomId: String?
    /// Why the last stage or send did not work, drawn on the chip.
    public private(set) var failure: Failure?

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
            try await client.attachmentSend(roomId: roomId, token: file.token, caption: caption)
            self.file = nil
            path = nil
            failure = nil
            self.roomId = nil
            return nil
        } catch {
            let message = Self.message(for: error, otherwise: "Couldn't send that file.")
            await restage(after: file, in: roomId)
            failure = Failure(filename: file.filename, message: message)
            return message
        }
    }

    /// Put a fresh token behind the chip after a send that may have consumed
    /// the old one. See this type's note on the token after a failed send.
    private func restage(after old: StagedFile, in roomId: String) async {
        // A no-op in the core when the token was already taken; a release
        // when it was not, so a live token is never orphaned.
        await client.attachmentDiscard(token: old.token)
        file = nil
        guard let path else { return }
        if let fresh = try? await client.attachmentStagePath(roomId: roomId, path: path) {
            file = fresh
        }
    }

    public func discard() async {
        if let file {
            await client.attachmentDiscard(token: file.token)
        }
        file = nil
        path = nil
        failure = nil
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
