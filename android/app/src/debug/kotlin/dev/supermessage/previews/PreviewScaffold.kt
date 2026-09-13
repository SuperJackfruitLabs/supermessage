package dev.supermessage.previews

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.supermessage.SupermessageTheme

/**
 * The backdrop every preview in this source set sits on.
 *
 * Not decoration. Most of these composables are transparent and draw only
 * their own text, so on the tooling's default white canvas a content-on-
 * surface pairing is being judged against a ground the app never uses — and
 * `SupermessageTheme` resolves **paper** for light rather than white, which
 * is the design language's "paper is what light means on a phone". A preview
 * without this shows the one appearance the product does not have.
 *
 * `width` frames a composable at a phone's width, for the previews whose
 * whole point is a wrap guard: unconstrained, a long unbroken run simply
 * makes the canvas wider and the preview then demonstrates the guard's
 * absence while appearing to show it holding.
 */
@Composable
fun PreviewGround(
    dark: Boolean = false,
    width: Dp? = null,
    content: @Composable () -> Unit,
) {
    SupermessageTheme(darkTheme = dark) {
        Box(
            modifier = (if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
                .background(SupermessageTheme.colors.surface)
                .padding(12.dp),
        ) {
            content()
        }
    }
}

/**
 * The first preview in this source set, and the only one whose job is to
 * prove the build rather than show a screen.
 *
 * Three things have to be true for this to render, none of which can be
 * checked on the machine this was written on — there is no Android SDK here,
 * no `adb` and no `kotlinc`:
 *
 *  1. `src/debug/kotlin` is discovered as a source set. AGP 9.3.1's built-in
 *     Kotlin support auto-discovers `src/main/kotlin`, `src/test/kotlin` and
 *     `src/androidTest/kotlin` (recorded in `app/build.gradle.kts`), and the
 *     old `AndroidSourceSet.kotlin.srcDir()` accessor throws a
 *     ClassCastException against this AGP's source set type — so if `debug`
 *     is *not* auto-discovered, the fix is not the obvious one.
 *  2. `compose-ui-tooling-preview` resolves in debug scope, where it has just
 *     been moved.
 *  3. `compose-ui-tooling` resolves at all, having just been added.
 *
 * A source set that is silently not compiled is the worst of those outcomes,
 * because nothing fails. `DebugSourceSetTest` in `src/test` is what makes it
 * fail: it reads a constant from this source set, so a missing source set is
 * a compile error in the unit test task CI already runs.
 */
@Preview(name = "The build works", showBackground = true)
@Composable
private fun PreviewScaffoldSmokeTest() {
    PreviewGround {
        androidx.compose.material3.Text("previews render")
    }
}
