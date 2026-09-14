package dev.supermessage

import com.github.takahirom.roborazzi.captureRoboImage
import sergio.sastre.composable.preview.scanner.android.AndroidComposablePreviewScanner
import sergio.sastre.composable.preview.scanner.android.AndroidPreviewInfo
import sergio.sastre.composable.preview.scanner.core.preview.ComposablePreview
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders every `@Preview` in the debug source set to a PNG, on the JVM.
 *
 * No emulator: Robolectric in `NATIVE` graphics mode draws with the real
 * Android rendering stack, and `ComposablePreviewScanner` finds the previews
 * by scanning the classpath — so there is no list here to fall behind the
 * previews it describes.
 *
 * **API 35, not 36.** Robolectric publishes nothing past 4.15.1 and SDK 36
 * support lands in 4.16, so these render one API level below this project's
 * `compileSdk`.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PreviewScreenshotTest(
    private val preview: ComposablePreview<AndroidPreviewInfo>,
) {
    companion object {
        /**
         * Previews that need the clock held still.
         *
         * Each shows an *indeterminate* animation — a
         * `CircularProgressIndicator`, or streaming text's `delay` loop — and
         * Robolectric auto-advances its clock, so left alone the composition
         * never reaches idle and the animation schedules frames as fast as
         * the CPU allows. `ComposerSending` ran for **2 hours 18 minutes**
         * that way, while the median preview took 0.3 seconds.
         *
         * **These used to be excluded outright, and that was one step short.**
         * The argument for excluding them was sound — a spinner has no
         * canonical frame, so any pixel the shutter catches is arbitrary —
         * but freezing the clock and advancing a fixed 1,000ms *gives* it
         * one. All 48 previews are gated now, and all 48 are byte-identical
         * across repeated renders.
         *
         * [AnimatedPreviewScreenshotTest] renders these. It has to be a
         * separate class: a `ComposeTestRule` and `captureRoboImage(preview)`
         * each stand up their own host and cannot share one.
         *
         * **A new preview in a loading state belongs here.** Left out, it
         * does not fail — it runs until someone notices.
         */
        internal val NEVER_SETTLES = setOf(
            "ComposerSending",      // sending = true
            "ShellStarting",        // Session.Phase.STARTING
            "Account",              // spinner until loadAccount resolves
            "LiveTurnMidTurn",      // streaming text
            "LiveTurnThinking",     // streaming text
            "TimelineLive",         // a live turn arriving
            "TimelinePaginating",   // isPaginating = true
        )

        @BeforeClass
        @JvmStatic
        fun ensureHostCoreIsBuilt() = HostCore.ensureBuilt()

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters
        fun previews(): List<ComposablePreview<AndroidPreviewInfo>> =
            AndroidComposablePreviewScanner()
                .scanPackageTrees("dev.supermessage.previews")
                .getPreviews()
                .filterNot { it.methodName in NEVER_SETTLES }
    }

    /**
     * Written into `src/test/previews/`, which is **committed**.
     *
     * That is what makes this a baseline rather than a viewer: with
     * `-Proborazzi.test.verify=true` the same path is compared instead of
     * overwritten, and a changed pixel fails the build with a diff image
     * beside it. Record with `-Proborazzi.test.record=true` when a change is
     * intended, and the review is then the image diff in the pull request.
     */
    @Test
    fun render() {
        preview.captureRoboImage("src/test/previews/${preview.methodName}.png")
    }
}
