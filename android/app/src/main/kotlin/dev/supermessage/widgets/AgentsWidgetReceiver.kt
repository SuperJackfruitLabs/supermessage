package dev.supermessage.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * The Agents widget's provider. Like [NeedsYouWidgetReceiver] it owns the shared redraw
 * schedule's lifetime ([WidgetRedraw]): armed on `onEnabled` and every `onUpdate`, and
 * cancelled on `onDisabled`, which the system sends when the last widget of **this** provider
 * goes. [WidgetRedraw.arm] looks at every provider of the app, so it cancels only when none is
 * left, not when the other widget is still placed.
 */
class AgentsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AgentsWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        armOffMain(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        armOffMain(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // The other widget may still be placed: arm() cancels only when none is.
        armOffMain(context)
    }
}
