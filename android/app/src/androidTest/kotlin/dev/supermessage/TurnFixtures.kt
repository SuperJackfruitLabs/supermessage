package dev.supermessage

import dev.supermessage.previews.PreviewFixtures
import uniffi.supermessage_core.ToolPhase
import uniffi.supermessage_core.TurnEnding
import uniffi.supermessage_core.TurnStep
import uniffi.supermessage_core.TurnSummary

/** Turn fixtures shared by the activity card's tests. The words in them are the test's; the host draws them as given. */
internal fun turnStep(
    id: String,
    title: String = id,
    phase: ToolPhase = ToolPhase.DONE,
    statusLabel: String = "Done",
    hasDetail: Boolean = false,
    input: String? = null,
    output: String? = null,
    locations: List<String> = emptyList(),
) = TurnStep(
    id = id, title = title, kind = null, status = "completed", phase = phase, statusLabel = statusLabel,
    locations = locations, input = input, output = output, seq = 1uL, hasDetail = hasDetail,
)

internal fun inProgress(
    startedAtMs: Long = 0L,
    lastActivityMs: Long = 8_000L,
    totalSteps: Int = 0,
    completedSteps: Int = 0,
    failedStep: String? = null,
    currentStepTitle: String = "Starting",
    stepIsTool: Boolean = false,
): TurnSummary = PreviewFixtures.turn(
    startedAtMs = startedAtMs, lastActivityMs = lastActivityMs, totalSteps = totalSteps,
    completedSteps = completedSteps, failedStep = failedStep, currentStepTitle = currentStepTitle,
    stepIsTool = stepIsTool,
)

internal fun ended(by: TurnEnding, recordLabel: String? = null, steps: Int = 1): TurnSummary =
    PreviewFixtures.turn(
        endedBy = by, recordLabel = recordLabel, totalSteps = steps, completedSteps = steps,
        lastActivityMs = 12_000L,
    )
