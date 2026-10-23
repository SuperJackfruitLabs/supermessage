package dev.supermessage.notify

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import dev.supermessage.kit.StubCore
import dev.supermessage.kit.notify.NotificationCoordinator
import java.util.Collections
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uniffi.supermessage_core.Membership
import uniffi.supermessage_core.PushRegistration
import uniffi.supermessage_core.RoomAffordance
import uniffi.supermessage_core.RoomIdentity
import uniffi.supermessage_core.RoomPreview
import uniffi.supermessage_core.RoomRow
import uniffi.supermessage_core.RoomSummary
import uniffi.supermessage_ffi.notificationsForRoster

/**
 * Endpoint → discovery → `PushRegistration` → `registerPusher`, with a fake
 * probe and a recording core. No pusher is ever registered anywhere real.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PushRegistrarTest {
    private class Recording(@Volatile var signedIn: Boolean = true) : StubCore() {
        val registered: MutableList<PushRegistration> = Collections.synchronizedList(mutableListOf())
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun restoreSessionQuietly(): Boolean = signedIn
        override fun registerPusher(registration: PushRegistration) {
            calls += "register ${registration.pushkey}"
            registered += registration
        }
        override fun unregisterPusher() {
            calls += "unregister"
        }
    }

    private val ntfy = "https://ntfy.sh/upAbC?up=1"
    private val gatewayAnswer = """{"unifiedpush":{"gateway":"matrix"}}"""

    private val core = Recording()
    private val probed = Collections.synchronizedList(mutableListOf<String>())
    private var answer: String? = gatewayAnswer
    private var distributorUnregistered = 0
    private val coordinator = NotificationCoordinator(poster = {}, modeOf = { null }, now = { 0L })

    private lateinit var savedLocale: Locale

    @Before
    fun pinTheLanguage() {
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("fr-CA"))
    }

    @After
    fun restoreTheLanguage() = Locale.setDefault(savedLocale)

    private val registrar = PushRegistrar(
        core = { core },
        unregisterDistributor = { distributorUnregistered++ },
        probe = { url -> probed += url; answer },
        coordinator = { coordinator },
    )

    private fun endpoint(url: String = ntfy) = runBlocking { registrar.newEndpoint(url, distributor = "ntfy") }

    @Test
    fun aNewEndpointRegistersAnEventIdOnlyPusherAtTheDiscoveredGateway() {
        endpoint()
        assertEquals(listOf("https://ntfy.sh/_matrix/push/v1/notify"), probed.toList())
        // `event_id_only` is the core's (`C/push.rs:47-60`, `pusher_for`): nothing
        // in a PushRegistration can make a push carry content.
        assertEquals(
            listOf(
                PushRegistration(
                    pushkey = ntfy,
                    appId = "dev.supermessage",
                    appDisplayName = "supermessage",
                    deviceDisplayName = Build.MODEL,
                    lang = "fr-CA",
                    gatewayUrl = "https://ntfy.sh/_matrix/push/v1/notify",
                ),
            ),
            core.registered.toList(),
        )
        assertEquals(
            "the app id is the applicationId",
            ApplicationProvider.getApplicationContext<android.content.Context>().packageName,
            PushRegistrar.APP_ID,
        )
        assertEquals(ntfy, registrar.registeredEndpoint)
        assertEquals(PushStatus.On("ntfy"), registrar.status.value)
    }

    /** Q2: no third-party fallback. */
    @Test
    fun noMatrixGatewayRegistersNothing() {
        answer = """{"unifiedpush":{"version":1}}"""
        endpoint()
        assertEquals(emptyList<PushRegistration>(), core.registered.toList())
        assertEquals(PushStatus.NoMatrixGateway, registrar.status.value)
        assertFalse(registrar.remotePush)
        assertFalse(coordinator.context.remotePush)

        answer = null // unreachable
        endpoint("https://push.example.org/up/1")
        assertEquals(emptyList<PushRegistration>(), core.registered.toList())
        assertEquals(PushStatus.NoMatrixGateway, registrar.status.value)
        assertTrue("only the endpoint's own server is asked", probed.all { it.startsWith("https://ntfy.sh/") || it.startsWith("https://push.example.org/") })
    }

    /** `PlatformCoordinator.swift:193`. */
    @Test
    fun theSameEndpointIsNotRegisteredTwice() {
        endpoint()
        endpoint()
        assertEquals(listOf("register $ntfy"), core.calls.toList())
        endpoint("https://ntfy.sh/upNew")
        assertEquals("a new endpoint replaces the old", listOf("register $ntfy", "register https://ntfy.sh/upNew"), core.calls.toList())
    }

    @Test
    fun onlyASignedInAccountRegisters() {
        core.signedIn = false
        endpoint()
        assertEquals(emptyList<String>(), core.calls.toList())
        assertNull(registrar.registeredEndpoint)
    }

    @Test
    fun aRegisteredPusherTurnsTheRosterPathOff() {
        val before = listOf(room(unread = 0))
        val after = listOf(room(unread = 1))
        assertEquals("without push the roster notifies", 1, notificationsForRoster(before, after, coordinator.context).size)
        endpoint()
        assertTrue(coordinator.context.remotePush)
        assertEquals(emptyList<Any>(), notificationsForRoster(before, after, coordinator.context))
    }

    /** RF5: the core's logout already removed the pusher; the distributor is told, and the endpoint forgotten. */
    @Test
    fun signingOutUnregistersAndForgets() {
        endpoint()
        registrar.signedOut()
        assertEquals(1, distributorUnregistered)
        assertNull(registrar.registeredEndpoint)
        assertFalse(registrar.remotePush)
        assertFalse(coordinator.context.remotePush)
        assertNull(registrar.status.value)
        assertEquals("nothing more is asked of the core", listOf("register $ntfy"), core.calls.toList())

        endpoint()
        assertEquals("the next sign-in registers again", listOf("register $ntfy", "register $ntfy"), core.calls.toList())
    }

    @Test
    fun theDistributorGoingAwayRemovesThePusherAndTheNextEndpointRegisters() {
        endpoint()
        runBlocking { registrar.unregistered() }
        assertEquals(listOf("register $ntfy", "unregister"), core.calls.toList())
        assertEquals(PushStatus.NoDistributor, registrar.status.value)
        assertFalse(coordinator.context.remotePush)

        endpoint()
        assertEquals(listOf("register $ntfy", "unregister", "register $ntfy"), core.calls.toList())
        assertEquals(PushStatus.On("ntfy"), registrar.status.value)
    }

    @Test
    fun noDistributorSaysSo() {
        runBlocking { registrar.noDistributor() }
        assertEquals(PushStatus.NoDistributor, registrar.status.value)
        assertEquals("the stored pusher goes", listOf("unregister"), core.calls.toList())
    }

    /**
     * A process that was killed and restarted has nothing in memory, but the
     * core persisted the pusher; every path that ends push must still ask the
     * core to remove it (it is a no-op when none is stored).
     */
    private fun coldRegistrar() = PushRegistrar(
        core = { core },
        unregisterDistributor = { distributorUnregistered++ },
        probe = { url -> probed += url; answer },
        coordinator = { coordinator },
    )

    @Test
    fun aColdRegistrarRemovesTheStoredPusherWhenTheDistributorUnregisters() {
        runBlocking { coldRegistrar().unregistered() }
        assertEquals(listOf("unregister"), core.calls.toList())
    }

    @Test
    fun aColdRegistrarRemovesTheStoredPusherWhenThereIsNoDistributor() {
        runBlocking { coldRegistrar().noDistributor() }
        assertEquals(listOf("unregister"), core.calls.toList())
    }

    @Test
    fun aColdRegistrarRemovesTheStoredPusherWhenTheServerHasNoMatrixGateway() {
        answer = """{"unifiedpush":{"version":1}}"""
        runBlocking { coldRegistrar().newEndpoint(ntfy, distributor = "ntfy") }
        assertEquals(listOf("unregister"), core.calls.toList())
        assertEquals(emptyList<PushRegistration>(), core.registered.toList())
    }

    private fun room(unread: Long) = RoomRow(
        room = RoomSummary(
            id = "!a:x", name = "Atlas", avatarUrl = null, unread = unread.toULong(), lastMessage = "hi",
            lastMessageIsOwn = false, lastMessageNamesSender = false, lastEventType = null,
            lastActivityMs = 5uL, runtime = null, membership = Membership.JOINED,
        ),
        identity = RoomIdentity(glyph = null, name = "Atlas", role = null, initial = "A"),
        preview = RoomPreview(text = "hi", pending = false),
        affordance = RoomAffordance.COMPOSE,
    )
}
