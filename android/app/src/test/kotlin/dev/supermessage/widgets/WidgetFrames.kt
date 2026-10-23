package dev.supermessage.widgets

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.GlanceRemoteViews
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking

/**
 * Renders a widget's Glance content as the launcher gets it (`RemoteViews`, inflated on the JVM),
 * light and dark, into `src/test/previews/<file>[Dark].png`, and fails a frame whose content is
 * clipped by its bucket, flush with the bottom edge, or whose header text is cut short.
 */
@OptIn(androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi::class)
internal object WidgetFrames {
    private fun contextFor(app: Application, dark: Boolean): Context {
        val config = Configuration(app.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        return app.createConfigurationContext(config)
    }

    /**
     * @param header the widget's title text, which may not be cut.
     * @param shows texts the rendered view must contain.
     */
    fun render(app: Application, file: String, size: DpSize, fontScale: Float, header: String, shows: List<String> = emptyList(), content: @Composable () -> Unit) {
        val name = file
        for (dark in listOf(false, true)) {
            val base = contextFor(app, dark)
            val context = if (fontScale == 1f) base else base.createConfigurationContext(
                Configuration(base.resources.configuration).apply { this.fontScale = fontScale },
            )
            val views = runBlocking { GlanceRemoteViews().compose(context, size) { content() }.remoteViews }
            val density = context.resources.displayMetrics.density
            val root = views.apply(context, FrameLayout(context))
            // Real clipping: draw the content with all the room it wants and find where it ends,
            // then ask that it end inside the bucket with the widget's own bottom padding left.
            // (A measured height would do, but Glance's weights make an unbounded measure lie.)
            val bucketPx = (size.height.value * density).toInt()
            val tall = bucketPx * 3
            val widthPx = (size.width.value * density).toInt()
            root.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(tall, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, widthPx, tall)
            val probe = android.graphics.Bitmap.createBitmap(widthPx, tall, android.graphics.Bitmap.Config.ARGB_8888)
            root.draw(android.graphics.Canvas(probe))
            val ground = probe.getPixel(widthPx / 2, tall - 1)
            val inset = (8 * density).toInt()
            val bottom = (tall - 1 downTo 0).first { y ->
                (3 * inset until widthPx - 3 * inset).any { x -> probe.getPixel(x, y) != ground }
            } + 1
            println("EXTENT $name dark=$dark ${bottom / density}dp of ${size.height.value}dp")
            org.junit.Assert.assertTrue(
                "$name${if (dark) " dark" else ""} is clipped or flush with the bottom edge: content ends at " +
                    "${bottom / density}dp of ${size.height.value}dp, which leaves under 8dp of padding",
                bottom + inset <= bucketPx,
            )
            root.measure(
                View.MeasureSpec.makeMeasureSpec((size.width.value * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((size.height.value * density).toInt(), View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            // The header is the one text that may not be cut ("Need..."): it is the widget's name.
            texts(root).filter { it.text.toString() == header }.forEach { title ->
                org.junit.Assert.assertEquals("$name: the header is on one line", 1, title.layout.lineCount)
                org.junit.Assert.assertEquals("$name: the header is cut short", 0, title.layout.getEllipsisCount(0))
            }
            // What the view model says is drawn really is: Glance drops what a container cannot hold.
            val drawn = texts(root).map { it.text.toString() }
            shows.forEach { org.junit.Assert.assertTrue("$name: \"$it\" is missing from what was drawn: $drawn", it in drawn) }
            val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
            // The launcher's own wallpaper is not ours; a neutral ground shows the widget's edge.
            bitmap.eraseColor(if (dark) 0xFF000000.toInt() else 0xFF808080.toInt())
            root.draw(android.graphics.Canvas(bitmap))
            bitmap.captureRoboImage("src/test/previews/$file${if (dark) "Dark" else ""}.png")
        }
    }

    private fun texts(view: View): List<android.widget.TextView> =
        (view as? android.widget.TextView)?.let { listOf(it) }.orEmpty() +
            ((view as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).flatMap { texts(g.getChildAt(it)) } }.orEmpty())
}
