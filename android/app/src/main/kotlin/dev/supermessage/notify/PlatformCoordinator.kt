package dev.supermessage.notify

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.supermessage.kit.Session
import dev.supermessage.kit.notify.shouldAskForNotifications
import kotlinx.coroutines.flow.StateFlow

/**
 * Android's `PlatformCoordinator` (`Supermessage/Platform/PlatformCoordinator.swift`):
 * the host-side lifecycle of notifications. Later tasks extend it.
 *
 * - After a **fresh sign-in** it asks for notification permission, once
 *   (`:124-131`, `:161-173`); a restored session never prompts.
 * - On `SIGNED_IN → SIGNED_OUT` it withdraws every delivered notification
 *   (`:143-155`, `LocalNotifier.swift:101-107`), so the next account never
 *   sees the last one's, and unregisters from the UnifiedPush distributor
 *   (the core's logout already removed the pusher, `C/session.rs:297`).
 * - **Signed in, allowed to notify, and in front**, it asks the connector for
 *   background push (`:188-205`). [run] only runs while the Activity is
 *   started, so each return to the foreground asks again; the registrar
 *   never registers the same endpoint twice.
 */
internal class PlatformCoordinator(
    context: Context,
    private val phases: StateFlow<Session.Phase>,
    private val permissionLauncher: () -> Unit,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val granted: () -> Boolean = {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    },
    /** Asks the UnifiedPush connector for a distributor and registers ([askForPush]). */
    private val askForPush: () -> Unit = {},
    /** The registrar's sign-out: unregister from the distributor and forget. */
    private val pushSignedOut: () -> Unit = {},
) {
    constructor(
        context: Context,
        session: Session,
        permissionLauncher: () -> Unit,
        askForPush: () -> Unit = {},
        pushSignedOut: () -> Unit = {},
    ) : this(context, session.phase, permissionLauncher, askForPush = askForPush, pushSignedOut = pushSignedOut)

    private val context = context.applicationContext

    // Kept across restarts of [run]: a sign-in that happened while the app was
    // stopped is still a transition from SIGNED_OUT when it comes back.
    @Volatile
    internal var lastPhase: Session.Phase? = null
        private set

    /** Collects until cancelled. The Activity runs it while STARTED. */
    suspend fun run() {
        ensureChannels(context)
        phases.collect { now ->
            val before = lastPhase
            lastPhase = now
            if (shouldAskForNotifications(before, now, sdkInt, granted())) {
                permissionLauncher()
            }
            if (now == Session.Phase.SIGNED_IN && allowed()) askForPush()
            if (before == Session.Phase.SIGNED_IN && now == Session.Phase.SIGNED_OUT) {
                NotificationManagerCompat.from(context).cancelAll()
                pushSignedOut()
            }
        }
    }

    /** The permission dialog's answer: a grant while signed in asks for push at once. */
    fun permissionAnswered(granted: Boolean) {
        if (granted && lastPhase == Session.Phase.SIGNED_IN) askForPush()
    }

    /** API 31–32 have no runtime permission to grant. */
    private fun allowed(): Boolean = sdkInt < 33 || granted()
}
