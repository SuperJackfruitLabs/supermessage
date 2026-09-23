import Foundation
import SupermessageFFI

/// What happened to a send, told apart by what the composer does next.
public enum SendResult: Equatable, Sendable {
    /// Everything that was in the composer went.
    case sent
    /// There was nothing to send — an empty field and no chip.
    case nothingToSend
    /// The attachment did not go, **so the text did not either**. The reason
    /// is on the chip (``StagedAttachment/failure``), and the draft stays.
    case attachmentFailed(String)
    /// The text did not go. Any attachment already had; the draft stays.
    case textFailed(String)

    /// Whether something reached the room, which is what the send haptic and
    /// the acknowledgement dock key off.
    public var delivered: Bool { self == .sent }
}

/// The order a composer's contents leave in.
///
/// Its own type rather than the body of `Session.send` so the ordering can be
/// tested against the two narrow seams it touches — `MessageSending` and
/// `AttachmentStaging` — instead of against the whole of `SessionClient`.
///
/// **Attachment first, and the text only if it went.** The reader wrote the
/// text *about* the file; a caption that arrives without its picture is the
/// defect D12 names. Sending the text first and the file second would get the
/// order of a failure exactly backwards: the part that survives is the part
/// that makes no sense alone.
@MainActor
public enum Outbox {
    public static func send(
        text: String, in roomId: String, mentioning members: [Mentionable],
        staged: StagedAttachment, replies: ReplyTarget, client: any MessageSending
    ) async -> SendResult {
        let body = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let hasAttachment = staged.isPresent(in: roomId)

        if hasAttachment, let failure = await staged.send(in: roomId) {
            return .attachmentFailed(failure)
        }
        guard !body.isEmpty else { return hasAttachment ? .sent : .nothingToSend }

        do {
            if let reply = replies.pending(for: roomId) {
                try await client.sendReply(roomId: roomId, body: body, inReplyTo: reply.eventId)
                replies.cancel(roomId)
            } else {
                // The core decides who is addressed — see `collect_mentions`.
                // The composer's only part is handing over who *could* be.
                let mentions = SupermessageFFI.collectMentions(text: body, members: members)
                try await client.sendMessage(roomId: roomId, body: body, mentions: mentions)
            }
            return .sent
        } catch let error as FfiError {
            return .textFailed(ErrorPresenter.message(for: error))
        } catch {
            return .textFailed("Couldn't send that.")
        }
    }
}
