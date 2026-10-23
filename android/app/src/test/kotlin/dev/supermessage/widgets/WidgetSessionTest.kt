package dev.supermessage.widgets

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.appwidget.runComposition
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import dev.supermessage.AppCore
import dev.supermessage.kit.widgets.WidgetFixtures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * C1: a write made while a widget is drawn reaches what it draws, in the same composition.
 *
 * Glance runs `provideGlance` once per session, and `updateAll` on a running session only
 * reloads Glance's own state. `runComposition` runs the widget's real `provideGlance` and
 * composition and hands back each `RemoteViews` it would publish, as the running session does;
 * so a snapshot read once in `provideGlance` never produces a second frame here, however often
 * the app redraws. The device test `WidgetLiveSessionTest` does the same through a real host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalGlanceApi::class)
class WidgetSessionTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        // The real snapshot file and the real redraw, with no widget placed and nothing to update:
        // the redraw's own `updateAll` is not what this test relies on.
        AppCore.installWidgetsForTest(
            null,
            WidgetRedraw(app, nextChangeMs = { null }, widgets = { emptyList() }, placed = { false }),
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        runBlocking { AppCore.widgets(app).signedOut() }
        AppCore.installWidgetsForTest(null)
    }

    private fun texts(views: RemoteViews): List<String> {
        val root = views.apply(app, FrameLayout(app))
        fun walk(view: View): List<String> =
            (view as? TextView)?.let { listOf(it.text.toString()) }.orEmpty() +
                ((view as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { walk(g.getChildAt(it)) } }.orEmpty())
        return walk(root)
    }

    /** The frames published until one passes [check]; fails when none does within 10 s. */
    private suspend fun Channel<RemoteViews>.until(what: String, check: (List<String>) -> Boolean): List<String> {
        var drawn = emptyList<String>()
        try {
            withTimeout(10_000) {
                do drawn = texts(receive()) while (!check(drawn))
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("never drawn: $what; the last frame drew $drawn")
        }
        return drawn
    }

    private fun composing(widget: androidx.glance.appwidget.GlanceAppWidget, size: androidx.compose.ui.unit.DpSize): Channel<RemoteViews> {
        val frames = Channel<RemoteViews>(Channel.UNLIMITED)
        scope.launch { widget.runComposition(app, sizes = listOf(size)).collect { frames.send(it) } }
        return frames
    }

    @Test(timeout = 60_000)
    fun aDecisionArrivingAndASignOutAreDrawnWhileTheNeedsYouWidgetIsShown() = runBlocking {
        val feed = AppCore.widgets(app)
        feed.signedOut()
        val frames = composing(NeedsYouWidget(), NeedsYouWidget.MEDIUM)
        frames.until("the signed-out widget") { WidgetCopy.SIGNED_OUT in it }

        // A decided push, through the app's writer and redraw.
        AppCore.widgetsDecided(app, WidgetFixtures.permission(at = System.currentTimeMillis() - WidgetFixtures.MINUTE))
        frames.until("the new decision, in the same composition") { "Run git push?" in it }

        // Answered from its notification: marked sent, and drawn at once.
        AppCore.widgetsAnswered(app, "!r:hs", "\$p", "Allow once")
        frames.until("marked sent") { "Sent: Allow once" in it }

        // Signed out: the last account's decision goes at once.
        AppCore.widgetRedraw(app).after(feed.signedOut())
        val last = frames.until("signed out again") { WidgetCopy.SIGNED_OUT in it }
        assertFalse("Run git push?" in last)
    }

    @Test(timeout = 60_000)
    fun aWriteIsDrawnWhileTheAgentsWidgetIsShown() = runBlocking {
        val feed = AppCore.widgets(app)
        feed.signedOut()
        val frames = composing(AgentsWidget(), AgentsWidget.MEDIUM)
        frames.until("the signed-out widget") { WidgetCopy.SIGNED_OUT in it }
        AppCore.widgetsDecided(app, WidgetFixtures.permission(at = System.currentTimeMillis() - WidgetFixtures.MINUTE))
        frames.until("signed in, in the same composition") { WidgetCopy.SIGNED_OUT !in it }
        Unit
    }
}
