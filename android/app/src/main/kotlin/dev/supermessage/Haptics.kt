package dev.supermessage

import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

/**
 * The four haptic meanings the app uses, named for what happened rather than
 * for the platform constant that expresses it. iOS's `sensoryFeedback`
 * (`ComposerView.swift:110-113`) takes the same four roles.
 *
 * Played through Compose's [HapticFeedback], which honours the system's
 * touch-feedback setting, so no extra permission or setting check lives here.
 */
enum class Haptic { Success, Failure, Selection, Light }

fun HapticFeedback.play(haptic: Haptic) {
    performHapticFeedback(
        when (haptic) {
            Haptic.Success -> HapticFeedbackType.Confirm
            Haptic.Failure -> HapticFeedbackType.Reject
            Haptic.Selection -> HapticFeedbackType.SegmentTick
            Haptic.Light -> HapticFeedbackType.ContextClick
        },
    )
}
