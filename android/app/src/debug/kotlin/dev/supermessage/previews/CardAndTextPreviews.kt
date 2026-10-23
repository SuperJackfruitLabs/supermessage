package dev.supermessage.previews

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.supermessage.AnsweredHere
import dev.supermessage.DecisionCard
import dev.supermessage.RichText
import dev.supermessage.rememberDecisionAnswers
import java.time.Instant

/** The signature element, and the only place amber appears in this app. */
@Preview(name = "Card, pending decision", showBackground = true, heightDp = 420)
@Composable
internal fun CardPending() {
    PreviewGround {
        DecisionCard(
            view = PreviewFixtures.cardPending,
            label = "Gate",
            eventType = "dev.superpipeline.gate.v1",
            senderName = "Atlas — Platform",
            onDecide = { true },
        )
    }
}

/** The remaining fields, opened: the digest line above holds the first two values. */
@Preview(name = "Card, pending, details open", showBackground = true, heightDp = 520)
@Composable
internal fun CardPendingDetailsOpen() {
    PreviewGround {
        DecisionCard(
            view = PreviewFixtures.cardPending,
            label = "Gate",
            eventType = "dev.superpipeline.gate.v1",
            senderName = "Atlas \u2014 Platform",
            detailsOpenInitially = true,
            onDecide = { true },
        )
    }
}

/**
 * A send that did not land: still amber and still answerable, with the reason
 * in `danger`. The line is the only red on the card.
 */
@Preview(name = "Card, send failed", showBackground = true, heightDp = 460)
@Composable
internal fun CardSendFailed() {
    PreviewGround {
        DecisionCard(
            view = PreviewFixtures.cardPending,
            label = "Gate",
            eventType = "dev.superpipeline.gate.v1",
            senderName = "Atlas \u2014 Platform",
            answers = rememberDecisionAnswers(failed = setOf("gate-7f21")),
            answerKey = "gate-7f21",
            onDecide = { false },
        )
    }
}

/** Answered on this device: a green check, not amber, and the board has not yet confirmed. */
@Preview(name = "Card, just answered", showBackground = true, heightDp = 360)
@Composable
internal fun CardJustAnswered() {
    PreviewGround {
        DecisionCard(
            view = PreviewFixtures.cardPending,
            label = "Gate",
            eventType = "dev.superpipeline.gate.v1",
            senderName = "Atlas \u2014 Platform",
            answers = rememberDecisionAnswers(
                answered = mapOf("gate-7f21" to AnsweredHere("Approved by you", Instant.now())),
            ),
            answerKey = "gate-7f21",
            onDecide = { true },
        )
    }
}

/** The largest text on a narrow phone: the buttons stack and "Request changes" stays on one line. */
@Preview(name = "Card, large text, narrow", showBackground = true, widthDp = 360, heightDp = 760, fontScale = 2f)
@Composable
internal fun CardLargeTextNarrow() {
    PreviewGround(width = 360.dp) {
        DecisionCard(
            view = PreviewFixtures.cardPending,
            label = "Gate",
            eventType = "dev.superpipeline.gate.v1",
            senderName = "Atlas \u2014 Platform",
            onDecide = { true },
        )
    }
}

/**
 * Answered: the amber is gone and the buttons have settled.
 *
 * The pair is the preview, not either half — the difference between these two
 * frames is the entire visual grammar of "this needs you" in the product.
 */
@Preview(name = "Card, answered", showBackground = true, heightDp = 300)
@Composable
internal fun CardAnswered() {
    PreviewGround {
        DecisionCard(
            view = PreviewFixtures.cardAnswered,
            label = "Gate",
            eventType = "dev.superpipeline.gate.v1",
            senderName = "Atlas — Platform",
        )
    }
}

/**
 * Resolved in the room: the board has the answer, and says whose it was. No
 * buttons and no amber — and unlike the per-device answered state, this one
 * survives leaving the room, because it comes from the room.
 */
@Preview(name = "Card, resolved in the room", showBackground = true, heightDp = 300)
@Composable
internal fun CardResolved() {
    PreviewGround {
        DecisionCard(
            view = PreviewFixtures.cardResolved,
            label = "Gate",
            eventType = "dev.superpipeline.gate.v1",
            senderName = "Atlas — Platform",
            onDecide = { true },
        )
    }
}

@Preview(name = "Card, with reasoning", showBackground = true, heightDp = 320)
@Composable
internal fun CardWithReasoning() {
    PreviewGround {
        DecisionCard(
            view = PreviewFixtures.cardWithReasoning,
            label = "Turn",
            eventType = "dev.agentpod.turn.v1",
            senderName = "Atlas — Platform",
        )
    }
}

/**
 * A field value that is one 71-character unbroken run.
 *
 * Every value on a card is arbitrary JSON from anyone who can send to the
 * room, so this is the shape that finds a missing wrap guard. Framed at a
 * phone's width because the web story for the same guard rendered 1147px
 * wide on its first attempt while claiming to show the guard holding.
 */
@Preview(name = "Card, unbreakable value", showBackground = true, widthDp = 360)
@Composable
internal fun CardLongValue() {
    PreviewGround(width = 360.dp) {
        DecisionCard(
            view = PreviewFixtures.cardLongValue,
            label = "Artifact",
            eventType = "dev.agentpod.artifact.v1",
            senderName = "Atlas — Platform",
        )
    }
}

/**
 * The three fallback states together.
 *
 * All three are the core telling the host that the sender knows more about
 * this event type than it does. Previewed together because the question is
 * whether they are visibly *different* — a host that draws them identically
 * has made the fallback chain pointless.
 */
@Preview(name = "Card, fallback chain", showBackground = true, heightDp = 640)
@Composable
internal fun CardFallbackChain() {
    PreviewGround {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            DecisionCard(
                view = PreviewFixtures.cardNewerVersion,
                label = "Station",
                eventType = "dev.agentpod.station.v2",
                senderName = "Atlas — Platform",
            )
            Spacer(Modifier.height(16.dp))
            DecisionCard(
                view = PreviewFixtures.cardFallback,
                label = "Station",
                eventType = "dev.agentpod.station.v2",
                senderName = "Atlas — Platform",
            )
            Spacer(Modifier.height(16.dp))
            DecisionCard(
                view = PreviewFixtures.cardPlaceholder,
                label = "Event",
                eventType = "dev.agentpod.unknown.v1",
                senderName = "Atlas — Platform",
            )
        }
    }
}

/**
 * A sender-controlled event type carrying a right-to-left override.
 *
 * `ItemView.CustomEvent`'s doc comment is explicit: truncate from the left,
 * never the right, and never render it with an RTL base direction, because
 * the obvious approach hands the bidi algorithm a crafted string and lets a
 * type reorder itself on screen. Nothing else in this catalogue would show
 * that happening.
 */
@Preview(name = "Card, hostile event type", showBackground = true, widthDp = 360)
@Composable
internal fun CardHostileEventType() {
    PreviewGround(width = 360.dp) {
        DecisionCard(
            view = PreviewFixtures.cardAnswered,
            label = "Station",
            eventType = PreviewFixtures.HOSTILE_EVENT_TYPE,
            senderName = "Atlas — Platform",
        )
    }
}

/**
 * One of every rich block kind together, which is the only arrangement that
 * shows whether the vertical rhythm between them is consistent.
 */
@Preview(name = "Rich text, every block", showBackground = true, heightDp = 820)
@Composable
internal fun RichTextEveryBlock() {
    PreviewGround {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            RichText(blocks = PreviewFixtures.richBlocks)
        }
    }
}

/**
 * A code block far wider than any phone.
 *
 * Code is the one thing here that may not be re-wrapped — a broken command is
 * a wrong command — so it has to scroll sideways inside its own box while the
 * page does not. This is the preview that shows which of those happens.
 */
@Preview(name = "Rich text, wide code", showBackground = true, widthDp = 360)
@Composable
internal fun RichTextWideCode() {
    PreviewGround(width = 360.dp) {
        RichText(blocks = PreviewFixtures.wideCode)
    }
}
