package dev.supermessage.previews

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.supermessage.ReaderFace
import dev.supermessage.TimelineRow
import java.time.Instant
import uniffi.supermessage_core.ItemView
import uniffi.supermessage_core.PlaceholderKind
import uniffi.supermessage_core.ReactionDto

/** Fixed, for the reason `NOW` in `RosterPreviews.kt` gives. */
private val NOW: Instant = Instant.ofEpochMilli(1_757_700_120_000L)

private val readerIds = listOf("@atlas:example.org", "@priya:example.org", "@ganesha:example.org", "@krishna:example.org")

private fun readers(count: Int): List<ReaderFace> = readerIds.take(count).mapIndexed { i, id ->
    // The first two have sent something here and so have an initial; the rest
    // are drawn as a person glyph.
    ReaderFace(id, null, if (i < 2) listOf("A", "P")[i] else null)
}

/** Two points inside a run, twelve between turns. */
@Preview(name = "Row rhythm", showBackground = true, heightDp = 420)
@Composable
internal fun RowRhythm() {
    PreviewGround(width = 411.dp) {
        Column {
            TimelineRow(row = PreviewFixtures.message, now = NOW)
            TimelineRow(row = PreviewFixtures.noticed, now = NOW, continuesRun = true)
            TimelineRow(row = PreviewFixtures.peerEdited, now = NOW, continuesRun = true)
            TimelineRow(row = PreviewFixtures.message, now = NOW)
            TimelineRow(row = PreviewFixtures.noticed, now = NOW, continuesRun = true)
        }
    }
}

/** Reactions hang off the bubble's bottom corner, on both sides. */
@Preview(name = "Reactions on the corner", showBackground = true, heightDp = 300)
@Composable
internal fun ReactionsOnCorner() {
    val ownReacted = PreviewFixtures.ownLast.let {
        it.copy(
            item = it.item.copy(
                reactions = listOf(ReactionDto("👍", "👍", 2u, true, listOf("@rakesh:example.org", "@atlas:example.org"))),
            ),
        )
    }
    PreviewGround(width = 411.dp) {
        Column {
            TimelineRow(row = PreviewFixtures.withReactions, now = NOW, onReact = {})
            TimelineRow(row = ownReacted, now = NOW, onReact = {})
        }
    }
}

/** Receipts as faces: one, three, and four with a count. */
@Preview(name = "Reader faces", showBackground = true, heightDp = 300)
@Composable
internal fun ReaderFaces() {
    PreviewGround(width = 411.dp) {
        Column {
            TimelineRow(row = PreviewFixtures.ownFirst, now = NOW, endsRun = false, readers = readers(1))
            TimelineRow(row = PreviewFixtures.ownEdited, now = NOW, continuesRun = true, endsRun = false, readers = readers(3))
            TimelineRow(row = PreviewFixtures.ownLast, now = NOW, continuesRun = true, readers = readers(4))
        }
    }
}

/** The unread line, named. */
@Preview(name = "Unread, New", showBackground = true, heightDp = 200)
@Composable
internal fun UnreadNew() {
    PreviewGround(width = 411.dp) {
        Column {
            TimelineRow(row = PreviewFixtures.message, now = NOW)
            TimelineRow(
                row = PreviewFixtures.row(PreviewFixtures.item("\$unread", kind = "virtual", body = null, msgtype = null), ItemView.UnreadMarker),
                now = NOW,
            )
            TimelineRow(row = PreviewFixtures.noticed, now = NOW)
        }
    }
}

/** A message this device holds no key for, and a deleted one for contrast. */
@Preview(name = "Undecryptable", showBackground = true, heightDp = 300)
@Composable
internal fun Undecryptable() {
    PreviewGround(width = 411.dp) {
        Column {
            TimelineRow(row = PreviewFixtures.encrypted, now = NOW)
            TimelineRow(row = PreviewFixtures.encrypted, now = NOW, continuesRun = true)
            TimelineRow(
                row = PreviewFixtures.row(
                    PreviewFixtures.item("\$gone", body = null, msgtype = null),
                    ItemView.Placeholder(PlaceholderKind.Redacted, "Message deleted"),
                ),
                now = NOW,
            )
        }
    }
}

/** A tapped quote's parent, lit: the reply above it, the message it quotes below, washed in accentSoft. */
@Preview(name = "Quote highlighted", showBackground = true, heightDp = 260)
@Composable
internal fun QuoteHighlighted() {
    PreviewGround(width = 411.dp) {
        Column {
            TimelineRow(row = PreviewFixtures.reply, now = NOW, onQuoteTap = {})
            TimelineRow(row = PreviewFixtures.message, now = NOW, highlighted = true)
        }
    }
}
