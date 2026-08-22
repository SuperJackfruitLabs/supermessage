package dev.supermessage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.supermessage.kit.stores.EditTarget
import dev.supermessage.kit.stores.ReplyTarget
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.supermessage_ffi.StagedFile

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
 * [replyTo]/[onCancelReply] render as [ReplyEditBanner]'s reply strip: who
 * the reply is addressed to, the excerpt when the core supplied one, and a
 * cancel action — mirrors `ComposerView.swift`'s `ReplyStrip`. [editing]
 * takes precedence when both are non-null, same as the Swift view's
 * `if pendingEdit != nil { EditStrip } else if let pendingReply { ReplyStrip }`;
 * the two are never shown together.
 *
 * ## An edit replaces the text, and cancelling has to put back what was there
 *
 * `EditTarget.Pending.body` is a snapshot of the message as it stood when the
 * edit began — exactly what the composer should show while rewriting it. But
 * the reader may have had something of their own half-typed at that moment,
 * and cancelling the edit must not lose it: that would be a normal person's
 * data, silently discarded, not a UI nit.
 *
 * This composable does not touch [dev.supermessage.kit.stores.DraftStore] —
 * per this file's own "per-room drafts are not this composable's job" note,
 * that store only exists once Task 6 wires it in. So the pre-edit text is
 * held right here, in [remember]ed state scoped to the current edit: a
 * [LaunchedEffect] keyed on [editing] captures whatever [text] held the
 * moment an edit starts, before overwriting it with the snapshot body, and
 * the cancel action plays that capture back through [onTextChange] before
 * forgetting it and calling [onCancelEdit]. Restoring through the same
 * [onTextChange] channel the reader's own typing goes through means that once
 * Task 6 does route it to `DraftStore.set`, the restore reasserts the value
 * already sitting there rather than fighting it — an idempotent no-op, the
 * same shape `ComposerView.swift`'s own `text = session.drafts.draft(for:
 * roomId)` gets from its `.onChange(of: text)` guard. Nothing beyond this one
 * edit's round trip is kept — no draft store of this composable's own is
 * being smuggled in.
 *
 * ## Attachments — the one place iOS is not the reference
 *
 * `ComposerView.swift` reaches for `PhotosPickerItem`/`fileImporter`, both
 * SwiftUI presentations with no Android counterpart worth imitating. Android's
 * own contract is [androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia],
 * launched from [rememberLauncherForActivityResult] right here — a platform
 * API, not something [dev.supermessage.kit.stores.StagedAttachment] could be
 * asked to know about.
 *
 * That launcher hands back a content [Uri], and
 * [dev.supermessage.kit.stores.StagedAttachment.stage] takes a `path`, not a
 * `Uri` — so [resolvePickedUri] copies the picked content into this app's
 * cache directory and returns the resulting file's absolute path. That
 * resolution happens *here*, in `:app`, rather than in `:kit` (which holds no
 * Android types by design — see that module's build file) or in the core
 * (which only ever reads a path it is handed, per `attachment_stage_path`'s
 * own doc comment). The copy is named after the picker's own
 * `OpenableColumns.DISPLAY_NAME` when one is available, which is why a staged
 * photo shows a real name rather than a generated one — an improvement this
 * task is free to make precisely because this corner has no iOS behaviour to
 * match.
 *
 * [onAttach] is fired with that resolved path once the copy finishes,
 * exactly the fire-and-forget shape [onSend]/[onCancelReply]/[onCancelEdit]
 * already use: this composable resolves a platform value into a plain one
 * and hands it up, it does not itself call
 * [dev.supermessage.kit.stores.StagedAttachment.stage] — that call is made by
 * whoever wires this callback to the real store (Task 6), the same
 * boundary [text]/[onTextChange] already draws around `DraftStore`.
 * [attachment] is the mirror of [replyTo]/[editing]: a plain snapshot handed
 * in from outside, displayed by name in [AttachmentChip], with
 * [onDiscardAttachment] as its cancel action. Actually sending a staged
 * attachment — calling
 * [dev.supermessage.kit.stores.StagedAttachment.send] — is no part of this
 * file; see this task's own report for why that is Task 6's wiring rather
 * than this one's.
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
    attachment: StagedFile? = null,
    onAttach: (String) -> Unit = {},
    onDiscardAttachment: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val canSend = text.isNotBlank()

    // The draft that was sitting in the field the moment this edit began —
    // see this function's KDoc for why it lives here rather than in a store.
    var priorDraft by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(editing) {
        val current = editing ?: return@LaunchedEffect
        priorDraft = text
        onTextChange(current.body)
    }

    // Resolves a picked content Uri to a path and hands it to [onAttach] —
    // see this function's "Attachments" KDoc section for why that resolution
    // lives here rather than in :kit or the core.
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pickMedia = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val path = withContext(Dispatchers.IO) { resolvePickedUri(context, uri) }
            if (path != null) onAttach(path)
        }
    }

    Column(modifier.fillMaxWidth()) {
        ReplyEditBanner(
            replyTo = replyTo,
            onCancelReply = onCancelReply,
            editing = editing,
            onCancelEdit = {
                onTextChange(priorDraft ?: "")
                priorDraft = null
                onCancelEdit()
            },
        )

        // Below the reply/edit banner, above the failure line — the same
        // slot `ComposerView.swift`'s AttachmentChip occupies.
        if (attachment != null) {
            AttachmentChip(staged = attachment, onDiscard = onDiscardAttachment)
        }

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
            TextButton(
                onClick = {
                    pickMedia.launch(
                        PickVisualMediaRequest(mediaType = ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                    )
                },
                modifier = Modifier.testTag("composer-attach"),
            ) {
                Text("Attach")
            }

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
 * The edit strip and reply strip `ComposerView.swift` draws above its text
 * field — see that file's `EditStrip` and `ReplyStrip`. An edit in progress
 * takes precedence over a reply, the same as that file's own
 * `if pendingEdit != nil { EditStrip } else if let pendingReply { ReplyStrip }`;
 * the two never show together, and drawing nothing at all is correct when
 * neither is set.
 *
 * Display plus cancel only, per this task's scope — nothing here sends, and
 * [onCancelEdit] arrives already wrapped by [Composer] with the draft-restore
 * behaviour documented on that function.
 */
@Composable
private fun ReplyEditBanner(
    replyTo: ReplyTarget.Pending?,
    onCancelReply: () -> Unit,
    editing: EditTarget.Pending?,
    onCancelEdit: () -> Unit,
) {
    if (editing != null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .testTag("composer-edit-banner"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Editing message",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            TextButton(onClick = onCancelEdit, modifier = Modifier.testTag("composer-cancel-edit")) {
                Text("Cancel")
            }
        }
    } else if (replyTo != null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .testTag("composer-reply-banner"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    "Replying to ${replyTo.sender}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("composer-reply-sender"),
                )
                // Only when the core supplied one — an absent excerpt (a
                // redacted or unloaded parent, say) draws the sender line
                // alone rather than an empty second line under it.
                val excerpt = replyTo.excerpt
                if (excerpt != null) {
                    Text(
                        excerpt,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.testTag("composer-reply-excerpt"),
                    )
                }
            }
            TextButton(onClick = onCancelReply, modifier = Modifier.testTag("composer-cancel-reply")) {
                Text("Cancel")
            }
        }
    }
}

/**
 * The one file waiting to be sent, shown by name — the mirror of
 * `ComposerView.swift`'s `AttachmentChip`. Display plus discard only, per
 * this task's scope: nothing here calls
 * [dev.supermessage.kit.stores.StagedAttachment.send], and [onDiscard]
 * arrives as a plain callback the same way [onCancelReply]/[onCancelEdit] do
 * — [Composer] does not reach for the store itself, so neither does this.
 */
@Composable
private fun AttachmentChip(staged: StagedFile, onDiscard: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag("composer-attachment-chip"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            staged.filename,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            modifier = Modifier.testTag("composer-attachment-name"),
        )
        TextButton(onClick = onDiscard, modifier = Modifier.testTag("composer-discard-attachment")) {
            Text("Remove")
        }
    }
}

/**
 * Copies a content [Uri] the photo picker handed back into this app's cache
 * directory, and returns the resulting file's absolute path — what
 * [dev.supermessage.kit.stores.StagedAttachment.stage] needs, since it reads
 * a path rather than a `Uri` (see `attachment_stage_path`'s own doc comment:
 * the core only ever opens a path it is given). Runs on the caller's
 * dispatcher, so [Composer] hops to [kotlinx.coroutines.Dispatchers.IO]
 * before calling this — copying bytes is not free-of-cost on the main
 * thread.
 *
 * Named after the picker's own `OpenableColumns.DISPLAY_NAME` when the
 * resolver can supply one, falling back to the `Uri`'s last path segment and
 * then a generated name — real content usually has one, but nothing here
 * assumes it does. `attachment_stage_path` derives `StagedFile.filename` from
 * this copy's own name (`path.file_name()`), which is exactly why a
 * meaningful name is worth resolving here rather than leaving the copy as an
 * opaque generated one.
 *
 * `null` on any failure to open or read the source — a picker result that
 * cannot be read is nothing to stage, not a crash.
 */
private fun resolvePickedUri(context: Context, uri: Uri): String? {
    val displayName = queryDisplayName(context, uri)
        ?: uri.lastPathSegment
        ?: UUID.randomUUID().toString()
    val destination = File(context.cacheDir, displayName)
    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        destination.absolutePath
    } catch (error: java.io.IOException) {
        null
    }
}

/** The display name a content resolver offers for [uri], or `null` if it has none. */
private fun queryDisplayName(context: Context, uri: Uri): String? {
    val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
    return context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (column < 0 || !cursor.moveToFirst()) null else cursor.getString(column)
    }
}
