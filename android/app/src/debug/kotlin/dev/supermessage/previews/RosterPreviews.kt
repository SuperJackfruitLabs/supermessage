package dev.supermessage.previews

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.supermessage.RoomAvatar
import androidx.compose.ui.tooling.preview.Preview
import dev.supermessage.RoomRow
import dev.supermessage.Roster
import java.time.Instant
import uniffi.supermessage_core.AgentState
import uniffi.supermessage_ffi.agentAvatarVariants

/**
 * The roster, and the row it is made of.
 *
 * **`now` is a fixed instant, not `Instant.now()`.** Every relative time on
 * these rows is measured against it, so a fixed one makes the preview
 * reproducible — with a live clock the same preview drifts from "2m" to "4h"
 * depending on when it is opened, and a screenshot of it dates itself.
 */
private val NOW: Instant = Instant.ofEpochMilli(1_757_700_120_000L)

/** The core's own generated tiles, as the roster hands them to its rows. */
private fun tile(i: Int) = agentAvatarVariants().let { it[i % it.size] }

/**
 * All five rows in one frame, which is the only arrangement that shows what
 * the row is for.
 *
 * The state word and the pending mark exist to be distinguishable *at a
 * glance in a list*; a row previewed alone cannot show that. Reading down
 * this preview exactly one row is amber — `docs/design-language.md` §2 — and
 * a second amber row anywhere in it is a defect rather than a taste
 * disagreement. `DebugSourceSetTest` pins the fixture side of that; this is
 * the half a person has to look at.
 */
@Preview(name = "Roster rows", showBackground = true, heightDp = 400)
@Composable
internal fun EveryState() {
    PreviewGround {
        androidx.compose.foundation.layout.Column {
            RoomRow(PreviewFixtures.roomNeedsYou, null, AgentState.NEEDS_YOU, "2m", style = tile(0))
            RoomRow(PreviewFixtures.roomActive, null, AgentState.ACTIVE, "14m", style = tile(1))
            RoomRow(PreviewFixtures.roomInvitation, null, AgentState.IDLE, "", describesAgent = false)
            RoomRow(PreviewFixtures.roomQuiet, null, AgentState.QUIET, "3d", style = tile(2))
            RoomRow(PreviewFixtures.roomBare, null, AgentState.IDLE, "1h", describesAgent = false)
        }
    }
}

/**
 * The same rows in dark, where the palette ramp runs the other way.
 *
 * `content-faint`'s contrast contract names all three grounds rather than
 * only the reading surface, because in dark the ground it fails on flips to
 * `surface-raised`. A roster row sits on one of those.
 */
@Preview(name = "Roster rows, dark", showBackground = true, heightDp = 400)
@Composable
internal fun EveryStateDark() {
    PreviewGround(dark = true) {
        androidx.compose.foundation.layout.Column {
            RoomRow(PreviewFixtures.roomNeedsYou, null, AgentState.NEEDS_YOU, "2m", style = tile(0))
            RoomRow(PreviewFixtures.roomActive, null, AgentState.ACTIVE, "14m", style = tile(1))
            RoomRow(PreviewFixtures.roomInvitation, null, AgentState.IDLE, "", describesAgent = false)
            RoomRow(PreviewFixtures.roomQuiet, null, AgentState.QUIET, "3d", style = tile(2))
            RoomRow(PreviewFixtures.roomBare, null, AgentState.IDLE, "1h", describesAgent = false)
        }
    }
}

/**
 * The state dot turned off.
 *
 * It is a stored roster preference, so every other preview here shows the
 * default. This is the arrangement a reader who changed them actually has,
 * and the question it answers is whether the row still balances without them.
 */
@Preview(name = "Roster rows, plain", showBackground = true, heightDp = 220)
@Composable
internal fun StateWordHidden() {
    PreviewGround {
        androidx.compose.foundation.layout.Column {
            RoomRow(
                PreviewFixtures.roomNeedsYou, null, AgentState.NEEDS_YOU, "2m",
                showsState = false, style = tile(0),
            )
            RoomRow(
                PreviewFixtures.roomActive, null, AgentState.ACTIVE, "14m",
                showsState = false, style = tile(1),
            )
        }
    }
}

/** The whole roster, sectioned as the core sectioned it. */
@Preview(name = "Roster", showBackground = true, heightDp = 620)
@Composable
internal fun RosterFurnished() {
    PreviewGround {
        Roster(
            sections = PreviewFixtures.rosterSections,
            invitations = PreviewFixtures.rosterInvitations,
            invitationsOpen = false,
            onToggleInvitations = {},
            now = NOW,
            avatarUri = { null },
        )
    }
}

/**
 * A synced account with no rooms at all.
 *
 * The first screen every new reader lands on, and the one most likely to have
 * been drawn once and never looked at again.
 */
@Preview(name = "Roster, nothing yet", showBackground = true, heightDp = 320)
@Composable
internal fun RosterNoRooms() {
    PreviewGround {
        Roster(
            sections = emptyList(),
            invitations = emptyList(),
            invitationsOpen = false,
            onToggleInvitations = {},
            now = NOW,
            avatarUri = { null },
        )
    }
}

/** Every tile the core generates, with each state, and a person's room beside them (`RoomAvatar.swift:120-154`). */
@Composable
private fun EveryAvatar(dark: Boolean) {
    PreviewGround(dark = dark) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val states = listOf(AgentState.NEEDS_YOU, AgentState.ACTIVE, AgentState.IDLE, AgentState.QUIET)
            agentAvatarVariants().chunked(4).forEach { chunk ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    chunk.forEachIndexed { i, style ->
                        RoomAvatar("!a:x", "ABCDEFGH"[i].toString(), null, true, style, states[i])
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                states.forEach { RoomAvatar("!p:x", "P", null, false, null, it) }
            }
        }
    }
}

@Preview(name = "Avatars", showBackground = true, heightDp = 260)
@Composable
internal fun Avatars() = EveryAvatar(dark = false)

@Preview(name = "Avatars, dark", showBackground = true, heightDp = 260)
@Composable
internal fun AvatarsDark() = EveryAvatar(dark = true)

/** Two lines at text 2.0: an invitation, an unread count and a long preview. */
@Preview(name = "Roster rows, text 2.0", showBackground = true, heightDp = 360)
@Composable
internal fun RowLargeText() {
    val base = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = 2f)) {
        PreviewGround {
            Column {
                RoomRow(PreviewFixtures.roomNeedsYou, null, AgentState.NEEDS_YOU, "2m", style = tile(0))
                RoomRow(PreviewFixtures.roomInvitation, null, AgentState.IDLE, "", describesAgent = false)
            }
        }
    }
}
