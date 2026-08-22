package dev.supermessage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * What an invited room shows in place of a composer — the port of
 * `apple/Supermessage/Panels/InvitationView.swift`.
 *
 * Which room this is shown for, and whether it is shown at all, is the
 * caller's decision (`row.affordance == respondToInvitation` on iOS): this
 * file only renders one invitation, given its room id and name, and asks
 * once, per room, who sent it.
 *
 * @param roomId The invited room.
 * @param roomName Already resolved by the caller — this file parses no name.
 * @param inviter Who invited this account to [roomId], or `null` — stands in
 *   for `Session.inviter`, asked once per [roomId] the same way iOS's own
 *   `.task(id: roomId)` does (see `Session::room_inviter`'s own doc for why
 *   this is not carried on every roster row instead).
 * @param joinRoom Accepts — `Session.joinRoom`. Returns a refusal message, or
 *   `null` on success.
 * @param leaveRoom Declines — `Session.leaveRoom`. Same refusal contract.
 */
@Composable
fun InvitationView(
    roomId: String,
    roomName: String,
    inviter: suspend (roomId: String) -> String?,
    joinRoom: suspend (roomId: String) -> String?,
    leaveRoom: suspend (roomId: String) -> String?,
    modifier: Modifier = Modifier,
) {
    var inviterName by remember(roomId) { mutableStateOf<String?>(null) }
    var busy by remember(roomId) { mutableStateOf(false) }
    var failure by remember(roomId) { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(roomId) {
        inviterName = inviter(roomId)
    }

    suspend fun respond(accept: Boolean) {
        busy = true
        failure = if (accept) joinRoom(roomId) else leaveRoom(roomId)
        busy = false
    }

    Column(
        modifier.fillMaxWidth().padding(20.dp).testTag("invitation"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                "You have been invited to $roomName.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("invitation-message"),
            )
            // By whom — the thing you would want before accepting.
            inviterName?.let {
                Text(
                    "from $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.testTag("invitation-inviter"),
                )
            }
        }

        failure?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("invitation-failure"),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { coroutineScope.launch { respond(accept = false) } },
                enabled = !busy,
                modifier = Modifier.testTag("invitation-decline"),
            ) { Text("Decline") }
            Button(
                onClick = { coroutineScope.launch { respond(accept = true) } },
                enabled = !busy,
                modifier = Modifier.testTag("invitation-accept"),
            ) { Text("Accept") }
        }
    }
}

/**
 * What the timeline shows for an invitation, in place of history — the port
 * of `InvitationEmptyTimeline` on `InvitationView.swift`.
 *
 * An invited room has no readable history: membership is `invite`, so the
 * homeserver sends state and nothing else, and the one event that does come
 * through renders as "… created the room", which reads like a broken room
 * rather than an unopened one.
 */
@Composable
fun InvitationEmptyTimeline(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(24.dp).testTag("invitation-empty-timeline"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Not joined yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Accept the invitation to see this room's messages.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}
