import AVFoundation
import Foundation
import Observation
import SupermessageFFI

/// Something that plays one file: `AVAudioPlayer` in the app, a fake in tests.
@MainActor
public protocol AudioPlaying: AnyObject {
    /// Seconds, exact once the file is open.
    var duration: TimeInterval { get }
    var currentTime: TimeInterval { get set }
    var isPlaying: Bool { get }
    /// Called once when playback reaches the end on its own.
    var onFinish: (() -> Void)? { get set }
    func play() -> Bool
    func pause()
    func stop()
}

/// Plays voice notes and audio messages — **one at a time**, across the app.
///
/// One player, not one per row: starting a note stops whichever was playing,
/// the way every messenger behaves, and a row scrolled out of the list (its
/// view torn down) does not take the playback with it or leave it running
/// with nothing on screen to pause it.
///
/// ## What it does not decide
///
/// What a note *is* — voice or not, how long, what its bars look like, what a
/// screen reader calls it — arrives decided on `AudioView`. What bytes to play
/// arrives from the core too: `mediaAudio(opusInCaf: true)` hands back CAF for
/// an Ogg/Opus note, because AVFoundation plays Opus from nothing else. This
/// owns only playback: which note, where in it, and whether it is going.
///
/// ## The audio session
///
/// `.playback`, like WhatsApp and Signal: a voice note plays with the ring/
/// silent switch on silent. A reader who pressed play meant to hear it, and a
/// note that silently "plays" with the switch down reads as broken. `.spokenAudio`
/// mode, so a navigation app's prompt pauses the note rather than ducking it
/// under. The session is activated on play and released — with
/// `notifyOthersOnDeactivation`, so the music the reader paused comes back —
/// on pause, stop and finish.
@MainActor
@Observable
public final class VoicePlayer {
    /// Where one note is.
    public enum State: Equatable, Sendable {
        /// Not started, or played to the end: drawn with its length.
        case idle
        /// Fetching (and, for Ogg, remuxing) the file.
        case loading
        case playing(elapsedMs: UInt64, durationMs: UInt64)
        case paused(elapsedMs: UInt64, durationMs: UInt64)
        /// Could not be fetched or opened. Tapping tries again.
        case failed(String)

        /// Between loading and the end: the clock shows elapsed time rather
        /// than the length.
        public var isUnderway: Bool {
            switch self {
            case .playing: return true
            case let .paused(elapsed, _): return elapsed > 0
            default: return false
            }
        }

        public var isPlaying: Bool {
            if case .playing = self { return true }
            return false
        }

        /// How far through, 0…1.
        public var progress: Double {
            switch self {
            case let .playing(elapsed, duration), let .paused(elapsed, duration):
                guard duration > 0 else { return 0 }
                return min(1, Double(elapsed) / Double(duration))
            default:
                return 0
            }
        }
    }

    /// The note that has the player, if any.
    public private(set) var activeEventId: String?
    private var activeState: State = .idle
    /// Notes that failed, by event id. Kept after another note starts, so a
    /// failure does not quietly turn back into a play button that fails again.
    private var failures: [String: String] = [:]

    private let client: any AudioFetching
    private let makePlayer: @MainActor (Data, String) throws -> any AudioPlaying
    private let activateSession: @MainActor () -> Void
    private let releaseSession: @MainActor () -> Void
    private var player: (any AudioPlaying)?
    private var ticker: Task<Void, Never>?
    private var loading: Task<Void, Never>?
    /// How often the clock and the bars move while a note plays.
    static let tick: Duration = .milliseconds(100)

    public init(
        client: any AudioFetching,
        makePlayer: @escaping @MainActor (Data, String) throws -> any AudioPlaying = VoicePlayer.avPlayer,
        activateSession: @escaping @MainActor () -> Void = VoicePlayer.activatePlaybackSession,
        releaseSession: @escaping @MainActor () -> Void = VoicePlayer.releasePlaybackSession
    ) {
        self.client = client
        self.makePlayer = makePlayer
        self.activateSession = activateSession
        self.releaseSession = releaseSession
    }

    /// Where `eventId` is. Every note but the active one is idle, or failed.
    public func state(for eventId: String) -> State {
        if eventId == activeEventId { return activeState }
        if let failure = failures[eventId] { return .failed(failure) }
        return .idle
    }

    /// Play, pause, resume or retry — whichever a tap on the button means.
    public func toggle(_ eventId: String) {
        guard eventId == activeEventId else {
            start(eventId, at: nil)
            return
        }
        switch activeState {
        case .playing:
            pause()
        case .paused, .idle:
            resume()
        case .loading:
            break
        case .failed:
            start(eventId, at: nil)
        }
    }

    /// Jump to `fraction` (0…1) of `eventId`, starting it there if it was not
    /// the note playing.
    public func seek(_ eventId: String, to fraction: Double) {
        let fraction = min(1, max(0, fraction))
        guard eventId == activeEventId, let player else {
            start(eventId, at: fraction)
            return
        }
        player.currentTime = player.duration * fraction
        publish(from: player, playing: player.isPlaying)
    }

    /// Move `eventId` by `seconds`, for VoiceOver's adjustable action.
    public func skip(_ eventId: String, by seconds: TimeInterval) {
        guard eventId == activeEventId, let player, player.duration > 0 else { return }
        seek(eventId, to: (player.currentTime + seconds) / player.duration)
    }

    /// Stop whatever is playing and let go of it. For leaving a room, going
    /// to the background, and starting a recording.
    public func stop() {
        loading?.cancel()
        loading = nil
        stopTicking()
        if let player {
            player.onFinish = nil
            player.stop()
            releaseSession()
        }
        player = nil
        activeEventId = nil
        activeState = .idle
    }

    // MARK: - Transitions

    private func start(_ eventId: String, at fraction: Double?) {
        stop()
        failures[eventId] = nil
        activeEventId = eventId
        activeState = .loading
        loading = Task { [weak self] in
            await self?.load(eventId, at: fraction)
        }
    }

    private func load(_ eventId: String, at fraction: Double?) async {
        let audio: PlayableAudio?
        do {
            audio = try await client.mediaAudio(eventId: eventId, opusInCaf: true)
        } catch {
            audio = nil
        }
        // A tap on another note, or a stop, while this one was fetching.
        guard !Task.isCancelled, activeEventId == eventId else { return }
        guard let audio else {
            fail(eventId, "Couldn't load this voice message.")
            return
        }
        do {
            let player = try makePlayer(audio.data, audio.fileExtension)
            self.player = player
            player.onFinish = { [weak self] in self?.finished(eventId) }
            if let fraction { player.currentTime = player.duration * fraction }
            activateSession()
            guard player.play() else {
                self.player = nil
                releaseSession()
                fail(eventId, "Couldn't play this audio.")
                return
            }
            publish(from: player, playing: true)
            startTicking()
        } catch {
            fail(eventId, "This audio can't be played here.")
        }
    }

    private func pause() {
        guard let player else { return }
        player.pause()
        stopTicking()
        releaseSession()
        publish(from: player, playing: false)
    }

    private func resume() {
        guard let player else { return }
        activateSession()
        guard player.play() else {
            releaseSession()
            return
        }
        publish(from: player, playing: true)
        startTicking()
    }

    /// Played to the end: back to its length, ready to play again without
    /// fetching it again.
    private func finished(_ eventId: String) {
        guard eventId == activeEventId, let player else { return }
        stopTicking()
        player.currentTime = 0
        releaseSession()
        activeState = .paused(elapsedMs: 0, durationMs: Self.ms(player.duration))
    }

    private func fail(_ eventId: String, _ message: String) {
        failures[eventId] = message
        if eventId == activeEventId {
            activeEventId = nil
            activeState = .idle
        }
    }

    private func publish(from player: any AudioPlaying, playing: Bool) {
        let elapsed = Self.ms(player.currentTime)
        let duration = Self.ms(player.duration)
        activeState = playing
            ? .playing(elapsedMs: elapsed, durationMs: duration)
            : .paused(elapsedMs: elapsed, durationMs: duration)
    }

    private func startTicking() {
        stopTicking()
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: VoicePlayer.tick)
                guard let self, !Task.isCancelled else { return }
                self.tickOnce()
            }
        }
    }

    /// Until the note being loaded is playing, failed or abandoned. For tests,
    /// which must not guess how many scheduler turns a fetch takes.
    func settled() async {
        await loading?.value
    }

    /// One tick of the clock. Also how a pause nobody asked for — a phone
    /// call, the headphones coming out — reaches the row.
    func tickOnce() {
        guard let player, activeState.isPlaying else { return }
        publish(from: player, playing: player.isPlaying)
        if !player.isPlaying {
            stopTicking()
            releaseSession()
        }
    }

    private func stopTicking() {
        ticker?.cancel()
        ticker = nil
    }

    private static func ms(_ seconds: TimeInterval) -> UInt64 {
        guard seconds.isFinite, seconds > 0 else { return 0 }
        return UInt64((seconds * 1000).rounded())
    }

    // MARK: - The real thing

    /// `AVAudioPlayer` over the bytes, told what container they are in.
    public static func avPlayer(data: Data, fileExtension: String) throws -> any AudioPlaying {
        try AVPlayerBox(data: data, fileExtension: fileExtension)
    }

    public static func activatePlaybackSession() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .spokenAudio)
        try? session.setActive(true)
    }

    public static func releasePlaybackSession() {
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

#if DEBUG
    /// Put a note in a state without playing anything, for previews.
    public func preview(_ eventId: String, _ state: State) {
        if case let .failed(message) = state {
            failures[eventId] = message
            return
        }
        activeEventId = eventId
        activeState = state
    }
#endif
}

/// `AVAudioPlayer`, and its delegate, behind `AudioPlaying`.
@MainActor
private final class AVPlayerBox: NSObject, AudioPlaying, @preconcurrency AVAudioPlayerDelegate {
    private let player: AVAudioPlayer
    var onFinish: (() -> Void)?

    init(data: Data, fileExtension: String) throws {
        let hint: String? = switch fileExtension.lowercased() {
        case "caf": AVFileType.caf.rawValue
        case "m4a", "mp4": AVFileType.m4a.rawValue
        case "mp3": AVFileType.mp3.rawValue
        case "wav": AVFileType.wav.rawValue
        case "aiff": AVFileType.aiff.rawValue
        default: nil
        }
        player = try AVAudioPlayer(data: data, fileTypeHint: hint)
        super.init()
        player.delegate = self
        guard player.prepareToPlay() else {
            throw CocoaError(.fileReadCorruptFile)
        }
    }

    var duration: TimeInterval { player.duration }
    var currentTime: TimeInterval {
        get { player.currentTime }
        set { player.currentTime = newValue }
    }
    var isPlaying: Bool { player.isPlaying }
    func play() -> Bool { player.play() }
    func pause() { player.pause() }
    func stop() { player.stop() }

    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        onFinish?()
    }
}
