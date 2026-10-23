package dev.supermessage

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.supermessage.kit.StubCore
import dev.supermessage.notify.PushRegistrar
import java.util.Collections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.unifiedpush.android.connector.INSTANCE_DEFAULT
import org.unifiedpush.android.connector.UnifiedPush
import uniffi.supermessage_core.AccountDto
import uniffi.supermessage_core.NotificationCategory
import uniffi.supermessage_core.NotificationDto
import uniffi.supermessage_core.PushRegistration

/**
 * The real connector, the real manifest and [SupermessagePushService], driven
 * by [FakeDistributor] in the test APK through the UnifiedPush broadcasts.
 * The core is a recording fake and the gateway probe answers locally, so no
 * pusher is registered anywhere and no network is touched.
 */
@RunWith(AndroidJUnit4::class)
class FakeDistributorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val distributor = InstrumentationRegistry.getInstrumentation().context.packageName

    private class Recording : StubCore() {
        val registered: MutableList<PushRegistration> = Collections.synchronizedList(mutableListOf())
        override fun restoreSessionQuietly(): Boolean = true
        override fun account(): AccountDto = AccountDto(userId = "@me:x", homeserver = "https://x")
        override fun registerPusher(registration: PushRegistration) {
            registered += registration
        }
        override fun unregisterPusher() {}
        override fun notificationFor(roomId: String, eventId: String) = NotificationDto(
            roomId = roomId, eventId = eventId, title = "Lyra", subtitle = null, body = "the build is green",
            category = NotificationCategory.MESSAGE, permission = null, gate = null, threadId = roomId,
            suppress = null, fallbackTitle = null, fallbackBody = null, activity = null,
        )
    }

    private val core = Recording()

    @Before
    fun setUp() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        manager.cancelAll()
        AppCore.coordinator = null
        AppCore.installForTest(core)
        // The fake answers REGISTER with an endpoint and a message at once, so the message can
        // beat the registrar's stamp. A real homeserver pushes only once the pusher is set, by
        // which time its account is stamped; this is that state.
        AppCore.pushOwner(context).account = "@me:x"
        AppCore.installRegistrarForTest(
            PushRegistrar(
                core = { core },
                unregisterDistributor = {},
                probe = { url -> if (url == FakeDistributor.GATEWAY) """{"unifiedpush":{"gateway":"matrix"}}""" else null },
                owner = AppCore.pushOwner(context),
            ),
        )
        UnifiedPush.saveDistributor(context, distributor)
        UnifiedPush.register(context, INSTANCE_DEFAULT, null, null)
    }

    @After
    fun tearDown() {
        UnifiedPush.removeDistributor(context)
        androidx.work.WorkManager.getInstance(context).cancelAllWorkByTag(dev.supermessage.notify.PushWorker.TAG)
        AppCore.pushOwner(context).account = null
        AppCore.installRegistrarForTest(null)
        AppCore.installForTest(null)
        manager.cancelAll()
    }

    private fun waitUntil(what: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!check()) {
            check(System.currentTimeMillis() < deadline) { what }
            Thread.sleep(50)
        }
    }

    @Test
    fun aFakeDistributorsEndpointBecomesThePusher() {
        waitUntil("the endpoint never reached the registrar") { core.registered.isNotEmpty() }
        val registration = core.registered.single()
        assertEquals(FakeDistributor.ENDPOINT, registration.pushkey)
        assertEquals(FakeDistributor.GATEWAY, registration.gatewayUrl)
        assertEquals("dev.supermessage", registration.appId)
    }

    @Test
    fun aFakeDistributorsMessagePostsTheNotification() {
        waitUntil("the push never posted") { manager.activeNotifications.any { it.tag == "\$push1" } }
        val shown = manager.activeNotifications.single { it.tag == "\$push1" }.notification
        assertEquals("Lyra", shown.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("the build is green", shown.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }
}
