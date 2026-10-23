package dev.supermessage.widgets

import android.app.Application
import androidx.compose.ui.unit.DpSize
import androidx.test.core.app.ApplicationProvider
import dev.supermessage.HostCore
import dev.supermessage.kit.widgets.WidgetFixtures
import dev.supermessage.kit.widgets.WidgetFixtures.MINUTE
import dev.supermessage.kit.widgets.WidgetFixtures.NOW
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Agents widget as the launcher draws it, light and dark, into
 * `src/test/previews/AgentsWidget*.png` (see [WidgetFrames]). Every frame is also checked for
 * content clipped by its bucket or flush with the bottom edge, and for a cut-short header.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "xhdpi")
class AgentsWidgetFrameTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private var clock = NOW
    private val feed = WidgetFixtures.feed(WidgetFixtures.Memory()) { clock }
    private val zone = TimeZone.getDefault()

    companion object {
        @BeforeClass
        @JvmStatic
        fun ensureHostCoreIsBuilt() = HostCore.ensureBuilt()
    }

    // The clock time in the header must not depend on the machine's zone.
    @Before
    fun utc() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    @After
    fun restoreZone() = TimeZone.setDefault(zone)

    private fun frame(name: String, size: DpSize, fontScale: Float = 1f, header: String? = null, shows: List<String> = emptyList()) =
        WidgetFrames.render(app, "AgentsWidget$name", size, fontScale, header = header ?: headerNow(), shows = shows) {
            AgentsContent(feed.view(NOW), NOW, size, fontScale)
        }

    private fun headerNow(): String {
        val view = feed.view(NOW)
        return if (view.openedAtMs != null && view.recap.isNotEmpty()) WidgetCopy.since(shortTime(app, view.openedAtMs!!.toLong())) else "Agents"
    }

    private fun opened() = runBlocking {
        clock = NOW - 30 * MINUTE
        feed.opened()
        clock = NOW
    }

    private fun recap(n: Int) = runBlocking {
        opened()
        (1..n).forEach { i ->
            val steps = if (i == 1) 3 else if (i == 2) 5 else null
            feed.apply(
                WidgetFixtures.said(
                    "!r$i:hs", "\$e$i", "Agent $i", if (steps == null) "Looking into the deploy of service $i" else "x",
                    NOW - i * 3 * MINUTE, steps = steps, failed = if (i == 1) 1 else 0,
                ),
            )
        }
        // Two pushes from the last one: "2 new".
        feed.apply(WidgetFixtures.said("!r$n:hs", "\$extra", "Agent $n", "And then the second line", NOW - MINUTE))
    }

    @Test
    fun aRecapOfFour() {
        recap(4)
        frame("RecapFour", AgentsWidget.MEDIUM, shows = feed.view(NOW).recap.take(3).map { it.name } + "1 more in the app")
    }

    @Test
    fun largeWithSeven() {
        recap(6)
        // The core keeps six agents: the seventh row is made here, so the "1 more" line is seen.
        val six = feed.view(NOW)
        val seven = six.copy(recap = six.recap + six.recap.last().copy(roomId = "!r7:hs", name = "Agent 7"))
        WidgetFrames.render(
            app, "AgentsWidgetLargeSeven", AgentsWidget.LARGE, 1f, header = headerNow(),
            shows = seven.recap.take(6).map { it.name } + "1 more in the app",
        ) {
            AgentsContent(seven, NOW, AgentsWidget.LARGE, 1f)
        }
    }

    @Test
    fun wideWithFour() {
        recap(4)
        frame("WideFour", AgentsWidget.WIDE)
    }

    @Test
    fun emptyWithSomeoneBusy() {
        runBlocking { feed.apply(listOf(WidgetFixtures.agentRow("!a:hs", "Atlas", NOW - 2 * MINUTE, "on it")), emptyList(), NOW) }
        opened()
        frame("Busy", AgentsWidget.WIDE)
    }

    @Test
    fun emptyAndQuiet() {
        runBlocking { feed.apply(listOf(WidgetFixtures.agentRow("!a:hs", "Atlas", NOW - 3 * 3_600_000, "hello")), emptyList(), NOW) }
        opened()
        frame("Quiet", AgentsWidget.WIDE)
    }

    @Test
    fun noAgents() {
        opened()
        frame("NoAgents", AgentsWidget.WIDE)
    }

    @Test
    fun signedOut() {
        runBlocking { feed.signedOut() }
        frame("SignedOut", AgentsWidget.WIDE, header = "supermessage")
    }

    @Test
    fun waiting() = frame("Waiting", AgentsWidget.WIDE, header = "supermessage")

    @Test
    fun recapTextTwoLarge() {
        recap(6)
        frame("LargeTextTwo", AgentsWidget.LARGE, fontScale = 2f, shows = feed.view(NOW).recap.take(3).map { it.name })
    }

    @Test
    fun recapTextTwoMedium() {
        recap(4)
        frame("MediumTextTwo", AgentsWidget.MEDIUM, fontScale = 2f)
    }

    @Test
    fun recapTextTwoWide() {
        recap(4)
        frame("WideTextTwo", AgentsWidget.WIDE, fontScale = 2f)
    }

    @Test
    fun recapTextOnePointFiveWide() {
        recap(4)
        frame("WideTextOnePointFive", AgentsWidget.WIDE, fontScale = 1.5f)
    }

    @Test
    fun busyTextTwo() {
        runBlocking { feed.apply(listOf(WidgetFixtures.agentRow("!a:hs", "Atlas", NOW - 2 * MINUTE, "on it")), emptyList(), NOW) }
        opened()
        frame("BusyTextTwo", AgentsWidget.WIDE, fontScale = 2f)
    }

    @Test
    fun quietTextTwo() {
        runBlocking { feed.apply(listOf(WidgetFixtures.agentRow("!a:hs", "Atlas", NOW - 3 * 3_600_000, "hello")), emptyList(), NOW) }
        opened()
        frame("QuietTextTwo", AgentsWidget.WIDE, fontScale = 2f)
    }
}
