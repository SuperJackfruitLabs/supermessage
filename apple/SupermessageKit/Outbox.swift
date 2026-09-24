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
/// **The text rides with the file, as its caption** (MSC2530): one event, so
/// the words can never arrive without their picture — the defect D12 names —
/// and an agent gets the picture and the question as one turn. Sent as two
/// messages, the question reached a bridged agent mid-turn and met "Session
/// is busy" (2026-09-24).
///
/// A reply is the exception: a caption cannot carry `in_reply_to`, so a file
/// sent while replying goes first and the text follows as the reply — and
/// only if the file went, for the same reason as always: the part that
/// survives a failure must not be the part that makes no sense alone.
@MainActor
public enum Outbox {
    public static func send(
        text: String, in roomId: String, mentioning members: [Mentionable],
        staged: StagedAttachment, replies: ReplyTarget, client: any MessageSending
    ) async -> SendResult {
        let body = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let hasAttachment = staged.isPresent(in: roomId)
        let replying = replies.pending(for: roomId) != nil
        let caption = hasAttachment && !replying && !body.isEmpty ? body : nil

        if hasAttachment, let failure = await staged.send(in: roomId, caption: caption) {
            return .attachmentFailed(failure)
        }
        // The words went with the file.
        if caption != nil { return .sent }
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
