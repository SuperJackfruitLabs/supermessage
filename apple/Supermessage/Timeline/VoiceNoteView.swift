import SupermessageFFI
import SupermessageKit
import SwiftUI

/// A voice note, or any other audio message, as a player: a round play
/// button, the note's bars filling as it plays, and its length — the elapsed
/// time while it is underway.
///
/// Drawn from `ItemView.audio`. Everything about *what* the note is arrives
/// decided on `AudioView` — voice or not, the length already formatted, the
/// bars already normalised, the sentence VoiceOver reads. Everything about
/// *where it is* comes from `VoicePlayer`, which plays one note at a time.
///
/// **An audio file that is not a voice note** — music, a recording someone
/// attached — gets the same player under its file name. It is audio either
/// way, and a file row that could only be saved was the one thing the reader
/// could not do with it: listen.
///
/// **No animation on the bars.** They move because the note is playing, ten
/// times a second, and that is the whole of their motion; nothing eases, so
/// Reduce Motion has nothing to take away. The glyph swap is instant too.
struct VoiceNoteBubble: View {
    let audio: AudioView
    /// `nil` for a note still on its way to the server: there is nothing to
    /// fetch yet, so it cannot be played.
    let eventId: String?
    let isOwn: Bool
    let player: VoicePlayer

    @Environment(\.rendersStill) private var rendersStill

    private var state: VoicePlayer.State {
        guard let eventId else { return .idle }
        return player.state(for: eventId)
    }

    private var failure: String? {
        if case let .failed(message) = state { return message }
        return nil
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if !audio.isVoice {
                fileTitle
            }
            HStack(spacing: 10) {
                PlayButton(state: state, enabled: eventId != nil, rendersStill: rendersStill) {
                    if let eventId { player.toggle(eventId) }
                }
                WaveformBars(
                    levels: audio.waveform,
                    progress: state.progress,
                    played: Theme.accent,
                    unplayed: isOwn ? Theme.accent.opacity(0.35) : Theme.contentFaint.opacity(0.55)
                ) { fraction in
                    if let eventId { player.seek(eventId, to: fraction) }
                }
                .frame(height: 28)
                .frame(minWidth: 96)
                .allowsHitTesting(eventId != nil)
                time
            }
            if let failure {
                Text(failure)
                    .metaFace()
                    .foregroundStyle(Theme.danger)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .background(
            isOwn ? AnyShapeStyle(Theme.accent.opacity(0.13)) : AnyShapeStyle(Theme.surfaceRaised),
            in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .frame(maxWidth: 300, alignment: isOwn ? .trailing : .leading)
        // One element for VoiceOver: "Voice message, 7 seconds", its state,
        // and what a double-tap does. Swiping up and down moves five seconds.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(audio.accessibilityLabel)
        .accessibilityValue(VoiceNotePresentation.accessibilityValue(state: state))
        .accessibilityHint(eventId == nil ? "" : VoiceNotePresentation.accessibilityHint(state: state))
        .accessibilityAddTraits(.isButton)
        .accessibilityAction {
            if let eventId { player.toggle(eventId) }
        }
        .accessibilityAdjustableAction { direction in
            guard let eventId else { return }
            switch direction {
            case .increment: player.skip(eventId, by: 5)
            case .decrement: player.skip(eventId, by: -5)
            @unknown default: break
            }
        }
    }

    /// An audio file's name and size, above its player.
    private var fileTitle: some View {
        HStack(spacing: 6) {
            Text(audio.title)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(Theme.content)
                .lineLimit(1)
                .truncationMode(.middle)
            if let size = audio.size {
                Text(ByteCountFormatter.string(fromByteCount: Int64(size), countStyle: .file))
                    .metaFace()
                    .foregroundStyle(Theme.contentFaint)
                    .fixedSize()
            }
        }
    }

    /// The length at rest, the clock while it plays. Tabular figures, so the
    /// row does not shimmy as the seconds change.
    private var time: some View {
        Text(VoiceNotePresentation.timeLabel(state: state, lengthLabel: audio.lengthLabel))
            .metaFace()
            .monospacedDigit()
            .foregroundStyle(state.isUnderway ? Theme.accent : Theme.contentMuted)
            .fixedSize()
    }
}

/// The round button. A glyph in a control of fixed size is an icon that must
/// not grow (design language §7), so it is sized in points, not by text
/// style.
private struct PlayButton: View {
    let state: VoicePlayer.State
    let enabled: Bool
    let rendersStill: Bool
    let action: () -> Void

    static let diameter: CGFloat = 36

    var body: some View {
        Button(action: action) {
            ZStack {
                Circle().fill(fill)
                glyph
            }
            .frame(width: Self.diameter, height: Self.diameter)
            .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.5)
        .sensoryFeedback(.selection, trigger: state.isPlaying)
    }

    private var fill: Color {
        if case .failed = state { return Theme.danger }
        return Theme.accent
    }

    @ViewBuilder private var glyph: some View {
        switch state {
        case .loading where !rendersStill:
            ProgressView()
                .controlSize(.small)
                .tint(Theme.accentContent)
        case .loading:
            // A still frame has no spinner: the settled rendering of "on its
            // way" is the dots, which a reader with Reduce Motion also sees
            // in effect.
            symbol("ellipsis")
        case .playing:
            symbol("pause.fill")
        case .failed:
            symbol("arrow.clockwise")
        default:
            // Nudged right: a triangle's visual centre is left of its box's.
            symbol("play.fill").offset(x: 1.5)
        }
    }

    private func symbol(_ name: String) -> some View {
        Image(systemName: name)
            .font(.system(size: 15, weight: .bold))
            .foregroundStyle(Theme.accentContent)
    }
}

/// The note's bars, played ones in the accent. Drawn in a `Canvas` — a row
/// of sixty views redrawn ten times a second is exactly what a list of these
/// cannot afford.
///
/// Tapping along it seeks. A tap, not a drag: the row sits in a vertically
/// scrolling list, and a drag recogniser that claims the touch at zero
/// distance would stop the list scrolling whenever a thumb landed on a note.
private struct WaveformBars: View {
    let levels: [Float]?
    let progress: Double
    let played: Color
    let unplayed: Color
    let onSeek: (Double) -> Void

    @State private var width: CGFloat = 0

    var body: some View {
        Canvas { context, size in
            let count = VoiceNotePresentation.barCount(width: Double(size.width))
            let bars = VoiceNotePresentation.bars(levels, count: count)
            let filled = VoiceNotePresentation.playedBars(progress: progress, count: count)
            let barWidth = CGFloat(VoiceNotePresentation.barWidth)
            let step = barWidth + CGFloat(VoiceNotePresentation.barGap)
            for (index, level) in bars.enumerated() {
                let height: CGFloat = max(2, size.height * CGFloat(level))
                let rect = CGRect(
                    x: CGFloat(index) * step,
                    y: (size.height - height) / 2,
                    width: barWidth,
                    height: height)
                context.fill(
                    Path(roundedRect: rect, cornerRadius: barWidth / 2),
                    with: .color(index < filled ? played : unplayed))
            }
        }
        .contentShape(Rectangle())
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
        .onTapGesture { location in
            guard width > 0 else { return }
            onSeek(Double(min(max(0, location.x / width), 1)))
        }
    }
}

#if DEBUG
// Every state a note can be in, both sides of the conversation.
#Preview("Voice notes") {
    PreviewGround(width: 390) {
        VoiceNoteGallery()
    }
}

#Preview("Voice notes, dark") {
    PreviewGround(width: 390) {
        VoiceNoteGallery()
    }
    .preferredColorScheme(.dark)
}

// Your note, and the hub's transcript of it directly under it.
#Preview("Voice note with transcript") {
    PreviewGround(width: 390) {
        VStack(alignment: .leading, spacing: 0) {
            TimelineRowView(
                row: PreviewFixtures.ownVoiceNote, media: PreviewFixtures.mediaCache(),
                faces: PreviewFixtures.faceCache(), voice: PreviewFixtures.voicePlayer())
            TimelineRowView(
                row: PreviewFixtures.transcriptRow(PreviewFixtures.transcriptShort, onOwnNote: true),
                media: PreviewFixtures.mediaCache(), faces: PreviewFixtures.faceCache())
            TimelineRowView(
                row: PreviewFixtures.colleagueVoiceNote, media: PreviewFixtures.mediaCache(),
                faces: PreviewFixtures.faceCache(), voice: PreviewFixtures.voicePlayer())
            TimelineRowView(
                row: PreviewFixtures.transcriptRow(PreviewFixtures.transcriptShort, onOwnNote: false),
                media: PreviewFixtures.mediaCache(), faces: PreviewFixtures.faceCache())
        }
    }
}

// Bigger text: the clock and the file name grow, the button does not.
#Preview("Voice notes, accessibility3") {
    PreviewGround(width: 390) {
        VoiceNoteGallery()
    }
    .dynamicTypeSize(.accessibility3)
}

/// One player per sample: the real one holds a single note at a time, and a
/// gallery wants every state on screen at once.
private struct VoiceNoteGallery: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach(PreviewFixtures.voiceNoteStates, id: \.name) { sample in
                VoiceNoteBubble(
                    audio: sample.audio, eventId: sample.eventId, isOwn: sample.isOwn,
                    player: PreviewFixtures.voicePlayer(sample.eventId, sample.state)
                )
                .frame(maxWidth: .infinity, alignment: sample.isOwn ? .trailing : .leading)
            }
        }
        // The loading note's spinner never settles; its still frame is the
        // dots, so the snapshot does not depend on when the shutter opened.
        .environment(\.rendersStill, true)
    }
}
#endif
