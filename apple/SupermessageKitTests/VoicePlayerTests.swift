import AVFoundation
import Foundation
import SupermessageFFI
import Testing

@testable import SupermessageKit

/// One note at a time, and every transition a tap can cause.
@MainActor
struct VoicePlayerTests {
    /// Answers every note with a few bytes, or with nothing for the ids it
    /// was told to fail.
    struct Audio: AudioFetching {
        var missing: Set<String> = []
        func mediaAudio(eventId: String, opusInCaf: Bool) async throws -> PlayableAudio? {
            guard opusInCaf else { throw CancellationError() }
            if missing.contains(eventId) { return nil }
            return PlayableAudio(
                data: Data(eventId.utf8), mimetype: "audio/x-caf", fileExtension: "caf",
                durationMs: 7_400)
        }
    }

    /// A player with no audio behind it: time moves only when a test moves it.
    final class FakePlayer: AudioPlaying {
        let source: String
        var duration: TimeInterval = 7.4
        var currentTime: TimeInterval = 0
        var isPlaying = false
        var onFinish: (() -> Void)?
        var stopped = false
        init(source: String) { self.source = source }
        func play() -> Bool {
            isPlaying = true
            return true
        }
        func pause() { isPlaying = false }
        func stop() {
            isPlaying = false
            stopped = true
        }
    }

    final class Made {
        var players: [FakePlayer] = []
        var sessionActive = 0
        var refuse = false
    }

    func player(_ audio: Audio = Audio(), made: Made) -> VoicePlayer {
        VoicePlayer(
            client: audio,
            makePlayer: { data, ext in
                if made.refuse { throw CocoaError(.fileReadCorruptFile) }
                #expect(ext == "caf")
                let fake = FakePlayer(source: String(decoding: data, as: UTF8.self))
                made.players.append(fake)
                return fake
            },
            activateSession: { made.sessionActive += 1 },
            releaseSession: { made.sessionActive -= 1 })
    }

    /// Lets the load task run to where it waits for nothing.
    func settle() async {
        for _ in 0..<20 { await Task.yield() }
    }

    @Test("a tap loads the note, then plays it")
    func playsAfterLoading() async {
        let made = Made()
        let voice = player(made: made)
        voice.toggle("$a")
        #expect(voice.state(for: "$a") == .loading)
        await voice.settled()
        #expect(voice.state(for: "$a") == .playing(elapsedMs: 0, durationMs: 7_400))
        #expect(made.players.map(\.source) == ["$a"])
        #expect(made.sessionActive == 1, "the audio session is taken while it plays")
    }

    @Test("starting a second note stops the first")
    func oneAtATime() async {
        let made = Made()
        let voice = player(made: made)
        voice.toggle("$a")
        await voice.settled()
        voice.toggle("$b")
        await voice.settled()
        #expect(voice.state(for: "$a") == .idle)
        #expect(voice.state(for: "$b").isPlaying)
        #expect(made.players[0].stopped, "the first note's player was let go")
        #expect(!made.players[0].isPlaying)
        #expect(made.sessionActive == 1, "one note, one hold on the session")
    }

    @Test("a second tap pauses where it is, a third resumes from there")
    func pauseAndResume() async {
        let made = Made()
        let voice = player(made: made)
        voice.toggle("$a")
        await voice.settled()
        made.players[0].currentTime = 3.2
        voice.toggle("$a")
        #expect(voice.state(for: "$a") == .paused(elapsedMs: 3_200, durationMs: 7_400))
        #expect(made.sessionActive == 0, "a paused note gives the session back")
        voice.toggle("$a")
        #expect(voice.state(for: "$a") == .playing(elapsedMs: 3_200, durationMs: 7_400))
        #expect(made.players.count == 1, "resuming does not fetch it again")
    }

    @Test("the clock follows the player while it plays")
    func ticks() async {
        let made = Made()
        let voice = player(made: made)
        voice.toggle("$a")
        await voice.settled()
        made.players[0].currentTime = 5.1
        voice.tickOnce()
        #expect(voice.state(for: "$a") == .playing(elapsedMs: 5_100, durationMs: 7_400))
        #expect(abs(voice.state(for: "$a").progress - 5.1 / 7.4) < 0.001)
    }

    @Test("a pause nobody asked for — a call, the headphones out — shows as paused")
    func externalPause() async {
        let made = Made()
        let voice = player(made: made)
        voice.toggle("$a")
        await voice.settled()
        made.players[0].currentTime = 2
        made.players[0].isPlaying = false
        voice.tickOnce()
        #expect(voice.state(for: "$a") == .paused(elapsedMs: 2_000, durationMs: 7_400))
        #expect(made.sessionActive == 0)
    }

    @Test("played to the end, it is back at its length and plays again without a fetch")
    func finishes() async {
        let made = Made()
        let voice = player(made: made)
        voice.toggle("$a")
        await voice.settled()
        made.players[0].currentTime = 7.4
        made.players[0].isPlaying = false
        made.players[0].onFinish?()
        #expect(voice.state(for: "$a") == .paused(elapsedMs: 0, durationMs: 7_400))
        #expect(!voice.state(for: "$a").isUnderway, "at rest, the row shows the length")
        #expect(made.sessionActive == 0)
        voice.toggle("$a")
        #expect(
            voice.state(for: "$a") == .playing(elapsedMs: 0, durationMs: 7_400),
            "played again from the start, not from the end")
        #expect(made.players.count == 1)
    }

    @Test("a note that cannot be fetched fails, and says so until it is tried again")
    func failsToLoad() async {
        let made = Made()
        let voice = player(Audio(missing: ["$a"]), made: made)
        voice.toggle("$a")
        await voice.settled()
        #expect(voice.state(for: "$a") == .failed("Couldn't load this voice message."))
        // Another note playing does not wipe the failure off this one.
        voice.toggle("$b")
        await voice.settled()
        #expect(voice.state(for: "$a") == .failed("Couldn't load this voice message."))
        #expect(made.players.map(\.source) == ["$b"])
    }

    @Test("bytes the platform cannot open fail rather than hang in loading")
    func failsToOpen() async {
        let made = Made()
        made.refuse = true
        let voice = player(made: made)
        voice.toggle("$a")
        await voice.settled()
        #expect(voice.state(for: "$a") == .failed("This audio can't be played here."))
        #expect(made.sessionActive == 0)
    }

    @Test("tapping the bars of a note that is not playing starts it there")
    func seekStarts() async {
        let made = Made()
        let voice = player(made: made)
        voice.seek("$a", to: 0.5)
        await voice.settled()
        #expect(voice.state(for: "$a") == .playing(elapsedMs: 3_700, durationMs: 7_400))
        voice.seek("$a", to: 0.25)
        #expect(voice.state(for: "$a") == .playing(elapsedMs: 1_850, durationMs: 7_400))
        voice.seek("$a", to: 7)
        #expect(voice.state(for: "$a") == .playing(elapsedMs: 7_400, durationMs: 7_400), "clamped")
    }

    @Test("stop lets go of everything — leaving a room, starting a recording")
    func stops() async {
        let made = Made()
        let voice = player(made: made)
        voice.toggle("$a")
        await voice.settled()
        voice.stop()
        #expect(voice.state(for: "$a") == .idle)
        #expect(voice.activeEventId == nil)
        #expect(made.players[0].stopped)
        #expect(made.sessionActive == 0)
    }

    @Test("a stop while a note is still loading means it never starts")
    func stopWhileLoading() async {
        let made = Made()
        let voice = player(made: made)
        voice.toggle("$a")
        voice.stop()
        // `stop` dropped the task, so there is nothing to await: give the
        // cancelled fetch every chance to finish and wrongly start a player.
        await settle()
        #expect(voice.state(for: "$a") == .idle)
        #expect(made.players.isEmpty)
        #expect(made.sessionActive == 0)
    }

    @Test("the core is asked for CAF, the only container AVFoundation plays Opus from")
    func asksForCaf() async {
        // `Audio` throws unless `opusInCaf` is set, so a player asking for Ogg
        // shows here as a failure.
        let made = Made()
        let voice = player(made: made)
        voice.toggle("$a")
        await voice.settled()
        #expect(voice.state(for: "$a").isPlaying)
    }
}

/// The recorder's format, checked against the encoder that has to accept it.
///
/// Through `AVAudioFile` rather than `AVAudioRecorder`: the recorder needs a
/// microphone, which a test host does not have and may not ask for. Both
/// drive the same system Opus encoder with the same settings dictionary.
struct VoiceRecordingFormatTests {
    @Test("the settings encode Opus into a CAF file, which is what the core remuxes")
    func encodesOpusInCaf() throws {
        let folder = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let url = folder.appendingPathComponent(VoiceRecordingFormat.fileName)
        #expect(url.pathExtension == "caf")

        let pcm = try #require(AVAudioFormat(standardFormatWithSampleRate: 48_000, channels: 1))
        let frames: AVAudioFrameCount = 48_000
        let buffer = try #require(AVAudioPCMBuffer(pcmFormat: pcm, frameCapacity: frames))
        buffer.frameLength = frames
        let samples = try #require(buffer.floatChannelData?[0])
        for i in 0..<Int(frames) {
            samples[i] = 0.3 * sin(2 * .pi * 440 * Float(i) / 48_000)
        }
        do {
            let file = try AVAudioFile(
                forWriting: url, settings: VoiceRecordingFormat.settings,
                commonFormat: .pcmFormatFloat32, interleaved: false)
            #expect(file.fileFormat.streamDescription.pointee.mFormatID == kAudioFormatOpus)
            try file.write(from: buffer)
        }

        // The header is CAF — `caff` — which is what the core looks for
        // before remuxing a marked recording into Ogg.
        let header = try FileHandle(forReadingFrom: url).read(upToCount: 4)
        #expect(header == Data("caff".utf8))
        let back = try AVAudioFile(forReading: url)
        #expect(back.fileFormat.streamDescription.pointee.mFormatID == kAudioFormatOpus)
        #expect(back.fileFormat.sampleRate == 48_000)
        #expect(back.fileFormat.channelCount == 1)
        #expect(abs(Double(back.length) - 48_000) < 1_000, "about a second: \(back.length)")
    }
}
