package dev.supermessage

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The Material glyphs this app draws that `material-icons-core` leaves out, as their own paths.
 *
 * The alternative, `material-icons-extended`, is a 37 MB library of every Material icon, and the
 * release build does not run R8 (see `app/build.gradle.kts`), so all of it would ship for these
 * few. The paths are Material's own (Apache 2.0), copied from the 1.7.8 `-extended` sources on a
 * 24-unit grid, as [Sparkle] (`RoomAvatar.kt`) already was.
 */
internal object Glyphs {
    /** Chats, selected: Material's filled "forum". */
    val ForumFilled = glyph(
        "Forum.Filled",
        "M21,6h-2v9L6,15v2c0,0.55,0.45,1,1,1h11l4,4L22,7c0,-0.55,-0.45,-1,-1,-1z" +
            "M17,12L17,3c0,-0.55,-0.45,-1,-1,-1L3,2c-0.55,0,-1,0.45,-1,1v14l4,-4h10c0.55,0,1,-0.45,1,-1z",
    )

    /** Chats: Material's outlined "forum". */
    val ForumOutlined = glyph(
        "Forum.Outlined",
        "M15,4v7L5.17,11L4,12.17L4,4h11m1,-2L3,2c-0.55,0,-1,0.45,-1,1v14l4,-4h10c0.55,0,1,-0.45,1,-1L17,3" +
            "c0,-0.55,-0.45,-1,-1,-1zM21,6h-2v9L6,15v2c0,0.55,0.45,1,1,1h11l4,4L22,7c0,-0.55,-0.45,-1,-1,-1z",
    )

    /** Needs you, selected, and the empty roster: Material's filled "inbox". */
    val InboxFilled = glyph(
        "Inbox.Filled",
        "M19,3L4.99,3c-1.11,0,-1.98,0.89,-1.98,2L3,19c0,1.1,0.88,2,1.99,2L19,21c1.1,0,2,-0.9,2,-2L21,5" +
            "c0,-1.11,-0.9,-2,-2,-2zM19,15h-4c0,1.66,-1.35,3,-3,3s-3,-1.34,-3,-3L4.99,15L4.99,5L19,5v10z",
    )

    /** Needs you: Material's outlined "inbox". */
    val InboxOutlined = glyph(
        "Inbox.Outlined",
        "M19,3L5,3c-1.1,0,-2,0.9,-2,2v14c0,1.1,0.89,2,2,2h14c1.1,0,2,-0.9,2,-2L21,5c0,-1.1,-0.9,-2,-2,-2z" +
            "M19,19L5,19v-3h3.56c0.69,1.19,1.97,2,3.45,2s2.75,-0.81,3.45,-2L19,16v3z" +
            "M19,14h-4.99c0,1.1,-0.9,2,-2,2s-2,-0.9,-2,-2L5,14L5,5h14v9z",
    )

    /** Agents: Material's outlined "auto awesome". Selected is the filled one, [Sparkle]. */
    val SparkleOutlined = glyph(
        "AutoAwesome.Outlined",
        // Three paths, as Material draws it: one path antialiases the edges differently.
        "M19,9l1.25,-2.75l2.75,-1.25l-2.75,-1.25l-1.25,-2.75l-1.25,2.75l-2.75,1.25l2.75,1.25z",
        "M19,15l-1.25,2.75l-2.75,1.25l2.75,1.25l1.25,2.75l1.25,-2.75l2.75,-1.25l-2.75,-1.25z",
        "M11.5,9.5L9,4L6.5,9.5L1,12l5.5,2.5L9,20l2.5,-5.5L17,12L11.5,9.5z" +
            "M9.99,12.99L9,15.17l-0.99,-2.18L5.83,12l2.18,-0.99L9,8.83l0.99,2.18L12.17,12L9.99,12.99z",
    )

    /** A chip with nothing in it: Material's "filter list". */
    val FilterList = glyph(
        "FilterList",
        "M10,18h4v-2h-4v2zM3,6v2h18L21,6L3,6zM6,13h12v-2L6,11v2z",
    )

    /** "You're all caught up": Material's "verified", the seal iOS draws as `checkmark.seal`. */
    val Verified = glyph(
        "Verified",
        "M23,12l-2.44,-2.79l0.34,-3.69l-3.61,-0.82L15.4,1.5L12,2.96L8.6,1.5L6.71,4.69L3.1,5.5L3.44,9.2L1,12" +
            "l2.44,2.79l-0.34,3.7l3.61,0.82L8.6,22.5l3.4,-1.47l3.4,1.46l1.89,-3.19l3.61,-0.82l-0.34,-3.69L23,12z" +
            "M10.09,16.72l-3.8,-3.81l1.48,-1.48l2.32,2.33l5.85,-5.87l1.48,1.48L10.09,16.72z",
    )

    private fun glyph(name: String, vararg paths: String): ImageVector = ImageVector.Builder(
        name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        paths.forEach { addPath(pathData = addPathNodes(it), fill = SolidColor(Color.Black)) }
    }.build()
}
