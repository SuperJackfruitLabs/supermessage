package dev.supermessage

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.supermessage.kit.widgets.WidgetFixtures
import dev.supermessage.widgets.NeedsYouWidgetReceiver
import dev.supermessage.widgets.WidgetCopy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * C1, on a device: the Needs you widget placed in a real `AppWidgetHost`, so Glance runs a
 * real session for it, and every write the app makes within that session (at least 45 s, the
 * session's first timeout) is what the host shows. `updateAll` on a running session reloads
 * only Glance's own state, so a widget that read the snapshot once, outside composition, kept
 * showing that first read here. **Nothing here touches a real account**: the writes are the
 * core's merges of fixture decisions into this app's snapshot file, reset after.
 */
@RunWith(AndroidJUnit4::class)
class WidgetLiveSessionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = AppWidgetManager.getInstance(context)
    private lateinit var host: AppWidgetHost
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var hostView: AppWidgetHostView? = null

    /** This process's user id (`UserHandle.getUserId`, which is hidden). */
    private val user = android.os.Process.myUid() / 100_000

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { String(it.readBytes()) }

    @Before
    fun place() {
        AppCore.installWidgetsForTest(null)
        runBlocking { AppCore.widgets(context).signedOut() }
        // What a launcher has: the right to bind this app's widgets. (`--user current` is
        // refused on the API 36 image, so the user is named.)
        shell("appwidget grantbind --package ${context.packageName} --user $user")
        instrumentation.runOnMainSync {
            host = AppWidgetHost(context, HOST_ID)
            host.startListening()
            widgetId = host.allocateAppWidgetId()
        }
        val provider = ComponentName(context, NeedsYouWidgetReceiver::class.java)
        check(manager.bindAppWidgetIdIfAllowed(widgetId, provider)) { "the widget could not be bound" }
        instrumentation.runOnMainSync {
            hostView = host.createView(context, widgetId, manager.getAppWidgetInfo(widgetId))
        }
    }

    @After
    fun remove() {
        instrumentation.runOnMainSync {
            host.deleteAppWidgetId(widgetId)
            host.stopListening()
            host.deleteHost()
        }
        runBlocking { AppCore.widgets(context).signedOut() }
        shell("appwidget revokebind --package ${context.packageName} --user $user")
    }

    private fun drawn(): List<String> {
        var texts = emptyList<String>()
        instrumentation.runOnMainSync { texts = hostView?.let(::texts).orEmpty() }
        return texts
    }

    private fun texts(view: View): List<String> =
        (view as? TextView)?.let { listOf(it.text.toString()) }.orEmpty() +
            ((view as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { texts(g.getChildAt(it)) } }.orEmpty())

    private fun waitFor(what: String, check: (List<String>) -> Boolean): List<String> {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var last = drawn()
        while (!check(last)) {
            check(SystemClock.elapsedRealtime() < deadline) { "never drawn: $what; the host shows $last" }
            Thread.sleep(100)
            last = drawn()
        }
        return last
    }

    @Test
    fun everyWriteWithinTheWidgetsSessionIsDrawn() {
        // Binding sends the provider its first update: Glance starts the session and draws.
        waitFor("the signed-out widget") { WidgetCopy.SIGNED_OUT in it }
        val sessionBegan = SystemClock.elapsedRealtime()

        // A decided push, through the app's own writer and redraw.
        runBlocking {
            AppCore.widgetsDecided(context, WidgetFixtures.permission(at = System.currentTimeMillis() - WidgetFixtures.MINUTE))
        }
        waitFor("the new decision") { "Run git push?" in it }

        // Its answer landed from the notification: the widget stops offering it.
        runBlocking { AppCore.widgetsAnswered(context, "!r:hs", "\$p", "Allow once") }
        waitFor("marked sent") { "Sent: Allow once" in it }

        // Signed out: the last account's decision leaves at once.
        runBlocking { AppCore.widgetRedraw(context).after(AppCore.widgets(context).signedOut()) }
        val last = waitFor("signed out again") { WidgetCopy.SIGNED_OUT in it }
        assertFalse("Run git push?" in last)

        assertTrue(
            "all inside the one session (Glance's first timeout is 45 s)",
            SystemClock.elapsedRealtime() - sessionBegan < 45_000,
        )
    }

    private companion object {
        const val HOST_ID = 4_217
    }
}
