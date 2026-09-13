package dev.supermessage.previews

import dev.supermessage.kit.stores.LiveStore
import uniffi.supermessage_core.AgentState
import uniffi.supermessage_core.CustomEventDecision
import uniffi.supermessage_core.CustomEventDecisionOption
import uniffi.supermessage_core.CustomEventField
import uniffi.supermessage_core.CustomEventView
import uniffi.supermessage_core.ItemView
import uniffi.supermessage_core.MediaFileLabel
import uniffi.supermessage_core.MediaMetaDto
import uniffi.supermessage_core.Membership
import uniffi.supermessage_core.PersonDto
import uniffi.supermessage_core.ReactionDto
import uniffi.supermessage_core.ReplyQuoteView
import uniffi.supermessage_core.ReplyToDto
import uniffi.supermessage_core.RichBlock
import uniffi.supermessage_core.RichInline
import uniffi.supermessage_core.RichListItem
import uniffi.supermessage_core.RichTableCell
import uniffi.supermessage_core.RichTableRow
import uniffi.supermessage_core.RoomAffordance
import uniffi.supermessage_core.RoomIdentity
import uniffi.supermessage_core.RoomInfoDto
import uniffi.supermessage_core.RoomMemberDto
import uniffi.supermessage_core.RoomPreview
import uniffi.supermessage_core.RoomRow
import uniffi.supermessage_core.RoomSummary
import uniffi.supermessage_core.RosterRow
import uniffi.supermessage_core.RosterSection
import uniffi.supermessage_core.RuntimeDto
import uniffi.supermessage_core.SearchResultDto
import uniffi.supermessage_core.TimelineItemDto
import uniffi.supermessage_core.TimelineRow as TimelineRowDto

/**
 * Everything the Compose previews render.
 *
 * ## Why this is a `src/debug` source set rather than a file in `src/main`
 *
 * Moving `compose-ui-tooling-preview` out of release scope is only possible
 * if no `@Preview` lives in `src/main` — see `app/build.gradle.kts`. The
 * source set and the dependency scope are one decision, and this directory is
 * the half of it that holds code.
 *
 * ## Why the fixtures are copied rather than shared
 *
 * `src/androidTest` already builds most of these — `RoomRowTest.row(...)`,
 * `TimelineRowTest`'s rows, `DecisionCardTest`'s cards. An instrumented test
 * source set is not visible from `src/debug`, so a preview cannot import
 * them. The copy is deliberate and this is the note that makes it allowed,
 * because an undocumented duplicate of a fixture builder is how two of them
 * quietly stop agreeing. The iOS half of this project carries the same
 * paragraph for the same reason, and
 * `apple/Supermessage/Previews/PreviewFixtures.swift` is the file to keep
 * these in step with — the values are deliberately the same values.
 *
 * ## What a fixture may not do
 *
 * `AGENTS.md`: the app parses nothing and decides nothing. Every value here
 * is one the **core** would have produced — a `RoomRow` arrives with its name
 * already split and its affordance already chosen, a `TimelineRow` with its
 * `ItemView` already decided. A fixture that computed one of those would be
 * previewing a decision this platform is not allowed to make.
 *
 * And `docs/design-language.md` §2: amber means a pending decision and
 * nothing else. Exactly two fixtures here are pending — [roomNeedsYou] and
 * [cardPending] — and amber in a preview built from any other one is a defect.
 */
object PreviewFixtures {
    /**
     * The string the release gate looks for.
     *
     * `scripts/tests/test_android_preview_leak.sh` assembles a release APK
     * and greps its dex for this. Long and unlikely on purpose: a marker that
     * could occur in real content would make the gate lie in the safe
     * direction.
     */
    const val MARKER = "__supermessage_android_preview_fixture_do_not_ship__"

    const val ROOM_ID = "!atlas:example.org"

    // ── Timeline rows ────────────────────────────────────────────────────

    fun item(
        id: String,
        sender: String? = "@atlas:example.org",
        body: String? = "Rebased onto main and the token diff is empty now.",
        atMs: ULong? = 1_757_700_000_000uL,
        isOwn: Boolean = false,
        kind: String = "message",
        msgtype: String? = "m.text",
        sendState: String? = null,
        reactions: List<ReactionDto> = emptyList(),
        media: MediaMetaDto? = null,
        replyTo: ReplyToDto? = null,
        edited: Boolean = false,
    ): TimelineItemDto = TimelineItemDto(
        id = id, eventId = id, kind = kind, msgtype = msgtype, detail = null, sender = sender,
        senderDisplayName = null, senderAvatar = null, body = body, formattedBody = null,
        media = media, customPayload = null, timestampMs = atMs, isOwn = isOwn,
        sendState = sendState, replyTo = replyTo, edited = edited, reactions = reactions,
        readBy = emptyList(), editable = isOwn,
    )

    fun row(
        item: TimelineItemDto,
        view: ItemView,
        senderName: String = "✳ Atlas — Platform",
        senderShort: String = "Atlas",
        membershipVerb: String? = null,
        replyQuote: ReplyQuoteView? = null,
        canReplyOrReact: Boolean = true,
        replyPreview: String? = null,
    ): TimelineRowDto = TimelineRowDto(
        item = item, view = view, senderName = senderName, senderShort = senderShort,
        membershipVerb = membershipVerb, replyQuote = replyQuote,
        canReplyOrReact = canReplyOrReact, replyPreview = replyPreview,
    )

    private fun paragraph(text: String): List<RichBlock> =
        listOf(RichBlock.Paragraph(listOf(RichInline.Text(text))))

    val message: TimelineRowDto
        get() = row(
            item("\$m1"),
            ItemView.Bubble(false, paragraph("Rebased onto main and the token diff is empty now.")),
        )

    /** `m.notice`, which is what most agent output in this org actually uses. */
    val noticed: TimelineRowDto
        get() = row(
            item("\$m2", body = "pnpm check passed in 41s.", msgtype = "m.notice"),
            ItemView.Bubble(true, paragraph("pnpm check passed in 41s.")),
        )

    val ownSending: TimelineRowDto
        get() = row(
            item(
                "\$own1", sender = "@rakesh:example.org", body = "Merging it.", isOwn = true,
                sendState = "sending",
            ),
            ItemView.Bubble(false, paragraph("Merging it.")),
            senderName = "Rakesh", senderShort = "Rakesh", canReplyOrReact = false,
        )

    val ownFailed: TimelineRowDto
        get() = row(
            item(
                "\$own2", sender = "@rakesh:example.org", body = "Merging it.", isOwn = true,
                sendState = "failed",
            ),
            ItemView.Bubble(false, paragraph("Merging it.")),
            senderName = "Rakesh", senderShort = "Rakesh", canReplyOrReact = false,
        )

    /**
     * A 104-character run with no break in it.
     *
     * **It was 96, and the comment said 104.** `DebugSourceSetTest` asserts
     * the length is over 100 and failed on the first CI run — which is the
     * whole reason that test exists: a fixture whose name or comment has
     * drifted from its value is worse than no fixture, because the preview
     * built on it demonstrates the wrong thing convincingly. The iOS half
     * carried the same wrong number and no test that could notice.
     *
     * The web story for the same guard rendered 1147px wide on its first
     * attempt while claiming to show the guard holding, which is worse than
     * having no story at all.
     */
    val unbreakable: TimelineRowDto
        get() {
            val value =
                "dGhpcyBpcyBub3QgYSByZWFsIHRva2VuIGJ1dCBpdCBpcyBsb25nIGVub3VnaCB0by" +
                    "BicmVhayBhIHBob25lIHdpZHRoIGxheW91dA=="
            return row(item("\$long", body = value), ItemView.Bubble(false, paragraph(value)))
        }

    val dayDivider: TimelineRowDto
        get() = row(
            item("\$day", body = null, kind = "divider", msgtype = null), ItemView.DateDivider,
        )

    val membership: TimelineRowDto
        get() = row(
            item("\$join", body = null, kind = "state", msgtype = null),
            ItemView.System("Krishna joined the room"),
            membershipVerb = "joined the room",
        )

    /** A type this build cannot render at all: a log line, not a bubble. */
    val encrypted: TimelineRowDto
        get() = row(
            item("\$enc", body = null, kind = "encrypted", msgtype = null),
            ItemView.Placeholder("Encrypted message"),
        )

    val withReactions: TimelineRowDto
        get() = row(
            item(
                "\$react",
                reactions = listOf(
                    ReactionDto("✅", "✅", 3u, true, listOf("@rakesh:example.org")),
                    ReactionDto("👀", "👀", 1u, false, emptyList()),
                    // `ReactionDto.key` is arbitrary sender-controlled text. A
                    // fixture set of pure emoji would never exercise that.
                    ReactionDto("shipped", "shipped", 2u, false, emptyList()),
                ),
            ),
            ItemView.Bubble(false, paragraph("Rebased onto main and the token diff is empty now.")),
        )

    val reply: TimelineRowDto
        get() = row(
            item(
                "\$reply", body = "Agreed — the contract belongs on the token.",
                replyTo = ReplyToDto(
                    eventId = "\$m1", available = true, sender = "@rakesh:example.org",
                    senderDisplayName = "Rakesh",
                    excerpt = "Should the contrast contract list every ground?", label = null,
                ),
            ),
            ItemView.Bubble(false, paragraph("Agreed — the contract belongs on the token.")),
            replyQuote = ReplyQuoteView.Available(
                sender = "Rakesh", excerpt = "Should the contrast contract list every ground?",
                label = null,
            ),
        )

    /** A reply whose parent is gone — redacted, or never paginated in. */
    val replyToNothing: TimelineRowDto
        get() = row(
            item("\$orphan", body = "Yes, that one."),
            ItemView.Bubble(false, paragraph("Yes, that one.")),
            replyQuote = ReplyQuoteView.Unavailable,
        )

    val image: TimelineRowDto
        get() = row(
            item(
                "\$img", body = "muster-dark.png", msgtype = "m.image",
                media = MediaMetaDto("muster-dark.png", "image/png", 184_320uL, 1500uL, 900uL),
            ),
            ItemView.Image("The muster board in dark", 1500uL, 900uL),
        )

    val attachment: TimelineRowDto
        get() = row(
            item(
                "\$file", body = "tokens.toml", msgtype = "m.file",
                media = MediaMetaDto("tokens.toml", "text/plain", 4_096uL, null, null),
            ),
            ItemView.MediaFile(MediaFileLabel.FILE, "tokens.toml", 4_096uL, "text/plain"),
        )

    val card: TimelineRowDto
        get() = row(
            item("\$card"),
            ItemView.CustomEvent(cardPending, "Gate", "dev.kaambaan.gate.v1"),
        )

    /** Newest last, which is the order the timeline holds them in. */
    val history: List<TimelineRowDto>
        get() = listOf(dayDivider, message, noticed, withReactions, reply, card, ownSending)

    // ── Cards ────────────────────────────────────────────────────────────

    val cardPending: CustomEventView
        get() = CustomEventView.Rendered(
            fields = listOf(
                CustomEventField("Repository", "SuperJackfruitLabs/supermessage"),
                CustomEventField("Branch", "spec/native-previews-parity"),
                CustomEventField("Changed", "7 files, +412 −38"),
            ),
            reasoning = null, newerVersion = false,
            decision = CustomEventDecision(
                prompt = "Merge this branch into main?",
                options = listOf(
                    CustomEventDecisionOption("Approve", "approve"),
                    CustomEventDecisionOption("Request changes", "request_changes"),
                    CustomEventDecisionOption("Reject", "reject"),
                ),
                subject = "gate-7f21",
            ),
            link = null,
        )

    /** Answered: no decision, and therefore no amber anywhere on it. */
    val cardAnswered: CustomEventView
        get() = CustomEventView.Rendered(
            fields = listOf(
                CustomEventField("Repository", "SuperJackfruitLabs/supermessage"),
                CustomEventField("Decision", "Approved by Rakesh"),
            ),
            reasoning = null, newerVersion = false, decision = null, link = null,
        )

    val cardWithReasoning: CustomEventView
        get() = CustomEventView.Rendered(
            fields = listOf(CustomEventField("Check", "pnpm check")),
            reasoning =
                "The contrast contract on content-faint lists all three grounds rather than " +
                    "only the reading surface, because the ground it fails on is never the " +
                    "one you are looking at.",
            newerVersion = false, decision = null, link = null,
        )

    /** A schema this build is too old to render fully. */
    val cardNewerVersion: CustomEventView
        get() = CustomEventView.Rendered(
            fields = listOf(CustomEventField("Station", "hermes-gateway")),
            reasoning = null, newerVersion = true, decision = null, link = null,
        )

    /** One field whose value is a 71-character unbroken run. */
    val cardLongValue: CustomEventView
        get() = CustomEventView.Rendered(
            fields = listOf(
                CustomEventField(
                    "Artifact",
                    "sha256:9f2c4e7a1b8d3f60a5c9e2b7d4f18a63c0e5b9d2f7a4c1e8b3d6f09a2c5e8b1d",
                ),
            ),
            reasoning = null, newerVersion = false, decision = null, link = null,
        )

    /** Nothing structured survived, so the core hands over the plain body. */
    val cardFallback: CustomEventView
        get() = CustomEventView.FallbackBody(
            "station hermes-gateway reported degraded at 23:04",
        )

    /** Not even a body — the card is a log line. */
    val cardPlaceholder: CustomEventView
        get() = CustomEventView.Placeholder("Unsupported suite event")

    /**
     * A sender-controlled event type long enough to need truncating, and
     * carrying a right-to-left override.
     *
     * `ItemView.CustomEvent`'s doc comment is explicit that this string is
     * hostile: truncate from the left, never the right, and never render it
     * with an RTL base direction, because the obvious approach hands the bidi
     * algorithm a crafted string and lets a type reorder itself on screen.
     */
    const val HOSTILE_EVENT_TYPE = "dev.agentpod.station.‮status.v1.extremely.long.suffix"

    // ── Roster rows ──────────────────────────────────────────────────────

    fun roomRow(
        id: String,
        glyph: String?,
        name: String,
        role: String?,
        initial: String,
        preview: RoomPreview?,
        unread: ULong = 0uL,
        lastActivityMs: ULong? = 1_757_700_000_000uL,
        runtime: RuntimeDto? = null,
        membership: Membership = Membership.JOINED,
        affordance: RoomAffordance = RoomAffordance.COMPOSE,
    ): RoomRow = RoomRow(
        room = RoomSummary(
            id = id, name = name, avatarUrl = null, unread = unread,
            lastMessage = preview?.text, lastMessageIsOwn = false,
            lastMessageNamesSender = false, lastEventType = "m.room.message",
            lastActivityMs = lastActivityMs, runtime = runtime, membership = membership,
        ),
        identity = RoomIdentity(glyph = glyph, name = name, role = role, initial = initial),
        preview = preview,
        affordance = affordance,
    )

    /** Silent long enough that its absence is the fact. */
    val roomQuiet: RoomRow
        get() = roomRow(
            id = "!quill:example.org", glyph = "✒", name = "✒ Quill — Writing", role = "Writing",
            initial = "Q", preview = RoomPreview("Draft is in the branch.", false),
            lastActivityMs = 1_756_000_000_000uL,
        )

    /** Spoke recently enough to count as active. */
    val roomActive: RoomRow
        get() = roomRow(
            id = ROOM_ID, glyph = "✳", name = "✳ Atlas — Platform", role = "Platform",
            initial = "A",
            preview = RoomPreview("Rebased onto main and the diff is empty.", false),
            runtime = RuntimeDto("claude-code", "foundry"),
        )

    /**
     * **The one amber row.** `preview.pending` is what the core sets when a
     * room owes the reader an answer, and it is the only thing in this
     * product allowed to paint `signal`.
     */
    val roomNeedsYou: RoomRow
        get() = roomRow(
            id = "!kaambaan:example.org", glyph = "⌘", name = "⌘ Kaambaan — Delivery",
            role = "Delivery", initial = "K",
            preview = RoomPreview("Merge this branch into main?", true), unread = 2uL,
            runtime = RuntimeDto("kaambaan", "foundry"),
        )

    /** An invitation, which may not be composed into and has no state word. */
    val roomInvitation: RoomRow
        get() = roomRow(
            id = "!estate:example.org", glyph = null, name = "Estate Planning", role = null,
            initial = "E", preview = null, lastActivityMs = null,
            membership = Membership.INVITED, affordance = RoomAffordance.RESPOND_TO_INVITATION,
        )

    /**
     * A name with no glyph, no role and nothing to say.
     *
     * `RoomRow.preview`: *there is no placeholder — a row with nothing to say
     * says nothing.* This is the row that proves the layout survives that.
     */
    val roomBare: RoomRow
        get() = roomRow(
            id = "!plain:example.org", glyph = null, name = "matrix-rust-sdk", role = null,
            initial = "M", preview = null,
        )

    val roster: List<RoomRow>
        get() = listOf(roomNeedsYou, roomActive, roomInvitation, roomQuiet, roomBare)

    /**
     * The roster as the core sections it.
     *
     * `RosterArrangement` decides the sections and this fixture states the
     * result rather than calling it: a preview that arranged its own roster
     * would be previewing a decision rather than a screen.
     */
    val rosterSections: List<RosterSection>
        get() = listOf(
            RosterSection(
                id = "waiting", title = "Waiting on you", detail = "1 needs you",
                rows = listOf(RosterRow(roomNeedsYou, AgentState.NEEDS_YOU)),
                // The one section allowed to draw attention, because it is the
                // one holding a pending decision.
                attention = true,
            ),
            RosterSection(
                id = "everything", title = "Everything else", detail = null,
                rows = listOf(
                    RosterRow(roomActive, AgentState.ACTIVE),
                    RosterRow(roomInvitation, AgentState.IDLE),
                    RosterRow(roomQuiet, AgentState.QUIET),
                    RosterRow(roomBare, AgentState.IDLE),
                ),
                attention = false,
            ),
        )

    // ── People, search, rooms ────────────────────────────────────────────

    val people: List<PersonDto>
        get() = listOf(
            PersonDto(
                "@atlas:example.org", "✳ Atlas — Platform",
                RuntimeDto("claude-code", "foundry"), null,
            ),
            PersonDto("@krishna:example.org", "Krishna", null, null),
            // No display name yet, so the row falls back to the raw id.
            PersonDto(
                "@9247e5a1b3c4:id.agentpod.dev", "@9247e5a1b3c4:id.agentpod.dev", null, null,
            ),
        )

    val searchResults: List<SearchResultDto>
        get() = listOf(
            SearchResultDto(
                "\$m1", ROOM_ID, "@atlas:example.org",
                "Rebased onto main and the token diff is empty now.", 1_757_700_000_000uL,
            ),
            SearchResultDto(
                "\$m9", "!quill:example.org", "@quill:example.org",
                "The contrast contract is asserted before emission, not after.",
                1_756_000_000_000uL,
            ),
        )

    val roomInfo: RoomInfoDto
        get() = RoomInfoDto(
            roomId = ROOM_ID, name = "✳ Atlas — Platform",
            identity = RoomIdentity("✳", "✳ Atlas — Platform", "Platform", "A"),
            topic = "Platform work: the core, the seams, and whatever is on fire.",
            runtime = RuntimeDto("claude-code", "foundry"),
            canonicalAlias = "#atlas:example.org", altAliases = emptyList(),
            activeMemberCount = 3uL,
            members = listOf(
                RoomMemberDto("@atlas:example.org", "✳ Atlas — Platform", null),
                RoomMemberDto("@rakesh:example.org", "Rakesh", null),
                RoomMemberDto("@krishna:example.org", null, null),
            ),
            notifications = NotificationModeFixture.allMessages, pinned = true,
        )

    // ── Rich text ────────────────────────────────────────────────────────

    /** One of every block kind, which is the only way to see them together. */
    val richBlocks: List<RichBlock>
        get() = listOf(
            RichBlock.Heading(2.toUByte(), listOf(RichInline.Text("What the seam is for"))),
            RichBlock.Paragraph(
                listOf(
                    RichInline.Text("Each store takes "),
                    RichInline.Code("any AvatarFetching"),
                    RichInline.Text(" rather than the "),
                    RichInline.Strong(listOf(RichInline.Text("CoreClient"))),
                    RichInline.Text(" actor, so a preview can "),
                    RichInline.Emphasis(listOf(RichInline.Text("build one"))),
                    RichInline.Text(". See "),
                    RichInline.Link(
                        "https://example.org/seam", listOf(RichInline.Text("CoreSeam.swift")),
                    ),
                    RichInline.Text("."),
                ),
            ),
            RichBlock.BlockQuote(
                listOf(
                    RichBlock.Paragraph(
                        listOf(RichInline.Text("Nothing above this file holds a Core reference.")),
                    ),
                ),
            ),
            RichBlock.ListBlock(
                ordered = true, start = 1u,
                items = listOf(
                    RichListItem(
                        listOf(RichBlock.Paragraph(listOf(RichInline.Text("Prove the seam")))),
                    ),
                    RichListItem(
                        listOf(RichBlock.Paragraph(listOf(RichInline.Text("Write the previews")))),
                    ),
                ),
            ),
            RichBlock.CodeBlock(
                "kotlin",
                "debugImplementation(libs.compose.ui.tooling)\n" +
                    "// the renderer this project never declared",
            ),
            RichBlock.ThematicBreak,
            RichBlock.Table(
                header = listOf(
                    RichTableCell(listOf(RichInline.Text("Platform"))),
                    RichTableCell(listOf(RichInline.Text("Previews"))),
                ),
                rows = listOf(
                    RichTableRow(
                        listOf(
                            RichTableCell(listOf(RichInline.Text("web"))),
                            RichTableCell(listOf(RichInline.Text("83 stories"))),
                        ),
                    ),
                    RichTableRow(
                        listOf(
                            RichTableCell(listOf(RichInline.Text("Android"))),
                            RichTableCell(listOf(RichInline.Text("this file"))),
                        ),
                    ),
                ),
            ),
        )

    /** A code block whose lines are far wider than any phone. */
    val wideCode: List<RichBlock>
        get() = listOf(
            RichBlock.CodeBlock(
                "bash",
                "./gradlew :app:assembleRelease && " +
                    "./scripts/tests/test_android_preview_leak.sh --verbose",
            ),
        )

    // ── Live turn ────────────────────────────────────────────────────────

    /**
     * A turn mid-flight: a thought, three tools with one failed, an answer.
     *
     * None of this is history — not persisted, not paginated, and a device
     * that was asleep never sees it. Which is why a still frame is worth
     * having: in a running app these states are gone in seconds.
     */
    val liveTools: List<LiveStore.ToolCall>
        get() = listOf(
            LiveStore.ToolCall(
                id = "c1", title = "read docs/tech-stack.md", status = "completed",
                kind = "read", locations = listOf("docs/tech-stack.md"), input = null,
                output = null,
            ),
            // The web's LiveActivity names the *last failed* tool ahead of any
            // running one, because a failure is the only state there that still
            // matters after the turn ends. This is the fixture that shows
            // whether Android does the same.
            LiveStore.ToolCall(
                id = "c2", title = "write src/lib/tokens.css", status = "failed",
                kind = "write", locations = listOf("src/lib/tokens.css"), input = null,
                output = "permission denied",
            ),
            LiveStore.ToolCall(
                id = "c3",
                title = "regenerate every design-token target and diff the checked-in " +
                    "output against the source that produces it",
                status = "in_progress", kind = "run", locations = emptyList(), input = null,
                output = null,
            ),
        )

    const val LIVE_THOUGHT = "Checking whether the roster is already sorted."
    const val LIVE_ANSWER = "It is sorted by pending first, then by last activity."

    val allStates: List<AgentState>
        get() = listOf(
            AgentState.NEEDS_YOU, AgentState.ACTIVE, AgentState.IDLE, AgentState.QUIET,
        )
}

/**
 * `NotificationMode` is named the same as a Compose symbol in some import
 * sets, so it is reached through this rather than imported at the top — the
 * kind of thing that is a one-line fix in an IDE and a CI round trip without
 * one.
 */
internal object NotificationModeFixture {
    val allMessages = uniffi.supermessage_core.NotificationMode.ALL_MESSAGES
}
