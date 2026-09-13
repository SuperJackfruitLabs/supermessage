package dev.supermessage

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sergio.sastre.composable.preview.scanner.android.AndroidComposablePreviewScanner
import sergio.sastre.composable.preview.scanner.android.AndroidPreviewInfo
import sergio.sastre.composable.preview.scanner.core.preview.ComposablePreview

/**
 * The previews that never settle, captured at a fixed instant.
 *
 * ## Why these need their own class
 *
 * `PreviewScreenshotTest` hands each preview to `captureRoboImage(preview)`,
 * which stands up its own host. A `ComposeTestRule` stands up another, and
 * the two cannot share a test class — adding the rule there failed 47 of 48.
 * So the seven live here, and the ordinary 41 keep the ordinary path.
 *
 * ## What freezing the clock buys
 *
 * These seven show an indeterminate animation: a `CircularProgressIndicator`,
 * or streaming text's `delay` loop. Left to run, the composition never
 * reaches idle — Robolectric auto-advances its clock, so the animation
 * schedules frames as fast as the CPU allows. `ComposerSending` ran for 2
 * hours 18 minutes that way.
 *
 * Freezing the clock **before** the composition exists means the animation
 * never gets to schedule anything. Advancing a fixed 1,000ms then puts every
 * run at the same simulated moment, so the frame is both reachable and
 * reproducible — which is what a baseline needs and what these could not
 * previously offer.
 *
 * ## What it costs
 *
 * This drives the composition directly rather than handing the preview to
 * Roborazzi, so the `@Preview` annotation's device and font-scale settings
 * are not applied. Of these seven only `ShellStarting` sets a width, and
 * 411dp is near enough the default that the frame still says what it is for.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AnimatedPreviewScreenshotTest(
    private val preview: ComposablePreview<AndroidPreviewInfo>,
) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters
        fun previews(): List<ComposablePreview<AndroidPreviewInfo>> =
            AndroidComposablePreviewScanner()
                .scanPackageTrees("dev.supermessage.previews")
                .getPreviews()
                .filter { it.methodName in PreviewScreenshotTest.NEVER_SETTLES }
    }

    @get:Rule val compose = createComposeRule()

    @Test
    fun render() {
        compose.mainClock.autoAdvance = false
        try {
            compose.setContent { preview() }
            compose.mainClock.advanceTimeBy(1_000)
            compose.onRoot().captureRoboImage("src/test/previews/${preview.methodName}.png")
        } finally {
            // Hand the clock back, and not as politeness.
            //
            // Robolectric shares a JVM across test classes, so a clock left
            // frozen here is frozen for whatever runs next: leaving it out
            // failed 11 of `PreviewScreenshotTest`'s 41 with idle timeouts —
            // tests that have nothing to do with animation and were waiting
            // for a clock that would never move again.
            compose.mainClock.autoAdvance = true
        }
    }
}
