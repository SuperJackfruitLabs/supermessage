package dev.supermessage.previews

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.supermessage.Timeline
import dev.supermessage.TimelineRow
import java.time.Instant

/** Fixed, for the reason [NOW] in `RosterPreviews.kt` gives. */
private val NOW: Instant = Instant.ofEpochMilli(1_757_700_120_000L)

/**
 * The whole timeline vocabulary in one frame.
 *
 * A row is previewed among its neighbours because almost every decision this
 * composable makes is about them: the run grouping, the day divider's
 * separation, whether a system line reads as belonging to the message above
 * it.
 */
@Preview(name = "Timeline rows", showBackground = true, heightDp = 900)
@Composable
internal fun TimelineVocabulary() {
    PreviewGround {
        Column {
            PreviewFixtures.history.forEach { row ->
                TimelineRow(row = row, now = NOW)
            }
        }
    }
}

/**
 * A second message from the same sender, moments later.
 *
 * `continuesRun` defaults to false, so every other preview here shows the
 * attributed form. This is the pair: the first row names its sender, the
 * second does not, and the gap between them is what says they are one turn.
 */
@Preview(name = "Sender run", showBackground = true, heightDp = 240)
@Composable
internal fun SenderRun() {
    PreviewGround {
        Column {
            TimelineRow(
                row = PreviewFixtures.message, now = NOW, attribution = "Atlas — Platform",
            )
            TimelineRow(row = PreviewFixtures.noticed, now = NOW, continuesRun = true)
        }
    }
}

/**
 * Own messages, and the two send states that are not "sent".
 *
 * A failed send is the one row in the timeline asking the reader for
 * something, and it may not use amber to do it — amber means a pending
 * decision. `danger` is the role that belongs here.
 */
@Preview(name = "Sending and failed", showBackground = true, heightDp = 240)
@Composable
internal fun SendStates() {
    PreviewGround {
        Column {
            TimelineRow(row = PreviewFixtures.ownSending, now = NOW)
            TimelineRow(row = PreviewFixtures.ownFailed, now = NOW)
        }
    }
}

/**
 * A reply whose parent is there, and one whose parent is gone.
 *
 * `ReplyQuoteView.Unavailable` is real and common — the parent was redacted,
 * or has not been paginated in — and the quote has to say so without looking
 * like a failure of this app.
 */
@Preview(name = "Replies", showBackground = true, heightDp = 300)
@Composable
internal fun Replies() {
    PreviewGround {
        Column {
            TimelineRow(row = PreviewFixtures.reply, now = NOW)
            TimelineRow(row = PreviewFixtures.replyToNothing, now = NOW)
        }
    }
}

/**
 * Reactions, including a key that is not an emoji.
 *
 * `ReactionDto.key` is arbitrary sender-controlled text, so a row previewed
 * with nothing but emoji would never show what a word-length key does to the
 * chip row's wrapping. Framed at a phone's width for that reason.
 */
@Preview(name = "Reactions", showBackground = true, widthDp = 360)
@Composable
internal fun Reactions() {
    PreviewGround(width = 360.dp) {
        TimelineRow(row = PreviewFixtures.withReactions, now = NOW, onReact = {})
    }
}

/**
 * Media, with no bytes behind it.
 *
 * Deliberately the interesting case: this is the frame a reader sees before
 * an image arrives, or permanently when it never does. `ItemView.Image`
 * carries the sender's own pixel dimensions so the box can be reserved before
 * the bytes are requested, and whether that reservation is honoured is what
 * this preview shows.
 */
@Preview(name = "Media without bytes", showBackground = true, heightDp = 360)
@Composable
internal fun MediaWithoutBytes() {
    PreviewGround {
        Column {
            TimelineRow(row = PreviewFixtures.image, now = NOW)
            TimelineRow(row = PreviewFixtures.attachment, now = NOW)
        }
    }
}

/**
 * The rows that are not bubbles at all.
 *
 * `ItemView.DateDivider` became a variant only after it was a contract in a
 * comment that iOS missed, putting "Unsupported event (dateDivider)" in the
 * middle of a conversation. Android reads the same enum; this is where the
 * same mistake would be visible.
 */
@Preview(name = "Not a message", showBackground = true, heightDp = 300)
@Composable
internal fun NotAMessage() {
    PreviewGround {
        Column {
            TimelineRow(row = PreviewFixtures.dayDivider, now = NOW)
            TimelineRow(row = PreviewFixtures.membership, now = NOW)
            TimelineRow(row = PreviewFixtures.encrypted, now = NOW)
        }
    }
}

/** A 104-character run with no break in it, at a phone's width. */
@Preview(name = "Unbreakable body", showBackground = true, widthDp = 360)
@Composable
internal fun UnbreakableBody() {
    PreviewGround(width = 360.dp) {
        TimelineRow(row = PreviewFixtures.unbreakable, now = NOW)
    }
}

/**
 * The list itself, with a live turn arriving at the bottom of it.
 *
 * This is the composition no single-row preview reaches: history, then the
 * typing line, then the live answer and its tools. In a running app the live
 * half is gone in seconds.
 */
@Preview(name = "Timeline with a live turn", showBackground = true, heightDp = 900)
@Composable
internal fun TimelineLive() {
    PreviewGround {
        Timeline(
            rows = PreviewFixtures.history,
            revision = 1uL,
            typingLine = "Atlas is typing…",
            isPaginating = false,
            canPaginate = false,
            onPaginate = {},
            onMarkRead = {},
            liveAnswer = PreviewFixtures.LIVE_ANSWER,
            liveThought = PreviewFixtures.LIVE_THOUGHT,
            liveTools = PreviewFixtures.liveTools,
            liveFinished = false,
        )
    }
}

/**
 * Paginating, with more history behind it.
 *
 * `isPaginating` and `canPaginate` are both true here and both false in every
 * other preview, so this is the only frame showing the spinner at the top of
 * the list — and the only one that would catch it sitting where the design
 * has content.
 */
@Preview(name = "Timeline, paginating", showBackground = true, heightDp = 600)
@Composable
internal fun TimelinePaginating() {
    PreviewGround {
        Timeline(
            rows = PreviewFixtures.history,
            revision = 1uL,
            typingLine = null,
            isPaginating = true,
            canPaginate = true,
            onPaginate = {},
            onMarkRead = {},
        )
    }
}

/** A room with nothing in it yet. */
@Preview(name = "Timeline, empty room", showBackground = true, heightDp = 320)
@Composable
internal fun TimelineEmpty() {
    PreviewGround {
        Timeline(
            rows = emptyList(),
            revision = 0uL,
            typingLine = null,
            isPaginating = false,
            canPaginate = false,
            onPaginate = {},
            onMarkRead = {},
        )
    }
}
