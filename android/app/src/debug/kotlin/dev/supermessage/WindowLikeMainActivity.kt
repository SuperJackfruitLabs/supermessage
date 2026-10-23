package dev.supermessage

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge

/**
 * Debug only: an empty host whose window is set up as [MainActivity]'s is, for tests that
 * compose real content over a fake core and depend on how the system treats the window
 * (the soft keyboard, above all). Edge to edge, and the soft-input mode the manifest
 * declares for [MainActivity], read from the installed package so that declaration is what
 * the tests exercise. Both are applied in `onCreate`, before the window is attached, as
 * [MainActivity] does: set on an attached window instead, API 34 still pans it for the
 * keyboard whatever the mode says (measured on the API 34 emulator), and API 36 does not.
 */
class WindowLikeMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        window.setSoftInputMode(
            packageManager.getActivityInfo(ComponentName(this, MainActivity::class.java), 0).softInputMode,
        )
        super.onCreate(savedInstanceState)
    }
}
