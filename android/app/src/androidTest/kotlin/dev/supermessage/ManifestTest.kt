package dev.supermessage

import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
}
