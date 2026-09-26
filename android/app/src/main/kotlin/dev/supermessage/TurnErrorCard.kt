package dev.supermessage

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import uniffi.supermessage_core.TurnErrorCard

/**
 * An agent's failed turn — `ItemView.TurnError`, which `core::turn_error`
 * parsed off the hub's error message.
 *
 * Deliberately simple on this platform: the headline and the provider's own
 * words, and each attempt as one line. Every string arrived decided (the
 * kind's wording, the headline, the "×4" count) and is drawn as text only.
 * iOS's `TurnErrorCard.swift` is the fuller version, with the chain collapsed
 * to its first line; the difference is recorded in `docs/platform-parity.md`.
 *
 * `error`, never the signal colour: amber means a decision the reader owes
 * (docs/design-language.md §2).
 */
@Composable
fun TurnErrorCardView(card: TurnErrorCard, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = modifier
            .padding(vertical = 6.dp)
            .widthIn(max = 600.dp)
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("turn-error-card"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            card.headline,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.error,
        )
        SelectionContainer {
            Text(card.message, style = MaterialTheme.typography.bodyMedium)
        }
        // The headline already names the first attempt, the model that was
        // asked for; under it go only the models the agent fell back to, and
        // a retried first model is said once. Same rules as iOS's
        // TurnErrorPresentation and the web's turnErrorView.ts.
        val first = card.attempts.firstOrNull()
        val headlineIsFirst = first != null && card.source != null && first.source == card.source
        val fallbacks = if (headlineIsFirst) card.attempts.drop(1) else card.attempts
        if (headlineIsFirst && first!!.count > 1u) {
            Text(
                "Tried ${first.count} times",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        for (attempt in fallbacks) {
            val count = if (attempt.count > 1u) " ×${attempt.count}" else ""
            Text(
                "${attempt.source} · ${attempt.label}$count",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
