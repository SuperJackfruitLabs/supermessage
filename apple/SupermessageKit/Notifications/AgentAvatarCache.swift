import CoreGraphics
import Foundation
import ImageIO
import SupermessageFFI

extension AgentAvatar: @retroactive @unchecked Sendable {}

/// The core's call that names an agent room's agent and fetches its picture
/// (`Session::agent_avatar`), as a seam a test can stand in for.
public protocol AgentAvatarFetching: Sendable {
    func agentAvatar(roomId: String) async throws -> AgentAvatar?
}

extension CoreClient: AgentAvatarFetching {}

/// Keeps `<App Group>/avatars` holding each agent's picture, for the fleet
/// Live Activity (spec 2026-09-30, B4), which cannot fetch one itself.
///
/// For each agent room in the roster the core says who the agent is, what
/// its file is called and what its picture is; this downscales the picture
/// to 64×64 PNG, writes it atomically and records user id → file in the
/// index `AgentAvatarStore` reads. A room is asked about once per launch and
/// again whenever its avatar changes, or the app comes back to the
/// foreground (`forget()`) — a Guild agent's picture changes on its profile,
/// which the room list does not see. An agent whose picture was removed has
/// its file removed too.
///
/// Every core call goes through `CoreClient`, off the cooperative pool.
public actor AgentAvatarCache {
    public static let side = 64

    private let client: any AgentAvatarFetching
    private let directory: URL
    /// Room id → the room avatar it was last cached against.
    private var seen: [String: String?] = [:]

    public init(client: any AgentAvatarFetching, directory: URL) {
        self.client = client
        self.directory = directory
    }

    /// The App Group's cache, or `nil` in a build without the group.
    public static func shared(client: any AgentAvatarFetching) -> AgentAvatarCache? {
        AgentAvatarStore.directory().map { AgentAvatarCache(client: client, directory: $0) }
    }

    /// Cache the agents of these rooms, each keyed by its room's avatar so a
    /// change is noticed. `rooms` is `(roomId, avatarUrl)`.
    public func refresh(_ rooms: [(roomId: String, avatar: String?)]) async {
        for room in rooms where seen[room.roomId] != .some(room.avatar) {
            seen[room.roomId] = .some(room.avatar)
            guard let avatar = try? await client.agentAvatar(roomId: room.roomId) else {
                // Unreadable now is not "no picture": try again next time.
                seen[room.roomId] = nil
                continue
            }
            store(avatar)
        }
    }

    /// Ask about every room again on the next refresh.
    public func forget() {
        seen = [:]
    }

    /// Write (or remove) one agent's file and its index entry.
    func store(_ avatar: AgentAvatar) {
        let url = directory.appendingPathComponent(avatar.fileName)
        var index = AgentAvatarStore.index(in: directory)
        if let image = avatar.image, let png = Self.png(image, side: Self.side) {
            guard (try? png.write(to: url, options: .atomic)) != nil else { return }
            index[avatar.userId] = avatar.fileName
        } else {
            try? FileManager.default.removeItem(at: url)
            index[avatar.userId] = nil
        }
        if let data = try? JSONEncoder().encode(index) {
            try? data.write(
                to: directory.appendingPathComponent(AgentAvatarStore.indexName), options: .atomic)
        }
    }

    /// `data` (any image ImageIO reads) as a `side`×`side` PNG, cropped to the
    /// middle square so a face is not squashed; `nil` when it is not an image.
    static func png(_ data: Data, side: Int) -> Data? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
            let image = CGImageSourceCreateImageAtIndex(source, 0, nil),
            image.width > 0, image.height > 0,
            let context = CGContext(
                data: nil, width: side, height: side, bitsPerComponent: 8, bytesPerRow: 0,
                space: CGColorSpace(name: CGColorSpace.sRGB)!,
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
        else { return nil }
        let scale = Double(side) / Double(min(image.width, image.height))
        let width = Double(image.width) * scale
        let height = Double(image.height) * scale
        context.interpolationQuality = .high
        context.draw(
            image,
            in: CGRect(
                x: (Double(side) - width) / 2, y: (Double(side) - height) / 2, width: width,
                height: height))
        guard let scaled = context.makeImage() else { return nil }
        let out = NSMutableData()
        guard
            let destination = CGImageDestinationCreateWithData(
                out as CFMutableData, "public.png" as CFString, 1, nil)
        else { return nil }
        CGImageDestinationAddImage(destination, scaled, nil)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return out as Data
    }
}
