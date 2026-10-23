package dev.supermessage.widgets

import android.app.Application
import androidx.compose.ui.unit.DpSize
import androidx.test.core.app.ApplicationProvider
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

    private fun frame(name: String, size: DpSize, view: WidgetView = feed.view(NOW), fontScale: Float = 1f) =
        WidgetFrames.render(app, "NeedsYouWidget$name", size, fontScale, header = "Needs you") {
            NeedsYouContent(view, NOW, size, fontScale)
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
