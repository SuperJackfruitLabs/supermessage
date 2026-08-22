package dev.supermessage

import android.content.res.Configuration
import android.graphics.Color
import android.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The white-flash regression: `AndroidManifest.xml`'s declared theme must
 * resolve a *different* `android:windowBackground` under a dark
 * [Configuration] than under a light one, and the dark one must not be
 * white. This is the one thing `styles.xml` (plus its `values-night` twin)
 * exists to guarantee, and it has to be checked before Compose ever paints —
 * `SupermessageTheme`'s own tests (`ThemeTest`) already cover the tokens
 * Compose resolves *after* that first frame, which is a different, later
 * moment than the one this test is about.
 *
 * No screenshot, and no Activity launch: `createConfigurationContext` plus
 * `ContextThemeWrapper` resolves the manifest's declared theme resource
 * against an overridden [Configuration] directly, which is what makes this
 * a real assertion about the resolved attribute rather than an inference
 * from what the screen happens to show.
 */
@RunWith(AndroidJUnit4::class)
class StylesTest {
    private fun windowBackground(nightMode: Int): Int {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val overridden = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
        }
        val configuredContext = context.createConfigurationContext(overridden)
        // The manifest declares its theme on <application>, not on
        // MainActivity, so ApplicationInfo.theme is the resource id that
        // matters here.
        val themeResId = context.packageManager.getApplicationInfo(context.packageName, 0).theme
        val themed = ContextThemeWrapper(configuredContext, themeResId)
        val attrs = themed.obtainStyledAttributes(intArrayOf(android.R.attr.windowBackground))
        try {
            return attrs.getColor(0, Color.WHITE)
        } finally {
            attrs.recycle()
        }
    }

    @Test
    fun darkConfigurationWindowBackgroundIsNotWhite() {
        val background = windowBackground(Configuration.UI_MODE_NIGHT_YES)
        assertNotEquals(
            "values-night/styles.xml's windowBackground resolved to white under a dark " +
                "Configuration — the exact cold-start white flash this theme exists to prevent.",
            Color.WHITE,
            background,
        )
    }

    /**
     * Not merely "not white" — the two appearances must resolve to actually
     * different backgrounds, or `values-night/styles.xml` could be a dead
     * file that never takes effect.
     */
    @Test
    fun lightAndDarkConfigurationsResolveDifferentBackgrounds() {
        val light = windowBackground(Configuration.UI_MODE_NIGHT_NO)
        val dark = windowBackground(Configuration.UI_MODE_NIGHT_YES)
        assertNotEquals(light, dark)
    }

    /** The light half stays paper — matches `SupermessageColorRoles.light.ground`. */
    @Test
    fun lightConfigurationWindowBackgroundIsPaper() {
        assertEquals(0xFFF6F4EF.toInt(), windowBackground(Configuration.UI_MODE_NIGHT_NO))
    }
}
