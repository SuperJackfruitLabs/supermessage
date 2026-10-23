package dev.supermessage.widgets

import android.app.Application
import android.content.Intent
import android.text.format.DateFormat
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.testing.unit.GlanceAppWidgetUnitTest
import androidx.glance.appwidget.testing.unit.hasStartActivityClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasAnyDescendant
import androidx.glance.testing.unit.hasTestTag
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.test.core.app.ApplicationProvider
import dev.supermessage.MainActivity
import dev.supermessage.kit.widgets.WidgetFixtures
import dev.supermessage.kit.widgets.WidgetFixtures.MINUTE
import dev.supermessage.kit.widgets.WidgetFixtures.NOW
import java.util.Date
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uniffi.supermessage_core.WidgetOutcome
import uniffi.supermessage_core.WidgetView

/**
 * The Agents widget's states (`AgentsWidgetView.swift`), drawn from snapshots the **real** core
 * made, asserted as text, tags and click actions (`runGlanceAppWidgetUnitTest`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgentsWidgetTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val store = WidgetFixtures.Memory()
    private var clock = NOW
    private val feed = WidgetFixtures.feed(store) { clock }
    private val zone = TimeZone.getDefault()

    @Before
    fun utc() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    @After
    fun restoreZone() = TimeZone.setDefault(zone)

    private fun view(): WidgetView = feed.view(NOW)

    private fun show(
        size: DpSize = AgentsWidget.LARGE,
        fontScale: Float = 1f,
        shown: WidgetView = view(),
        check: GlanceAppWidgetUnitTest.() -> Unit,
    ) = runGlanceAppWidgetUnitTest {
        setContext(app)
        setAppWidgetSize(size)
        provideComposable { AgentsContent(shown, NOW, size, fontScale) }
        check()
    }

    private fun text(s: String) = hasTextEqualTo(s)

    private fun GlanceAppWidgetUnitTest.assertAbsent(s: String) {
        onNode(text(s)).assertDoesNotExist()
    }

    /** The app was opened `minutesAgo` before [NOW]; the clock is back at [NOW] after. */
    private suspend fun openedMinutesAgo(minutesAgo: Long) {
        clock = NOW - minutesAgo * MINUTE
        feed.opened()
        clock = NOW
    }

    private fun shortTime(ms: Long) = DateFormat.getTimeFormat(app).format(Date(ms))

    @Test
    fun aRecapListsFailedThenFinishedThenSaidAsTheCoreOrdered() = runBlocking {
        openedMinutesAgo(30)
        feed.apply(WidgetFixtures.said("!s:hs", "\$1", "Talker", "newest line", NOW - MINUTE))
        feed.apply(WidgetFixtures.said("!d:hs", "\$2", "Finisher", "x", NOW - 2 * MINUTE, steps = 4))
        feed.apply(WidgetFixtures.said("!f:hs", "\$3", "Failer", "x", NOW - 9 * MINUTE, steps = 3, failed = 1))
        assertEquals(listOf("Failer", "Finisher", "Talker"), view().recap.map { it.name })
        show {
            val rows = onAllNodes(hasTestTag("recap"))
            rows.assertCountEquals(3)
            listOf("Failer", "Finisher", "Talker").forEachIndexed { i, name ->
                rows[i].assert(hasAnyDescendant(text(name)))
            }
            onNode(text("1 of 3 steps failed")).assertExists()
            onNode(text("Finished · 4 steps")).assertExists()
            onNode(text("newest line")).assertExists()
        }
    }

    @Test
    fun theHeaderSaysSinceWhenTheAppWasOpened() = runBlocking {
        feed.apply(WidgetFixtures.said("!s:hs", "\$0", "Early", "before anyone opened", NOW - 40 * MINUTE))
        show {
            // Never opened: plain "Agents".
            onNode(text("Agents")).assertExists()
        }
        openedMinutesAgo(10)
        feed.apply(WidgetFixtures.said("!s:hs", "\$1", "Talker", "hello", NOW - 5 * MINUTE))
        val opened = shortTime(NOW - 10 * MINUTE)
        assertTrue("the device's short time, 07:50 UTC: $opened", opened.contains("7:50"))
        show {
            onNode(text("Since $opened")).assertExists()
            assertAbsent("Agents")
        }
    }

    @Test
    fun anEmptyRecapWithSomeoneWorkingNamesThemNotNothingNew() = runBlocking {
        feed.apply(listOf(WidgetFixtures.agentRow("!a:hs", "Atlas", NOW - 2 * MINUTE, "on it")), emptyList(), NOW)
        openedMinutesAgo(10)
        val opened = shortTime(NOW - 10 * MINUTE)
        val frame = view().frame!!
        assertEquals("Atlas is working", frame.busy)
        show {
            onNode(text("Atlas is working")).assertExists()
            onNode(text("Nothing finished since $opened")).assertExists()
            assertAbsent("Nothing new since $opened")
            assertAbsent("Quiet while you were away.")
            // The header says "Agents" and the pulse "1 working": no contradiction beside them.
            onNode(text("Agents")).assertExists()
            onNode(text(frame.pulse)).assertExists()
        }
    }

    @Test
    fun anEmptyQuietRecapSaysNothingNewSince() = runBlocking {
        feed.apply(listOf(WidgetFixtures.agentRow("!a:hs", "Atlas", NOW - 3 * 3_600_000, "hello")), emptyList(), NOW)
        openedMinutesAgo(10)
        val opened = shortTime(NOW - 10 * MINUTE)
        show {
            onNode(text("Nothing new since $opened")).assertExists()
            onNode(text("Quiet while you were away.")).assertExists()
            onNode(text("All quiet")).assertExists()
        }
    }

    @Test
    fun anEmptyRecapBeforeTheAppWasEverOpenedSaysNothingNewAndNothingFinishedYet() = runBlocking {
        feed.apply(listOf(WidgetFixtures.agentRow("!a:hs", "Atlas", NOW - 3 * 3_600_000, "hello")), emptyList(), NOW)
        show {
            onNode(text("Nothing new")).assertExists()
            onNode(text("Quiet while you were away.")).assertExists()
        }
        feed.apply(listOf(WidgetFixtures.agentRow("!a:hs", "Atlas", NOW - MINUTE, "hello")), emptyList(), NOW + 1)
        show { onNode(text("Nothing finished yet")).assertExists() }
    }

    @Test
    fun noAgentRoomsSaysSo() = runBlocking {
        openedMinutesAgo(10)
        val opened = shortTime(NOW - 10 * MINUTE)
        show {
            onNode(text("Nothing new since $opened")).assertExists()
            onNode(text("No agent rooms yet.")).assertExists()
            assertAbsent("Quiet while you were away.")
        }
    }

    @Test
    fun signedOutAndWaitingSayWhatToDo() = runBlocking {
        show {
            onNode(text("supermessage")).assertExists()
            onNode(text("Open the app once to start.")).assertExists()
        }
        feed.signedOut()
        show {
            onNode(text("Sign in to see what needs you.")).assertExists()
            assertAbsent("Agents")
        }
    }

    @Test
    fun oneUnreadShowsNoCountAndTwoShowTwoNew() = runBlocking {
        openedMinutesAgo(30)
        feed.apply(WidgetFixtures.said("!one:hs", "\$1", "Solo", "just one", NOW - 3 * MINUTE))
        feed.apply(WidgetFixtures.said("!two:hs", "\$2", "Pair", "first", NOW - 2 * MINUTE))
        feed.apply(WidgetFixtures.said("!two:hs", "\$3", "Pair", "second", NOW - MINUTE))
        assertEquals(listOf(1u, 2u), view().recap.sortedBy { it.name }.reversed().map { it.unread }.sorted())
        show {
            onNode(text("2 new")).assertExists()
            assertAbsent("1 new")
            onAllNodes(hasTextEqualTo("1 new")).assertCountEquals(0)
        }
    }

    @Test
    fun aFailedRowIsDanger() = runBlocking {
        openedMinutesAgo(30)
        feed.apply(WidgetFixtures.said("!f:hs", "\$1", "Failer", "x", NOW - 3 * MINUTE, steps = 3, failed = 1))
        feed.apply(WidgetFixtures.said("!d:hs", "\$2", "Finisher", "x", NOW - 2 * MINUTE, steps = 3))
        feed.apply(WidgetFixtures.said("!s:hs", "\$3", "Talker", "hi", NOW - MINUTE))
        assertEquals(
            listOf(WidgetOutcome.FAILED, WidgetOutcome.FINISHED, WidgetOutcome.SAID),
            view().recap.map { it.outcome },
        )
        // The dot and, for a failure alone, the line under the name.
        assertEquals(RecapPalette(dot = WidgetColors.danger, line = WidgetColors.danger), recapPalette(WidgetOutcome.FAILED))
        assertEquals(RecapPalette(dot = WidgetColors.ok, line = WidgetColors.contentMuted), recapPalette(WidgetOutcome.FINISHED))
        assertEquals(RecapPalette(dot = WidgetColors.accent, line = WidgetColors.contentMuted), recapPalette(WidgetOutcome.SAID))
        show {
            onNode(hasTestTag("recap-line:failed")).assertExists()
            onAllNodes(hasTestTag("recap-line:failed")).assertCountEquals(1)
        }
    }

    @Test
    fun theHeaderPulseIsAccentOnlyWhileSomethingNeedsYou() {
        assertEquals(WidgetColors.accent, pulseColor(needsYou = 1u))
        assertEquals(WidgetColors.contentFaint, pulseColor(needsYou = 0u))
    }

    @Test
    fun aRowSaysHowLongAgoTheWayIosDoes() = runBlocking {
        openedMinutesAgo(300)
        feed.apply(WidgetFixtures.said("!a:hs", "\$1", "Recent", "hi", NOW - 3 * MINUTE))
        feed.apply(WidgetFixtures.said("!b:hs", "\$2", "Older", "hi", NOW - 130 * MINUTE))
        show {
            onNode(text("3m")).assertExists()
            onNode(text("2h")).assertExists()
        }
    }

    @Test
    fun aRowOpensItsRoom() = runBlocking {
        openedMinutesAgo(30)
        feed.apply(WidgetFixtures.said("!room1:hs", "\$1", "Talker", "hi", NOW - MINUTE))
        val link = Intent(
            Intent.ACTION_VIEW,
            android.net.Uri.parse("supermessage://open?room=%21room1%3Ahs"),
            app,
            MainActivity::class.java,
        ).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("dev.supermessage.extra.ROOM", "!room1:hs")
        show { onNode(hasStartActivityClickAction(link)).assertExists() }
    }

    /** Six agents with a line each, newest first: the most the core's snapshot holds (`MAX_AGENTS`). */
    private suspend fun sixSaid() = (1..6).forEach { i ->
        feed.apply(WidgetFixtures.said("!r$i:hs", "\$e$i", "Agent $i", "line $i", NOW - i * MINUTE))
    }

    @Test
    fun sevenOnLargeShowSixAndOneMoreInTheApp() = runBlocking {
        openedMinutesAgo(30)
        sixSaid()
        // The core keeps six agents, so a seventh row is made here: the widget draws what the view has.
        val six = view()
        val seven = six.copy(recap = six.recap + six.recap.last().copy(roomId = "!r7:hs", name = "Agent 7"))
        assertEquals(7, seven.recap.size)
        show(AgentsWidget.LARGE, shown = seven) {
            onAllNodes(hasTestTag("recap")).assertCountEquals(6)
            onNode(text("Agent 1")).assertExists()
            onNode(text("Agent 6")).assertExists()
            assertAbsent("Agent 7")
            onNode(text("1 more in the app")).assertExists()
        }
    }

    @Test
    fun theBucketsAreTheSizesTheLauncherPicksFrom() {
        assertEquals(
            listOf(DpSize(250.dp, 110.dp), DpSize(250.dp, 180.dp), DpSize(250.dp, 250.dp)),
            listOf(AgentsWidget.WIDE, AgentsWidget.MEDIUM, AgentsWidget.LARGE),
        )
        assertEquals(listOf(3, 3, 6), listOf(AgentsWidget.WIDE, AgentsWidget.MEDIUM, AgentsWidget.LARGE).map(::recapLimit))
    }

    @Test
    fun theFitCountsRowsThenTheMoreLineThenTheHeader() {
        assertEquals(RowFit(3, true, true), rowsThatFit(3, 6, AgentsWidget.MEDIUM.height, 1f))
        // 4x2 is 110dp: two rows; the "N more" line would crop a row, so it goes.
        assertEquals(RowFit(2, false, true), rowsThatFit(3, 6, AgentsWidget.WIDE.height, 1f))
        assertEquals(RowFit(6, true, true), rowsThatFit(6, 7, AgentsWidget.LARGE.height, 1f))
        assertEquals(RowFit(3, false, true), rowsThatFit(6, 6, AgentsWidget.LARGE.height, 2f))
        // Twice the text in 110dp: not even one row under the header, so the header is dropped.
        assertEquals(RowFit(1, false, false), rowsThatFit(3, 6, AgentsWidget.WIDE.height, 2f))
        assertEquals(RowFit(1, true, true), rowsThatFit(3, 6, AgentsWidget.MEDIUM.height, 2f))
    }

    @Test
    fun mediumShowsThreeAndTheRestAreCounted() = runBlocking {
        openedMinutesAgo(30)
        sixSaid()
        show(AgentsWidget.MEDIUM) {
            onAllNodes(hasTestTag("recap")).assertCountEquals(3)
            onNode(text("3 more in the app")).assertExists()
        }
        // The wide bucket is 110dp high: a row is worth more than the "N more" line.
        show(AgentsWidget.WIDE) {
            onAllNodes(hasTestTag("recap")).assertCountEquals(2)
            assertAbsent("4 more in the app")
        }
    }

    @Test
    fun atFontScaleTwoTheHeaderIsKeptAndRowsAreFewer() = runBlocking {
        openedMinutesAgo(30)
        sixSaid()
        val opened = shortTime(NOW - 30 * MINUTE)
        show(AgentsWidget.LARGE, fontScale = 2f) {
            onNode(text("Since $opened")).assertExists()
            onNode(text(view().frame!!.pulse)).assertExists()
            onAllNodes(hasTestTag("recap")).assertCountEquals(3)
            onNode(text("Agent 1")).assertExists()
            assertAbsent("Agent 4")
        }
        // 110dp at twice the text: the one row, with no room for the header above it.
        show(AgentsWidget.WIDE, fontScale = 2f) {
            onAllNodes(hasTestTag("recap")).assertCountEquals(1)
            assertAbsent("Since $opened")
        }
    }
}
