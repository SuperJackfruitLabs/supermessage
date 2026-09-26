import SupermessageFFI
import SupermessageKit
import SwiftUI

/// What a voice note said, drawn directly under the note.
///
/// Drawn from `ItemView.voiceTranscript`, which `core::voice_transcript`
/// parsed off the AgentPod hub's transcript notice. The notice replies to the
/// note, and the transcript belongs to the note rather than to the agent that
/// posted it — so there is no sender header here, and the block sits on the
/// **note's** side: trailing under your own note, leading under anyone
/// else's (`onOwnNote`, decided by the core from the reply's parent).
///
/// **Quieter than a message.** No bubble: a small caption ("Transcript · hi ·
/// 0:42") and the words in the secondary rank, set off by a hairline rule the
/// way a reply's quote is — a transcript is a quote of the note. Everything is
/// text: the transcript came from whoever sent the notice.
///
/// A long transcript opens at `VoiceTranscriptPresentation.clampedLines`
/// lines with "Show more". Whether it is long is *measured* — the full text
/// against the clamped text at the width it is given — rather than guessed
/// from a character count, so a short transcript at a large text size still
/// gets the disclosure and a long one on an iPad does not get a pointless one.
struct VoiceTranscriptView: View {
    let transcript: VoiceNoteTranscript
    let onOwnNote: Bool
    @State private var expanded: Bool
    /// The text's height unclamped, and at the clamp. Measured from two
    /// hidden copies, so the answer does not change when the reader opens it.
    @State private var fullHeight: CGFloat = 0
    @State private var clampedHeight: CGFloat = 0

    init(transcript: VoiceNoteTranscript, onOwnNote: Bool, expanded: Bool = false) {
        self.transcript = transcript
        self.onOwnNote = onOwnNote
        // `expanded` exists for previews: the open transcript is otherwise
        // reachable only through a tap.
        _expanded = State(initialValue: expanded)
    }

    private var isLong: Bool { fullHeight > clampedHeight + 1 }

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            caption
            words
            if isLong {
                // No animation: this is a self-sizing cell in the timeline's
                // collection view, and an animated resize there re-lays its
                // neighbours every frame.
                Button {
                    expanded.toggle()
                } label: {
                    Text(VoiceTranscriptPresentation.toggleLabel(expanded: expanded))
                        .font(ThemeType.ui.weight(.semibold))
                        .foregroundStyle(Theme.accent)
                }
                .buttonStyle(.borderless)
                .accessibilityHint(expanded ? "Shows the first lines only" : "Shows the whole transcript")
            }
        }
        .padding(.leading, 10)
        .overlay(alignment: .leading) {
            // An overlay, not an HStack sibling: a `Rectangle` in a stack takes
            // every point of height a self-sizing cell offers (see
            // `ReplyQuote`).
            Rectangle().fill(Theme.border).frame(width: 2)
        }
        // Aligned inside the measure too, as a bubble is: a measure frame
        // is as wide as it is offered, so aligning only the outer frame left
        // a short transcript under your own note on the wrong side.
        .frame(
            maxWidth: onOwnNote ? MessageMeasure.own : MessageMeasure.card,
            alignment: onOwnNote ? .trailing : .leading)
        .frame(maxWidth: .infinity, alignment: onOwnNote ? .trailing : .leading)
        // Close under the note it belongs to, not a turn's twelve points away.
        .padding(.top, 4)
        .padding(.bottom, 2)
    }

    /// "Transcript · hi · 0:42", beside the glyph the note's own row carries.
    private var caption: some View {
        Label {
            Text(transcript.caption)
        } icon: {
            Image(systemName: "waveform")
        }
        .labelStyle(TightLabel())
        .metaFace()
        .foregroundStyle(Theme.contentFaint)
        .accessibilityHidden(true)
    }

    private var words: some View {
        Text(transcript.text)
            .font(.subheadline)
            .foregroundStyle(Theme.contentMuted)
            .lineLimit(expanded ? nil : VoiceTranscriptPresentation.clampedLines)
            .fixedSize(horizontal: false, vertical: true)
            .textSelection(.enabled)
            .background { measurements }
            .accessibilityLabel(transcript.accessibilityLabel)
    }

    /// The same text twice, invisible, at the width the visible copy is
    /// given: once whole and once clamped. Their heights differing is what
    /// "long" means.
    private var measurements: some View {
        ZStack(alignment: .topLeading) {
            Text(transcript.text)
                .font(.subheadline)
                .fixedSize(horizontal: false, vertical: true)
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { fullHeight = $0 }
            Text(transcript.text)
                .font(.subheadline)
                .lineLimit(VoiceTranscriptPresentation.clampedLines)
                .fixedSize(horizontal: false, vertical: true)
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { clampedHeight = $0 }
        }
        .hidden()
        .accessibilityHidden(true)
    }
}

/// A label with its glyph close to its words, as the meta line's spacing is.
private struct TightLabel: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 4) {
            configuration.icon.imageScale(.small)
            configuration.title
        }
    }
}

#if DEBUG
// Your own voice note, and the hub's transcript of it directly under it on
// your side — not a centred system line from the agent.
#Preview("Transcript under own note") {
    PreviewGround(width: 390) {
        VStack(alignment: .leading, spacing: 0) {
            TimelineRowView(
                row: PreviewFixtures.ownVoiceNote, media: PreviewFixtures.mediaCache(),
                faces: PreviewFixtures.faceCache())
            TimelineRowView(
                row: PreviewFixtures.transcriptRow(PreviewFixtures.transcriptShort, onOwnNote: true),
                media: PreviewFixtures.mediaCache(), faces: PreviewFixtures.faceCache())
        }
    }
}

// The same, in dark.
#Preview("Transcript under own note, dark") {
    PreviewGround(width: 390) {
        VStack(alignment: .leading, spacing: 0) {
            TimelineRowView(
                row: PreviewFixtures.ownVoiceNote, media: PreviewFixtures.mediaCache(),
                faces: PreviewFixtures.faceCache())
            TimelineRowView(
                row: PreviewFixtures.transcriptRow(PreviewFixtures.transcriptShort, onOwnNote: true),
                media: PreviewFixtures.mediaCache(), faces: PreviewFixtures.faceCache())
        }
    }
    .preferredColorScheme(.dark)
}

// A two-minute note: the transcript opens at six lines, with Show more.
#Preview("Transcript, long") {
    PreviewGround(width: 390) {
        VoiceTranscriptView(transcript: PreviewFixtures.transcriptLong, onOwnNote: false)
    }
}

// The same, opened.
#Preview("Transcript, long, open") {
    PreviewGround(width: 390) {
        VoiceTranscriptView(
            transcript: PreviewFixtures.transcriptLong, onOwnNote: false, expanded: true)
    }
}

// A colleague's note, and a transcript the hub gave no language or length for:
// the caption says only "Transcript".
#Preview("Transcript, bare, under a colleague's note") {
    PreviewGround(width: 390) {
        VStack(alignment: .leading, spacing: 0) {
            TimelineRowView(
                row: PreviewFixtures.colleagueVoiceNote, media: PreviewFixtures.mediaCache(),
                faces: PreviewFixtures.faceCache())
            TimelineRowView(
                row: PreviewFixtures.transcriptRow(PreviewFixtures.transcriptBare, onOwnNote: false),
                media: PreviewFixtures.mediaCache(), faces: PreviewFixtures.faceCache())
        }
    }
}

// Devanagari, which sits taller than Latin: its marks must not clip.
#Preview("Transcript, Hindi") {
    PreviewGround(width: 390) {
        VStack(alignment: .leading, spacing: 0) {
            TimelineRowView(
                row: PreviewFixtures.ownVoiceNote, media: PreviewFixtures.mediaCache(),
                faces: PreviewFixtures.faceCache())
            TimelineRowView(
                row: PreviewFixtures.transcriptRow(PreviewFixtures.transcriptHindi, onOwnNote: true),
                media: PreviewFixtures.mediaCache(), faces: PreviewFixtures.faceCache())
        }
    }
}
#endif
