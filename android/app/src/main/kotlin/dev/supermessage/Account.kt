package dev.supermessage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import uniffi.supermessage_core.AccountDto

/**
 * Who you are signed in as, and the way out — the port of
 * `apple/Supermessage/Panels/AccountPanel.swift`.
 *
 * **The way out is the point.** `Session.signOut` exists and is tested but,
 * until this file, was called from nowhere in the Android app — a
 * signed-in device could not be signed out except by clearing app data. See
 * `AccountPanel.swift`'s own KDoc for the same defect on the other platform.
 *
 * ## No store behind this one
 *
 * The same shape [RoomInfoPanel] and `SearchPanel` document on their own
 * KDoc: an account is a request the core answers once (`Session.account`),
 * not a diff-driven stream, so this composable holds its own [AccountDto]
 * state rather than reading a `StateFlow`.
 *
 * ## Sign-out is destructive
 *
 * `Session.signOut` deletes the local encrypted store, so [onSignOut] is
 * never called except from behind [confirmingSignOut]'s dialog — the exact
 * guard [RoomInfoPanel] already applies to leaving a room, for the same
 * reason: nothing here calls an irreversible action on a single tap.
 *
 * @param loadAccount Fetches [AccountDto] — stands in for `Session.account`,
 *   which already swallows a failure into `null` (this file adds no second
 *   failure path on top of it, matching [SearchPanel]'s own note on why).
 * @param onSignOut Actually signs out — `Session.signOut`. Only reachable
 *   through the confirmation dialog below.
 * @param onClose What "Done" (or a completed sign-out) does to the pane
 *   holding this panel — this file does not know whether that is a
 *   `ListDetailPaneScaffold`'s extra pane, a sheet, or anything else, the
 *   same boundary [RoomInfoPanel]'s own `onClose` draws.
 */
@Composable
fun AccountPanel(
    loadAccount: suspend () -> AccountDto?,
    onSignOut: suspend () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var account by remember { mutableStateOf<AccountDto?>(null) }
    var confirmingSignOut by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        account = loadAccount()
    }

    val name = accountName(account)

    Column(modifier.fillMaxSize().testTag("account")) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Account", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onClose, modifier = Modifier.testTag("account-done")) { Text("Done") }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                // A plain initial in place of a picture — this panel has no
                // avatar to show, the same choice `AccountPanel.swift`'s own
                // `initial` fallback makes for every reader.
                Text(name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium)
            }

            Column {
                Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("account-name"))
                account?.let {
                    Text(
                        it.userId,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("account-user-id"),
                    )
                }
            }
        }

        account?.let {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("Homeserver", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                Text(it.homeserver, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("account-homeserver"))
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        TextButton(
            onClick = { confirmingSignOut = true },
            modifier = Modifier.testTag("account-sign-out"),
        ) { Text("Sign out", color = MaterialTheme.colorScheme.error) }

        // Said plainly, because it is true and because signing out of this
        // app is not the small thing it is elsewhere — the encrypted store
        // goes with it. Mirrors `AccountPanel.swift`'s own footer text.
        Text(
            "Signing out removes this account and its messages from this device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }

    if (confirmingSignOut) {
        AlertDialog(
            modifier = Modifier.testTag("account-sign-out-confirm"),
            onDismissRequest = { confirmingSignOut = false },
            title = { Text("Sign out of $name?") },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("account-sign-out-confirm-button"),
                    onClick = {
                        confirmingSignOut = false
                        scope.launch {
                            onSignOut()
                            onClose()
                        }
                    },
                ) { Text("Sign out", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(
                    modifier = Modifier.testTag("account-sign-out-cancel"),
                    onClick = { confirmingSignOut = false },
                ) { Text("Cancel") }
            },
        )
    }
}

/**
 * The local part of the Matrix id — `@rakesh:id.agentpod.dev` is a name and
 * an address, and only the first half is worth a headline. Mirrors
 * `AccountPanel.swift`'s own `name` computed property exactly, including its
 * fallback: `null` while still loading reads "Signed in", and an id this
 * app cannot parse (no `@`, no `:`) is shown whole rather than guessed at.
 */
private fun accountName(account: AccountDto?): String {
    val id = account?.userId ?: return "Signed in"
    if (!id.startsWith("@")) return id
    val colon = id.indexOf(':')
    if (colon < 0) return id
    return id.substring(1, colon)
}
