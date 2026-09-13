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
import dev.supermessage.DecisionCard
import dev.supermessage.LiveTurn
import dev.supermessage.RichText

/** The signature element, and the only place amber appears in this app. */
@Preview(name = "Card, pending decision", showBackground = true, heightDp = 420)
@Composable
internal fun CardPending() {
    PreviewGround {
        DecisionCard(
            view = PreviewFixtures.cardPending,
            label = "Gate",
            eventType = "dev.kaambaan.gate.v1",
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
            eventType = "dev.kaambaan.gate.v1",
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
            )
            Spacer(Modifier.height(16.dp))
            DecisionCard(
                view = PreviewFixtures.cardFallback,
                label = "Station",
                eventType = "dev.agentpod.station.v2",
            )
            Spacer(Modifier.height(16.dp))
            DecisionCard(
                view = PreviewFixtures.cardPlaceholder,
                label = "Event",
                eventType = "dev.agentpod.unknown.v1",
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

/**
 * A turn in flight: a thought, three tools with one failed, and an answer
 * arriving.
 *
 * None of this is history — not persisted, not paginated, and a device that
 * was asleep never sees it. Which is why the still frame matters: in a
 * running app the failed-tool row is gone in seconds.
 */
@Preview(name = "Live turn, mid-turn", showBackground = true, heightDp = 420)
@Composable
internal fun LiveTurnMidTurn() {
    PreviewGround {
        LiveTurn(
            answer = PreviewFixtures.LIVE_ANSWER,
            thought = PreviewFixtures.LIVE_THOUGHT,
            tools = PreviewFixtures.liveTools,
            finished = false,
        )
    }
}

/**
 * Thinking, with nothing to show yet.
 *
 * The first thing a reader sees after sending.
 */
@Preview(name = "Live turn, thinking", showBackground = true, heightDp = 200)
@Composable
internal fun LiveTurnThinking() {
    PreviewGround {
        LiveTurn(
            answer = null,
            thought = PreviewFixtures.LIVE_THOUGHT,
            tools = emptyList(),
            finished = false,
        )
    }
}

/** A finished turn, which is what the composable has to stop showing. */
@Preview(name = "Live turn, finished", showBackground = true, heightDp = 200)
@Composable
internal fun LiveTurnFinished() {
    PreviewGround {
        LiveTurn(
            answer = PreviewFixtures.LIVE_ANSWER,
            thought = null,
            tools = emptyList(),
            finished = true,
        )
    }
}
