package dev.supermessage

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.supermessage.kit.word
import uniffi.supermessage_core.AgentState
import uniffi.supermessage_core.RoomAffordance
import uniffi.supermessage_core.RoomRow

/**
 * One roster row, laid out the way
 * `apple/Supermessage/Rooms/RoomRowView.swift` describes it.
 *
 * Everything on it was decided by the core — the sigil and name come from
 * `row.identity`, the preview line from `row.preview`. This composable
 * parses nothing and composes nothing; it lays out what it was handed.
 *
 * The avatar is its own tap target: tapping it asks *about the room*
 * ([onOpenInfo]), tapping anywhere else opens the conversation — a callback
 * this composable does not itself expose, because that behaviour belongs to
 * whoever places this row in a list (Task 6), exactly as the Swift original
 * relies on the enclosing `List` row for it rather than handling it here.
 */
@Composable
fun RoomRow(
    row: RoomRow,
    avatarUri: String?,
    state: AgentState,
    `when`: String,
    showsState: Boolean = true,
    hidesHost: Boolean = false,
    onOpenInfo: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(row = row, avatarUri = avatarUri, onOpenInfo = onOpenInfo)

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    row.identity.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (row.affordance == RoomAffordance.RESPOND_TO_INVITATION) {
                    InvitationBadge()
                }
                Spacer(Modifier.weight(1f))
                if (`when`.isNotEmpty()) {
                    Text(
                        `when`,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                if (row.room.unread > 0uL) {
                    UnreadBadge(row.room.unread)
                }
            }

            // State, harness and host on one quiet line — metadata *about*
            // the room, kept off the preview's line below so the two never
            // compete. `null` collapses the line entirely rather than
            // drawing an empty one, the same posture as the preview.
            metaLine(row = row, state = state, showsState = showsState, hidesHost = hidesHost)?.let { meta ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    if (showsState) {
                        StateDot(state)
                    }
                    Text(
                        meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            row.preview?.let { preview ->
                Text(
                    preview.text,
                    style = MaterialTheme.typography.bodyMedium,
                    // The row's amber switch, and the only place this
                    // composable may use it: true means only ever "the
                    // operator owes someone an answer", never a severity.
                    color = if (preview.pending) PendingAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * State and runtime, joined only where both exist.
 *
 * `null` collapses the line entirely rather than drawing an empty row.
 */
private fun metaLine(row: RoomRow, state: AgentState, showsState: Boolean, hidesHost: Boolean): String? {
    val parts = mutableListOf<String>()
    if (showsState) parts += state.word
    val runtime = row.room.runtime
    if (runtime != null) {
        parts += runtime.harness
        // The host is the section header in the machine view, so repeating
        // it on every row there would be saying it twice.
        if (!hidesHost) parts += runtime.host
    } else {
        row.identity.role?.let { parts += it }
    }
    return if (parts.isEmpty()) null else parts.joinToString(" · ")
}

/**
 * The avatar, or the initial the core derived from the *parsed* name.
 *
 * Never the raw name's first character: for a structured room that is the
 * glyph, and taking it directly is the bug `core::room_identity` exists to
 * have fixed once.
 */
@Composable
private fun Avatar(row: RoomRow, avatarUri: String?, onOpenInfo: (() -> Unit)?) {
    val bitmap = remember(avatarUri) { avatarUri?.decodeDataUri() }

    var modifier = Modifier
        .size(34.dp)
        .testTag("avatar")
    if (onOpenInfo != null) {
        modifier = modifier.clickable(
            onClickLabel = "About ${row.identity.name}",
            onClick = onOpenInfo,
        )
    }

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(row.identity.initial, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun InvitationBadge() {
    Text(
        "Invitation",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .testTag("invitation-badge")
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/**
 * How many messages a room has that the reader has not seen.
 *
 * Never amber — an unread count is something waiting, not something owed.
 */
@Composable
private fun UnreadBadge(count: ULong) {
    Text(
        if (count > 99uL) "99+" else count.toString(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier
            .testTag("unread-badge")
            // Without this a screen reader announces a bare number, which in a
            // list of rooms is indistinguishable from a timestamp or a count of
            // anything else. iOS says "N unread" here; the port dropped it.
            // The clamped label deliberately reads the true count rather than
            // "99+", because "more than 99 unread" is the useful fact and the
            // clamp exists only to bound the badge's width.
            .semantics { contentDescription = "$count unread" }
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
private fun StateDot(state: AgentState) {
    Box(
        modifier = Modifier
            .testTag("state-dot")
            .size(7.dp)
            .clip(CircleShape)
            .background(dotColor(state)),
    )
}

private fun dotColor(state: AgentState): Color = when (state) {
    AgentState.NEEDS_YOU -> PendingAmber
    AgentState.ACTIVE -> Color(0xFF34C759)
    AgentState.IDLE -> Color.Gray.copy(alpha = 0.55f)
    AgentState.QUIET -> Color.Transparent
}

/** The row's one amber — reserved for "the operator owes someone an answer". */
private val PendingAmber = Color(0xFFFF9500)

/**
 * Decode the `data:` URI the core produced. No network, no URL loading — the
 * bytes already crossed the boundary.
 *
 * Returns `null` on anything malformed — a bad avatar must not take the row
 * down — rather than throwing.
 */
private fun String.decodeDataUri(): ImageBitmap? = try {
    val comma = indexOf(',')
    if (comma < 0) {
        null
    } else {
        val bytes = Base64.decode(substring(comma + 1), Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }
} catch (e: Exception) {
    null
}
