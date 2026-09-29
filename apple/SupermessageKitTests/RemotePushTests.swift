import Foundation
import SupermessageFFI
import Testing

@testable import SupermessageKit

/// Remote push: reading the gateway's payload, where the stores and secrets
/// live for the app and the extension, which APNs environment a token is in,
/// and how local notifications step aside once pushes arrive.
struct RemotePushTests {
    // MARK: - The gateway's payload

    @Test("a push names its room, its event and the unread count")
    func readsThePayload() {
        let push = RemotePush(userInfo: [
            "aps": ["alert": "New message", "mutable-content": 1],
            "room_id": "!r:hs", "event_id": "$e", "unread_count": NSNumber(value: 3),
        ])
        #expect(push == RemotePush(userInfo: ["room_id": "!r:hs", "event_id": "$e", "unread_count": 3]))
        #expect(push?.roomId == "!r:hs")
        #expect(push?.eventId == "$e")
        #expect(push?.unreadCount == 3)
    }

    @Test("a push without both ids has nothing to fetch")
    func needsBothIds() {
        #expect(RemotePush(userInfo: ["room_id": "!r:hs"]) == nil)
        #expect(RemotePush(userInfo: ["event_id": "$e"]) == nil)
        #expect(RemotePush(userInfo: ["room_id": "", "event_id": "$e"]) == nil)
        #expect(RemotePush(userInfo: ["room_id": "!r", "event_id": "$e"])?.unreadCount == nil)
    }

    @Test("an answer's push carries how its turn went, as counts only")
    func turnCounts() {
        let base: [AnyHashable: Any] = ["room_id": "!r:hs", "event_id": "$e"]
        func push(_ turn: Any?) -> RemotePush? {
            var info = base
            info["turn"] = turn
            return RemotePush(userInfo: info)
        }
        #expect(push(["total": 7, "failed": 1])?.turn == RemotePush.Turn(total: 7, failed: 1))
        #expect(push(["total": NSNumber(value: 3), "failed": NSNumber(value: 0)])?.turn == RemotePush.Turn(total: 3, failed: 0))
        #expect(RemotePush(userInfo: base)?.turn == nil, "most pushes are not an answer")
        // Half a turn, or something that is not a count, is no turn — and the
        // push itself still stands.
        #expect(push(["total": 7])?.turn == nil)
        #expect(push(["total": -1, "failed": 0])?.turn == nil)
        #expect(push(["total": 2.5, "failed": 0])?.turn == nil)
        #expect(push(["total": true, "failed": false])?.turn == nil)
        #expect(push("7/1")?.turn == nil)
        #expect(push("7/1")?.eventId == "$e")
    }

    @Test("a push the extension could not improve still opens its room, and answers nothing")
    func openingKeys() {
        let push = RemotePush(userInfo: ["room_id": "!r:hs", "event_id": "$e"])!
        #expect(push.openingUserInfo == [NotificationKeys.roomId: "!r:hs", NotificationKeys.eventId: "$e"])
        // A PERMISSION category from the gateway with no option carried opens
        // the room rather than sending something the core never decided.
        let response = NotificationKeys.response(
            actionIdentifier: NotificationKeys.allowAction, userInfo: push.openingUserInfo)
        #expect(response == .open(roomId: "!r:hs"))
    }

    // MARK: - Where the stores and secrets live

    let group = URL(fileURLWithPath: "/group/supermessage")
    let legacy = URL(fileURLWithPath: "/app/Library/Application Support/supermessage")
    let info: [String: Any] = [
        CoreLocation.keychainGroupKey: "TEAM.dev.supermessage.shared",
        CoreLocation.legacyKeychainGroupKey: "TEAM.dev.supermessage.ios",
    ]

    @Test("with the App Group, the app opens the group and moves its old stores and secrets there")
    func appWithGroup() throws {
        let options = try #require(
            CoreLocation.options(process: .app, info: info, groupDirectory: group, legacyDirectory: legacy))
        #expect(options.dataDir == group.path)
        #expect(options.legacyDataDir == legacy.path)
        #expect(options.processName == "main")
        #expect(options.keychainAccessGroup == "TEAM.dev.supermessage.shared")
        #expect(options.legacyKeychainAccessGroup == "TEAM.dev.supermessage.ios")
    }

    @Test("the extension opens the same group under its own lock name, and moves nothing")
    func extensionWithGroup() throws {
        let options = try #require(
            CoreLocation.options(
                process: .notificationService, info: info, groupDirectory: group,
                legacyDirectory: legacy))
        #expect(options.dataDir == group.path)
        #expect(options.processName == "nse")
        #expect(options.legacyDataDir == nil)
        #expect(options.keychainAccessGroup == "TEAM.dev.supermessage.shared")
        #expect(options.legacyKeychainAccessGroup == nil)
    }

    @Test("without the App Group the app keeps everything where it always was")
    func appWithoutGroup() throws {
        let options = try #require(
            CoreLocation.options(process: .app, info: info, groupDirectory: nil, legacyDirectory: legacy))
        #expect(options.dataDir == legacy.path)
        #expect(options.legacyDataDir == nil)
        #expect(options.keychainAccessGroup == nil)
        #expect(options.legacyKeychainAccessGroup == nil)
    }

    @Test("without the App Group the extension has nothing to read")
    func extensionWithoutGroup() {
        #expect(
            CoreLocation.options(
                process: .notificationService, info: info, groupDirectory: nil,
                legacyDirectory: legacy) == nil)
    }

    @Test("an empty or unexpanded keychain group is no group")
    func unexpandedGroup() throws {
        for value in ["", "  ", "$(SM_KEYCHAIN_GROUP)"] {
            let options = try #require(
                CoreLocation.options(
                    process: .app, info: [CoreLocation.keychainGroupKey: value],
                    groupDirectory: group, legacyDirectory: legacy))
            #expect(options.keychainAccessGroup == nil)
            #expect(options.legacyKeychainAccessGroup == nil)
        }
    }

    // MARK: - Which APNs a token belongs to

    func profile(_ environment: String) -> Data {
        // The shape of a .mobileprovision: binary CMS around an XML plist.
        var data = Data([0x30, 0x82, 0x0A, 0x00, 0x06, 0x09])
        data.append(
            Data(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0"><dict>
                <key>Name</key><string>supermessage iOS App Store</string>
                <key>Entitlements</key><dict>
                <key>aps-environment</key><string>\(environment)</string>
                </dict></dict></plist>
                """.utf8))
        data.append(Data([0xA0, 0x82, 0x01]))
        return data
    }

    @Test("the embedded profile decides, whatever the configuration says")
    func profileDecides() {
        let debug: [String: Any] = [PushConfiguration.apsEnvironmentKey: "development"]
        let release: [String: Any] = [PushConfiguration.apsEnvironmentKey: "production"]
        #expect(PushConfiguration.isSandbox(provisioningProfile: profile("development"), info: release))
        #expect(!PushConfiguration.isSandbox(provisioningProfile: profile("production"), info: debug))
    }

    @Test("with no profile — an App Store install — the configuration decides, and Release is production")
    func configurationDecides() {
        #expect(
            PushConfiguration.isSandbox(
                provisioningProfile: nil, info: [PushConfiguration.apsEnvironmentKey: "development"]))
        #expect(
            !PushConfiguration.isSandbox(
                provisioningProfile: nil, info: [PushConfiguration.apsEnvironmentKey: "production"]))
        #expect(!PushConfiguration.isSandbox(provisioningProfile: nil, info: nil))
        #expect(!PushConfiguration.isSandbox(provisioningProfile: Data("junk".utf8), info: nil))
    }

    // MARK: - Local notifications once pushes arrive

    let now: UInt64 = 1_700_000_000_000

    func row(_ eventId: String) -> TimelineRow {
        TimelineRow(
            item: TimelineItemDto(
                id: "u-\(eventId)", eventId: eventId, kind: "message", msgtype: "m.text",
                detail: nil, sender: "@atlas:x.org", senderDisplayName: "Atlas", senderAvatar: nil,
                body: "hi", formattedBody: nil, media: nil, customPayload: nil, timestampMs: now,
                isOwn: false, sendState: nil, replyTo: nil, edited: false, reactions: [],
                readBy: [], editable: false, membershipSubject: nil),
            view: .bubble(muted: false, blocks: []), senderName: "Atlas", senderShort: "Atlas",
            senderInitial: "A", membershipVerb: nil, replyQuote: nil, canReplyOrReact: true,
            replyPreview: "hi")
    }

    func roomRow(_ id: String, unread: UInt64) -> RoomRow {
        RoomRow(
            room: RoomSummary(
                id: id, name: id, avatarUrl: nil, unread: unread, lastMessage: "hi",
                lastMessageIsOwn: false, lastMessageNamesSender: false, lastEventType: nil,
                lastActivityMs: now, runtime: nil, membership: .joined),
            identity: RoomIdentity(glyph: nil, name: "Room \(id)", role: nil, initial: "R"),
            preview: RoomPreview(text: "hi", pending: false), affordance: .compose)
    }

    @Test("with push, the roster posts nothing: every room's news is a push")
    func rosterStepsAside() {
        let context = NotificationContext(
            openRoomId: nil, appActive: true, timelineRoomId: nil, remotePush: true)
        let notes = NotificationComposer.forRoster(
            previous: [roomRow("!a", unread: 0)], next: [roomRow("!a", unread: 1)], context: context)
        #expect(notes.isEmpty)
        let without = NotificationContext(openRoomId: nil, appActive: true, timelineRoomId: nil)
        #expect(
            NotificationComposer.forRoster(
                previous: [roomRow("!a", unread: 0)], next: [roomRow("!a", unread: 1)],
                context: without
            ).count == 1)
    }

    @Test("with push, the open timeline notifies in the foreground and not in the background")
    func timelineStepsAsideInTheBackground() {
        func notes(active: Bool, push: Bool) -> [LocalNotification] {
            NotificationComposer.forTimeline(
                roomId: "!a", roomName: "Room !a", rows: [row("$1")], since: now - 1,
                alreadyNotified: [],
                context: NotificationContext(
                    openRoomId: nil, appActive: active, timelineRoomId: "!a", remotePush: push))
        }
        #expect(notes(active: false, push: true).isEmpty)
        #expect(notes(active: true, push: true).count == 1)
        #expect(notes(active: false, push: false).count == 1)
    }

    @Test("a push in the foreground is shown unless its room is open or it was already posted")
    func presentsRemote() {
        let open = NotificationContext(
            openRoomId: "!a", appActive: true, timelineRoomId: "!a", remotePush: true)
        #expect(
            !NotificationComposer.presentsRemote(
                roomId: "!a", eventId: "$1", context: open, alreadyNotified: []))
        #expect(
            !NotificationComposer.presentsRemote(
                roomId: "!b", eventId: "$1", context: open, alreadyNotified: ["$1"]))
        #expect(
            NotificationComposer.presentsRemote(
                roomId: "!b", eventId: "$2", context: open, alreadyNotified: ["$1"]))
        #expect(
            NotificationComposer.presentsRemote(
                roomId: nil, eventId: nil, context: open, alreadyNotified: ["$1"]))
    }

    @Test("a decided notification is posted under its event id, the push's collapse id")
    func decidedNotification() {
        let note = LocalNotification(
            decided: NotificationDto(
                roomId: "!a", eventId: "$gate", title: "Room !a", subtitle: "Approval",
                body: "Ship it?", category: .gate, permission: nil,
                gate: GateAnswers(gateId: "g1", prompt: "Ship it?", optionIds: ["approve", "reject"]),
                threadId: "!a", suppress: nil, fallbackTitle: nil, fallbackBody: nil, activity: nil))
        #expect(note.id == "$gate")
        #expect(note.category == .gate)
        #expect(note.gate == LocalNotification.Gate(gateId: "g1", prompt: "Ship it?", optionIds: ["approve", "reject"]))
        #expect(NotificationKeys.userInfo(for: note)[NotificationKeys.gateOptions] == "approve reject")
        #expect(notificationCategoryIdentifier(category: .gate) == LocalNotification.Category.gate.rawValue)
    }

    // MARK: - What the extension does with a decided push

    func decided(
        suppress: NotificationSuppression?, fallbackTitle: String? = nil,
        fallbackBody: String? = nil
    ) -> NotificationDto {
        NotificationDto(
            roomId: "!a", eventId: "$e", title: "", subtitle: nil, body: "New message",
            category: .message, permission: nil, gate: nil, threadId: "!a",
            suppress: suppress, fallbackTitle: fallbackTitle, fallbackBody: fallbackBody, activity: nil)
    }

    @Test("a suppressed push without the filtering entitlement says its quiet line, never nothing")
    func suppressedIsQuietNotBlank() {
        let note = decided(
            suppress: .reaction, fallbackTitle: "Hermes",
            fallbackBody: "Krishna reacted ✅ to a message")
        #expect(
            RemotePresentation(note, canFilter: false)
                == .quiet(title: "Hermes", body: "Krishna reacted ✅ to a message"))
        // Every kind of suppression, not only reactions.
        for why in [NotificationSuppression.edit, .redaction, .filtered, .own, .notNews] {
            let quiet = RemotePresentation(decided(suppress: why, fallbackBody: "x"), canFilter: false)
            #expect(quiet == .quiet(title: nil, body: "x"))
        }
    }

    @Test("a suppressed push with no line from the core still is not blank")
    func lastResortIsNotBlank() {
        #expect(
            RemotePresentation(decided(suppress: .filtered), canFilter: false)
                == .quiet(title: nil, body: RemotePresentation.lastResortBody))
        #expect(
            RemotePresentation(decided(suppress: .filtered, fallbackBody: ""), canFilter: false)
                == .quiet(title: nil, body: RemotePresentation.lastResortBody))
        #expect(!RemotePresentation.lastResortBody.isEmpty)
    }

    @Test("with the filtering entitlement a suppressed push is dropped")
    func filteringDrops() {
        #expect(
            RemotePresentation(decided(suppress: .notNews, fallbackBody: "x"), canFilter: true)
                == .drop)
    }

    @Test("a push that is news is shown as the core decided, filtering or not")
    func newsIsShown() {
        let note = NotificationDto(
            roomId: "!a", eventId: "$m", title: "Krishna", subtitle: "Ops", body: "done",
            category: .message, permission: nil, gate: nil, threadId: "!a", suppress: nil,
            fallbackTitle: nil, fallbackBody: nil, activity: nil)
        for canFilter in [false, true] {
            #expect(
                RemotePresentation(note, canFilter: canFilter)
                    == .show(LocalNotification(decided: note)))
        }
    }

    @Test("filtering is on only when the build says YES")
    func filteringFlag() {
        let key = NotificationFiltering.infoKey
        #expect(key == "SMNotificationFiltering")
        #expect(NotificationFiltering.isEnabled(infoDictionary: [key: "YES"]))
        #expect(NotificationFiltering.isEnabled(infoDictionary: [key: true]))
        // Unset: the build setting expands to an empty string.
        #expect(!NotificationFiltering.isEnabled(infoDictionary: [key: ""]))
        #expect(!NotificationFiltering.isEnabled(infoDictionary: [key: "NO"]))
        #expect(!NotificationFiltering.isEnabled(infoDictionary: [:]))
        #expect(!NotificationFiltering.isEnabled(infoDictionary: nil))
    }
}
