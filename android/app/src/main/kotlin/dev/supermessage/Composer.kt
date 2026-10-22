package dev.supermessage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.supermessage.kit.stores.EditTarget
import dev.supermessage.kit.stores.ReplyTarget
import dev.supermessage.kit.stores.StagedAttachment
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.supermessage_ffi.StagedFile

/**
 * What the chip draws: the staged file, where it came from (for the
 * thumbnail), and why it could not go. Any of the three may be absent; a
 * failure with no file is a chip that only says why (iOS `AttachmentChip`).
 */
data class ChipState(
    val file: StagedFile?,
    val path: String?,
    val failure: StagedAttachment.Failure?,
) {
    /** A failed chip with nothing behind it holds the text back too (iOS `blocksSend`). */
    val blocksSend: Boolean get() = file == null && failure != null
}

/**
 * Where a message is written, the shape iOS draws at
 * `apple/Supermessage/Composer/ComposerView.swift`: one raised capsule that
 * holds the edit or reply strip, the attachment chip and the field row ("+",
 * the field, the send control), with the failure line under it and a fade
 * from the page behind it.
 *
 * ## Per-room drafts are not this composable's job
 *
 * [text] arrives already resolved and [onTextChange] is the whole way this
 * composable reports a change; keying drafts by room is the caller's.
 *
 * ## `canSend`
 *
 * Editing: there must be text. Otherwise a chip that [ChipState.blocksSend]
 * holds everything back, and else text or a present chip sends. Text is
 * trimmed (`Session.send` trims), so whitespace alone enables nothing. [sending]
 * is the caller's double-tap guard, threaded into `enabled`.
 *
 * ## An edit replaces the text, and cancelling puts back what was there
 *
 * Starting an edit stashes whatever was in the field and shows the message's
 * body; cancelling replays the stash through [onTextChange]. Nothing beyond
 * that one round trip is kept here.
 *
 * ## Clearing the keyboard
 *
 * [MainActivity] draws edge to edge, so the composer's own [imePadding] is
 * what keeps it above the keyboard.
 *
 * ## Attaching
 *
 * "+" offers Photo (the system photo picker) and File (any document). Both
 * hand back a content [Uri], copied into the cache by [copyPickedUri] because
 * the core stages a path. A copy that cannot be read is [onRefuse]d with the
 * iOS wording, so the chip says so instead of nothing happening. The "+"
 * stays while editing (iOS keeps it).
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
    attachment: ChipState? = null,
    onAttach: (path: String) -> Unit = {},
    onRefuse: (filename: String, message: String) -> Unit = { _, _ -> },
    onDiscardAttachment: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val canSend = when {
        editing != null -> text.isNotBlank()
        attachment?.blocksSend == true -> false
        else -> text.isNotBlank() || attachment != null
    }

    // The draft that was sitting in the field the moment this edit began.
    var priorDraft by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(editing) {
        val current = editing ?: return@LaunchedEffect
        priorDraft = text
        onTextChange(current.body)
    }

    val colors = SupermessageTheme.colors
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .fillMaxWidth()
            // A fade from the page into the composer's strip rather than a
            // bar: the capsule floats and the conversation shows behind it.
            .background(
                Brush.verticalGradient(
                    0f to colors.surface.copy(alpha = 0f),
                    0.5f to colors.surface,
                    1f to colors.surface,
                ),
            )
            .imePadding()
            .padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 4.dp,
                    shape = shape,
                    ambientColor = colors.scrim.copy(alpha = 0.1f),
                    spotColor = colors.scrim.copy(alpha = 0.1f),
                )
                .background(colors.surfaceRaised, shape)
                .border(1.dp, colors.border, shape)
                .testTag("composer-capsule"),
        ) {
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
            if (attachment != null) {
                AttachmentChip(
                    state = attachment,
                    onDiscard = onDiscardAttachment,
                    modifier = Modifier.padding(horizontal = 6.dp).padding(top = 6.dp),
                )
            }
            FieldRow(
                text = text,
                onTextChange = onTextChange,
                editing = editing != null,
                canSend = canSend,
                sending = sending,
                onSend = onSend,
                onAttach = onAttach,
                onRefuse = onRefuse,
            )
        }

        // Below the capsule, in danger (iOS `ComposerView.swift:87-93`).
        if (failure != null) {
            Text(
                failure,
                style = MaterialTheme.typography.labelMedium,
                color = colors.danger,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .testTag("composer-failure"),
            )
        }
    }
}

@Composable
private fun FieldRow(
    text: String,
    onTextChange: (String) -> Unit,
    editing: Boolean,
    canSend: Boolean,
    sending: Boolean,
    onSend: () -> Unit,
    onAttach: (path: String) -> Unit,
    onRefuse: (filename: String, message: String) -> Unit,
) {
    val colors = SupermessageTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        AttachMenu(onAttach = onAttach, onRefuse = onRefuse)

        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.content),
            cursorBrush = SolidColor(colors.accent),
            minLines = 1,
            maxLines = 6,
            keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Default),
            decorationBox = { inner ->
                Box(Modifier.padding(vertical = 11.dp), contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) {
                        Text(
                            if (editing) "Edit message" else "Message",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.contentFaint,
                        )
                    }
                    inner()
                }
            },
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp).testTag("composer-text"),
        )

        IconButton(
            onClick = onSend,
            // The double-tap guard: a send already in flight cannot be
            // started twice.
            enabled = canSend && !sending,
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = colors.accentContent,
                disabledContentColor = colors.accentContent,
            ),
            modifier = Modifier.size(44.dp).testTag("composer-send").semantics {
                contentDescription = if (editing) "Save edit" else "Send"
            },
        ) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (canSend) colors.accent else colors.contentFaint),
                contentAlignment = Alignment.Center,
            ) {
                if (sending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = colors.accentContent,
                    )
                } else {
                    // A tick rather than an arrow while editing: nothing is
                    // being sent to anyone, a message is being replaced.
                    Icon(
                        if (editing) Icons.Filled.Check else Icons.AutoMirrored.Filled.Send,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/** "+", with Photo and File (iOS `attachMenu`). */
@Composable
private fun AttachMenu(onAttach: (path: String) -> Unit, onRefuse: (filename: String, message: String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }

    // Resolves a picked content Uri to a cache path off the main thread and
    // hands it up; a copy that fails is a refusal the chip will say.
    fun picked(uri: Uri?, couldNotRead: String) {
        if (uri == null) return
        scope.launch {
            val (name, path) = withContext(Dispatchers.IO) {
                val name = displayNameOf(context, uri)
                name to copyPickedUri(context, uri, name)
            }
            if (path != null) onAttach(path) else onRefuse(name, couldNotRead)
        }
    }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
        picked(it, "Couldn't read that photo.")
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        picked(it, "Couldn't read that file.")
    }

    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(44.dp).testTag("composer-attach")) {
            Icon(
                Icons.Filled.Add,
                contentDescription = "Attach",
                tint = SupermessageTheme.colors.contentMuted,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Photo") },
                onClick = {
                    open = false
                    pickPhoto.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                    )
                },
                modifier = Modifier.testTag("composer-attach-photo"),
            )
            DropdownMenuItem(
                text = { Text("File") },
                onClick = {
                    open = false
                    pickFile.launch(arrayOf("*/*"))
                },
                modifier = Modifier.testTag("composer-attach-file"),
            )
        }
    }
}

/**
 * The edit strip and reply strip `ComposerView.swift` draws at the top of the
 * capsule (`EditStrip`, `ReplyStrip`). An edit takes precedence over a reply;
 * nothing is drawn for neither. Each has a 44dp "Cancel".
 */
@Composable
private fun ReplyEditBanner(
    replyTo: ReplyTarget.Pending?,
    onCancelReply: () -> Unit,
    editing: EditTarget.Pending?,
    onCancelEdit: () -> Unit,
) {
    val colors = SupermessageTheme.colors
    if (editing != null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 2.dp)
                .testTag("composer-edit-banner"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Filled.Edit,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(14.dp),
            )
            Text(
                "Editing message",
                style = MaterialTheme.typography.labelMedium,
                color = colors.content,
                modifier = Modifier.weight(1f),
            )
            CancelButton(onClick = onCancelEdit, tag = "composer-cancel-edit")
        }
    } else if (replyTo != null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 2.dp, top = 4.dp)
                .testTag("composer-reply-banner"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(width = 2.dp, height = 28.dp).background(colors.accent))
            // weight(1f) keeps the cancel button from being starved by a long
            // sender name: the unweighted button is measured first.
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Replying to ${replyTo.sender}",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("composer-reply-sender"),
                )
                // Only when the core supplied one.
                val excerpt = replyTo.excerpt
                if (excerpt != null) {
                    Text(
                        excerpt,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.contentMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("composer-reply-excerpt"),
                    )
                }
            }
            CancelButton(onClick = onCancelReply, tag = "composer-cancel-reply")
        }
    }
}

@Composable
private fun CancelButton(onClick: () -> Unit, tag: String) {
    IconButton(onClick = onClick, modifier = Modifier.size(44.dp).testTag(tag)) {
        Icon(
            Icons.Filled.Close,
            contentDescription = "Cancel",
            tint = SupermessageTheme.colors.contentMuted,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * A file waiting to be sent, or one that could not be and says why (iOS
 * `AttachmentChip`): a 44dp picture or kind badge, the name in the middle
 * truncated, then the reason in danger (up to three lines) or the size, and
 * "Remove attachment". A failure draws a danger border.
 */
@Composable
private fun AttachmentChip(state: ChipState, onDiscard: () -> Unit, modifier: Modifier = Modifier) {
    val colors = SupermessageTheme.colors
    val file = state.file
    val failure = state.failure
    val shape = RoundedCornerShape(16.dp)
    val filename = file?.filename ?: failure?.filename ?: "Attachment"
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surfaceSunken, shape)
            .border(1.dp, if (failure == null) colors.border else colors.danger, shape)
            .padding(start = 6.dp, top = 6.dp, bottom = 6.dp)
            .testTag("composer-attachment-chip"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(colors.surfaceRaised),
            contentAlignment = Alignment.Center,
        ) {
            if (failure != null) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = colors.danger,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                KindBadge(file?.mime)
            }
            if (file != null && file.mime.startsWith("image/") && state.path != null) {
                Thumbnail(state.path, size = 44.dp, tag = "composer-chip-thumbnail", modifier = Modifier.size(44.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                filename,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = colors.content,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.testTag("composer-attachment-name"),
            )
            if (failure != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = colors.danger,
                        modifier = Modifier.size(14.dp).padding(top = 2.dp),
                    )
                    Text(
                        failure.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.danger,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("composer-chip-failure"),
                    )
                }
            } else if (file != null) {
                Text(
                    Formatter.formatShortFileSize(LocalContext.current, file.sizeBytes.toLong()),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.contentMuted,
                )
            }
        }
        IconButton(onClick = onDiscard, modifier = Modifier.size(44.dp).testTag("composer-discard-attachment")) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Remove attachment",
                tint = colors.contentMuted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * What kind of thing a staged file is, where there is no picture of it. A word
 * rather than iOS's SF Symbols: `material-icons-core` has no audio, film or
 * document glyph, and the extended set is deliberately not a dependency.
 */
@Composable
private fun KindBadge(mime: String?) {
    val word = when {
        mime == null -> return
        mime.startsWith("audio/") -> "AUDIO"
        mime.startsWith("video/") -> "VIDEO"
        mime.startsWith("image/") -> "IMAGE"
        else -> "FILE"
    }
    Text(
        word,
        style = MaterialTheme.typography.labelSmall,
        color = SupermessageTheme.colors.contentMuted,
        maxLines = 1,
    )
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
private fun copyPickedUri(context: Context, uri: Uri, displayName: String): String? {
    val destination = File(context.cacheDir, displayName)
    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        destination.absolutePath
    } catch (error: java.io.IOException) {
        null
    } catch (error: SecurityException) {
        null
    }
}

/**
 * The name to show and to copy under: the resolver's own, else the last path
 * segment, else a generated one. Only the final component, so a name with a
 * slash cannot place the copy outside the cache directory.
 */
private fun displayNameOf(context: Context, uri: Uri): String {
    val raw = try {
        queryDisplayName(context, uri)
    } catch (error: Exception) {
        null
    } ?: uri.lastPathSegment ?: UUID.randomUUID().toString()
    return File(raw).name.ifEmpty { UUID.randomUUID().toString() }
}

/** The display name a content resolver offers for [uri], or `null` if it has none. */
private fun queryDisplayName(context: Context, uri: Uri): String? {
    val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
    return context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (column < 0 || !cursor.moveToFirst()) null else cursor.getString(column)
    }
}
