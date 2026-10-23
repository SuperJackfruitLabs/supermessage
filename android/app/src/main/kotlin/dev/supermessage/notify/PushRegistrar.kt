package dev.supermessage.notify

import android.app.Activity
import android.content.Context
import android.os.Build
import dev.supermessage.AppCore
import dev.supermessage.kit.CoreClient
import dev.supermessage.kit.notify.NotificationCoordinator
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.unifiedpush.android.connector.INSTANCE_DEFAULT
import org.unifiedpush.android.connector.UnifiedPush
import uniffi.supermessage_core.PushRegistration
import uniffi.supermessage_ffi.CoreInterface

/**
 * What Account → Notifications says about background push (owner decisions
 * Q1/Q2) *(no iOS copy)*. `null` (not yet known, or a registration that did
 * not land) shows no row.
 */
sealed interface PushStatus {
    data class On(val distributor: String) : PushStatus
    data object NoDistributor : PushStatus
    data object NoMatrixGateway : PushStatus
    data object NotificationsOff : PushStatus
}

/** The row's words, verbatim from the plan (Q1/Q2) *(no iOS copy)*. */
internal fun PushStatus.words(): String = when (this) {
    is PushStatus.On -> "On, through $distributor"
    PushStatus.NoDistributor ->
        "Off: install a UnifiedPush app such as ntfy to get notifications while supermessage is closed"
    PushStatus.NoMatrixGateway -> "Off: your UnifiedPush server can't take Matrix notifications"
    PushStatus.NotificationsOff -> "Off: notifications are turned off for supermessage"
}

/**
 * Turns a UnifiedPush endpoint into this device's pusher
 * (`PlatformCoordinator.swift:188-205`, `PushConfiguration.swift:95-103`):
 * endpoint → gateway discovery → [PushRegistration] → `registerPusher`.
 *
 * - **Only when signed in.** `restoreSessionQuietly` is the check, and the
 *   way a push service in a cold process gets a session at all.
 * - **Never the same endpoint twice.** The registered endpoint is kept in
 *   memory, as iOS keeps `registeredPusher`; a new one replaces the old (the
 *   core removes a pusher whose pushkey changed, `C/session.rs:1324-1328`).
 * - **No fallback gateway** (Q2): see [PushGateway].
 * - A registered pusher sets the live coordinator's `remotePush`, so local
 *   notifications step aside for what the pushes now show
 *   (`NotificationContext.remotePush`, `PlatformCoordinator.swift:219`).
 *
 * One per process ([AppCore.registrar]): the push service and the Activity
 * both reach it. Calls that change it are serialised.
 */
internal class PushRegistrar(
    private val core: () -> CoreInterface,
    /** `UnifiedPush.unregister(context, INSTANCE_DEFAULT)`; a seam, so tests reach no distributor. */
    private val unregisterDistributor: () -> Unit,
    private val probe: GatewayProbe = HttpGatewayProbe,
    private val coordinator: () -> NotificationCoordinator? = { AppCore.coordinator },
    private val deviceName: () -> String = { Build.MODEL },
    private val language: () -> String = { Locale.getDefault().toLanguageTag() },
) {
    private val lock = Mutex()

    /** Bumped on sign-out: a registration under way must not land for the account that left. */
    @Volatile
    private var generation = 0

    @Volatile
    var registeredEndpoint: String? = null
        private set

    private val _status = MutableStateFlow<PushStatus?>(null)
    val status: StateFlow<PushStatus?> = _status.asStateFlow()

    /** Whether this device has a pusher: what a newly built coordinator starts from. */
    val remotePush: Boolean get() = registeredEndpoint != null

    suspend fun newEndpoint(endpoint: String, distributor: String): Unit = lock.withLock {
        if (endpoint == registeredEndpoint) return@withLock
        val mine = generation
        val client = withContext(Dispatchers.IO) { CoreClient(core()) }
        if (!attempt { client.restoreSessionQuietly() }) return@withLock
        val gateway = PushGateway.discoveryUrl(endpoint)
            ?.takeIf { url -> probe.get(url)?.let(PushGateway::isMatrixGateway) == true }
        if (mine != generation) return@withLock
        if (gateway == null) {
            // The core works from its persisted pusher, so this removes one a
            // previous process registered too; it is a no-op when none is stored.
            attempt { client.unregisterPusher(); true }
            forget()
            _status.value = PushStatus.NoMatrixGateway
            return@withLock
        }
        val registration = PushRegistration(
            pushkey = endpoint,
            appId = APP_ID,
            appDisplayName = "supermessage",
            deviceDisplayName = deviceName(),
            lang = language(),
            gatewayUrl = gateway,
        )
        val landed = attempt { client.registerPusher(registration); true }
        if (mine != generation) return@withLock
        if (!landed) {
            // The core removes a changed pushkey's pusher before it sets the
            // new one, so a failure here leaves none. The next foreground asks again.
            forget()
            _status.value = null
            return@withLock
        }
        registeredEndpoint = endpoint
        coordinator()?.setRemotePush(true)
        _status.value = PushStatus.On(distributor)
    }

    /** No distributor on the device, or none the reader picked. Local notifications only. */
    suspend fun noDistributor(): Unit = lock.withLock {
        removeStoredPusher()
        forget()
        _status.value = PushStatus.NoDistributor
    }

    /**
     * The distributor unregistered this app (it went away, or the reader
     * removed it there). Its endpoint is dead, so the pusher naming it is
     * removed through the core; the next endpoint registers afresh.
     */
    suspend fun unregistered(): Unit = lock.withLock {
        removeStoredPusher()
        forget()
        _status.value = PushStatus.NoDistributor
    }

    /** The distributor could not register (network, or it needs the reader's action there). */
    suspend fun registrationFailed(): Unit = lock.withLock {
        forget()
        _status.value = null
    }

    /**
     * RF5. The core's logout already removed the pusher (`C/session.rs:297`);
     * the distributor is told, and everything is forgotten, so the next
     * account registers afresh.
     */
    fun signedOut() {
        generation++
        unregisterDistributor()
        forget()
        _status.value = null
    }

    /**
     * Ask the core to remove this device's pusher. It reads the pusher it
     * persisted (`C/session.rs:1394-1398`) and does nothing when there is none,
     * so no in-memory state is consulted: a cold process still removes what an
     * earlier one registered.
     */
    private suspend fun removeStoredPusher() {
        val client = withContext(Dispatchers.IO) { CoreClient(core()) }
        attempt { client.restoreSessionQuietly() && run { client.unregisterPusher(); true } }
    }

    private fun forget() {
        registeredEndpoint = null
        coordinator()?.setRemotePush(false)
    }

    private suspend fun attempt(body: suspend () -> Boolean): Boolean = try {
        body()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    companion object {
        /** The applicationId, which the pusher's `app_id` names. */
        const val APP_ID = "dev.supermessage"
    }
}

/**
 * Asks the connector for a distributor, and registers with it
 * (`UnifiedPush.tryUseCurrentOrDefaultDistributor`). The distributor answers
 * with an endpoint, which reaches [SupermessagePushService.onNewEndpoint].
 * With none, the registrar says so and nothing else happens: no dialog,
 * local notifications keep working (Q1).
 */
internal fun askForPush(activity: Activity, registrar: PushRegistrar) {
    val context = activity.applicationContext
    UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { ok ->
        if (ok) {
            UnifiedPush.register(context, INSTANCE_DEFAULT, null, null)
        } else {
            AppCore.scope.launch { registrar.noDistributor() }
        }
    }
}

/** The distributor's app label ("ntfy"), or its package name when it has none to give. */
internal fun distributorLabel(context: Context): String {
    val pkg = UnifiedPush.getAckDistributor(context) ?: UnifiedPush.getSavedDistributor(context) ?: return "UnifiedPush"
    return try {
        val pm = context.packageManager
        // The int overload: the flags object is API 33, and minSdk is 31.
        @Suppress("DEPRECATION")
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }
}
