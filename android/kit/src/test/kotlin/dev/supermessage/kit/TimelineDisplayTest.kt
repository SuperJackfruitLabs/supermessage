package dev.supermessage.kit

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import uniffi.supermessage_core.ItemView
import uniffi.supermessage_core.SystemKind
import uniffi.supermessage_core.TimelineItemDto
import uniffi.supermessage_core.TimelineRow
import uniffi.supermessage_ffi.groupTimelineRows

/**
 * How the kit draws the core's grouping.
 *
 * **The rules are the core's** (`timeline_grouping.rs`, pinned in Rust). What
 * is left to this host is the walk from row fields to entries to draw, and
 * which collapsed stretches the reader has opened. These tests run rows
 * through the real `groupTimelineRows` so the walk is checked against what the
 * core actually emits, not against fields set by hand.
 *
 * Like `RosterArrangementTest`, this crosses into Rust and needs a host build
 * (`cargo build -p supermessage-ffi`).
 */
class TimelineDisplayTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun ensureHostCoreIsBuilt() {
            val dir = System.getProperty("jna.library.path")
            val hostLib = listOf("libsupermessage_ffi.dylib", "libsupermessage_ffi.so")
                .map { File(dir, it) }
                .firstOrNull { it.exists() }
                ?: File(dir, "libsupermessage_ffi.<dylib|so>")
            if (hostLib.exists()) return
            if (System.getProperty("kit.allowMissingHostCore").toBoolean()) {
                assumeTrue("kit.allowMissingHostCore is set: $hostLib is missing.", false)
            } else {
                fail(
                    "TimelineDisplayTest calls the real Core and needs a host build at $hostLib " +
                        "- run `cargo build -p supermessage-ffi`, or pass " +
                        "-Pkit.allowMissingHostCore=true to skip.",
                )
            }
        }

        private fun item(id: String, kind: String, sender: String?, at: ULong): TimelineItemDto =
            TimelineItemDto(
                id = id, eventId = id, kind = kind, msgtype = null, detail = null,
                sender = sender, senderDisplayName = null, senderAvatar = null, body = null,
                formattedBody = null, media = null, customPayload = null, timestampMs = at,
                isOwn = false, sendState = null, replyTo = null, edited = false,
                reactions = emptyList(), readBy = emptyList(), editable = false,
                membershipSubject = null,
            )

        /** A membership change about [who] with [verb]. */
        fun change(id: String, who: String, verb: String): TimelineRow = TimelineRow(
            item = item(id, "membership", "@$who:x", id.toULong()),
            view = ItemView.System(
                kind = SystemKind.MembershipChanged(who = who, detail = verb),
                text = "$who $verb",
            ),
            senderName = who,
            senderShort = who,
            senderInitial = "?",
            membershipVerb = verb,
            replyQuote = null,
            canReplyOrReact = false,
            replyPreview = null,
        )

        fun message(id: String): TimelineRow = TimelineRow(
            item = item(id, "message", "@a:x", id.toULong()),
            view = ItemView.Bubble(muted = false, blocks = emptyList(), voice = null),
            senderName = "a",
            senderShort = "a",
            senderInitial = "A",
            membershipVerb = null,
            replyQuote = null,
            canReplyOrReact = true,
            replyPreview = null,
        )

        fun silent(id: String): TimelineRow = TimelineRow(
            item = item(id, "state", "@a:x", id.toULong()),
            view = ItemView.None,
            senderName = "a",
            senderShort = "a",
            senderInitial = "A",
            membershipVerb = null,
            replyQuote = null,
            canReplyOrReact = false,
            replyPreview = null,
        )

        /** Five lines, three sentences: the core folds them into one stretch. */
        fun fiveChanges(): List<TimelineRow> = groupTimelineRows(
            listOf(
                change("1", "Strategy Sam", "was invited"),
                change("2", "Strategy Sam", "joined the room"),
                change("3", "Rakesh", "updated their membership"),
                change("4", "Annapurna", "left the room"),
                change("5", "Annapurna", "left the room"),
            ),
        )
    }

    @Test
    fun collapsedStretchIsOneEntry() {
        val out = displayRows(fiveChanges(), expanded = emptySet())
        assertEquals(1, out.size)
        val stretch = out[0] as? DisplayRow.MembershipRun ?: run { fail("expected a run"); return }
        assertEquals("stretch:1", stretch.id)
        assertTrue(stretch.isStretch)
        assertEquals("5 membership changes · Strategy Sam, Rakesh and 1 other", stretch.text)
        assertEquals(5, stretch.rows.size)
    }

    @Test
    fun openedStretchShowsEachRun() {
        val out = displayRows(fiveChanges(), expanded = setOf("stretch:1"))
        assertEquals(
            listOf(
                "group:1" to "Strategy Sam was invited, then joined the room",
                "group:3" to "Rakesh updated their membership",
                "group:4" to "Annapurna left the room",
            ),
            out.map { (it as DisplayRow.MembershipRun).let { run -> run.id to run.text } },
        )
        assertTrue(out.none { (it as DisplayRow.MembershipRun).isStretch })
        assertEquals(listOf(2, 1, 2), out.map { (it as DisplayRow.MembershipRun).rows.size })
    }

    @Test
    fun silentRowsAreDropped() {
        val rows = groupTimelineRows(
            listOf(
                change("1", "Annapurna", "joined the room"),
                silent("2"),
                change("3", "Krishna", "joined the room"),
                message("4"),
            ),
        )
        val out = displayRows(rows, expanded = emptySet())
        assertEquals(listOf("group:1", "4"), out.map { it.id })
        // The silent row neither breaks the run nor is drawn.
        assertEquals(2, (out[0] as DisplayRow.MembershipRun).rows.size)
        assertFalse(out.any { it.id == "2" })
    }
}
