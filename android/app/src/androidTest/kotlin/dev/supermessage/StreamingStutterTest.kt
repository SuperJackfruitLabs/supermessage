package dev.supermessage

import android.os.Handler
import android.os.HandlerThread
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.compose.ui.unit.dp
import dev.supermessage.previews.PreviewFixtures
import java.util.Collections
import kotlinx.coroutines.delay
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.supermessage_core.ItemView
import uniffi.supermessage_core.RichBlock
import uniffi.supermessage_core.RichInline
import uniffi.supermessage_core.TimelineRow as TimelineRowDto

/**
 * The stutter check for the history glide (owner decision Q5), in real time.
 *
 * An answer streams into the card, a few lines per sentence, as a Guild
 * agent's does (`-fixtureStreaming` on iOS). Two things are measured while it
 * does, from the platform and not from the test clock:
 *
 * - **Frame timing**, through `FrameMetrics`: no long frames.
 * - **That the history does not jump**, through a `Choreographer` callback
 *   that records, every frame, where the newest history row is drawn (the
 *   semantics bounds include the layer transform, so this is the drawn
 *   position, not layout's) and how tall the card is. The growth is unanimated
 *   and lands in one frame; if the history moved with it, its largest
 *   single-frame step would equal the largest growth. With the glide it is a
 *   fraction of it.
 *
 * It runs in a plain activity, not the compose test rule: that rule's clock
 * is the test's, and does not advance while the test sleeps, so neither the
 * frames nor the streaming would be real.
 *
 * The device's numbers are printed to the instrumentation log (tag
 * `StreamingStutter`) so the report can quote them.
 */
class StreamingStutterTest {
    private fun message(n: Int): TimelineRowDto = PreviewFixtures.row(
        PreviewFixtures.item("\$m$n", body = "message $n", atMs = 1_757_700_000_000uL + n.toULong() * 1_000uL),
        ItemView.Bubble(
            muted = false,
            blocks = listOf(RichBlock.Paragraph(listOf(RichInline.Text("message $n")))),
            voice = null,
        ),
    )

    private val sentence =
        "The roster is sorted by pending decisions first, then by when each agent last said anything, so the " +
            "room you owe an answer to is always at the top. "

    private fun find(node: SemanticsNode, tag: String): SemanticsNode? {
        if (node.config.getOrNull(SemanticsProperties.TestTag) == tag) return node
        for (child in node.children) find(child, tag)?.let { return it }
        return null
    }

    private fun rootForTest(view: View): RootForTest? {
        if (view is RootForTest) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) rootForTest(view.getChildAt(i))?.let { return it }
        return null
    }

    private class Sample(val historyTop: Float, val cardHeight: Float)

    @Test
    fun theHistoryDoesNotStutterWhileTheCardGrows() {
        var answer by mutableStateOf("")
        val rows = (1..12).map(::message)
        val thread = HandlerThread("frame-metrics").apply { start() }
        val totals = Collections.synchronizedList(mutableListOf<Long>())
        val samples = Collections.synchronizedList(mutableListOf<Sample>())
        val listener = android.view.Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 0L) {
                totals += metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            }
        }
        var recording = true
        lateinit var callback: Choreographer.FrameCallback
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            activity.setContent {
                CompositionLocalProvider(LocalReduceMotion provides false) {
                    Box(Modifier.requiredWidth(411.dp)) {
                        LaunchedEffect(Unit) {
                            repeat(10) { n ->
                                answer = (sentence.repeat(n + 1)).trim()
                                delay(450)
                            }
                        }
                        Timeline(
                            rows = rows, revision = 0uL, typingLine = null, isPaginating = false,
                            canPaginate = false, onPaginate = {}, onMarkRead = {},
                            liveSummary = inProgress(), liveAnswer = answer.ifEmpty { null }, liveName = "Atlas",
                        )
                    }
                }
            }
            val decor = activity.window.decorView
            callback = Choreographer.FrameCallback {
                if (recording) {
                    val root = rootForTest(decor)?.semanticsOwner?.unmergedRootSemanticsNode
                    val history = root?.let { find(it, "row-\$m12") }
                    val card = root?.let { find(it, "live-turn") }
                    if (history != null && card != null) {
                        samples += Sample(history.boundsInRoot.top, card.size.height.toFloat())
                    }
                    Choreographer.getInstance().postFrameCallback(callback)
                }
            }
            activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
            Choreographer.getInstance().postFrameCallback(callback)
        }
        Thread.sleep(6_000)
        scenario.onActivity { activity ->
            recording = false
            activity.window.removeOnFrameMetricsAvailableListener(listener)
        }
        scenario.close()
        thread.quitSafely()

        // Drop the warm-up: the first frames include the list's first layout.
        val frames = totals.drop(10)
        val longest = frames.max()
        val ms = { n: Long -> n / 1_000_000.0 }
        val sorted = frames.sorted()
        val p50 = sorted[sorted.size / 2]
        val p95 = sorted[(sorted.size * 95) / 100]

        val ys = samples.map { it.historyTop }
        val heights = samples.map { it.cardHeight }
        // Only while the card is not shrinking: the glide is for growth, as iOS's is (a line of
        // status text going away when the answer's first words show is a step, not a glide).
        val steps = ys.indices.drop(1).filter { heights[it] >= heights[it - 1] }.map { kotlin.math.abs(ys[it] - ys[it - 1]) }
        val growths = heights.zipWithNext { a, b -> b - a }.filter { it > 0.5f }
        val biggestStep = steps.max()
        val biggestGrowth = growths.max()
        android.util.Log.i(
            "StreamingStutter",
            "frames=${frames.size} p50=${ms(p50)}ms p95=${ms(p95)}ms max=${ms(longest)}ms; " +
                "samples=${samples.size} growths=${growths.size} biggestGrowth=${biggestGrowth}px biggestHistoryStep=${biggestStep}px",
        )

        assertTrue("saw only ${frames.size} frames", frames.size > 100)
        assertTrue("the card must have grown for this to mean anything: $growths", growths.size >= 3 && biggestGrowth > 20f)
        assertTrue(
            "a long frame: ${ms(longest)} ms (p50 ${ms(p50)}, p95 ${ms(p95)})",
            longest < LONG_FRAME_NANOS,
        )
        assertTrue(
            "the history jumped: moved $biggestStep px in one frame when the card grew by at most $biggestGrowth px",
            biggestStep < biggestGrowth * 0.5f,
        )
    }

    private companion object {
        /** A frame this long is a visible hitch: nine 60 Hz frames (the emulator's own frames are ~27 ms at the median). */
        const val LONG_FRAME_NANOS = 150_000_000L
    }
}
