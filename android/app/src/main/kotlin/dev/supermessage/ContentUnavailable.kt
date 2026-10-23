package dev.supermessage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * An empty pane that says why (SwiftUI's `ContentUnavailableView`): a glyph,
 * a title, a line under it and, when there is something to do about it, an
 * [action]. Centred in whatever space it is given, and read as one element.
 *
 * The words are the caller's: this decides nothing about when a pane is empty.
 * A caller shows it only once the first read has landed, so that "nothing
 * here" never means "not read yet".
 */
@Composable
internal fun ContentUnavailable(
    title: String,
    description: String?,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = SupermessageTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = colors.contentFaint, modifier = Modifier.size(48.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = colors.content,
            textAlign = TextAlign.Center,
        )
        if (description != null) {
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.contentMuted,
                textAlign = TextAlign.Center,
            )
        }
        action?.invoke()
    }
}
