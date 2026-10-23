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
            root.measure(
                View.MeasureSpec.makeMeasureSpec((size.width.value * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((size.height.value * density).toInt(), View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
            // The launcher's own wallpaper is not ours; a neutral ground shows the widget's edge.
            bitmap.eraseColor(if (dark) 0xFF000000.toInt() else 0xFF808080.toInt())
            root.draw(android.graphics.Canvas(bitmap))
            bitmap.captureRoboImage("src/test/previews/NeedsYouWidget$name${if (dark) "Dark" else ""}.png")
        }
    }

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
        frame("AllClear", NeedsYouWidget.MEDIUM)
    }

    @Test
    fun signedOut() {
        runBlocking { feed.signedOut() }
        frame("SignedOut", NeedsYouWidget.MEDIUM)
    }

    @Test
    fun waiting() = frame("Waiting", NeedsYouWidget.MEDIUM)

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
        frame("OpenToAnswer", NeedsYouWidget.MEDIUM)
    }

    @Test
    fun fontScaleTwo() {
        decisions(3)
        frame("TextTwo", NeedsYouWidget.LARGE, fontScale = 2f)
    }
}
