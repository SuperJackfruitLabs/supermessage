import CoreGraphics
import Foundation
import ImageIO
import SupermessageFFI
import Testing

@testable import SupermessageKit

/// The agents' pictures in the App Group, for the fleet Live Activity
/// (spec 2026-09-30, B4).
struct AgentAvatarCacheTests {
    /// A core that answers from a table and counts what it was asked.
    actor FakeCore: AgentAvatarFetching {
        var answers: [String: AgentAvatar?] = [:]
        var failing: Set<String> = []
        private(set) var asked: [String] = []

        func set(_ roomId: String, _ avatar: AgentAvatar?) { answers[roomId] = .some(avatar) }
        func fail(_ roomId: String, _ fails: Bool) {
            if fails { failing.insert(roomId) } else { failing.remove(roomId) }
        }

        func agentAvatar(roomId: String) async throws -> AgentAvatar? {
            asked.append(roomId)
            if failing.contains(roomId) { throw CancellationError() }
            return answers[roomId] ?? nil
        }
    }

    static func directory() -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("agent-avatars-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    /// A `width`×`height` PNG, as a server might send.
    static func picture(width: Int, height: Int) -> Data {
        let context = CGContext(
            data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
            space: CGColorSpace(name: CGColorSpace.sRGB)!,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        context.setFillColor(red: 0.9, green: 0.3, blue: 0.5, alpha: 1)
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        let out = NSMutableData()
        let destination = CGImageDestinationCreateWithData(out as CFMutableData, "public.png" as CFString, 1, nil)!
        CGImageDestinationAddImage(destination, context.makeImage()!, nil)
        CGImageDestinationFinalize(destination)
        return out as Data
    }

    static func size(of png: Data) -> (Int, Int)? {
        guard let source = CGImageSourceCreateWithData(png as CFData, nil),
            let image = CGImageSourceCreateImageAtIndex(source, 0, nil)
        else { return nil }
        return (image.width, image.height)
    }

    static let lyra = AgentAvatar(
        userId: "@agent_lyra:hs", fileName: widgetAvatarFileName(userId: "@agent_lyra:hs"),
        image: picture(width: 96, height: 80))

    @Test("an agent's picture is kept as a 64×64 PNG the card finds by user id")
    func writesAndReads() async throws {
        let dir = Self.directory()
        let core = FakeCore()
        await core.set("!lyra", Self.lyra)
        let cache = AgentAvatarCache(client: core, directory: dir)
        await cache.refresh([(roomId: "!lyra", avatar: "mxc://hs/room")])

        let png = try #require(AgentAvatarStore.imageData(for: "@agent_lyra:hs", in: dir))
        #expect(png.starts(with: [0x89, 0x50, 0x4E, 0x47]))
        let size = try #require(Self.size(of: png))
        #expect(size.0 == 64 && size.1 == 64, "cropped square, not squashed or left at the server's size")
        #expect(FileManager.default.fileExists(atPath: dir.appendingPathComponent(Self.lyra.fileName).path))
        #expect(AgentAvatarStore.imageData(for: "@someone_else:hs", in: dir) == nil)
    }

    @Test("a room is asked about once, again when its avatar changes, and again after forget")
    func asksOnlyWhenWorthIt() async {
        let dir = Self.directory()
        let core = FakeCore()
        await core.set("!lyra", Self.lyra)
        let cache = AgentAvatarCache(client: core, directory: dir)
        await cache.refresh([(roomId: "!lyra", avatar: "mxc://hs/a")])
        await cache.refresh([(roomId: "!lyra", avatar: "mxc://hs/a")])
        #expect(await core.asked == ["!lyra"])
        await cache.refresh([(roomId: "!lyra", avatar: "mxc://hs/b")])
        #expect(await core.asked == ["!lyra", "!lyra"])
        await cache.forget()
        await cache.refresh([(roomId: "!lyra", avatar: "mxc://hs/b")])
        #expect(await core.asked.count == 3)
    }

    @Test("a fetch that failed is tried again next time")
    func retriesAFailure() async {
        let dir = Self.directory()
        let core = FakeCore()
        await core.set("!lyra", Self.lyra)
        await core.fail("!lyra", true)
        let cache = AgentAvatarCache(client: core, directory: dir)
        await cache.refresh([(roomId: "!lyra", avatar: nil)])
        #expect(AgentAvatarStore.imageData(for: "@agent_lyra:hs", in: dir) == nil)
        await core.fail("!lyra", false)
        await cache.refresh([(roomId: "!lyra", avatar: nil)])
        #expect(await core.asked.count == 2)
        #expect(AgentAvatarStore.imageData(for: "@agent_lyra:hs", in: dir) != nil)
    }

    @Test("an agent whose picture was removed loses its file")
    func removesAGonePicture() async {
        let dir = Self.directory()
        let core = FakeCore()
        await core.set("!lyra", Self.lyra)
        let cache = AgentAvatarCache(client: core, directory: dir)
        await cache.refresh([(roomId: "!lyra", avatar: "mxc://hs/a")])
        #expect(AgentAvatarStore.imageData(for: "@agent_lyra:hs", in: dir) != nil)

        await core.set(
            "!lyra", AgentAvatar(userId: Self.lyra.userId, fileName: Self.lyra.fileName, image: nil))
        await cache.refresh([(roomId: "!lyra", avatar: nil)])
        #expect(AgentAvatarStore.imageData(for: "@agent_lyra:hs", in: dir) == nil)
        #expect(!FileManager.default.fileExists(atPath: dir.appendingPathComponent(Self.lyra.fileName).path))
    }

    @Test("an index entry that is not a bare file name is not followed")
    func refusesAPath() throws {
        let dir = Self.directory()
        let index = ["@a:hs": "../outside.png"]
        try JSONEncoder().encode(index).write(to: dir.appendingPathComponent("index.json"))
        #expect(AgentAvatarStore.imageData(for: "@a:hs", in: dir) == nil)
    }
}
