import AVFoundation
import Foundation
import Observation

/// Records a voice message to an `.m4a` in this app's temporary directory.
///
/// That is the whole of it. What happens next is the ordinary attachment
/// path: the composer stages the file with `session.staged.stage`, the core
/// sniffs `audio/mp4` from the content and sends `m.audio`. Nothing here
/// knows about Matrix, and nothing about Matrix had to change for voice.
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
    /// Between asking to start and recording. The first time, that gap is
    /// the permission prompt — long enough for a held finger to lift.
    private var starting = false
    /// The finger lifted during that gap: throw the recording away the
    /// moment it starts, rather than leave a "held" recording nobody holds.
    private var abandonStart = false

    /// Shorter than this is a slip of the thumb, not a message.
    static let shortest: TimeInterval = 0.6

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
            // the filename, and "Voice message.m4a" says what it is where a
            // UUID would not.
            let folder = FileManager.default.temporaryDirectory
                .appendingPathComponent(UUID().uuidString, isDirectory: true)
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            let url = folder.appendingPathComponent("Voice message.m4a")

            let settings: [String: Any] = [
                AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
                AVSampleRateKey: 44_100,
                AVNumberOfChannelsKey: 1,
                AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue,
            ]
            let recorder = try AVAudioRecorder(url: url, settings: settings)
            guard recorder.record() else {
                failure = "Couldn't start recording."
                deactivate()
                return false
            }
            self.recorder = recorder
            file = url
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
    func finish() -> URL? {
        guard let recorder, let file else { return nil }
        let duration = recorder.currentTime
        recorder.stop()
        reset()
        guard duration >= Self.shortest else {
            try? FileManager.default.removeItem(at: file.deletingLastPathComponent())
            return nil
        }
        return file
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
