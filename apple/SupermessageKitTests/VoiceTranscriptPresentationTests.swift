import SupermessageFFI
import Testing

@testable import SupermessageKit

/// How a voice note's transcript sits under its note. The wording is the
/// core's (`core::voice_transcript`); these are the rules the web's
/// `voiceTranscriptView.ts` shares.
struct VoiceTranscriptPresentationTests {
    /// As `host_fixtures::voice_transcripts` pins it.
    static let transcript = VoiceNoteTranscript(
        text: "Can you move the review to Thursday?", language: "en", seconds: 42,
        duration: "0:42", caption: "Transcript · en · 0:42",
        accessibilityLabel: "Transcript of voice note: Can you move the review to Thursday?")

    static func row(id: String, view: ItemView, at ms: UInt64, sender: String = "@agentpod:x.org")
        -> TimelineRow
    {
        let item = TimelineItemDto(
            id: id, eventId: id, kind: "message", msgtype: "m.notice", detail: nil,
            sender: sender, senderDisplayName: "AgentPod", senderAvatar: nil,
            body: "Transcript: Can you move the review to Thursday?", formattedBody: nil,
            media: nil, customPayload: nil, timestampMs: ms, isOwn: false, sendState: nil,
            replyTo: nil, edited: false, reactions: [], readBy: [], editable: false,
            membershipSubject: nil)
        return TimelineRow(
            item: item, view: view, senderName: "AgentPod", senderShort: "AgentPod",
            senderInitial: "A", membershipVerb: nil, replyQuote: nil, canReplyOrReact: true,
            replyPreview: "Transcript: Can you move the review to Thursday?")
    }

    @Test("a long transcript opens at six lines and says how to see the rest")
    func clamp() {
        #expect(VoiceTranscriptPresentation.clampedLines == 6)
        #expect(VoiceTranscriptPresentation.toggleLabel(expanded: false) == "Show more")
        #expect(VoiceTranscriptPresentation.toggleLabel(expanded: true) == "Show less")
    }

    @Test("a transcript does not group with the agent's messages around it")
    func doesNotGroup() {
        // It belongs to the note, not to the agent that posted it: a message
        // from that agent after it must still carry its own header.
        let transcript = Self.row(
            id: "$1", view: .voiceTranscript(transcript: Self.transcript, onOwnNote: true), at: 1_000)
        let message = Self.row(id: "$2", view: .bubble(muted: false, blocks: []), at: 2_000)
        #expect(!TimelineGrouping.continuesRun(message, after: transcript))
        #expect(!TimelineGrouping.continuesRun(transcript, after: message))
    }
}
