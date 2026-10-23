package dev.supermessage

import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The manifest declarations that are load-bearing at runtime.
 *
 * These are not covered by any other test because nothing about them is a
 * compile error: the app builds, installs and launches perfectly well without
 * them, and fails only when it tries to do the one thing they permit.
 */
@RunWith(AndroidJUnit4::class)
class ManifestTest {
    /**
     * Without INTERNET the app cannot reach a homeserver at all.
     *
     * This is a regression test for a real bug, not a hypothetical. The
     * permission was missing from the scaffold onwards and nobody noticed,
     * because sign-in failed *earlier* — inside `load_or_create_passphrase`,
     * before any socket was opened. Implementing the Android secret store
     * moved the failure one layer outward and exposed it.
     *
     * What it looked like was `error sending request for url
     * (…/_matrix/client/versions)` — which reads like the homeserver is down,
     * not like a missing declaration. That is why this test exists: the
     * failure mode points away from the cause.
     */
    @Test
    fun theAppMayReachTheNetwork() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val declared = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()
        assertTrue(
            "android.permission.INTERNET is not declared: no request will leave the app. " +
                "Declared: ${declared.joinToString()}",
            declared.contains(android.Manifest.permission.INTERNET),
        )
    }

    @Test
    fun declaresPostNotifications() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val declared = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()
        assertTrue(
            "android.permission.POST_NOTIFICATIONS is not declared: on API 33+ no notification " +
                "can be shown. Declared: ${declared.joinToString()}",
            declared.contains(android.Manifest.permission.POST_NOTIFICATIONS),
        )
    }

    /** Voice messages record from the microphone; without it `MediaRecorder` cannot start. */
    @Test
    fun declaresRecordAudio() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val declared = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()
        assertTrue(
            "android.permission.RECORD_AUDIO is not declared. Declared: ${declared.joinToString()}",
            declared.contains(android.Manifest.permission.RECORD_AUDIO),
        )
    }

    /**
     * No component may run in a second process (the controller's note from
     * the Task 1 review): a second process loads a second native library and
     * opens a second core over the same store. The push service is the one
     * most tempting to move out, so it is named; the connector's own
     * components are checked with it.
     */
    @Test
    fun everyComponentRunsInTheAppsOneProcess() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS,
        )
        val process = info.applicationInfo!!.processName
        val components = info.activities.orEmpty().toList() + info.services.orEmpty() +
            info.receivers.orEmpty() + info.providers.orEmpty()
        assertTrue(
            "the push service is declared",
            components.any { it.name == dev.supermessage.notify.SupermessagePushService::class.java.name },
        )
        val elsewhere = components.filter { it.processName != process }.map { "${it.name} in ${it.processName}" }
        assertEquals("every component in $process", emptyList<String>(), elsewhere)
    }

    /** The connector binds `PUSH_EVENT` in this package; only this app reaches it. */
    @Test
    fun thePushServiceAnswersTheConnectorAndNoOneElse() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val resolved = context.packageManager.queryIntentServices(
            android.content.Intent("org.unifiedpush.android.connector.PUSH_EVENT").setPackage(context.packageName),
            0,
        )
        assertEquals(
            listOf(dev.supermessage.notify.SupermessagePushService::class.java.name),
            resolved.map { it.serviceInfo.name },
        )
        assertFalse("not exported", resolved.single().serviceInfo.exported)
    }

    /** The widgets' redraw alarms: declared, and reachable only by this app's own PendingIntents. */
    @Test
    fun theWidgetRedrawReceiverIsNotExported() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val receivers = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_RECEIVERS)
            .receivers.orEmpty()
        val redraw = receivers.single { it.name == dev.supermessage.widgets.WidgetRedrawReceiver::class.java.name }
        assertFalse("not exported", redraw.exported)
    }

    /**
     * `supermessage://open?room=` is the link iOS registers and its widgets build. Without the
     * filter the system finds no app for it and a tap on one does nothing.
     */
    @Test
    fun theLinkResolvesToTheApp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val link = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("supermessage://open?room=%21a%3Ax"),
        ).addCategory(android.content.Intent.CATEGORY_BROWSABLE).setPackage(context.packageName)
        val found = context.packageManager.queryIntentActivities(link, 0).map { it.activityInfo.name }
        assertTrue("MainActivity is not offered the link: $found", found.contains("dev.supermessage.MainActivity"))
    }

    /**
     * One MainActivity, in the app's own task. A link tapped in another app is started in that
     * app's task; under singleTop it made a second MainActivity there, with a second session and
     * core over the same stores. `LinkIntentTest` shows the link reaching the running instance.
     */
    @Test
    fun theAppRunsInOneTask() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info = context.packageManager.getActivityInfo(
            android.content.ComponentName(context, MainActivity::class.java), 0,
        )
        assertTrue(
            "MainActivity's launchMode is ${info.launchMode}, not singleTask",
            info.launchMode == android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK,
        )
    }
}
