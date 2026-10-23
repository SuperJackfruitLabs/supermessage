import AVFoundation
import Foundation
import SupermessageFFI

/// What the composer records a voice note as: **Opus, in CAF**.
///
/// A Matrix voice message is Ogg/Opus (MSC3245), and Element X on iOS plays
/// nothing else — the AAC `.m4a` notes this app sent until 0.0.13 showed there
/// as voice messages and never played. `AVAudioRecorder` encodes Opus but
/// writes it only into CAF, so that is what it records; the core moves the
/// same packets into Ogg pages on the way out (`core::opus_container`) and
/// reads the note's exact length from them. Nothing is transcoded.
///
/// The numbers — 48 kHz mono at 24 kbit/s, MSC3245's suggestion: about 3 KB a
/// second, a fifth of what the AAC recordings cost, at a quality made for
/// speech — are the core's (`voiceRecordingSpec()`), so every client records
/// the same note.
public enum VoiceRecordingFormat {
    /// The recorder's file. The core sends it as `Voice message.ogg`.
    public static var fileName: String { "\(voiceRecordingSpec().fileStem).caf" }

    /// Computed rather than stored: `[String: Any]` is not `Sendable`, so a
    /// stored static could not be shared across isolation domains.
    public static var settings: [String: Any] {
        let spec = voiceRecordingSpec()
        return [
            AVFormatIDKey: Int(kAudioFormatOpus),
            AVSampleRateKey: Int(spec.sampleRate),
            AVNumberOfChannelsKey: Int(spec.channels),
            AVEncoderBitRateKey: Int(spec.bitRate),
        ]
    }
}
