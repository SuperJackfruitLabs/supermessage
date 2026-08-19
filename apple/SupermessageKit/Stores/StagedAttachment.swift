import Foundation
import Observation
import SupermessageFFI

/// The one file waiting to be sent.
///
/// One, not many: multiple attachments in a single send are out of scope, and
/// the strip shows a single chip. A second pick replaces the first rather than
/// queueing, so what is on screen is always what will be sent.
@MainActor
@Observable
public final class StagedAttachment {
    public private(set) var file: StagedFile?

    private let client: CoreClient

    public init(client: CoreClient) {
        self.client = client
    }

    /// Hand the core a path and keep the token it returns.
    ///
    /// The core does the rest — sniffing the mime from the file's *content*
    /// rather than trusting its extension, reading dimensions from the header,
    /// bounding the size. Returns a message when it refuses, or `nil`.
    public func stage(path: String, in roomId: String) async -> String? {
        do {
            // Replacing rather than queueing: discard whatever was staged
            // first, so a token cannot be orphaned in the core.
            await discard()
            file = try await client.attachmentStagePath(roomId: roomId, path: path)
            return nil
        } catch let error as FfiError {
            return ErrorPresenter.message(for: error)
        } catch {
            return "Couldn't attach that file."
        }
    }

    /// Send it, consuming the token. Returns a message on refusal.
    public func send(in roomId: String) async -> String? {
        guard let file else { return nil }
        do {
            try await client.attachmentSend(roomId: roomId, token: file.token)
            self.file = nil
            return nil
        } catch let error as FfiError {
            return ErrorPresenter.message(for: error)
        } catch {
            return "Couldn't send that file."
        }
    }

    public func discard() async {
        guard let file else { return }
        await client.attachmentDiscard(token: file.token)
        self.file = nil
    }
}
