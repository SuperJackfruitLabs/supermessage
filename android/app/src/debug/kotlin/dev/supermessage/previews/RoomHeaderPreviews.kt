package dev.supermessage.previews

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import dev.supermessage.LocalReduceMotion
import dev.supermessage.RoomHeader
import dev.supermessage.StatusPill
import uniffi.supermessage_core.RoomStatus

/** A room of people: the name, and how many are in it. */
@Preview(name = "Room header, people", showBackground = true, heightDp = 80)
@Composable
internal fun RoomHeaderPeople() {
    PreviewGround(width = 411.dp) {
        RoomHeader(PreviewFixtures.roomQuiet, status = null, memberCount = 4uL, onOpenInfo = {})
    }
}

/** An agent's room says no count: it says what the agent is doing. */
@Preview(name = "Room header, agent", showBackground = true, heightDp = 80)
@Composable
internal fun RoomHeaderAgent() {
    PreviewGround(width = 411.dp) {
        RoomHeader(PreviewFixtures.roomActive, status = RoomStatus.ACTIVE, memberCount = null, onOpenInfo = {})
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
                status = null,
                memberCount = 12uL,
                onOpenInfo = {},
            )
        }
    }
}

/** All five statuses, as the pill draws them (`RoomScreen.swift:268-290`); still, as Working would pulse. */
@Composable
private fun EveryStatus(dark: Boolean) {
    CompositionLocalProvider(LocalReduceMotion provides true) {
        PreviewGround(dark = dark, width = 411.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RoomStatus.entries.forEach { StatusPill(it) }
                Row { StatusPill(RoomStatus.NEEDS_YOU, compact = false) }
            }
        }
    }
}

@Preview(name = "Header statuses", showBackground = true, heightDp = 220)
@Composable
internal fun HeaderStatuses() = EveryStatus(dark = false)

@Preview(name = "Header statuses, dark", showBackground = true, heightDp = 220)
@Composable
internal fun HeaderStatusesDark() = EveryStatus(dark = true)

/** A long name and "Needs you" at text 2.0: the name gives way, the pill stays whole. */
@Preview(name = "Room header, status, text 2.0", showBackground = true, heightDp = 140)
@Composable
internal fun HeaderLargeText() {
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
                status = RoomStatus.NEEDS_YOU,
                memberCount = null,
                onOpenInfo = {},
            )
        }
    }
}
