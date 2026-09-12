#if DEBUG
import SupermessageFFI
import SupermessageKit
import SwiftUI

/// Everything the previews render, and the stub that stands in for the core.
///
/// ## Why this file exists at all
///
/// Eleven of this target's eighteen views take a `Session`, and until
/// `CoreSeam.swift` there was no way to build one without a `CoreClient`
/// building a `Core` — so eleven screens could not be looked at outside a
/// running, signed-in app. That is the same condition that let the three
/// platforms' palettes drift apart without anyone noticing.
///
/// ## Why the builders are copied rather than imported
///
/// `SupermessageKitTests/TimelineGroupingTests.swift` already has the
/// `TimelineRow` builder this file needs. **A test target is not visible to
/// the app target**, so a preview cannot import it — and the app cannot use
/// `@testable import` the way those tests do. So it is copied, and this
/// paragraph is the reason it is allowed to be: an undocumented duplicate of
/// a fixture builder is how two of them quietly stop agreeing.
///
/// ## What the fixtures may not do
///
/// `AGENTS.md`: *the app parses nothing and decides nothing.* Every value
/// below is one the **core** would have produced — `RoomRow` arrives with its
/// name already split and its affordance already chosen, `TimelineRow` with
/// its `ItemView` already decided. A fixture that computed one of those would
/// be previewing a decision this platform is not allowed to make.
///
/// And `docs/design-language.md` §2: **amber means a pending decision and
/// nothing else.** Exactly two fixtures here are pending — `roomNeedsYou` and
/// `cardPending` — and amber appearing in a preview built from any other one
/// is a defect rather than a style choice.

/// The string the release gate looks for.
///
/// `scripts/tests/test_ios_preview_leak.sh` builds for Release and greps the
/// binary for it. Long and unlikely on purpose: a marker that could occur in
/// real content would make the gate lie in the safe direction.
public let PREVIEW_FIXTURE_MARKER = "__supermessage_ios_preview_fixture_do_not_ship__"

// MARK: - The stub core

/// A `SessionClient` with nothing behind it.
///
/// **Every method answers immediately and none of them block**, which is the
/// one thing this type has to get right. `CoreClient` exists because the real
/// calls all block and therefore may not run on a cooperative thread; a stub
/// that slept, or spun, would reintroduce exactly that hazard in the one
/// place nobody would think to look for it.
///
/// A `struct` with a single enum field, so `Sendable` is checked rather than
/// asserted — `CoreSeam.swift` requires it and `@unchecked` here would defeat
/// the point of requiring it.
struct PreviewClient: SessionClient {
    /// How much the stub pretends to know.
    enum Population: Sendable {
        /// A furnished account: a roster, spaces, people, a history.
        case furnished
        /// A new account that has synced and genuinely has nothing. The
        /// empty states are screens too, and are the ones most likely to
        /// have been drawn once and never looked at again.
        case empty
    }

    let population: Population

    init(_ population: Population = .furnished) {
        self.population = population
        // Keeps `PREVIEW_FIXTURE_MARKER` reachable from any code that builds
        // a stub, which is what makes the release gate able to find it. A
        // `precondition` rather than an `assert`, because `assert` is
        // compiled out under `-O` and the whole point is to survive into a
        // release binary if this type ever reaches one. An unused binding
        // would not survive: the equivalent check on the web side passed
        // meaninglessly on its first attempt because the bundler stripped
        // exactly that.
        precondition(!PREVIEW_FIXTURE_MARKER.isEmpty)
    }

    private var isEmpty: Bool { population == .empty }

    // MARK: AvatarFetching

    // `nil` rather than a bundled image, and that is the interesting case
    // rather than the lazy one: with no avatar the row falls back to
    // `RoomIdentity.initial` on a tinted disc, which is what most rooms in
    // this product actually show.
    func roomAvatar(roomId: String) async throws -> String? { nil }
    func memberAvatar(mxcUri: String) async throws -> String? { nil }

    // MARK: MediaFetching

    func mediaFetch(eventId: String) async throws -> String? { nil }

    // MARK: RoomsSnapshotting

    func roomsSnapshot() async throws -> RoomsSnapshot {
        RoomsSnapshot(seq: 1, rooms: isEmpty ? [] : PreviewFixtures.roster)
    }

    // MARK: SpaceSelecting

    func spacesList() async throws -> [SpaceSummary] {
        isEmpty ? [] : PreviewFixtures.spaces
    }
    func spaceSelect(spaceId: String?) async throws {}

    // MARK: AttachmentStaging

    func attachmentStagePath(roomId: String, path: String) async throws -> StagedFile {
        PreviewFixtures.stagedFile
    }
    func attachmentSend(roomId: String, token: String) async throws {}
    func attachmentDiscard(token: String) async {}

    // MARK: TimelineSubscribing

    func timelineSubscribe(roomId: String, sink: any CoreEventSink) async throws {}
    func timelineResync() async throws -> TimelineSnapshot {
        TimelineSnapshot(
            roomId: PreviewFixtures.roomId, seq: 1,
            items: isEmpty ? [] : PreviewFixtures.history)
    }
    // `false`: there is no more history behind a fixture, and saying so is
    // what stops a preview's list from trying to paginate forever.
    func timelinePaginateBack(roomId: String, count: UInt16) async throws -> Bool { false }
    func markRoomRead(roomId: String) async throws {}

    // MARK: SessionAuthenticating

    func login(
        homeserver: String, username: String, password: String, sink: any CoreEventSink
    ) async throws {}
    // `false` — nothing stored. A preview says which phase it wants at
    // construction instead of arriving at one through this.
    func restoreSession(sink: any CoreEventSink) async throws -> Bool { false }
    func logout() async throws {}

    // MARK: MessageSending

    func sendMessage(roomId: String, body: String, mentions: [String]) async throws {}
    func sendReply(roomId: String, body: String, inReplyTo: String) async throws {}
    func editMessage(roomId: String, eventId: String, body: String) async throws {}
    func deleteMessage(roomId: String, eventId: String) async throws {}
    func toggleReaction(roomId: String, eventId: String, key: String) async throws -> Bool { true }
    func sendGateDecision(
        roomId: String, gateId: String, optionId: String, comment: String?,
        inReplyTo: String, prompt: String
    ) async throws {}
    func setTyping(roomId: String, typing: Bool) async throws {}

    // MARK: RoomMembership

    func joinRoom(roomId: String) async throws {}
    func joinRoomByAlias(aliasOrId: String) async throws -> String { PreviewFixtures.roomId }
    func leaveRoom(roomId: String) async throws {}
    func createRoom(name: String, invite: [String], isDirect: Bool) async throws -> String {
        PreviewFixtures.roomId
    }
    func directRoomWith(userId: String) async throws -> String? { PreviewFixtures.roomId }
    func roomInviter(roomId: String) async throws -> String? { "@krishna:example.org" }

    // MARK: RoomAdministering

    func setRoomNotifications(roomId: String, mode: NotificationMode) async throws {}
    func setRoomPinned(roomId: String, pinned: Bool) async throws {}
    func roomInfo(roomId: String) async throws -> RoomInfoDto { PreviewFixtures.roomInfo }
    func roomAvatarFull(roomId: String) async throws -> String? { nil }

    // MARK: AccountDirectory

    func account() async throws -> AccountDto {
        AccountDto(userId: "@rakesh:example.org", homeserver: "https://matrix.example.org")
    }
    func knownPeople() async throws -> [PersonDto] {
        isEmpty ? [] : PreviewFixtures.people
    }
    func searchMessages(term: String, roomId: String?) async throws -> [SearchResultDto] {
        isEmpty ? [] : PreviewFixtures.searchResults
    }
}

// MARK: - The values

enum PreviewFixtures {
    static let roomId = "!atlas:example.org"

    // MARK: Timeline rows

    /// Copied from `TimelineGroupingTests.row`, for the reason in this file's
    /// header: a test target is invisible to an app target.
    static func item(
        id: String,
        sender: String = "@atlas:example.org",
        body: String? = "Rebased onto main and the token diff is empty now.",
        at ms: UInt64 = 1_757_700_000_000,
        isOwn: Bool = false,
        kind: String = "message",
        msgtype: String? = "m.text",
        sendState: String? = nil,
        reactions: [ReactionDto] = [],
        media: MediaMetaDto? = nil,
        replyTo: ReplyToDto? = nil,
        edited: Bool = false
    ) -> TimelineItemDto {
        TimelineItemDto(
            id: id, eventId: id, kind: kind, msgtype: msgtype, detail: nil, sender: sender,
            senderDisplayName: nil, senderAvatar: nil, body: body, formattedBody: nil,
            media: media, customPayload: nil, timestampMs: ms, isOwn: isOwn,
            sendState: sendState, replyTo: replyTo, edited: edited, reactions: reactions,
            readBy: [], editable: !isOwn ? false : true)
    }

    static func row(
        _ item: TimelineItemDto,
        view: ItemView,
        senderName: String = "✳ Atlas — Platform",
        senderShort: String = "Atlas",
        membershipVerb: String? = nil,
        replyQuote: ReplyQuoteView? = nil,
        canReplyOrReact: Bool = true,
        replyPreview: String? = nil
    ) -> TimelineRow {
        TimelineRow(
            item: item, view: view, senderName: senderName, senderShort: senderShort,
            membershipVerb: membershipVerb, replyQuote: replyQuote,
            canReplyOrReact: canReplyOrReact, replyPreview: replyPreview)
    }

    static var message: TimelineRow {
        row(item(id: "$m1"), view: .bubble(muted: false, blocks: [.paragraph(inlines: [
            .text(text: "Rebased onto main and the token diff is empty now.")])]))
    }

    /// `m.notice`, which is what most agent output in this org actually uses.
    /// A message carrying a `replyPreview`, so it can be the parent of one.
    ///
    /// `replyPreview` is a snapshot of this item's own body for the
    /// composer's "Replying to …" row. It is `nil` on most fixtures here
    /// because it is `nil` on most real items — a media message with no
    /// caption has nothing to preview.
    static var replyParent: TimelineRow {
        row(
            item(id: "$m1", sender: "@rakesh:example.org",
                 body: "Should the contrast contract list every ground?", isOwn: true),
            view: .bubble(muted: false, blocks: [.paragraph(inlines: [
                .text(text: "Should the contrast contract list every ground?")])]),
            senderName: "Rakesh", senderShort: "Rakesh",
            replyPreview: "Should the contrast contract list every ground?")
    }

    static var noticed: TimelineRow {
        row(
            item(id: "$m2", body: "pnpm check passed in 41s.", msgtype: "m.notice"),
            view: .bubble(muted: true, blocks: [.paragraph(inlines: [
                .text(text: "pnpm check passed in 41s.")])]))
    }

    static var ownSending: TimelineRow {
        row(
            item(id: "$own1", sender: "@rakesh:example.org", body: "Merging it.",
                 isOwn: true, sendState: "sending"),
            view: .bubble(muted: false, blocks: [.paragraph(inlines: [.text(text: "Merging it.")])]),
            senderName: "Rakesh", senderShort: "Rakesh", canReplyOrReact: false)
    }

    static var ownFailed: TimelineRow {
        row(
            item(id: "$own2", sender: "@rakesh:example.org", body: "Merging it.",
                 isOwn: true, sendState: "failed"),
            view: .bubble(muted: false, blocks: [.paragraph(inlines: [.text(text: "Merging it.")])]),
            senderName: "Rakesh", senderShort: "Rakesh", canReplyOrReact: false)
    }

    /// A 104-character run with no break in it.
    ///
    /// It was 96 and the comment said 104, which the Android half of this
    /// project caught: `DebugSourceSetTest` asserts the length is over 100
    /// and the same value failed there. There is no equivalent check on
    /// this side — these fixtures live in the app target, which has no test
    /// target that runs in CI — so the numbers in these comments are
    /// verified on Android or not at all.
    ///
    /// The guard being exercised is the bubble's own width limit. The web
    /// story for this rendered 1147px wide on its first attempt while
    /// claiming to show the guard holding, which is worse than having no
    /// story at all — so this one is worth actually looking at.
    static var unbreakable: TimelineRow {
        let value =
            "dGhpcyBpcyBub3QgYSByZWFsIHRva2VuIGJ1dCBpdCBpcyBsb25nIGVub3VnaCB0byBicmVhayBhIHBob25lIHdpZHRoIGxheW91dA=="
        return row(
            item(id: "$long", body: value),
            view: .bubble(muted: false, blocks: [.paragraph(inlines: [.text(text: value)])]))
    }

    static var dayDivider: TimelineRow {
        row(item(id: "$day", body: nil, kind: "divider", msgtype: nil), view: .dateDivider)
    }

    static var membership: TimelineRow {
        row(
            item(id: "$join", body: nil, kind: "state", msgtype: nil),
            view: .system(text: "Krishna joined the room"), membershipVerb: "joined the room")
    }

    /// A type this build cannot render at all, which is a log line rather
    /// than a bordered object.
    static var encrypted: TimelineRow {
        row(
            item(id: "$enc", body: nil, kind: "encrypted", msgtype: nil),
            view: .placeholder(text: "Encrypted message"))
    }

    static var withReactions: TimelineRow {
        row(
            item(id: "$react", reactions: [
                ReactionDto(key: "✅", displayKey: "✅", count: 3, byMe: true,
                            senders: ["@rakesh:example.org"]),
                ReactionDto(key: "👀", displayKey: "👀", count: 1, byMe: false, senders: []),
                // `Reaction.key` is arbitrary sender-controlled text. A
                // fixture set of pure emoji would never exercise that.
                ReactionDto(key: "shipped", displayKey: "shipped", count: 2, byMe: false,
                            senders: []),
            ]),
            view: .bubble(muted: false, blocks: [.paragraph(inlines: [
                .text(text: "Rebased onto main and the token diff is empty now.")])]))
    }

    static var reply: TimelineRow {
        row(
            item(id: "$reply", body: "Agreed — the contract belongs on the token.",
                 replyTo: ReplyToDto(
                    eventId: "$m1", available: true, sender: "@rakesh:example.org",
                    senderDisplayName: "Rakesh",
                    excerpt: "Should the contrast contract list every ground?", label: nil)),
            view: .bubble(muted: false, blocks: [.paragraph(inlines: [
                .text(text: "Agreed — the contract belongs on the token.")])]),
            replyQuote: .available(
                sender: "Rakesh", excerpt: "Should the contrast contract list every ground?",
                label: nil))
    }

    /// A reply whose parent is gone — redacted, or never paginated in.
    static var replyToNothing: TimelineRow {
        row(
            item(id: "$orphan", body: "Yes, that one."),
            view: .bubble(muted: false, blocks: [.paragraph(inlines: [
                .text(text: "Yes, that one.")])]),
            replyQuote: .unavailable)
    }

    static var image: TimelineRow {
        row(
            item(id: "$img", body: "muster-dark.png", msgtype: "m.image",
                 media: MediaMetaDto(
                    filename: "muster-dark.png", mimetype: "image/png", size: 184_320,
                    width: 1500, height: 900)),
            view: .image(alt: "The muster board in dark", width: 1500, height: 900))
    }

    static var attachment: TimelineRow {
        row(
            item(id: "$file", body: "tokens.toml", msgtype: "m.file",
                 media: MediaMetaDto(
                    filename: "tokens.toml", mimetype: "text/plain", size: 4_096,
                    width: nil, height: nil)),
            view: .mediaFile(
                label: .file, filename: "tokens.toml", size: 4_096, mimetype: "text/plain"))
    }

    static var card: TimelineRow {
        row(item(id: "$card"), view: .customEvent(
            view: cardPending, label: "Gate", eventType: "dev.kaambaan.gate.v1"))
    }

    /// Newest last, which is the order the timeline holds them in.
    static var history: [TimelineRow] {
        [dayDivider, message, noticed, withReactions, reply, card, ownSending]
    }

    // MARK: Cards

    static var cardPending: CustomEventView {
        .rendered(
            fields: [
                CustomEventField(label: "Repository", value: "SuperJackfruitLabs/supermessage"),
                CustomEventField(label: "Branch", value: "spec/native-previews-parity"),
                CustomEventField(label: "Changed", value: "7 files, +412 −38"),
            ],
            reasoning: nil, newerVersion: false,
            decision: CustomEventDecision(
                prompt: "Merge this branch into main?",
                options: [
                    CustomEventDecisionOption(label: "Approve", id: "approve"),
                    CustomEventDecisionOption(label: "Request changes", id: "request_changes"),
                    CustomEventDecisionOption(label: "Reject", id: "reject"),
                ],
                subject: "gate-7f21"),
            link: nil)
    }

    /// The same card once it has been answered: no decision, and therefore no
    /// amber anywhere on it.
    static var cardAnswered: CustomEventView {
        .rendered(
            fields: [
                CustomEventField(label: "Repository", value: "SuperJackfruitLabs/supermessage"),
                CustomEventField(label: "Decision", value: "Approved by Rakesh"),
            ],
            reasoning: nil, newerVersion: false, decision: nil, link: nil)
    }

    static var cardWithReasoning: CustomEventView {
        .rendered(
            fields: [CustomEventField(label: "Check", value: "pnpm check")],
            reasoning:
                "The contrast contract on content-faint lists all three grounds rather than "
                + "only the reading surface, because the ground it fails on is never the one "
                + "you are looking at.",
            newerVersion: false, decision: nil, link: nil)
    }

    /// A schema this build is too old to render fully — the core telling the
    /// host that the sender knows more about this event type than it does.
    static var cardNewerVersion: CustomEventView {
        .rendered(
            fields: [CustomEventField(label: "Station", value: "hermes-gateway")],
            reasoning: nil, newerVersion: true, decision: nil, link: nil)
    }

    /// One field whose value is a 71-character unbroken run.
    ///
    /// Every value on a card is arbitrary JSON from anyone who can send to
    /// the room, so this is the shape that finds a missing wrap guard.
    static var cardLongValue: CustomEventView {
        .rendered(
            fields: [CustomEventField(
                label: "Artifact",
                value: "sha256:9f2c4e7a1b8d3f60a5c9e2b7d4f18a63c0e5b9d2f7a4c1e8b3d6f09a2c5e8b1d")],
            reasoning: nil, newerVersion: false, decision: nil, link: nil)
    }

    /// Nothing structured survived, so the core hands over the plain body.
    static var cardFallback: CustomEventView {
        .fallbackBody(text: "station hermes-gateway reported degraded at 23:04")
    }

    /// Not even a body — the card is a log line.
    static var cardPlaceholder: CustomEventView {
        .placeholder(text: "Unsupported suite event")
    }

    /// A sender-controlled event type long enough to need truncating, and
    /// carrying a right-to-left run.
    ///
    /// `ItemView.customEvent`'s own doc comment is explicit that this string
    /// is hostile: truncated from the left, never the right, and never
    /// rendered with an RTL base direction, because the obvious CSS approach
    /// hands the bidi algorithm a crafted string and lets it reorder itself
    /// on screen. This is the fixture that would show that happening.
    static let hostileEventType = "dev.agentpod.station.\u{202E}status.v1.extremely.long.suffix"

    // MARK: Roster rows

    static func roomRow(
        id: String,
        glyph: String?,
        name: String,
        role: String?,
        initial: String,
        preview: RoomPreview?,
        unread: UInt64 = 0,
        lastActivityMs: UInt64? = 1_757_700_000_000,
        runtime: RuntimeDto? = nil,
        membership: Membership = .joined,
        affordance: RoomAffordance = .compose
    ) -> RoomRow {
        RoomRow(
            room: RoomSummary(
                id: id, name: name, avatarUrl: nil, unread: unread,
                lastMessage: preview?.text, lastMessageIsOwn: false,
                lastMessageNamesSender: false, lastEventType: "m.room.message",
                lastActivityMs: lastActivityMs, runtime: runtime, membership: membership),
            identity: RoomIdentity(glyph: glyph, name: name, role: role, initial: initial),
            preview: preview, affordance: affordance)
    }

    /// Silent long enough that its absence is the fact.
    static var roomQuiet: RoomRow {
        roomRow(
            id: "!quill:example.org", glyph: "✒", name: "✒ Quill — Writing", role: "Writing",
            initial: "Q", preview: RoomPreview(text: "Draft is in the branch.", pending: false),
            lastActivityMs: 1_756_000_000_000)
    }

    /// Spoke recently enough to count as active.
    static var roomActive: RoomRow {
        roomRow(
            id: "!atlas:example.org", glyph: "✳", name: "✳ Atlas — Platform", role: "Platform",
            initial: "A",
            preview: RoomPreview(text: "Rebased onto main and the diff is empty.", pending: false),
            runtime: RuntimeDto(harness: "claude-code", host: "foundry"))
    }

    /// **The one amber row.** `preview.pending` is what the core sets when a
    /// room owes the reader an answer, and it is the only thing in this
    /// product allowed to paint `signal`.
    static var roomNeedsYou: RoomRow {
        roomRow(
            id: "!kaambaan:example.org", glyph: "⌘", name: "⌘ Kaambaan — Delivery",
            role: "Delivery", initial: "K",
            preview: RoomPreview(text: "Merge this branch into main?", pending: true),
            unread: 2, runtime: RuntimeDto(harness: "kaambaan", host: "foundry"))
    }

    /// An invitation, which may not be composed into and has no state word.
    static var roomInvitation: RoomRow {
        roomRow(
            id: "!estate:example.org", glyph: nil, name: "Estate Planning", role: nil,
            initial: "E", preview: nil, lastActivityMs: nil, membership: .invited,
            affordance: .respondToInvitation)
    }

    /// A name with no glyph, no role and nothing to say.
    ///
    /// `RoomRow.preview`'s doc comment: *there is no placeholder — a row with
    /// nothing to say says nothing.* This is the row that proves the layout
    /// survives that.
    static var roomBare: RoomRow {
        roomRow(
            id: "!plain:example.org", glyph: nil, name: "matrix-rust-sdk", role: nil,
            initial: "M", preview: nil)
    }

    static var roster: [RoomRow] {
        [roomNeedsYou, roomActive, roomInvitation, roomQuiet, roomBare]
    }

    // MARK: Spaces, people, search, rooms

    static var spaces: [SpaceSummary] {
        [
            SpaceSummary(
                id: "!work:example.org", name: "Work", avatarUrl: nil, childCount: 12,
                membership: .joined,
                identity: RoomIdentity(glyph: nil, name: "Work", role: nil, initial: "W")),
            SpaceSummary(
                id: "!suite:example.org", name: "Suite", avatarUrl: nil, childCount: 5,
                membership: .joined,
                identity: RoomIdentity(glyph: nil, name: "Suite", role: nil, initial: "S")),
            // An invitation to a space, which the strip marks rather than
            // treating as somewhere the reader already is.
            SpaceSummary(
                id: "!estate:example.org", name: "Estate", avatarUrl: nil, childCount: 0,
                membership: .invited,
                identity: RoomIdentity(glyph: nil, name: "Estate", role: nil, initial: "E")),
        ]
    }

    static var people: [PersonDto] {
        [
            PersonDto(
                userId: "@atlas:example.org", name: "✳ Atlas — Platform",
                runtime: RuntimeDto(harness: "claude-code", host: "foundry"), avatarUrl: nil),
            PersonDto(userId: "@krishna:example.org", name: "Krishna", runtime: nil,
                      avatarUrl: nil),
            // No display name yet, so the row falls back to the raw id.
            PersonDto(
                userId: "@9247e5a1b3c4:id.agentpod.dev", name: "@9247e5a1b3c4:id.agentpod.dev",
                runtime: nil, avatarUrl: nil),
        ]
    }

    static var searchResults: [SearchResultDto] {
        [
            SearchResultDto(
                eventId: "$m1", roomId: roomId, sender: "@atlas:example.org",
                body: "Rebased onto main and the token diff is empty now.",
                timestampMs: 1_757_700_000_000),
            SearchResultDto(
                eventId: "$m9", roomId: "!quill:example.org", sender: "@quill:example.org",
                body: "The contrast contract is asserted before emission, not after.",
                timestampMs: 1_756_000_000_000),
        ]
    }

    static var roomInfo: RoomInfoDto {
        RoomInfoDto(
            roomId: roomId, name: "✳ Atlas — Platform",
            identity: RoomIdentity(
                glyph: "✳", name: "✳ Atlas — Platform", role: "Platform", initial: "A"),
            topic: "Platform work: the core, the seams, and whatever is on fire.",
            runtime: RuntimeDto(harness: "claude-code", host: "foundry"),
            canonicalAlias: "#atlas:example.org", altAliases: [], activeMemberCount: 3,
            members: [
                RoomMemberDto(
                    userId: "@atlas:example.org", displayName: "✳ Atlas — Platform",
                    avatarUrl: nil),
                RoomMemberDto(userId: "@rakesh:example.org", displayName: "Rakesh",
                              avatarUrl: nil),
                RoomMemberDto(userId: "@krishna:example.org", displayName: nil, avatarUrl: nil),
            ],
            notifications: .allMessages, pinned: true)
    }

    static var stagedFile: StagedFile {
        StagedFile(
            token: "staged-1", filename: "muster-dark.png", sizeBytes: 184_320,
            mime: "image/png", width: 1500, height: 900)
    }

    // MARK: Rich text

    /// One of every block kind, which is the only way to see them together.
    static var richBlocks: [RichBlock] {
        [
            .heading(level: 2, inlines: [.text(text: "What the seam is for")]),
            .paragraph(inlines: [
                .text(text: "Each store takes "),
                .code(text: "any AvatarFetching"),
                .text(text: " rather than the "),
                .strong(inlines: [.text(text: "CoreClient")]),
                .text(text: " actor, so a preview can "),
                .emphasis(inlines: [.text(text: "build one")]),
                .text(text: ". See "),
                .link(href: "https://example.org/seam", inlines: [.text(text: "CoreSeam.swift")]),
                .text(text: "."),
            ]),
            .blockQuote(blocks: [.paragraph(inlines: [
                .text(text: "Nothing above this file holds a Core reference.")])]),
            .listBlock(ordered: true, start: 1, items: [
                RichListItem(blocks: [.paragraph(inlines: [.text(text: "Prove the seam")])]),
                RichListItem(blocks: [.paragraph(inlines: [.text(text: "Write the previews")])]),
            ]),
            .codeBlock(
                language: "swift",
                text: "extension CoreClient: SessionClient {}\n"
                    + "// every conformance is empty, which is the point"),
            .thematicBreak,
            .table(
                header: [
                    RichTableCell(inlines: [.text(text: "Platform")]),
                    RichTableCell(inlines: [.text(text: "Previews")]),
                ],
                rows: [
                    RichTableRow(cells: [
                        RichTableCell(inlines: [.text(text: "web")]),
                        RichTableCell(inlines: [.text(text: "83 stories")]),
                    ]),
                    RichTableRow(cells: [
                        RichTableCell(inlines: [.text(text: "iOS")]),
                        RichTableCell(inlines: [.text(text: "this file")]),
                    ]),
                ]),
        ]
    }

    /// A code block whose lines are far wider than any phone.
    static var wideCode: [RichBlock] {
        [.codeBlock(
            language: "bash",
            text: "xcodebuild test -project apple/Supermessage.xcodeproj -scheme SupermessageKit "
                + "-destination 'platform=iOS Simulator,name=iPhone 16 Pro' -quiet")]
    }

    // MARK: Sessions

    /// A session with fixture data already in its stores.
    ///
    /// **Populated the way the core populates it.** The roster and the
    /// history go in as a `seq: 1` reset envelope through the same
    /// `handle(_:)` the event pump uses, because `DiffTracker` starts at
    /// `expectedSeq: 1` and therefore applies it immediately, synchronously,
    /// with no `await` in a preview body. The alternative — reaching past the
    /// stores and assigning `rooms` — would need a public setter that exists
    /// for previews and for nothing else.
    ///
    /// Spaces are the exception: `SpacesStore` has no envelope route, only
    /// `refresh()`, so a preview that wants the strip populated wraps itself
    /// in ``PreviewSeeded``.
    @MainActor
    static func session(
        _ population: PreviewClient.Population = .furnished,
        phase: Session.Phase = .signedIn,
        connection: String = "live",
        openRoom: Bool = true
    ) -> Session {
        let session = Session(previewClient: PreviewClient(population), phase: phase)
        session.connection.apply(
            ConnectionState(
                state: connection,
                message: connection == "error" ? "Sync failed: the homeserver timed out." : nil))

        if population == .furnished {
            session.rooms.handle(
                RoomDiffEnvelope(
                    channel: "rooms", subject: "", seq: 1, ops: [.reset(values: roster)]))
            session.timeline.handle(
                TimelineDiffEnvelope(
                    channel: "timeline", subject: "", seq: 1, ops: [.reset(values: history)]))
            if openRoom { session.rooms.select(roomId) }
        }
        return session
    }

    /// A `LiveStore` mid-turn: a thought, three tools with one failed, and an
    /// answer arriving.
    ///
    /// None of this is history — it is not persisted, not paginated, and a
    /// device that was asleep never sees it. Which is exactly why it is worth
    /// a preview: these states are gone in seconds in a running app.
    @MainActor
    static func liveStore(
        thinking: Bool = true, answering: Bool = true, tools: Bool = true
    ) -> LiveStore {
        let live = LiveStore()
        live.focus(roomId)
        var seq: UInt64 = 1
        if thinking {
            live.handleThought(
                roomId: roomId, seq: seq,
                text: "Checking whether the roster is already sorted.", done: false)
            seq += 1
        }
        if tools {
            live.handleTool(
                roomId: roomId, seq: seq, toolCallId: "c1", title: "read docs/tech-stack.md",
                kind: "read", status: "completed", locations: ["docs/tech-stack.md"],
                input: nil, output: nil)
            seq += 1
            // `LiveActivity` on the web names the *last failed* tool ahead of
            // any running one, because a failure is the only state there that
            // still matters after the turn ends. This is the fixture that
            // shows whether iOS does the same.
            live.handleTool(
                roomId: roomId, seq: seq, toolCallId: "c2", title: "write src/lib/tokens.css",
                kind: "write", status: "failed", locations: ["src/lib/tokens.css"],
                input: nil, output: "permission denied")
            seq += 1
            live.handleTool(
                roomId: roomId, seq: seq, toolCallId: "c3",
                title: "regenerate every design-token target and diff the checked-in output "
                    + "against the source that produces it",
                kind: "run", status: "in_progress", locations: [], input: nil, output: nil)
            seq += 1
        }
        if answering {
            live.handleLive(
                roomId: roomId, seq: seq,
                text: "It is sorted by pending first, then by last activity.", done: false)
        }
        return live
    }

    @MainActor
    static func spacesStore(_ population: PreviewClient.Population = .furnished) -> SpacesStore {
        SpacesStore(client: PreviewClient(population))
    }

    @MainActor
    static func mediaCache() -> MediaCache { MediaCache(client: PreviewClient()) }

    @MainActor
    static func faceCache() -> AvatarCache { AvatarCache.forMembers(client: PreviewClient()) }
}

// MARK: - Seeding what only an await can reach

/// Runs an async seed before the preview settles.
///
/// `#Preview` bodies are synchronous, and two things a preview wants —
/// `SpacesStore.refresh()` and anything behind `seed()` — are only reachable
/// with an `await`. This renders its content immediately and lets
/// `@Observable` update it when the seed lands, which is the same sequence the
/// real app goes through on launch.
/// A preview backdrop on the app's own ground.
///
/// Not decoration: most of these components are transparent and draw only
/// their own text, so on Xcode's white canvas a `content`-on-`surface`
/// pairing is being judged against a ground the app never uses. `Theme` also
/// resolves **paper** for light rather than a plain white — that is the
/// design language's "paper is what light means on a phone" — so a preview
/// without this is the one appearance the product does not have.
struct PreviewGround<Content: View>: View {
    var width: CGFloat? = nil
    @ViewBuilder var content: Content

    var body: some View {
        content
            .frame(width: width)
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.surface)
    }
}

struct PreviewSeeded<Content: View>: View {
    let seed: @MainActor () async -> Void
    @ViewBuilder var content: Content

    var body: some View {
        content.task { await seed() }
    }
}
#endif
