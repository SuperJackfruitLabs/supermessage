package dev.supermessage

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.supermessage.kit.Session.Phase
import dev.supermessage.notify.Channels
import dev.supermessage.notify.PlatformCoordinator
import dev.supermessage.notify.ensureChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlatformNotificationsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Before
    fun allowNotifications() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun waitUntil(what: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!check()) {
            check(System.currentTimeMillis() < deadline) { what }
            Thread.sleep(50)
        }
    }

    @Test
    fun signingOutWithdrawsEveryNotification() {
        ensureChannels(context)
        manager.cancelAll()
        for (id in 1..2) {
            val n = NotificationCompat.Builder(context, Channels.MESSAGES)
                .setSmallIcon(R.drawable.ic_stat_supermessage)
                .setContentTitle("Room $id")
                .setGroup("!room$id:x")
                .build()
            NotificationManagerCompat.from(context).notify(id, n)
        }
        waitUntil("two notifications posted") { manager.activeNotifications.size == 2 }

        val phases = MutableStateFlow(Phase.SIGNED_IN)
        val coordinator = PlatformCoordinator(context, phases, permissionLauncher = {})
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            scope.launch { coordinator.run() }
            waitUntil("the coordinator saw SIGNED_IN") { coordinator.lastPhase == Phase.SIGNED_IN }
            assertEquals("signed in: nothing is withdrawn", 2, manager.activeNotifications.size)

            phases.value = Phase.SIGNED_OUT

            waitUntil("every notification withdrawn") { manager.activeNotifications.isEmpty() }
            assertTrue(manager.activeNotifications.isEmpty())
        } finally {
            scope.cancel()
        }
    }
}
