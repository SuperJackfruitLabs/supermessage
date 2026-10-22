package dev.supermessage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import uniffi.supermessage_core.SpaceSummary

/**
 * The roster's title, as a menu of spaces (`Rooms/SpaceMenu.swift:12-91`).
 *
 * With no spaces it is only the title, "Chats". Otherwise it names the
 * selected space (or "Chats") and offers "All spaces · N" and each space with
 * its room count, or "Invitation" for one not yet joined. Names are the
 * core's: `identity.name` is parsed from the already-readable space name.
 */
@Composable
fun SpaceMenu(
    spaces: List<SpaceSummary>,
    selectedId: String?,
    allCount: Int,
    isInvitation: (SpaceSummary) -> Boolean,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (spaces.isEmpty()) {
        Text("Chats", style = MaterialTheme.typography.titleMedium, modifier = modifier)
        return
    }
    val title = spaces.firstOrNull { it.id == selectedId }?.identity?.name ?: "Chats"
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier
                .clickable(onClickLabel = "Shows the rooms of one space") { open = true }
                .semantics(mergeDescendants = true) { contentDescription = "Space: $title" }
                .padding(vertical = 4.dp)
                .testTag("space-menu"),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clearAndSetSemantics { },
            )
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = SupermessageTheme.colors.contentMuted,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("All spaces · $allCount") },
                onClick = { open = false; onSelect(null) },
                modifier = Modifier.testTag("space-all"),
            )
            for (space in spaces) {
                val name = space.identity.name
                DropdownMenuItem(
                    // No count for an invitation: the account cannot see into a
                    // space it has not joined, so one would be invented.
                    text = { Text(if (isInvitation(space)) "$name · Invitation" else "$name · ${space.childCount}") },
                    onClick = { open = false; onSelect(space.id) },
                    modifier = Modifier.testTag("space-${space.id}"),
                )
            }
        }
    }
}
