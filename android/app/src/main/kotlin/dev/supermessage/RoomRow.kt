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
    /**
     * The core's `RosterRow.describesAgent`: whether [state]'s activity word
     * (`active`, `idle`, `quiet`) says anything about this room. For a room
     * of people it does not, and "idle" under a friend's name is noise.
     * `NEEDS_YOU` shows regardless — owing an answer is not about agents.
     */
    describesAgent: Boolean = true,
    onOpenInfo: (() -> Unit)? = null,
) {
    val stateShown = showsState && (describesAgent || state == AgentState.NEEDS_YOU)
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
                // The name and its badge take all the slack; the time and the
                // unread count take what they need.
                //
                // This was `Text(weight(1f, fill = false))` beside
                // `Spacer(weight(1f))`, and two competing weights split the
                // leftover space **evenly** — so the name could never have
                // more than half of it, however little that was. At the
                // default text size there is slack and nothing shows. At
                // `fontScale = 2.0` the row read `Kaa…`: four characters of
                // `Superpipeline`, truncated to make room for whitespace, while
                // `2m` and the unread badge kept their full width.
                //
                // The name is the most important thing on the row and it was
                // losing to a Spacer. Grouping it with its badge under one
                // weight means the slack goes to content and the truncation
                // happens last.
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Top,
                ) {
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
                }
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
            metaLine(row = row, state = state, showsState = stateShown, hidesHost = hidesHost)?.let { meta ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    if (stateShown) {
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
                    color = if (preview.pending) SupermessageTheme.colors.signal else MaterialTheme.colorScheme.onSurfaceVariant,
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

/**
 * `NEEDS_YOU` and `ACTIVE` reach for [SupermessageColorRoles.signal] and
 * [SupermessageColorRoles.ok] respectively — the same two tokens, and the
 * same rule, as the amber switch above: amber means only "the operator owes
 * someone an answer", never used for anything else, and a working room is
 * never amber (see `ok`'s own KDoc in `Theme.kt`).
 */
@Composable
private fun dotColor(state: AgentState): Color = when (state) {
    AgentState.NEEDS_YOU -> SupermessageTheme.colors.signal
    AgentState.ACTIVE -> SupermessageTheme.colors.ok
    AgentState.IDLE -> Color.Gray.copy(alpha = 0.55f)
    AgentState.QUIET -> Color.Transparent
}

/**
 * Decode the `data:` URI the core produced. No network, no URL loading — the
 * bytes already crossed the boundary.
 *
 * Returns `null` on anything malformed — a bad avatar must not take the row
 * down — rather than throwing.
 *
 * `internal`, not `private`: [dev.supermessage.RoomInfoPanel]'s header avatar
 * decodes the exact same `data:` URI shape from the exact same cache
 * ([dev.supermessage.kit.stores.AvatarCache]), and a second copy of this
 * parsing is how one of the two quietly regresses — the reason
 * [dev.supermessage.kit.stores.AvatarCache]'s own KDoc gives for being one
 * type rather than two. `TimelineRow.kt`'s own `SenderFace` used to carry a
 * second, `private` copy of exactly this function (its own KDoc said so —
 * "mirroring `RoomRow`'s `decodeDataUri()`"); widening this one to `internal`
 * and deleting that copy is this task's fix for the very duplication its
 * comment already named.
 */
internal fun String.decodeDataUri(): ImageBitmap? = try {
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
