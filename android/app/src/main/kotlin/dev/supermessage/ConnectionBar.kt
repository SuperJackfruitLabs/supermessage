package dev.supermessage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.supermessage.kit.stores.ConnectionStore.Connection

/**
 * A slim line when the core is not live, and nothing when it is — iOS's
 * `ConnectionBar` (`apple/Supermessage/RootView.swift`).
 *
 * Never amber. Amber means the operator owes someone an answer, and a flaky
 * connection is not that: the row sits on the sunken surface and the message,
 * when the core gave one, is muted.
 */
@Composable
fun ConnectionBar(
    state: Connection,
    message: String?,
    worthShowing: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!worthShowing) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(SupermessageTheme.colors.surfaceSunken)
            .padding(vertical = 4.dp)
            .testTag("connection-bar"),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            connectionLabel(state),
            style = MaterialTheme.typography.labelSmall,
            color = SupermessageTheme.colors.content,
        )
        message?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = SupermessageTheme.colors.contentMuted,
            )
        }
    }
}

/** The words iOS uses; a state this build has not been taught shows as the core said it. */
internal fun connectionLabel(state: Connection): String = when (state) {
    Connection.Live -> "live"
    Connection.Connecting -> "connecting"
    Connection.Offline -> "offline"
    Connection.Error -> "reconnecting…"
    is Connection.Unknown -> state.raw
}
