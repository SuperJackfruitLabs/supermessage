package dev.supermessage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import uniffi.supermessage_core.TimelineRow as TimelineRowDto

/**
 * Which entries a message's menu offers, decided from the row's own flags and
 * nothing else — `TimelineCollectionView.swift:1039-1150`:
 *
 * - react and reply: `row.canReplyOrReact`;
 * - copy: a non-empty `item.body`;
 * - edit: `item.editable`;
 * - delete: `item.isOwn && row.canReplyOrReact`;
 * - report: someone else's message the server has acknowledged, with a
 *   sender to name (`:1128-1145`).
 */
data class MessageMenuActions(
    val react: Boolean,
    val reply: Boolean,
    val copy: Boolean,
    val edit: Boolean,
    val delete: Boolean,
    val report: Boolean,
) {
    /** No menu at all when nothing would be offered (`:1147`). */
    val isEmpty: Boolean get() = !(react || reply || copy || edit || delete || report)
}

internal fun messageMenuActions(row: TimelineRowDto): MessageMenuActions = MessageMenuActions(
    react = row.canReplyOrReact,
    reply = row.canReplyOrReact,
    copy = !row.item.body.isNullOrEmpty(),
    edit = row.item.editable,
    delete = row.item.isOwn && row.canReplyOrReact,
    report = !row.item.isOwn && row.canReplyOrReact && row.item.eventId != null && row.item.sender != null,
)

/**
 * The long-press menu for one message: a strip of [quickReactions], then
 * Reply, Copy, Edit and Delete as the row allows ([messageMenuActions]), and
 * a destructive "Report…" last on someone else's message.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageMenu(
    row: TimelineRowDto,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReport: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("message-menu"),
    ) {
        MessageMenuContent(row, onReact, onReply, onCopy, onEdit, onDelete, onReport)
    }
}

/** The sheet's body, apart from the sheet, so a preview can draw it. */
@Composable
internal fun MessageMenuContent(
    row: TimelineRowDto,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReport: () -> Unit,
) {
    val actions = messageMenuActions(row)
    Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp)) {
        if (actions.react) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                quickReactions.forEach { emoji ->
                    Text(
                        emoji,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable(onClickLabel = "React with $emoji") { onReact(emoji) }
                            .testTag("quick-reaction-$emoji")
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
        if (actions.reply) MenuItem("Reply", "menu-reply", onReply)
        if (actions.copy) MenuItem("Copy", "menu-copy", onCopy)
        if (actions.edit) MenuItem("Edit", "menu-edit", onEdit)
        if (actions.delete) MenuItem("Delete", "menu-delete", onDelete, destructive = true)
        if (actions.report) {
            HorizontalDivider()
            MenuItem("Report…", "menu-report", onReport, destructive = true)
        }
    }
}

@Composable
private fun MenuItem(label: String, tag: String, onClick: () -> Unit, destructive: Boolean = false) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .testTag(tag)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/**
 * Deleting is a redaction every member sees, so it asks first
 * (`TimelineCollectionView.swift:975-989`).
 */
@Composable
fun DeleteConfirm(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        modifier = Modifier.testTag("delete-confirm"),
        onDismissRequest = onDismiss,
        title = { Text("Delete message?") },
        text = { Text("This removes it for everyone in the room.") },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("delete-confirm-button")) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("delete-confirm-cancel")) { Text("Cancel") }
        },
    )
}
