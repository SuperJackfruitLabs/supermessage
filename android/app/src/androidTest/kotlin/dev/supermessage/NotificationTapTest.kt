package dev.supermessage

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.supermessage.notify.AndroidPoster
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.supermessage_core.NotificationCategory
import uniffi.supermessage_core.PlannedNotification

/** Tapping a note must reach the running Activity, not rebuild it and its Session. */
@RunWith(AndroidJUnit4::class)
class NotificationTapTest {
    @Test
    fun tappingANoteKeepsTheSameActivityAndViewModel() {
        val context: Context = ApplicationProvider.getApplicationContext()
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        AndroidPoster(context, accountId = "@me:x").post(
            PlannedNotification(
                id = "\$tap", roomId = "!fake:x", eventId = "\$tap", title = "Lyra", subtitle = null,
                body = "hello", category = NotificationCategory.MESSAGE, permission = null, gate = null,
            ),
        )
        // The system takes a post asynchronously.
        val deadline = System.currentTimeMillis() + 10_000
        while (manager.activeNotifications.none { it.tag == "\$tap" }) {
            check(System.currentTimeMillis() < deadline) { "the note never appeared" }
            Thread.sleep(50)
        }
        val pending = manager.activeNotifications.single { it.tag == "\$tap" }.notification.contentIntent
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var first: MainActivity? = null
            var model: SessionViewModel? = null
            scenario.onActivity {
                first = it
                model = androidx.lifecycle.ViewModelProvider(it)[SessionViewModel::class.java]
            }
            pending.send()
            Thread.sleep(2_000)
            scenario.onActivity {
                assertSame("the same Activity instance", first, it)
                assertFalse(it.isFinishing)
                assertSame(model, androidx.lifecycle.ViewModelProvider(it)[SessionViewModel::class.java])
            }
        }
        manager.cancelAll()
    }
}
