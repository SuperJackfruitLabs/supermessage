package dev.supermessage

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.supermessage.kit.RosterArrangement
import uniffi.supermessage_core.RosterFilter
import uniffi.supermessage_core.RosterFilterChip

/**
 * All · Unread, above the Chats list (`Rooms/RosterFilterChips.swift:14-57`).
 *
 * The chips, their words and their counts are the core's ([chips] is
 * `RosterArrangement.filterChips` over the whole arrangement, so a chip says how
 * many rooms it would leave before it is chosen). A chip narrows the list and
 * never re-orders it. No amber: the accent marks the chip in force.
 *
 * Sits in the list's scroll content, so it scrolls away; a row that wraps its
 * content in height, never a share of what is left, so a long list cannot squash it.
 */
@Composable
fun FilterChips(
    chips: List<RosterFilterChip>,
    selected: RosterFilter,
    onSelect: (RosterFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .testTag("filter-chips"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (chip in chips) {
            FilterChip(chip, isSelected = chip.filter == selected) {
                if (chip.filter != selected) {
                    haptic.play(Haptic.Selection)
                    onSelect(chip.filter)
                }
            }
        }
    }
}

@Composable
private fun FilterChip(chip: RosterFilterChip, isSelected: Boolean, onClick: () -> Unit) {
    val colors = SupermessageTheme.colors
    val key = RosterArrangement.filterKey(chip.filter)
    val textColor = if (isSelected) colors.accentContent else colors.content
    // Drawn at iOS's 28dp, touched at 48dp: the node, and so the hit area, is the outer box.
    Box(
        Modifier
            .selectable(selected = isSelected, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = "${chip.title}, ${chip.count}" }
            .testTag("chip-$key")
            // After the semantics, so the touch target is the enlarged box and the chip is drawn inside it.
            .minimumInteractiveComponentSize(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = if (isSelected) colors.accent else colors.surfaceRaised,
            border = if (isSelected) null else BorderStroke(1.dp, colors.border),
            modifier = Modifier.testTag("chip-drawn-$key"),
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    chip.title,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = textColor,
                    maxLines = 1,
                )
                if (chip.filter != RosterFilter.ALL && chip.count > 0u) {
                    Text(
                        "${chip.count}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) colors.accentContent else colors.contentMuted,
                        maxLines = 1,
                        modifier = Modifier.testTag("chip-count-$key"),
                    )
                }
            }
        }
    }
}
