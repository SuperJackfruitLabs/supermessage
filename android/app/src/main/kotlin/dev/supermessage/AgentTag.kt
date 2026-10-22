package dev.supermessage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The small "Agent" tag beside an agent's name: accent text on accent-soft
 * (iOS `AgentTag`). Shared by the mention picker and the member rows, so a
 * person and an agent read apart the same way everywhere.
 */
@Composable
internal fun AgentTag(modifier: Modifier = Modifier) {
    val colors = SupermessageTheme.colors
    Text(
        "Agent",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = colors.accent,
        modifier = modifier
            .background(colors.accentSoft, CircleShape)
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .semantics { contentDescription = "Agent" },
    )
}
