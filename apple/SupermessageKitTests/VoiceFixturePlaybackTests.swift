import AVFoundation
import Foundation
import SupermessageFFI
import Testing

@testable import SupermessageKit

/// Apple's own decoder over the core's own remux, which the core's tests
/// cannot run.
///
/// `Fixtures/voice-libsndfile-4s.caf` is what `core::opus_container::ogg_to_caf`
/// writes for a 4.014 s note from libsndfile — AgentPod's speech service,
/// the source of every agent's spoken reply — and the core's
/// `the_ios_player_fixture_is_what_the_remuxer_writes` fails the moment the
/// remuxer writes anything else, so this is testing that code.
///
/// The bug of 2026-09-30: libsndfile ends on a 2.5 ms packet, the remux
/// wrote a CAF with "0 frames per packet", and `AVAudioPlayer.prepareToPlay()`
/// said no — "This audio can't be played here." on an agent's every short
/// reply.
@MainActor
struct VoiceFixturePlaybackTests {
    private final class BundleToken {}

    static func fixture() throws -> Data {
        let url = try #require(
            Bundle(for: BundleToken.self).url(forResource: "voice-libsndfile-4s", withExtension: "caf"))
        return try Data(contentsOf: url)
    }

    struct Fixture: AudioFetching {
        let data: Data
        func mediaAudio(eventId: String, opusInCaf: Bool) async throws -> PlayableAudio? {
            PlayableAudio(data: data, mimetype: "audio/x-caf", fileExtension: "caf", durationMs: 4_014)
        }
    }

    @Test("an agent's spoken reply opens in AVAudioPlayer at its own length")
    func opensInApplesPlayer() throws {
        let player = try VoicePlayer.avPlayer(data: Self.fixture(), fileExtension: "caf")
        #expect(abs(player.duration - 4.014) < 0.001, "duration \(player.duration)")
    }

    @Test("Apple's decoder gives back every sample the note says it has")
    func decodesEverySample() throws {
        // AVAudioFile reads a URL, not bytes.
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString).appendingPathExtension("caf")
        try Self.fixture().write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        let file = try AVAudioFile(forReading: url)
        // 4.014 s at 48 kHz, after the pre-skip and the end trim.
        #expect(file.length == 192_672)
        let buffer = try #require(
            AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: AVAudioFrameCount(file.length)))
        try file.read(into: buffer)
        // A decoder that skips an odd-length packet comes up short here.
        #expect(buffer.frameLength == 192_672)
    }

    @Test("tapping play on it plays, rather than failing")
    func playsFromATap() async throws {
        let voice = VoicePlayer(
            client: Fixture(data: try Self.fixture()),
            activateSession: {}, releaseSession: {})
        voice.toggle("$reply")
        await voice.settled()
        #expect(voice.state(for: "$reply").isPlaying, "\(voice.state(for: "$reply"))")
        voice.stop()
    }
}
