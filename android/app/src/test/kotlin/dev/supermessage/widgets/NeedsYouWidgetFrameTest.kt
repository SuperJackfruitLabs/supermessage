package dev.supermessage.widgets

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.widget.FrameLayout
import android.view.View
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.supermessage.HostCore
import dev.supermessage.kit.widgets.WidgetFixtures
import dev.supermessage.kit.widgets.WidgetFixtures.MINUTE
import dev.supermessage.kit.widgets.WidgetFixtures.NOW
import kotlinx.coroutines.runBlocking
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uniffi.supermessage_core.WidgetView

/**
 * The widget as the launcher draws it: the Glance content turned into the `RemoteViews` the
 * home screen gets, inflated and rendered on the JVM, one frame per state, light and dark
 * (Glance's own `@Preview`s, in `previews/NeedsYouWidgetPreviews.kt`, are for Android Studio:
 * the repo's preview scanner finds Compose's annotation, not Glance's). Written into
 * `src/test/previews/`, which is committed, so a changed pixel is a reviewed diff.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "xhdpi")
@OptIn(androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi::class)
class NeedsYouWidgetFrameTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val store = WidgetFixtures.Memory()
    private val feed = WidgetFixtures.feed(store)

    companion object {
        @BeforeClass
        @JvmStatic
        fun ensureHostCoreIsBuilt() = HostCore.ensureBuilt()
    }

    private fun contextFor(dark: Boolean): Context {
        val config = Configuration(app.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        return app.createConfigurationContext(config)
    }

    private fun frame(name: String, size: DpSize, view: WidgetView = feed.view(NOW), fontScale: Float = 1f) {
        for (dark in listOf(false, true)) {
            val base = contextFor(dark)
            val context = if (fontScale == 1f) base else base.createConfigurationContext(
                Configuration(base.resources.configuration).apply { this.fontScale = fontScale },
            )
            val views = runBlocking {
                GlanceRemoteViews().compose(context, size) { NeedsYouContent(view, NOW, size, fontScale) }.remoteViews
            }
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
            texts(root).filter { it.text.toString() == "Needs you" }.forEach { header ->
                org.junit.Assert.assertEquals("$name: the header is on one line", 1, header.layout.lineCount)
                org.junit.Assert.assertEquals("$name: the header is cut short", 0, header.layout.getEllipsisCount(0))
            }
            val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
            // The launcher's own wallpaper is not ours; a neutral ground shows the widget's edge.
            bitmap.eraseColor(if (dark) 0xFF000000.toInt() else 0xFF808080.toInt())
            root.draw(android.graphics.Canvas(bitmap))
            bitmap.captureRoboImage("src/test/previews/NeedsYouWidget$name${if (dark) "Dark" else ""}.png")
        }
    }

    private fun texts(view: View): List<android.widget.TextView> =
        (view as? android.widget.TextView)?.let { listOf(it) }.orEmpty() +
            ((view as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).flatMap { texts(g.getChildAt(it)) } }.orEmpty())

    private fun decisions(n: Int) = (1..n).forEach { i ->
        runBlocking {
            feed.apply(
                WidgetFixtures.permission(
                    event = "\$q$i", room = "!room$i:hs", agent = "Hermes $i", question = "Run git push to origin $i?",
                    at = NOW - i * 4 * MINUTE,
                ),
            )
        }
    }

    @Test
    fun twoOwed() {
        runBlocking {
            feed.apply(WidgetFixtures.permission())
            feed.apply(WidgetFixtures.gate(at = NOW - 9 * MINUTE))
        }
        frame("TwoOwed", NeedsYouWidget.LARGE)
    }

    @Test
    fun oneSent() {
        runBlocking {
            feed.apply(WidgetFixtures.gate())
            feed.markAnswered("!b:hs", "\$g", "approve")
        }
        frame("Sent", NeedsYouWidget.LARGE)
    }

    @Test
    fun allClear() {
        runBlocking {
            feed.apply(listOf(WidgetFixtures.row("!a:hs", "Atlas", NOW - 3 * 3_600_000, "hello")), emptyList(), NOW)
        }
        frame("AllClear", NeedsYouWidget.WIDE)
    }

    @Test
    fun signedOut() {
        runBlocking { feed.signedOut() }
        frame("SignedOut", NeedsYouWidget.WIDE)
    }

    @Test
    fun waiting() = frame("Waiting", NeedsYouWidget.WIDE)

    @Test
    fun small() {
        decisions(2)
        frame("Small", NeedsYouWidget.SMALL)
    }

    @Test
    fun largeWithFour() {
        decisions(4)
        frame("LargeFour", NeedsYouWidget.LARGE)
    }

    @Test
    fun noInlineOptions() {
        runBlocking { feed.apply(WidgetFixtures.open()) }
        frame("OpenToAnswer", NeedsYouWidget.WIDE)
    }

    @Test
    fun fontScaleTwo() {
        decisions(3)
        frame("TextTwo", NeedsYouWidget.LARGE, fontScale = 2f)
    }

    @Test
    fun mediumWithTwo() {
        decisions(3)
        frame("MediumTwo", NeedsYouWidget.MEDIUM)
    }

    @Test
    fun wideWithOne() {
        decisions(3)
        frame("WideOne", NeedsYouWidget.WIDE)
    }

    @Test
    fun mediumTextTwo() {
        decisions(3)
        frame("MediumTextTwo", NeedsYouWidget.MEDIUM, fontScale = 2f)
    }

    @Test
    fun wideTextTwo() {
        decisions(3)
        frame("WideTextTwo", NeedsYouWidget.WIDE, fontScale = 2f)
    }

    @Test
    fun smallTextTwo() {
        decisions(3)
        frame("SmallTextTwo", NeedsYouWidget.SMALL, fontScale = 2f)
    }

    @Test
    fun smallTextOnePointFive() {
        decisions(3)
        frame("SmallTextOnePointFive", NeedsYouWidget.SMALL, fontScale = 1.5f)
    }
}
