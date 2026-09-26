#if DEBUG
import Foundation
import SupermessageFFI
import SupermessageKit

/// Voice notes and audio files, as the core draws them, in every state the
/// player can put them in.
///
/// The labels are hand-written from `core::audio` and pinned there: the Rust
/// test `host_fixtures::audio_players` builds the same notes and asserts these
/// strings, so a wording change fails on the side that makes it.
extension PreviewFixtures {
    /// A speech-shaped waveform: bursts and pauses, not noise. Fixed, so a
    /// snapshot does not move.
    static func waveform(seed: Int = 1, bars: Int = 60) -> [Float] {
        // A fixed pseudo-random sequence (an LCG), shaped by a slow envelope
        // so it reads as words and breaths rather than as a repeating pattern.
        var state = UInt32(truncatingIfNeeded: seed &* 2_654_435_761 &+ 12_345)
        return (0..<bars).map { i in
            state = state &* 1_664_525 &+ 1_013_904_223
            let noise = Double(state >> 8) / Double(1 << 24)
            let x = Double(i) / Double(bars)
            let words = pow(sin(x * .pi * 5 + Double(seed)), 2)
            let level = 0.15 + 0.85 * words * (0.45 + 0.55 * noise)
            return Float(min(1, max(0.05, level)))
        }
    }

    /// A voice note's view, as `core::audio::audio_view` builds it.
    static func voiceAudio(
        ms: UInt64? = 7_400, label: String? = "0:07", spoken: String? = "7 seconds",
        size: UInt64? = 11_900, seed: Int = 1, waveform: Bool = true
    ) -> AudioView {
        AudioView(
            isVoice: true, durationMs: ms, lengthLabel: label,
            waveform: waveform ? PreviewFixtures.waveform(seed: seed) : nil,
            title: "Voice message", filename: "Voice message.ogg", size: size,
            mimetype: "audio/ogg", caption: nil,
            accessibilityLabel: spoken.map { "Voice message, \($0)" } ?? "Voice message")
    }

    /// An audio file somebody attached: a player under its name.
    static let standupRecording = AudioView(
        isVoice: false, durationMs: 185_000, lengthLabel: "3:05", waveform: nil,
        title: "Standup recording.mp3", filename: "Standup recording.mp3", size: 2_960_000,
        mimetype: "audio/mpeg", caption: nil,
        accessibilityLabel: "Audio, Standup recording.mp3, 3 minutes 5 seconds")

    /// One sample for the gallery: a note, its side, and where the player is.
    struct VoiceNoteSample {
        let name: String
        let audio: AudioView
        let isOwn: Bool
        let state: VoicePlayer.State
        var eventId: String? { "$voice-\(name)" }
    }

    static let voiceNoteStates: [VoiceNoteSample] = [
        .init(name: "own-idle", audio: voiceAudio(), isOwn: true, state: .idle),
        .init(
            name: "other-idle",
            audio: voiceAudio(ms: 12_000, label: "0:12", spoken: "12 seconds", seed: 5),
            isOwn: false, state: .idle),
        .init(
            name: "own-playing", audio: voiceAudio(), isOwn: true,
            state: .playing(elapsedMs: 3_200, durationMs: 7_400)),
        .init(
            name: "other-paused",
            audio: voiceAudio(ms: 12_000, label: "0:12", spoken: "12 seconds", seed: 5),
            isOwn: false, state: .paused(elapsedMs: 5_100, durationMs: 12_000)),
        .init(name: "other-loading", audio: voiceAudio(seed: 3), isOwn: false, state: .loading),
        .init(
            name: "other-failed", audio: voiceAudio(seed: 3), isOwn: false,
            state: .failed("Couldn't load this voice message.")),
        .init(
            name: "other-no-waveform",
            audio: voiceAudio(ms: 12_000, label: "0:12", spoken: "12 seconds", waveform: false),
            isOwn: false, state: .idle),
        .init(
            name: "own-long",
            audio: voiceAudio(ms: 299_000, label: "4:59", spoken: "4 minutes 59 seconds", seed: 9),
            isOwn: true, state: .idle),
        .init(name: "other-file", audio: standupRecording, isOwn: false, state: .idle),
    ]

    /// A player that plays nothing, for rows drawn outside a session.
    @MainActor static func voicePlayer() -> VoicePlayer {
        VoicePlayer(client: PreviewClient())
    }

    /// A player holding one note in `state`, for a still frame of it.
    @MainActor static func voicePlayer(_ eventId: String?, _ state: VoicePlayer.State) -> VoicePlayer {
        let player = VoicePlayer(client: PreviewClient())
        if let eventId { player.preview(eventId, state) }
        return player
    }
}
#endif
