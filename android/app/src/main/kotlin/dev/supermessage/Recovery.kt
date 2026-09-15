package dev.supermessage

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * The key that gets a conversation back.
 *
 * Room keys live in this device's encrypted store. Without recovery a lost or
 * reset phone takes every encrypted conversation on it — there is no
 * server-side copy to fall back on, because that is what end-to-end encryption
 * means. Backup uploads those keys encrypted under a key the server never sees;
 * this screen hands that key over, and uses it on a new device.
 *
 * Four states, four screens, because they are four different situations. Mirrors
 * `RecoveryView.swift` and the desktop `RecoveryPanel.svelte` deliberately: the
 * same words in all three, so one explanation covers the product.
 *
 * @param state "enabled", "disabled", "incomplete" or "unknown"
 * @param onEnable Turns recovery on, returning the key — shown once, never stored.
 * @param onRecover Uses a key on this device.
 */
@Composable
fun RecoveryPanel(
    state: String,
    onEnable: suspend () -> String,
    onRecover: suspend (String) -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var freshKey by remember { mutableStateOf<String?>(null) }
    var entered by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Encryption recovery", style = MaterialTheme.typography.titleMedium)

        val key = freshKey
        if (key != null) {
            // The one moment this key exists outside the SDK.
            Text(
                "Save this somewhere safe. It is shown once, and it is the only way to read " +
                    "your encrypted messages on a new device.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                key,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(vertical = 12.dp).testTag("recovery-key"),
            )
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(key))
                copied = true
            }) { Text(if (copied) "Copied" else "Copy") }
            TextButton(onClick = onClose) { Text("I have saved it") }
        } else when (state) {
            // Never "not set up": offering a second key to somebody who already
            // has one is how the first gets orphaned.
            "unknown" -> Text("Checking this account…", modifier = Modifier.padding(top = 8.dp))

            "enabled" -> {
                Text(
                    "Recovery is on. Your messages can be restored on a new device with your " +
                        "recovery key. There is no way to show it again.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                TextButton(onClick = onClose) { Text("Done") }
            }

            "incomplete" -> {
                Text(
                    "This device is missing your encryption keys. Enter your recovery key to " +
                        "read your earlier messages here.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = entered,
                    onValueChange = { entered = it },
                    label = { Text("Recovery key") },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
                Button(
                    enabled = !busy && entered.isNotBlank(),
                    onClick = {
                        scope.launch {
                            busy = true
                            failure = null
                            try {
                                onRecover(entered.trim())
                                onClose()
                            } catch (e: Exception) {
                                // Led with what the reader can act on: the
                                // likeliest cause by far is a mistyped key.
                                failure = "That key was not accepted — check it for typos. (${e.message})"
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text(if (busy) "Restoring…" else "Restore") }
            }

            else -> {
                Text(
                    "Set up recovery so you can read your encrypted messages on a new device. " +
                        "Without it, messages stay on this device only.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Button(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            failure = null
                            try {
                                freshKey = onEnable()
                            } catch (e: Exception) {
                                failure = e.message
                            } finally {
                                busy = false
                            }
                        }
                    },
                    modifier = Modifier.padding(top = 8.dp).testTag("recovery-enable"),
                ) { Text(if (busy) "Setting up…" else "Set up recovery") }
            }
        }

        failure?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
