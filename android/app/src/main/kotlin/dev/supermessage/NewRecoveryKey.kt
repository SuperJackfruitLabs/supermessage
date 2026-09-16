package dev.supermessage

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * The recovery key, at the moment it is created, on the first sign-in.
 *
 * **This exists because a backup is not recovery.** The SDK's
 * `auto_enable_backups` creates a key backup; it does not create the secret
 * storage that holds that backup's key. An account with only the former has a
 * backup nothing can ever restore from, and for two builds that was every
 * account this app signed in — the reassurance without the recovery.
 *
 * Offering it in a settings screen does not fix that, because the people who
 * most need the key are the ones who will never open that screen. So it is
 * made here, once, where the reader already is.
 *
 * Not dismissible by tapping away: this is the only time the key is ever
 * shown, and a dialog closed by accident is indistinguishable from one that
 * was read. Mirrors `NewRecoveryKeyView.swift`.
 */
@Composable
fun NewRecoveryKeyDialog(key: String, onDone: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = {},
        title = { Text("Keep this safe") },
        text = {
            Column {
                Text(
                    "Save this somewhere safe. It is shown once, and it is the only way to " +
                        "read your encrypted messages on a new device.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    key,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 12.dp).testTag("new-recovery-key"),
                )
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(key))
                    copied = true
                }) { Text(if (copied) "Copied" else "Copy") }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDone,
                modifier = Modifier.testTag("new-recovery-key-done"),
            ) { Text("I've saved it") }
        },
    )
}
