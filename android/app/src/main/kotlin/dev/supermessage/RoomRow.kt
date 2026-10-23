package dev.supermessage

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import uniffi.supermessage_core.AgentAvatarStyle
import uniffi.supermessage_core.AgentState
import uniffi.supermessage_core.RoomAffordance
import uniffi.supermessage_core.RoomRow

/**
 * One roster row, laid out the way
 * `apple/Supermessage/Rooms/RoomRowView.swift:27-136` describes it: two
 * lines and only two. The runtime lives in room info and the state is the
 * dot on the face ([RoomAvatar]).
 *
 * Everything on it was decided by the core — the sigil and name come from
 * `row.identity`, the preview line from `row.preview`, the tile from
 * `RosterRow.avatar` ([style]). This composable parses nothing and composes
 * nothing; it lays out what it was handed.
 *
 * The avatar is its own tap target: tapping it asks *about the room*
 * ([onOpenInfo]), tapping anywhere else opens the conversation — a callback
 * this composable does not itself expose, because that behaviour belongs to
 * whoever places this row in a list.
 */
@Composable
fun RoomRow(
    row: RoomRow,
    avatarUri: String?,
    state: AgentState,
    `when`: String,
    showsState: Boolean = true,
    /**
     * The core's `RosterRow.describesAgent`: whether [state]'s activity word
     * (`active`, `idle`, `quiet`) says anything about this room. For a room
     * of people it does not, and "idle" under a friend's name is noise.
     * `NEEDS_YOU` shows regardless — owing an answer is not about agents.
     */
    describesAgent: Boolean = true,
    /** The core's generated tile for an agent's room (`RosterRow.avatar`). */
    style: AgentAvatarStyle? = null,
    onOpenInfo: (() -> Unit)? = null,
) {
    val colors = SupermessageTheme.colors
    val stateShown = showsState && (describesAgent || state == AgentState.NEEDS_YOU)
    val unread = row.room.unread
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoomAvatar(
            roomId = row.room.id,
            initial = row.identity.initial,
            avatarUri = avatarUri,
            describesAgent = describesAgent,
            style = style,
            state = state.takeIf { stateShown },
            onOpenInfo = onOpenInfo,
            infoLabel = "About ${row.identity.name}",
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // The name and its badge take all the slack; the time takes
                // what it needs. Two competing weights split the slack evenly
                // and truncated the name at large text (it read `Kaa…`).
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
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
                        color = if (unread > 0uL) colors.accent else colors.contentFaint,
                        maxLines = 1,
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // The preview; the room's role when it has said nothing; a
                // blank line otherwise, so rows keep one height.
                val preview = row.preview
                Text(
                    preview?.text ?: row.identity.role.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    // The row's amber switch, and the only place this
                    // composable may use it: true means only ever "the
                    // operator owes someone an answer", never a severity.
                    color = if (preview?.pending == true) colors.signal else colors.contentMuted,
                    minLines = 1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).testTag("row-preview"),
                )
                if (unread > 0uL) {
                    UnreadBadge(unread)
                }
            }
        }
    }
}

@Composable
private fun InvitationBadge() {
    val accent = SupermessageTheme.colors.accent
    Text(
        "Invitation",
        style = MaterialTheme.typography.labelSmall,
        color = accent,
        maxLines = 1,
        modifier = Modifier
            .testTag("invitation-badge")
            .border(1.dp, accent, CircleShape)
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
    Box(
        modifier = Modifier
            .testTag("unread-badge")
            // A screen reader hears "3 unread", not a bare number. The label
            // reads the true count rather than "99+": "more than 99" is the
            // useful fact, and the clamp exists only to bound the width.
            .semantics { contentDescription = "$count unread" }
            .defaultMinSize(minWidth = 19.dp, minHeight = 19.dp)
            .background(SupermessageTheme.colors.accent, CircleShape)
            .padding(horizontal = 5.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99uL) "99+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = SupermessageTheme.colors.accentContent,
            maxLines = 1,
        )
    }
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
