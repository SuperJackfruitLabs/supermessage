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
import dev.supermessage.kit.Session
import dev.supermessage.kit.widgets.SnapshotStore
import dev.supermessage.kit.widgets.WidgetFeed
import dev.supermessage.kit.widgets.WidgetFixtures
import dev.supermessage.widgets.FileSnapshotStore
import dev.supermessage.widgets.NeedsYouWidgetReceiver
import dev.supermessage.widgets.WidgetCopy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.supermessage_core.WidgetContent

/**
 * C1, on a device: the Needs you widget placed in a real `AppWidgetHost`, so Glance runs a
 * real session for it, and every write the app makes within that session (at least 45 s, the
 * session's first timeout) is what the host shows. `updateAll` on a running session reloads
 * only Glance's own state, so a widget that read the snapshot once, outside composition, kept
 * showing that first read here. **Nothing here touches a real account**: the writes are the
 * core's merges of fixture decisions into this app's snapshot file, reset after.
 *
 * The snapshot is the process's, and so is its publisher: an earlier test in the same
 * instrumentation process can leave both signed in ([isolateTheSnapshot] says how, and why it
 * must be stopped before the reset).
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

    /** Set by [place] as each step completes, so a failed setup names the step it died in. */
    private var step = "not started"

    @Before
    fun place() {
        try {
            isolateTheSnapshot()
            // What a launcher has: the right to bind this app's widgets. (`--user current` is
            // refused on the API 36 image, so the user is named.)
            step = "granting bind permission"
            val granted = shell("appwidget grantbind --package ${context.packageName} --user $user")
            step = "starting the host (grantbind said: ${granted.trim().ifEmpty { "nothing" }})"
            instrumentation.runOnMainSync {
                host = AppWidgetHost(context, HOST_ID)
                host.startListening()
                widgetId = host.allocateAppWidgetId()
            }
            val provider = ComponentName(context, NeedsYouWidgetReceiver::class.java)
            step = "binding widget $widgetId (grantbind said: ${granted.trim().ifEmpty { "nothing" }})"
            check(manager.bindAppWidgetIdIfAllowed(widgetId, provider)) { "the widget could not be bound" }
            step = "creating the host view"
            instrumentation.runOnMainSync {
                hostView = host.createView(context, widgetId, manager.getAppWidgetInfo(widgetId))
            }
            step = "placed"
        } catch (e: Throwable) {
            // Say which step, here, so the cleanup below can never be the loudest thing.
            throw AssertionError("the widget test's setup failed while $step", e)
        }
    }

    /**
     * Leaves this test the only writer of a signed-out snapshot.
     *
     * An earlier test in this process can leave the process's [dev.supermessage.widgets.WidgetPublisher]
     * signed in, with its roster in the snapshot: `NotificationTapTest` runs the real MainActivity
     * over a signed-in fake core (two rooms that need you) and never signs it out. That publisher
     * still follows the process's foreground (`AppCore.observeProcess`), so when the previous
     * test's Activity stops, `ProcessLifecycleOwner` makes it write the recap about 700 ms later,
     * which is during this setup, and through its **own** feed and lock: `installWidgetsForTest`
     * gives this test a new [WidgetFeed] over the same file. Two read-merge-writes of one file
     * under two locks: on CI's slow AVD the old one read the signed-in snapshot before the reset
     * and wrote it back after (the widget drew "2 need you", never "Sign in"), or the two shared
     * the store's temporary file and the reset's rename failed (setup died on main's CI).
     *
     * So the old publisher is signed out first, through its own queue: the reset lands after
     * anything it has in flight, and a signed-out publisher writes nothing when the foreground
     * changes. Only then is this test's feed installed and the snapshot reset.
     */
    private fun isolateTheSnapshot() {
        step = "signing out the process's widget publisher"
        val previous = AppCore.widgetPublisher(context)
        previous.stopWatching()
        previous.phaseChanged(Session.Phase.SIGNED_OUT)
        runBlocking { previous.writesDone() }
        step = "installing the widget test hooks"
        AppCore.installWidgetsForTest(null)
        step = "resetting the widget snapshot"
        runBlocking { AppCore.widgets(context).signedOut() }
    }

    // Cleanup that is safe after any partial setup: it must never replace the real failure.
    @After
    fun remove() {
        instrumentation.runOnMainSync {
            if (::host.isInitialized) {
                runCatching { host.deleteAppWidgetId(widgetId) }
                runCatching { host.stopListening() }
                runCatching { host.deleteHost() }
            }
        }
        runCatching { runBlocking { AppCore.widgets(context).signedOut() } }
        runCatching { shell("appwidget revokebind --package ${context.packageName} --user $user") }
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

    /**
     * What broke this test on CI, made certain: a publisher an earlier test left signed in, with
     * the account's roster in the snapshot and a write in flight (held here, as a slow disk holds
     * it), cannot put that account back once [isolateTheSnapshot] has run. A reset made beside
     * that publisher, through another feed, is overwritten when its write lands.
     */
    @Test
    fun aPublisherLeftSignedInCannotUndoTheReset() {
        val reading = CountDownLatch(1)
        val held = CountDownLatch(1)
        val holding = AtomicBoolean(false)
        val file = FileSnapshotStore.inside(context.filesDir)
        val slow = object : SnapshotStore {
            override fun read(): String? = file.read()
            override fun write(json: String) {
                if (holding.get()) {
                    reading.countDown()
                    check(held.await(20, TimeUnit.SECONDS)) { "the held write was never let go" }
                }
                file.write(json)
            }
        }
        // What `NotificationTapTest` leaves: the process's publisher, signed in, its roster stored.
        AppCore.installWidgetsForTest(WidgetFeed(slow))
        val stale = AppCore.widgetPublisher(context)
        stale.phaseChanged(Session.Phase.SIGNED_IN)
        stale.roomsChanged(NavRows.fleet())
        runBlocking { stale.writesDone() }
        assertEquals(WidgetContent.SNAPSHOT, AppCore.widgets(context).view().content)

        // The previous test's Activity stops: the recap is read now and written a second later.
        holding.set(true)
        stale.activeChanged(false)
        check(reading.await(10, TimeUnit.SECONDS)) { "the old publisher never wrote" }
        Thread { SystemClock.sleep(1_000); held.countDown() }.start()

        isolateTheSnapshot()
        runBlocking { stale.writesDone() }
        assertEquals(
            "the reset is what is stored once the old publisher's write has landed",
            WidgetContent.SIGNED_OUT,
            AppCore.widgets(context).view().content,
        )
        // And a foreground change afterwards writes nothing of that account.
        stale.activeChanged(true)
        runBlocking { stale.writesDone() }
        assertEquals(WidgetContent.SIGNED_OUT, AppCore.widgets(context).view().content)
    }

    private companion object {
        const val HOST_ID = 4_217
    }
}
