package dev.supermessage.previews

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.supermessage.ConnectionBar
import dev.supermessage.kit.stores.ConnectionStore.Connection

/** Offline with the reason the core gave: "Offline" in the meta face, reason muted. */
@Preview(name = "Connection bar, offline", showBackground = true, widthDp = 360, heightDp = 60)
@Composable
internal fun ConnectionBarOffline() {
    PreviewGround { ConnectionBar(Connection.Offline, message = "connection refused", worthShowing = true) }
}

/** A sync failure that is being retried reads "Reconnecting…", with its message. */
@Preview(name = "Connection bar, reconnecting", showBackground = true, widthDp = 360, heightDp = 60)
@Composable
internal fun ConnectionBarReconnecting() {
    PreviewGround { ConnectionBar(Connection.Error, message = "sync timed out", worthShowing = true) }
}
