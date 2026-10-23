package dev.supermessage.previews

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.supermessage.LiveTurnCard
import dev.supermessage.LocalReduceMotion
import dev.supermessage.TimelineRow
import dev.supermessage.WhatIDid
import java.time.Instant
import uniffi.supermessage_core.TurnEnding

private val NOW: Instant = Instant.ofEpochMilli(1_757_700_120_000L)

/**
 * The activity card, still: Reduce Motion on and `still = true`, so no
 * spinner turns, the answer lands whole and the elapsed time is measured to
 * the last event (a frame is the same whenever it is taken).
 */
@Composable
private fun StillGround(width: androidx.compose.ui.unit.Dp? = null, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalReduceMotion provides true) {
        PreviewGround(width = width, content = content)
    }
}

/** A turn mid-flight: a thought, three steps with one failed and one running, and an answer arriving. */
@Preview(name = "Live turn, mid-turn", showBackground = true, heightDp = 560)
@Composable
internal fun LiveTurnMidTurn() {
    StillGround {
        LiveTurnCard(
            summary = PreviewFixtures.turn(
                totalSteps = 3, completedSteps = 1, failedStep = "write src/lib/tokens.css",
                currentStepTitle = "regenerate every design-token target", stepIsTool = true,
                hasThought = true, answering = true,
            ),
            steps = PreviewFixtures.liveTools,
            thought = PreviewFixtures.LIVE_THOUGHT,
            answer = PreviewFixtures.LIVE_ANSWER,
            name = "Atlas", still = true,
        )
    }
}

/** Thinking, with nothing else to show yet: the first thing a reader sees after sending. */
@Preview(name = "Live turn, thinking", showBackground = true, heightDp = 220)
@Composable
internal fun LiveTurnThinkingOnly() {
    StillGround {
        LiveTurnCard(
            summary = PreviewFixtures.turn(lastActivityMs = 3_000L, currentStepTitle = "Thinking", hasThought = true),
            steps = emptyList(), thought = PreviewFixtures.LIVE_THOUGHT, answer = null,
            name = "Atlas", still = true,
        )
    }
}

/** An answer with markdown in it; the step line has gone because the text says what is happening. */
@Preview(name = "Live turn, answering", showBackground = true, heightDp = 300)
@Composable
internal fun LiveTurnAnsweringOnly() {
    StillGround {
        LiveTurnCard(
            summary = PreviewFixtures.turn(currentStepTitle = "Writing the answer", answering = true),
            steps = emptyList(), thought = null,
            answer = "# Findings\n**Observed:** the build is green\n- two warnings\n- no failures\nNext I will check the",
            name = "Atlas", still = true,
        )
    }
}

/** iOS `ComposerRevampFixtures.longTurn()`: 1m 04s, two steps done, one failed, one running. */
@Preview(name = "Live turn, activity card", showBackground = true, heightDp = 300)
@Composable
internal fun LiveTurnActivityCard() {
    StillGround {
        LiveTurnCard(
            summary = PreviewFixtures.turn(
                lastActivityMs = 64_000L, totalSteps = 4, completedSteps = 2,
                failedStep = "Lint the workspace", currentStepTitle = "Run the integration suite", stepIsTool = true,
            ),
            steps = PreviewFixtures.liveTools, thought = null, answer = null,
            name = "Atlas", still = true,
        )
    }
}

/** A finished turn drawn as a card (previews only: in the app it has no cell). */
@Preview(name = "Live turn, finished", showBackground = true, heightDp = 220)
@Composable
internal fun LiveTurnActivityCardFinished() {
    StillGround {
        LiveTurnCard(
            summary = PreviewFixtures.turn(
                lastActivityMs = 64_000L, endedBy = TurnEnding.DONE, totalSteps = 3, completedSteps = 3,
                recordLabel = "3 steps · 1m 4s",
            ),
            steps = PreviewFixtures.liveTools, thought = null, answer = null,
            name = "Atlas", still = true,
        )
    }
}

/** A turn the core gave up on: "Stopped", in the neutral colour. */
@Preview(name = "Live turn, stopped", showBackground = true, heightDp = 200)
@Composable
internal fun LiveTurnStopped() {
    StillGround {
        LiveTurnCard(
            summary = PreviewFixtures.turn(lastActivityMs = 90_000L, endedBy = TurnEnding.TIMED_OUT, totalSteps = 1),
            steps = PreviewFixtures.liveTools.take(1), thought = null, answer = null,
            name = "Atlas", still = true,
        )
    }
}

/** The record in the answer's own row, collapsed (T6): iOS preview "What I did". */
@Preview(name = "What I did, in the answer", showBackground = true, heightDp = 260)
@Composable
internal fun WhatIDidInTheAnswer() {
    StillGround(width = 390.dp) {
        TimelineRow(
            row = PreviewFixtures.message, now = NOW, attribution = "Atlas",
            prelude = { WhatIDid(label = "Thought for 12s · 2 steps", thought = PreviewFixtures.LIVE_THOUGHT, steps = PreviewFixtures.liveTools.take(2)) },
        )
    }
}

/** Font scale 2.0 (RF3): a long name and a long failed step must not push the state word off the card. */
@Preview(name = "Live turn, large text", showBackground = true, heightDp = 700, widthDp = 411)
@Composable
internal fun LiveTurnLargeText() {
    StillGround {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
            Column {
                LiveTurnCard(
                    summary = PreviewFixtures.turn(
                        lastActivityMs = 64_000L, totalSteps = 4, completedSteps = 2,
                        failedStep = "Run the integration suite against staging",
                        currentStepTitle = "Run the integration suite", stepIsTool = true,
                    ),
                    steps = PreviewFixtures.liveTools.take(1), thought = null, answer = null,
                    name = "A very long agent name that needs to be cut off somewhere", still = true,
                )
            }
        }
    }
}
