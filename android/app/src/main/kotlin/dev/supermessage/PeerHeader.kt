package dev.supermessage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import uniffi.supermessage_core.TimelineRow as TimelineRowDto

/**
 * The word that says a participant is an agent (`AgentLabel`,
 * `TimelineChrome.swift:177-190`): accent on accentSoft, a capsule, never
 * compressed. [TimelineRowDto.isAgent] decides when to draw it; this never does.
 */
@Composable
internal fun AgentLabel(modifier: Modifier = Modifier) {
    Text(
        "Agent",
        style = MaterialTheme.typography.labelSmall,
        fontFamily = ThemeType.label,
        fontWeight = FontWeight.SemiBold,
        color = SupermessageTheme.colors.accent,
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            .testTag("agent-label")
            .background(SupermessageTheme.colors.accentSoft, CircleShape)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/**
 * A peer's header: face at 22, the name on one line in the peer's tint, the
 * Agent label when the core says the row is an agent's, the time, and
 * "edited" (`TimelineRowView.swift:381-406`). Shared by a message, a voice
 * note and an agent's failed turn. The name gives way first; the label and
 * the time are measured before it and never compressed.
 */
@Composable
internal fun PeerHeader(
    row: TimelineRowDto,
    named: String,
    faceUri: (mxcUri: String) -> String?,
    loadFace: suspend (mxcUri: String) -> Unit,
    showsEdited: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.testTag("peer-header").semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // `row.senderInitial`, not `named`: for an agent the first character of
        // the attribution is the glyph, which the core already split off.
        SenderFace(
            mxcUri = row.item.senderAvatar,
            initial = row.senderInitial,
            faceUri = faceUri,
            loadFace = loadFace,
            size = 22.dp,
        )
        Text(
            named,
            style = MaterialTheme.typography.labelLarge,
            color = row.item.sender?.let { SupermessageTheme.peer(it) } ?: Color.Unspecified,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).testTag("peer-name"),
        )
        if (row.isAgent) AgentLabel()
        row.item.timestampMs?.let {
            Text(clockLabel(it), style = MaterialTheme.typography.labelSmall, color = SupermessageTheme.colors.contentFaint, maxLines = 1, softWrap = false)
        }
        if (showsEdited && row.item.edited) EditedMarker()
    }
}
