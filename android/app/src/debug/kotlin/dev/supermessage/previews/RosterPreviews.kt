package dev.supermessage.previews

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.supermessage.RoomRow
import dev.supermessage.Roster
import java.time.Instant
import uniffi.supermessage_core.AgentState

/**
 * The roster, and the row it is made of.
 *
 * **`now` is a fixed instant, not `Instant.now()`.** Every relative time on
 * these rows is measured against it, so a fixed one makes the preview
 * reproducible — with a live clock the same preview drifts from "2m" to "4h"
 * depending on when it is opened, and a screenshot of it dates itself.
 */
private val NOW: Instant = Instant.ofEpochMilli(1_757_700_120_000L)

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
@Preview(name = "Roster rows", showBackground = true, heightDp = 460)
@Composable
internal fun RoomRowStates() {
    PreviewGround {
        androidx.compose.foundation.layout.Column {
            RoomRow(PreviewFixtures.roomNeedsYou, null, AgentState.NEEDS_YOU, "2m")
            RoomRow(PreviewFixtures.roomActive, null, AgentState.ACTIVE, "14m")
            RoomRow(PreviewFixtures.roomInvitation, null, AgentState.IDLE, "", describesAgent = false)
            RoomRow(PreviewFixtures.roomQuiet, null, AgentState.QUIET, "3d")
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
@Preview(name = "Roster rows, dark", showBackground = true, heightDp = 460)
@Composable
internal fun RoomRowStatesDark() {
    PreviewGround(dark = true) {
        androidx.compose.foundation.layout.Column {
            RoomRow(PreviewFixtures.roomNeedsYou, null, AgentState.NEEDS_YOU, "2m")
            RoomRow(PreviewFixtures.roomActive, null, AgentState.ACTIVE, "14m")
            RoomRow(PreviewFixtures.roomInvitation, null, AgentState.IDLE, "", describesAgent = false)
            RoomRow(PreviewFixtures.roomQuiet, null, AgentState.QUIET, "3d")
            RoomRow(PreviewFixtures.roomBare, null, AgentState.IDLE, "1h", describesAgent = false)
        }
    }
}

/**
 * The state word turned off, and the host suffix hidden.
 *
 * Both are stored roster preferences, so every other preview here shows the
 * defaults. This is the arrangement a reader who changed them actually has,
 * and the question it answers is whether the row still balances without them.
 */
@Preview(name = "Roster rows, plain", showBackground = true, heightDp = 220)
@Composable
internal fun RoomRowWithoutState() {
    PreviewGround {
        androidx.compose.foundation.layout.Column {
            RoomRow(
                PreviewFixtures.roomNeedsYou, null, AgentState.NEEDS_YOU, "2m",
                showsState = false, hidesHost = true,
            )
            RoomRow(
                PreviewFixtures.roomActive, null, AgentState.ACTIVE, "14m",
                showsState = false, hidesHost = true,
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
            hiddenInvitations = 0,
            now = NOW,
            avatarUri = { null },
        )
    }
}

/**
 * Invitations hidden, with one behind the count.
 *
 * `hiddenInvitations` is what the roster offers to reveal. Zero in every
 * other preview, so this is the only one showing that affordance at all.
 */
@Preview(name = "Roster, invitations hidden", showBackground = true, heightDp = 620)
@Composable
internal fun RosterWithHiddenInvitations() {
    PreviewGround {
        Roster(
            sections = PreviewFixtures.rosterSections,
            hiddenInvitations = 1,
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
internal fun RosterEmpty() {
    PreviewGround {
        Roster(
            sections = emptyList(),
            hiddenInvitations = 0,
            now = NOW,
            avatarUri = { null },
        )
    }
}
