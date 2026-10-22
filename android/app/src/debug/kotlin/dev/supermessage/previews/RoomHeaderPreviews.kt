package dev.supermessage.previews

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.supermessage.RoomHeader

/** A room of people: the name, and how many are in it. */
@Preview(name = "Room header, people", showBackground = true, heightDp = 80)
@Composable
internal fun RoomHeaderPeople() {
    PreviewGround(width = 411.dp) {
        RoomHeader(PreviewFixtures.roomQuiet, memberCount = 4uL, onOpenInfo = {})
    }
}

/** An agent's room says no count; its status is a later milestone's. */
@Preview(name = "Room header, agent", showBackground = true, heightDp = 80)
@Composable
internal fun RoomHeaderAgent() {
    PreviewGround(width = 411.dp) {
        RoomHeader(PreviewFixtures.roomActive, memberCount = null, onOpenInfo = {})
    }
}

/** The accessibility case: one line, ellipsized, still one tap target. */
@Preview(name = "Room header, long name, text 2.0", showBackground = true, heightDp = 120)
@Composable
internal fun RoomHeaderLongNameLargeText() {
    val base = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = 2f)) {
        PreviewGround(width = 411.dp) {
            RoomHeader(
                PreviewFixtures.roomRow(
                    id = "!long:example.org",
                    rawName = "The quarterly platform reliability and capacity planning review",
                    glyph = null,
                    name = "The quarterly platform reliability and capacity planning review",
                    role = null, initial = "T", preview = null,
                ),
                memberCount = 12uL,
                onOpenInfo = {},
            )
        }
    }
}
