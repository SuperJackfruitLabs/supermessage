package dev.supermessage.previews

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.supermessage.LongReadContent
import dev.supermessage.TimelineRow
import java.time.Instant
import uniffi.supermessage_core.ItemView
import uniffi.supermessage_core.RichBlock
import uniffi.supermessage_core.RichInline
import uniffi.supermessage_core.TimelineRow as TimelineRowDto
import uniffi.supermessage_core.TurnErrorCard
import uniffi.supermessage_core.TurnErrorKind

private val NOW: Instant = Instant.ofEpochMilli(1_757_700_120_000L)

private fun report(paragraphs: Int) = (1..paragraphs).map {
    RichBlock.Paragraph(
        listOf(
            RichInline.Text(
                "Paragraph $it. The migration moved the token table into the core, so the hosts only draw " +
                    "what they are given; nothing here recomputes a threshold or a word the core already chose.",
            ),
        ),
    )
}

private val agentCard: TimelineRowDto
    get() = PreviewFixtures.message.copy(isAgent = true)

private val longRead: TimelineRowDto
    get() = PreviewFixtures.row(
        PreviewFixtures.item("\$long", body = "A long report"),
        ItemView.Bubble(false, report(14), voice = null),
    ).copy(isAgent = true, isLongRead = true)

private val failedTurn: TimelineRowDto
    get() = PreviewFixtures.row(
        PreviewFixtures.item("\$err", body = "Usage limit reached"),
        ItemView.TurnError(
            TurnErrorCard(
                kind = TurnErrorKind.QUOTA, label = "Usage limit reached", source = "kimi-coding / k2p6",
                headline = "Usage limit reached · kimi-coding / k2p6", message = "You have hit the limit for this period.",
                harness = "openclaw", provider = null, model = null, retryable = null, attempts = emptyList(),
            ),
        ),
    ).copy(isAgent = true)

/** An agent's message: a card on the raised ground, headed by the face, the name and "Agent". */
@Preview(name = "Agent card", showBackground = true, heightDp = 220)
@Composable
internal fun AgentCard() {
    PreviewGround { TimelineRow(row = agentCard, now = NOW, attribution = "Atlas — Platform") }
}

/** A person's message is a card too; the label is what marks an agent. */
@Preview(name = "Person card", showBackground = true, heightDp = 220)
@Composable
internal fun PersonCard() {
    PreviewGround {
        TimelineRow(
            row = PreviewFixtures.message.copy(senderName = "Rakesh", senderShort = "Rakesh", senderInitial = "R"),
            now = NOW, attribution = "Rakesh",
        )
    }
}

/** More than the core's threshold: cut at 320, faded, and a Read button under it. */
@Preview(name = "Agent card, long read", showBackground = true, heightDp = 520)
@Composable
internal fun AgentCardLongRead() {
    PreviewGround { TimelineRow(row = longRead, now = NOW, attribution = "Atlas — Platform") }
}

/** The reading view's content: serif, +4 line spacing, a title and Done. */
@Preview(name = "Long read view", showBackground = true, heightDp = 640)
@Composable
internal fun LongReadView() {
    PreviewGround { LongReadContent(title = "Atlas — Platform", blocks = report(8), onDone = {}) }
}

/** A reply directly under its parent: no quote. Then one that is not, with its quote. */
@Preview(name = "Quote under its parent", showBackground = true, heightDp = 460)
@Composable
internal fun QuoteUnderItsParent() {
    PreviewGround {
        Column {
            TimelineRow(row = PreviewFixtures.message.copy(senderName = "Rakesh", senderShort = "Rakesh", senderInitial = "R"), now = NOW)
            TimelineRow(row = PreviewFixtures.reply.copy(hidesQuote = true), now = NOW, attribution = "Atlas — Platform")
            TimelineRow(row = PreviewFixtures.reply, now = NOW, attribution = "Atlas — Platform")
        }
    }
}

/** Font scale 2: the name gives way, the Agent label and the time stay whole. */
@Preview(name = "Agent card, 2x", showBackground = true, heightDp = 360, fontScale = 2.0f)
@Composable
internal fun AgentCardLargeText() {
    PreviewGround {
        TimelineRow(
            row = agentCard.copy(senderName = "Atlas — Platform engineering on call"),
            now = NOW, attribution = "Atlas — Platform engineering on call",
        )
    }
}

/** An agent's voice note and failed turn carry the same header. */
@Preview(name = "Agent headers, voice note and failed turn", showBackground = true, heightDp = 420)
@Composable
internal fun AgentHeadersOnVoiceAndFailedTurn() {
    PreviewGround {
        Column {
            TimelineRow(row = PreviewFixtures.voicePeer.copy(isAgent = true), now = NOW, attribution = "Atlas — Platform")
            TimelineRow(row = failedTurn, now = NOW, attribution = "Atlas — Platform")
        }
    }
}
