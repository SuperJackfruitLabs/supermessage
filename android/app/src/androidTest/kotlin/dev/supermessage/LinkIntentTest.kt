package dev.supermessage

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How a `supermessage://` link reaches the app as an Intent: from another app's task, and again
 * from Recents. The real MainActivity, so the manifest's launch mode is what is under test.
 */
@RunWith(AndroidJUnit4::class)
class LinkIntentTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val link = Uri.parse("supermessage://open?room=%21linked%3Ax")
    private val started = mutableListOf<Activity>()

    @After
    fun finishAll() {
        instrumentation.runOnMainSync { started.forEach { it.finishAndRemoveTask() } }
        instrumentation.waitForIdleSync()
    }

    private fun mainActivities(): List<MainActivity> {
        var found = emptyList<MainActivity>()
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            found = Stage.values().filter { it != Stage.DESTROYED }
                .flatMap { monitor.getActivitiesInStage(it) }
                .filterIsInstance<MainActivity>()
                .distinct()
        }
        return found
    }

    /**
     * Another app's screen starts the link in its own task, with no NEW_TASK flag, as a
     * TextView's link does. The app already runs in its task: the link must reach that one
     * MainActivity, not start a second in the other app's task.
     */
    @Test
    fun aLinkFromAnotherAppsTaskReachesTheRunningApp() {
        val app = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        started += app
        // Stands in for the other app: a screen at the root of a task of its own.
        val other = instrumentation.startActivitySync(
            Intent(context, ComponentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
        )
        started += other
        assertNotEquals("the other screen has its own task", app.taskId, other.taskId)

        instrumentation.runOnMainSync {
            other.startActivity(
                Intent(Intent.ACTION_VIEW, link)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .setPackage(context.packageName),
            )
        }
        val deadline = System.currentTimeMillis() + 10_000
        var delivered: Uri? = null
        while (System.currentTimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { delivered = app.intent?.data }
            if (delivered == link || mainActivities().size > 1) break
            Thread.sleep(100)
        }
        val all = mainActivities()
        started += all.filter { it !== app }
        assertEquals("one MainActivity: $all", listOf(app), all)
        assertEquals("the running instance was handed the link", link, delivered)
    }

    /**
     * Reopening the app from Recents relaunches its task with the Intent that started it. A link
     * that started the app was followed then, and is not followed again.
     */
    @Test
    fun aLinkReopenedFromRecentsIsNotFollowedAgain() {
        val view = Intent(Intent.ACTION_VIEW, link)
        assertEquals("!linked:x", linkedRoom(view))
        assertNull(linkedRoom(Intent(view).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)))
        assertNull(linkedRoom(Intent(Intent.ACTION_MAIN)))
        assertNull(linkedRoom(null))
    }
}
