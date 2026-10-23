package dev.supermessage

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.supermessage.widgets.NeedsYouWidgetReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The launcher can list the widget: the receiver, its metadata and its words are declared. */
@RunWith(AndroidJUnit4::class)
class WidgetPlacementTest {
    @Test
    fun theReceiverIsDeclaredAndProvidesTheWidget() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val manager = AppWidgetManager.getInstance(context)
        val component = ComponentName(context, NeedsYouWidgetReceiver::class.java)
        val provider = manager.getInstalledProvidersForPackage(context.packageName, null)
            .firstOrNull { it.provider == component }
        assertNotNull("the launcher does not list the Needs you widget", provider)
        provider!!
        assertEquals("Needs you", provider.loadLabel(context.packageManager))
        assertEquals("Decisions waiting on you — answer them from here.", provider.loadDescription(context)?.toString())
        assertEquals(1_800_000, provider.updatePeriodMillis)
        assertTrue(provider.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0)
        assertTrue(
            "resizable both ways",
            provider.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0 &&
                provider.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0,
        )
    }
}
