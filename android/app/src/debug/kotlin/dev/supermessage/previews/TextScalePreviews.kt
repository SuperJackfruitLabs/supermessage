package dev.supermessage.previews

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.supermessage.DecisionCard
import dev.supermessage.RichText
import dev.supermessage.RoomRow
import dev.supermessage.TimelineRow
import java.time.Instant
import uniffi.supermessage_core.AgentState

/**
 * The same screens a reader has turned their text up for.
 *
 * ## Why this file exists
 *
 * Every frame in the catalogue — 48 here, 40 on iOS, 83 web stories — was
 * rendered at **one text size**, and it is the default one. A reader at
 * `fontScale = 2.0` is using an application nobody has ever looked at, and
 * the defects that live there are layout defects: a name that truncates to
 * nothing, a row that pushes its timestamp off the edge, a card whose
 * buttons stop fitting side by side.
 *
 * Those are invisible in code review and invisible in a default-size
 * preview, which is the same combination that hid a glyph being drawn twice
 * under every agent message until someone rendered a frame.
 *
 * ## Which scales
 *
 * **1.5 and 2.0**, not the whole ladder. Android's accessibility sizes go to
 * 2.0, and a frame at each end of the range finds what a frame in the middle
 * would: 1.5 is where two-column rows start to fight, 2.0 is where they lose.
 * Every intermediate step costs a reference image and finds the same bugs.
 *
 * ## Which frames
 *
 * The ones whose layout has somewhere to break — a row with a name, a time
 * and a badge competing for one line; a card with three buttons; rich text
 * with a table in it. A frame that is one line of text at one size is one
 * line of text at every size.
 */
private val NOW: Instant = Instant.ofEpochMilli(1_757_700_120_000L)

@Preview(name = "Roster rows, 1.5x", showBackground = true, heightDp = 560, fontScale = 1.5f)
@Composable
internal fun RoomRowStatesLarge() {
    PreviewGround {
        Column {
            RoomRow(PreviewFixtures.roomNeedsYou, null, AgentState.NEEDS_YOU, "2m")
            RoomRow(PreviewFixtures.roomActive, null, AgentState.ACTIVE, "14m")
            RoomRow(PreviewFixtures.roomInvitation, null, AgentState.IDLE, "")
        }
    }
}

/**
 * The largest size Android offers, on the row that has the most to fit: a
 * glyph, a name, a state word, a runtime, a time and an unread badge.
 */
@Preview(name = "Roster rows, 2x", showBackground = true, heightDp = 720, fontScale = 2.0f)
@Composable
internal fun RoomRowStatesHuge() {
    PreviewGround {
        Column {
            RoomRow(PreviewFixtures.roomNeedsYou, null, AgentState.NEEDS_YOU, "2m")
            RoomRow(PreviewFixtures.roomActive, null, AgentState.ACTIVE, "14m")
        }
    }
}

/** Three buttons that have to fit a row, or stop trying to. */
@Preview(name = "Card, pending, 2x", showBackground = true, heightDp = 640, fontScale = 2.0f)
@Composable
internal fun CardPendingHuge() {
    PreviewGround {
        DecisionCard(
            view = PreviewFixtures.cardPending,
            label = "Gate",
            eventType = "dev.superpipeline.gate.v1",
            onDecide = { true },
        )
    }
}

/** A table, a code block and a quote, none of which wrap like a paragraph. */
@Preview(name = "Rich text, 2x", showBackground = true, heightDp = 1400, fontScale = 2.0f)
@Composable
internal fun RichTextEveryBlockHuge() {
    PreviewGround {
        RichText(blocks = PreviewFixtures.richBlocks)
    }
}

/** A message with its sender, time and reactions all on one line's worth. */
@Preview(name = "Timeline row, 2x", showBackground = true, heightDp = 520, fontScale = 2.0f)
@Composable
internal fun TimelineRowHuge() {
    PreviewGround {
        Column {
            TimelineRow(row = PreviewFixtures.message, now = NOW, attribution = "Atlas — Platform")
            TimelineRow(row = PreviewFixtures.withReactions, now = NOW, onReact = {})
        }
    }
}
