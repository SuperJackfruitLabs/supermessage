package dev.supermessage

import com.github.takahirom.roborazzi.captureRoboImage
import sergio.sastre.composable.preview.scanner.android.AndroidComposablePreviewScanner
import sergio.sastre.composable.preview.scanner.android.AndroidPreviewInfo
import sergio.sastre.composable.preview.scanner.core.preview.ComposablePreview
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
         * Previews whose content never settles, and therefore have no frame
         * worth keeping.
         *
         * **Excluded on principle, not for speed** — though the speed is how
         * they were noticed. Each is a fixture that puts its screen in a state
         * with an *indeterminate* animation: a `CircularProgressIndicator`, or
         * streaming text's `delay` loop. Robolectric auto-advances its clock,
         * so the composition never goes idle and the renderer waits.
         * `ComposerSending` ran for **2 hours 18 minutes** before anyone
         * measured it; the whole suite took 3h31m while its median preview
         * took 0.3 seconds.
         *
         * Forcing them to render would not fix the real problem, which is
         * that a spinner has no canonical frame. Whatever pixel the shutter
         * caught would be arbitrary, and a baseline of arbitrary pixels fails
         * at random. They stay in the catalogue and render in Android Studio,
         * which is the right place to watch an animation anyway.
         *
         * **Adding a preview in a loading state means adding it here.** The
         * symptom is a run that never finishes rather than one that fails,
         * which is why this list carries its reasons.
         */
        private val NEVER_SETTLES = setOf(
            "ComposerSending",      // sending = true
            "ShellStarting",        // Session.Phase.STARTING
            "Account",              // spinner until loadAccount resolves
            "LiveTurnMidTurn",      // streaming text
            "LiveTurnThinking",     // streaming text
            "TimelineLive",         // a live turn arriving
            "TimelinePaginating",   // isPaginating = true
        )

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
