package dev.supermessage.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import dev.supermessage.AppCore

/**
 * The Needs you widget's provider.
 *
 * It owns the redraw schedule's lifetime ([WidgetRedraw]): armed when the first widget is
 * placed and on every system update (which the system also sends after a reboot, so the
 * frame alarm a reboot dropped is set again), cancelled when the last is removed.
 */
class NeedsYouWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NeedsYouWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        AppCore.widgetRedraw(context).arm()
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        AppCore.widgetRedraw(context).arm()
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        AppCore.widgetRedraw(context).cancelAll()
    }
}
