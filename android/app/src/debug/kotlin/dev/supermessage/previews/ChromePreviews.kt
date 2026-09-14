package dev.supermessage.previews

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.supermessage.JumpToNewestButton

/**
 * The route home from a long scrollback.
 *
 * It had no preview at all until the icon inside it was wrong. Inline in
 * `Timeline` the button was reachable only by scrolling a real list on a real
 * device, so replacing `Text("↓")` with an `Icon` passed 281 tests and 61
 * frames without one of them looking at it. A fix nothing can see is a fix
 * nobody will notice breaking.
 */
@Preview(name = "Jump to newest", showBackground = true, widthDp = 120, heightDp = 100)
@Composable
internal fun JumpToNewest() {
    PreviewGround { JumpToNewestButton(onClick = {}) }
}

/**
 * The same button at double text size, which is the whole reason it changed.
 *
 * `Text("↓")` was drawn in the body font at the body text style, so at this
 * scale the arrow grew and the 40dp button did not. An `Icon` is 24dp at
 * every scale, and this frame is what holds that true.
 */
@Preview(
    name = "Jump to newest, fontScale 2",
    showBackground = true,
    widthDp = 120,
    heightDp = 100,
    fontScale = 2.0f,
)
@Composable
internal fun JumpToNewestLargeText() {
    PreviewGround { JumpToNewestButton(onClick = {}) }
}
