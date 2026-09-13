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
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters
        fun previews(): List<ComposablePreview<AndroidPreviewInfo>> =
            AndroidComposablePreviewScanner()
                .scanPackageTrees("dev.supermessage.previews")
                .getPreviews()
    }

    @Test
    fun render() {
        preview.captureRoboImage("build/outputs/preview-captures/${preview.methodName}.png")
    }
}
