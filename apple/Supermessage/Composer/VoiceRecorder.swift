import AVFoundation
import Foundation
import Observation
import SupermessageFFI
import SupermessageKit

/// Records a voice message — Opus, in a CAF file — in this app's temporary
/// directory, with its length and a waveform.
///
/// What happens next is the ordinary attachment path — the composer stages
/// the file with `session.staged.stage` — plus one step: the staged file is
/// marked as a voice message with the length and waveform taken here. Sent
/// as a bare file, Element drew a file row and Hermes never transcribed it,
/// because both only treat audio flagged as voice (MSC3245) as a voice
/// message (2026-09-26).
@MainActor
@Observable
final class VoiceRecorder {
    enum Phase: Equatable {
        case idle
        /// `held` when the reader is holding the mic down: releasing sends.
        /// Otherwise it was tapped, and the pill's own buttons stop it.
        case recording(startedAt: Date, held: Bool)
    }

    private(set) var phase: Phase = .idle
    /// Why recording could not start — shown in the composer until the next
    /// attempt.
    private(set) var failure: String?

    private var recorder: AVAudioRecorder?
    private var file: URL?
    /// dBFS readings, sampled while recording.
    private var decibels: [Float] = []
    private var meter: Timer?

    /// A finished recording.
    struct Recording {
        let url: URL
        let durationMs: UInt64
        /// At most `voiceRecordingSpec().waveformBins` levels between 0 and 1,
        /// oldest first.
        let waveform: [Float]
    }

    /// The recorder's numbers — meter interval, waveform bins, the shortest
    /// message — are the core's, so every client keeps the same ones.
    private static let spec = voiceRecordingSpec()
    /// Between asking to start and recording. The first time, that gap is
    /// the permission prompt — long enough for a held finger to lift.
    private var starting = false
    /// The finger lifted during that gap: throw the recording away the
    /// moment it starts, rather than leave a "held" recording nobody holds.
    private var abandonStart = false

    var isRecording: Bool { phase != .idle }

    var isHeld: Bool {
        if case .recording(_, true) = phase { return true }
        return false
    }

    var startedAt: Date? {
        if case let .recording(startedAt, _) = phase { return startedAt }
        return nil
    }

    /// Start recording. Asks for the microphone the first time.
    @discardableResult
    func start(held: Bool) async -> Bool {
        guard phase == .idle, !starting else { return false }
        starting = true
        abandonStart = false
        defer { starting = false }
        failure = nil
        guard await AVAudioApplication.requestRecordPermission() else {
            failure = "Microphone access is off. Turn it on in Settings to record."
            return false
        }
        do {
            let audio = AVAudioSession.sharedInstance()
            try audio.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetooth])
            try audio.setActive(true)

            // Its own folder, so the name can be plain: the recipient sees
            // the filename, and "Voice message" says what it is where a UUID
            // would not.
            let folder = FileManager.default.temporaryDirectory
                .appendingPathComponent(UUID().uuidString, isDirectory: true)
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            let url = folder.appendingPathComponent(VoiceRecordingFormat.fileName)

            // Opus in CAF, which the core sends as Ogg/Opus — see
            // `VoiceRecordingFormat` for why not AAC any more.
            let recorder = try AVAudioRecorder(url: url, settings: VoiceRecordingFormat.settings)
            recorder.isMeteringEnabled = true
            guard recorder.record() else {
                failure = "Couldn't start recording."
                deactivate()
                return false
            }
            self.recorder = recorder
            file = url
            decibels = []
            startMetering()
            phase = .recording(startedAt: Date(), held: held)
            if abandonStart {
                abandonStart = false
                cancel()
                return false
            }
            return true
        } catch {
            failure = "Couldn't start recording."
            deactivate()
            return false
        }
    }

    /// Stop, and hand back the recording — or `nil` when it was too short to
    /// be anything, in which case it is deleted.
    func finish() -> Recording? {
        guard let recorder, let file else { return nil }
        let duration = recorder.currentTime
        let sampled = decibels
        recorder.stop()
        reset()
        let durationMs = UInt64((duration * 1000).rounded())
        // Shorter than this is a slip of the thumb, not a message.
        guard voiceLongEnough(durationMs: durationMs) else {
            try? FileManager.default.removeItem(at: file.deletingLastPathComponent())
            return nil
        }
        return Recording(
            url: file,
            durationMs: durationMs,
            waveform: voiceWaveform(decibels: sampled, bins: Self.spec.waveformBins))
    }

    private func startMetering() {
        meter?.invalidate()
        meter = Timer.scheduledTimer(withTimeInterval: TimeInterval(Self.spec.meterIntervalMs) / 1000, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, let recorder = self.recorder else { return }
                recorder.updateMeters()
                self.decibels.append(recorder.averagePower(forChannel: 0))
            }
        }
    }

    /// A hold ended before recording had begun — see ``abandonStart``.
    func abandonPendingStart() {
        if starting { abandonStart = true }
    }

    /// Stop and throw the recording away.
    func cancel() {
        recorder?.stop()
        if let file { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        reset()
    }

    private func reset() {
        meter?.invalidate()
        meter = nil
        recorder = nil
        file = nil
        phase = .idle
        deactivate()
    }

    private func deactivate() {
        // Hands the audio route back, so music the reader paused to record
        // resumes rather than staying silent.
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}
