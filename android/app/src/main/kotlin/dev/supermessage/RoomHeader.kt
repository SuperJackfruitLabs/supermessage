package dev.supermessage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import uniffi.supermessage_core.RoomRow

/**
 * The count line under a room's name: `"1 member"` or `"N members"`, and
 * nothing for an unknown or empty room (iOS `RoomScreen.swift:212`).
 */
internal fun memberCountLabel(count: ULong?): String? = when {
    count == null || count == 0uL -> null
    count == 1uL -> "1 member"
    else -> "$count members"
}

/**
 * What the room is called, and for a room of people how many are in it —
 * `RoomScreen.swift:199-220`. The whole header is the way into room info
 * (`RoomScreen.swift:102-115`), so there is no separate info button.
 *
 * No avatar: iOS draws none (owner decision O2). No status pill: that is M4.
 * [memberCount] is `null` for an agent's room, an invitation, or until the
 * core has answered.
 */
@Composable
fun RoomHeader(
    row: RoomRow,
    memberCount: ULong?,
    onOpenInfo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = row.identity.name
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("room-header")
            .clickable(onClickLabel = "About this room", onClick = onOpenInfo)
            .semantics(mergeDescendants = true) { contentDescription = "$name, about this room" }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            color = SupermessageTheme.colors.content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        memberCountLabel(memberCount)?.let { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = SupermessageTheme.colors.contentMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
