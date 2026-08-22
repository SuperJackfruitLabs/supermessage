package dev.supermessage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.supermessage.kit.stores.EditTarget
import dev.supermessage.kit.stores.ReplyTarget

/**
 * Where a message is written, the shape iOS draws at
 * `apple/Supermessage/Composer/ComposerView.swift`: a text field and a send
 * control next to it, gated on whether there is anything worth sending and
 * on whether a send is already in flight.
 *
 * ## Per-room drafts are not this composable's job
 *
 * [text] arrives already resolved and [onTextChange] is the whole way this
 * composable reports a change — routing that to [dev.supermessage.kit.stores.DraftStore]
 * (keying by room, clearing on send) is Task 6's wiring, the same way
 * `ComposerView.swift` reads and writes `session.drafts` itself rather than
 * this file reaching for a store directly.
 *
 * ## `canSend` is non-blank, not non-empty
 *
 * `Session.send` trims (`val body = text.trim()`) before deciding there is
 * anything to send, so enabling the control on whitespace alone would offer
 * a tap that does nothing — the fault
 * [dev.supermessage.ComposerTest.whitespaceAloneDoesNotEnableSend] exists to
 * catch, confirmed by the mandatory mutation (see that file, and this
 * task's report).
 *
 * ## `sending` is a view concern, not a `Session` one
 *
 * Exactly the shape `LoginScreen`'s `busy` already established: the double-
 * tap guard belongs to whichever caller is driving a send, not to `Session`,
 * because it guards *this form*, not something true about the session
 * itself. [sending] is threaded straight into `enabled` alongside `canSend`
 * rather than tracked internally, so a caller in the middle of a suspend
 * `send()` call can hold this composer disabled for exactly as long as that
 * call is in flight.
 *
 * ## Reply and edit banners
 *
 * [replyTo]/[onCancelReply] and [editing]/[onCancelEdit] are accepted here
 * so Task 3 extends this signature rather than reshaping it, but nothing is
 * drawn for them yet — [ReplyEditBanner] is a deliberate no-op placeholder.
 * [editing] does change the placeholder text and the send button's label,
 * because those two are display-only and cost nothing to get right now;
 * rendering the banner itself is left to Task 3.
 */
@Composable
fun Composer(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    sending: Boolean = false,
    failure: String? = null,
    replyTo: ReplyTarget.Pending? = null,
    onCancelReply: () -> Unit = {},
    editing: EditTarget.Pending? = null,
    onCancelEdit: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val canSend = text.isNotBlank()

    Column(modifier.fillMaxWidth()) {
        ReplyEditBanner(replyTo = replyTo, onCancelReply = onCancelReply, editing = editing, onCancelEdit = onCancelEdit)

        // Only when there is one to show — the placeholder this composable
        // relies on staying null (rather than an empty string) when nothing
        // has gone wrong. Mirrors LoginScreen's own failure line.
        if (failure != null) {
            Text(
                failure,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .testTag("composer-failure"),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                placeholder = { Text(if (editing == null) "Message" else "Edit message") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                modifier = Modifier
                    .weight(1f)
                    .testTag("composer-text"),
            )

            Button(
                onClick = onSend,
                // The double-tap guard: a send already in flight cannot be
                // started twice, mirroring LoginScreen's `!busy` clause.
                enabled = canSend && !sending,
                modifier = Modifier.testTag("composer-send"),
            ) {
                if (sending) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                } else {
                    // A tick's-worth of a word rather than an arrow while
                    // editing: nothing is being sent to anyone, an existing
                    // message is being replaced. Mirrors ComposerView.swift's
                    // checkmark-vs-arrow choice, in Material's vocabulary
                    // (a labeled button) rather than SF Symbols'.
                    Text(if (editing == null) "Send" else "Save")
                }
            }
        }
    }
}

/**
 * A deliberate no-op. Task 3 replaces this with the edit strip and reply
 * strip `ComposerView.swift` draws above the text field — see that file's
 * `EditStrip` and `ReplyStrip`. Parameters are already threaded through
 * [Composer] so that task extends this function's body rather than
 * reshaping [Composer]'s signature.
 */
@Composable
private fun ReplyEditBanner(
    replyTo: ReplyTarget.Pending?,
    onCancelReply: () -> Unit,
    editing: EditTarget.Pending?,
    onCancelEdit: () -> Unit,
) {
    // Nothing drawn yet. `replyTo`/`onCancelReply`/`editing`/`onCancelEdit`
    // are unused here on purpose — see this function's KDoc.
}
