package dev.supermessage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.supermessage.kit.ReportSubject
import dev.supermessage.kit.Session
import kotlinx.coroutines.launch
import uniffi.supermessage_ffi.reportReasonRemaining

/**
 * Every word of the report sheet, from `apple/Supermessage/Panels/ReportSheet.swift`
 * (`:171-228`). Apart from the composables so a JVM test can pin each string.
 */
internal object ReportCopy {
    /** The counter appears only this close to the limit (`ReportSheet.swift:60-61`). */
    const val COUNTER_THRESHOLD = 200L

    const val REASON_HEADER = "Reason"
    const val REASON_PLACEHOLDER = "What's wrong? (optional)"
    const val REASON_LABEL = "Reason for the report"
    const val REASON_HINT = "Optional. Seen only by the homeserver's administrators."
    const val SEND_HINT = "Asks before sending"
    const val SENT_TITLE = "Report sent"

    fun title(subject: ReportSubject): String = when (subject) {
        is ReportSubject.Message -> "Report message"
        is ReportSubject.Room -> "Report room"
        is ReportSubject.User -> "Report"
    }

    fun subjectName(subject: ReportSubject): String = when (subject) {
        is ReportSubject.Message -> subject.senderName
        is ReportSubject.Room -> subject.name
        is ReportSubject.User -> subject.name
    }

    /** Who receives the report, and, first, who does not. */
    fun explanation(subject: ReportSubject): String = when (subject) {
        is ReportSubject.Message ->
            "Your report goes to your homeserver's administrators, not to ${subject.senderName}. " +
                "The message is hidden from you once the report is sent."
        is ReportSubject.Room ->
            "Your report about ${subject.name} goes to your homeserver's administrators, " +
                "not to the people in it."
        is ReportSubject.User ->
            "Your report goes to your homeserver's administrators, not to ${subject.name}." +
                if (subject.isAgent) " Reporting an agent doesn't stop it running." else ""
    }

    fun confirmationTitle(subject: ReportSubject): String = when (subject) {
        is ReportSubject.Message -> "Report this message?"
        is ReportSubject.Room -> "Report ${subject.name}?"
        is ReportSubject.User -> "Report ${subject.name}?"
    }

    fun confirmationMessage(subject: ReportSubject): String =
        "The homeserver's administrators will see it. ${subjectName(subject)} won't be told."

    fun sendLabel(subject: ReportSubject, alsoBlock: Boolean): String =
        if (alsoBlock && subject.blockable != null) "Report and block" else "Report"

    fun blockConsequence(isAgent: Boolean): String =
        if (isAgent) {
            "Its messages are hidden everywhere, on all your devices. The agent keeps running and isn't told."
        } else {
            "You won't see their messages in any room, on any of your devices. They aren't told."
        }

    fun alreadyBlocked(name: String): String = "$name is blocked"

    fun alsoBlock(name: String): String = "Also block $name"

    /** The counter's text and whether it is over the limit; `null` while there is room to spare. */
    fun counter(remaining: Long): Pair<String, Boolean>? = when {
        remaining >= COUNTER_THRESHOLD -> null
        remaining < 0 -> "${-remaining} characters over the limit" to true
        else -> "$remaining characters left" to false
    }
}

/**
 * Report a message, a room or a person — `ReportSheet.swift`.
 *
 * Sending asks first, and the answer is the session's: `null` means the
 * report landed (success haptic, the sheet closes); a string is the refusal
 * and the reason stays where it was typed. While a report is being sent the
 * sheet cannot be dismissed.
 *
 * A report that landed is never offered again: when [onSend] says the report
 * went but blocking did not ([Session.REPORTED_BUT_PREFIX]), the sheet turns
 * into "Report sent" with the block failure and a single Done.
 *
 * A fully expanded `ModalBottomSheet` (open question Q13), with the reason
 * field above the keyboard.
 *
 * @param remaining how many characters the reason may still take. Only ever
 *   the core's `reportReasonRemaining`; a count of `String.length` would be
 *   wrong for the emoji and combining marks the core measures differently.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportSheet(
    subject: ReportSubject,
    isBlocked: (String) -> Boolean,
    onSend: suspend (reason: String, alsoBlock: Boolean) -> String?,
    onClose: () -> Unit,
    remaining: (String) -> Long = ::reportReasonRemaining,
) {
    // Read here, outside the sheet's window: a dialog window provides its own
    // platform haptics, which would hide the caller's from everything inside.
    val haptics = LocalHapticFeedback.current
    var sending by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target -> target != SheetValue.Hidden || !sending },
    )
    ModalBottomSheet(
        onDismissRequest = { if (!sending) onClose() },
        sheetState = sheetState,
        modifier = Modifier.testTag("report-sheet"),
    ) {
        ReportSheetBody(
            subject = subject,
            isBlocked = isBlocked,
            onSend = onSend,
            onClose = onClose,
            remaining = remaining,
            haptics = haptics,
            sending = sending,
            onSending = { sending = it },
        )
    }
}

/** The sheet's contents, apart from the sheet chrome, so a preview can draw them. */
@Composable
internal fun ReportSheetBody(
    subject: ReportSubject,
    isBlocked: (String) -> Boolean,
    onSend: suspend (reason: String, alsoBlock: Boolean) -> String?,
    onClose: () -> Unit,
    remaining: (String) -> Long,
    haptics: HapticFeedback = LocalHapticFeedback.current,
    sending: Boolean = false,
    onSending: (Boolean) -> Unit = {},
    initialReason: String = "",
    initialFailure: String? = null,
    confirming: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    var reason by remember(subject.id) { mutableStateOf(initialReason) }
    var alsoBlock by remember(subject.id) { mutableStateOf(true) }
    var asking by remember(subject.id) { mutableStateOf(confirming) }
    var failure by remember(subject.id) { mutableStateOf(initialFailure) }

    val target = subject.blockable
    val alreadyBlocked = target != null && isBlocked(target.userId)
    val blocking = alsoBlock && target != null && !alreadyBlocked
    val left = remaining(reason)
    val over = left < 0
    // The report went; only the block did not. Nothing is offered but Done.
    val landed = failure?.startsWith(Session.REPORTED_BUT_PREFIX) == true
    val locked = sending || landed

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            // Escape closes it, as the scrim and TalkBack's dismiss do; not
            // while a report is on its way.
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Escape && !sending) {
                    if (event.type == KeyEventType.KeyUp) onClose()
                    true
                } else {
                    false
                }
            },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (landed) {
                // Spacer of the same width keeps the title centred.
                Text("", modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 16.dp))
            } else {
                TextButton(
                    onClick = onClose,
                    enabled = !sending,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("report-cancel"),
                ) { Text("Cancel") }
            }
            Text(
                if (landed) ReportCopy.SENT_TITLE else ReportCopy.title(subject),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            when {
                landed -> TextButton(
                    onClick = onClose,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("report-done"),
                ) { Text("Done") }
                sending -> CircularProgressIndicator(
                    modifier = Modifier.padding(horizontal = 14.dp).size(20.dp).testTag("report-sending"),
                    strokeWidth = 2.dp,
                )
                else -> Text(
                    "Send",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (over) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clickable(
                            enabled = !over,
                            onClickLabel = ReportCopy.SEND_HINT,
                            role = Role.Button,
                        ) { asking = true }
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                        .testTag("report-send"),
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!landed) {
                Text(
                    ReportCopy.explanation(subject),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("report-explanation"),
                )
                Text(
                    ReportCopy.REASON_HEADER,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() },
                )
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    enabled = !locked,
                    minLines = 4,
                    maxLines = 10,
                    placeholder = { Text(ReportCopy.REASON_PLACEHOLDER) },
                    supportingText = { Text(ReportCopy.REASON_HINT) },
                    isError = over,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("report-reason")
                        .semantics { contentDescription = ReportCopy.REASON_LABEL },
                )
                ReportCopy.counter(left)?.let { (text, isOver) ->
                    Text(
                        text,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isOver) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("report-counter"),
                    )
                }
                if (target != null) {
                    if (alreadyBlocked) {
                        Text(
                            ReportCopy.alreadyBlocked(target.name),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("report-already-blocked"),
                        )
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .toggleable(
                                    value = alsoBlock,
                                    enabled = !locked,
                                    role = Role.Switch,
                                    onValueChange = { alsoBlock = it },
                                )
                                .testTag("report-also-block"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                ReportCopy.alsoBlock(target.name),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(checked = alsoBlock, onCheckedChange = null, enabled = !locked)
                        }
                    }
                    Text(
                        ReportCopy.blockConsequence(target.isAgent),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("report-block-footer"),
                    )
                }
            }
            failure?.let { text ->
                Text(
                    if (landed) text.removePrefix(Session.REPORTED_BUT_LEAD).replaceFirstChar { it.uppercase() } else text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .testTag("report-failure")
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }

    if (asking) {
        AlertDialog(
            modifier = Modifier.testTag("report-confirm"),
            onDismissRequest = { asking = false },
            title = { Text(ReportCopy.confirmationTitle(subject)) },
            text = { Text(ReportCopy.confirmationMessage(subject)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        asking = false
                        failure = null
                        scope.launch {
                            onSending(true)
                            val refusal = try {
                                onSend(reason, blocking)
                            } finally {
                                onSending(false)
                            }
                            if (refusal == null) {
                                haptics.play(Haptic.Success)
                                onClose()
                            } else {
                                failure = refusal
                                haptics.play(Haptic.Failure)
                            }
                        }
                    },
                    modifier = Modifier.testTag("report-confirm-send"),
                ) { Text(ReportCopy.sendLabel(subject, blocking), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { asking = false }, modifier = Modifier.testTag("report-confirm-cancel")) {
                    Text("Cancel")
                }
            },
        )
    }
}
