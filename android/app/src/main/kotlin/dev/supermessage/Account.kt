package dev.supermessage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import dev.supermessage.kit.RosterChoice
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
import dev.supermessage.notify.PushStatus
import dev.supermessage.notify.words
import uniffi.supermessage_core.AccountDto
import uniffi.supermessage_core.RoomMemberDto
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import uniffi.supermessage_ffi.displayInitial
import uniffi.supermessage_ffi.peopleLabel

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
 * @param rosterView The stored arrangement, already mapped through
 *   [RosterChoice.offeredOrDefault]: a Machine left by an older build reads as Waiting.
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
    rosterView: RosterChoice,
    onRosterView: suspend (RosterChoice) -> Unit,
    showsState: Boolean,
    onShowsState: suspend (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** The Appearance section (Task 14's [AppearanceSettings]); empty where there is no theme store. */
    appearance: @Composable () -> Unit = {},
    // Defaulted so existing callers and tests compile unchanged; the real ones
    // are wired at the call site. A screen that cannot reach recovery shows
    // "unknown", which reads as "checking" — never as "not set up".
    recoveryState: suspend () -> String = { "unknown" },
    onEnableRecovery: suspend () -> String = { "" },
    onRecoverWithKey: suspend (String) -> Unit = {},
    onResetRecovery: suspend (String) -> String = { throw NotImplementedError() },
    /**
     * Who this account has blocked, read when the panel opens and again only
     * when [blockedNow] holds someone the loaded list lacks. `null` means the
     * read failed: the list already shown stays.
     */
    loadBlocked: suspend () -> List<RoomMemberDto>? = { emptyList() },
    /** Unblocks one user: `null` when it landed, otherwise the refusal in words. */
    onUnblock: suspend (String) -> String? = { null },
    /**
     * The session's blocked ids. The list drawn is the loaded one filtered by
     * these, so a landed unblock stays gone while the core's own list is stale
     * (it changes only on the sync echo). `null` draws the loaded list as is.
     */
    blockedNow: Set<String>? = null,
    /** Only for a preview of the refused state. */
    initialUnblockFailure: String? = null,
    /**
     * Whether background push is on, and if not why (Q1/Q2) *(no iOS copy)*.
     * `null` (not yet known) shows no Notifications section.
     */
    notifications: PushStatus? = null,
) {
    var account by remember { mutableStateOf<AccountDto?>(null) }
    var confirmingSignOut by remember { mutableStateOf(false) }
    var showingRecovery by remember { mutableStateOf(false) }
    var recovery by remember { mutableStateOf("unknown") }
    val scope = rememberCoroutineScope()
    var loadedBlocked by remember { mutableStateOf<List<RoomMemberDto>>(emptyList()) }
    var triedBlocked by remember { mutableStateOf(false) }
    val blocked = if (blockedNow == null) loadedBlocked else loadedBlocked.filter { it.userId in blockedNow }
    var unblockFailure by remember { mutableStateOf(initialUnblockFailure) }
    var unblocking by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(Unit) {
        account = loadAccount()
    }
    // Loads when the panel opens (`AccountPanel.swift:177-180`). After that it
    // reloads only for an id the list lacks, a block made from room info or on
    // another device. The core's list is its local store, which still holds
    // someone just unblocked until sync echoes it, so it is never trusted to
    // remove a row; `blockedNow` does that. A failed read keeps the last list.
    LaunchedEffect(blockedNow) {
        val missing = blockedNow?.any { id -> loadedBlocked.none { it.userId == id } } == true
        if (!triedBlocked || missing) {
            triedBlocked = true
            loadBlocked()?.let { loadedBlocked = it }
        }
    }

    val name = accountName(account)

    val colors = SupermessageTheme.colors

    Column(modifier.fillMaxSize().testTag("account")) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Account", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onClose, modifier = Modifier.testTag("account-done")) { Text("Done") }
        }

        // Scrolls: the sections together are taller than a short phone, and
        // Sign out, the last of them, must stay reachable.
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            AccountSectionHeader("Signed in as")
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
                    //
                    // The initial comes from the core, not from `name.take(1)`.
                    // Kotlin's `take` counts UTF-16 code units, so an emoji-initial
                    // name yielded half a surrogate pair and rendered as tofu —
                    // the exact defect `room_identity`'s header records shipping
                    // once already. `displayInitial` is code-point-aware, and in
                    // Rust a `char` is a scalar value, so the hazard cannot be
                    // expressed there.
                    Text(
                        displayInitial(name),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag("account-initial"),
                    )
                }

                Column {
                    Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("account-name"))
                    account?.let {
                        Text(
                            it.userId,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.contentMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("account-user-id"),
                        )
                    }
                }
            }

            account?.let {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text("Homeserver", style = MaterialTheme.typography.labelSmall, color = colors.contentMuted)
                    Text(it.homeserver, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("account-homeserver"))
                }
            }

            appearance()

            notifications?.let { NotificationsRow(it) }

            AccountSectionHeader("Chats")
            Column(Modifier.selectableGroup()) {
                Text(
                    "Order rooms by",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                RosterChoice.offered.forEach { option ->
                    val tag = if (option == RosterChoice.WAITING) "account-order-waiting" else "account-order-recent"
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = rosterView == option,
                                role = Role.RadioButton,
                                onClick = { scope.launch { onRosterView(option) } },
                            )
                            .padding(horizontal = 16.dp)
                            .testTag(tag),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = rosterView == option, onClick = null)
                        Text(if (option == RosterChoice.WAITING) "Waiting first" else "Most recent")
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(
                        value = showsState,
                        role = Role.Switch,
                        onValueChange = { scope.launch { onShowsState(it) } },
                    )
                    .padding(horizontal = 16.dp)
                    .testTag("account-agent-state"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Agent state")
                Switch(checked = showsState, onCheckedChange = null)
            }
            AccountSectionFooter(
                "Waiting first puts what needs an answer at the top. Agent state is the dot and word beside an agent's name.",
            )

            // Only when someone is in it (`AccountPanel.swift:112-130`).
            if (blocked.isNotEmpty()) {
                Column(Modifier.testTag("account-blocked")) {
                    AccountSectionHeader("Blocked")
                    blocked.forEach { member ->
                        val name = member.displayName ?: member.userId
                        Row(
                            Modifier.fillMaxWidth().padding(end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MemberRow(member, blocked = false, onClick = null, modifier = Modifier.weight(1f))
                            TextButton(
                                onClick = {
                                    unblockFailure = null
                                    unblocking = true
                                    scope.launch {
                                        try {
                                            val refused = onUnblock(member.userId)
                                            if (refused != null) {
                                                unblockFailure = refused
                                            } else {
                                                loadedBlocked = loadedBlocked.filter { it.userId != member.userId }
                                                haptics.play(Haptic.Success)
                                            }
                                        } finally {
                                            unblocking = false
                                        }
                                    }
                                },
                                enabled = !unblocking,
                                modifier = Modifier
                                    .semantics { contentDescription = "Unblock $name" }
                                    .testTag("account-unblock-${member.userId}"),
                            ) { Text("Unblock") }
                        }
                    }
                    val failure = unblockFailure
                    if (failure != null) {
                        Text(
                            failure,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .semantics { liveRegion = LiveRegionMode.Polite }
                                .testTag("account-blocked-failure"),
                        )
                    } else {
                        Text(
                            "Their messages are hidden in every room, on all your devices. A blocked agent keeps running. " +
                                "Block someone from a room's member list.",
                            style = MaterialTheme.typography.bodySmall,
                            color = SupermessageTheme.colors.contentFaint,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .testTag("account-blocked-footer"),
                        )
                    }
                }
            }

            // No heading, as on iOS: the three links name themselves.
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            HelpLink("Help & support", SupportUrl, "account-link-support")
            HelpLink("Terms of use", TermsUrl, "account-link-terms")
            HelpLink("Privacy", PrivacyUrl, "account-link-privacy")
            Text(
                "To report abuse, long-press a message or open a room's info. " +
                    "Reports go to your homeserver's administrators.",
                style = MaterialTheme.typography.bodySmall,
                color = SupermessageTheme.colors.contentFaint,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag("account-report-footer"),
            )

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            // Beside `Sign out` because it is the same rarely-visited class of
            // account action — and because the day it is needed is the day someone
            // is setting up a new device and looking for exactly this.
            TextButton(
                onClick = {
                    showingRecovery = true
                    scope.launch { recovery = runCatching { recoveryState() }.getOrDefault("unknown") }
                },
                modifier = Modifier.testTag("account-recovery"),
            ) { Text("Encryption recovery") }

            if (showingRecovery) {
                RecoveryPanel(
                    state = recovery,
                    onEnable = onEnableRecovery,
                    onRecover = onRecoverWithKey,
                    onReset = onResetRecovery,
                    onClose = { showingRecovery = false },
                )
            }

            TextButton(
                onClick = { confirmingSignOut = true },
                modifier = Modifier.testTag("account-sign-out"),
            ) { Text("Sign out", color = MaterialTheme.colorScheme.error) }

            // Said plainly, because it is true and because signing out of this
            // app is not the small thing it is elsewhere — the encrypted store
            // goes with it. Mirrors `AccountPanel.swift`'s own footer text.
            AccountSectionFooter("Signing out removes this account and its messages from this device.")
        }
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
 * The person behind the Matrix id — `@rakesh:id.agentpod.dev` is a name and
 * an address, and only the first half is worth a headline.
 *
 * Delegates to [peopleLabel], which delegates to the core's own
 * `display_name::user_label` — not a local reimplementation. The two have
 * disagreed before: a hand-rolled substring between `@` and `:` reads
 * `@_agentpod_ganesha:id.agentpod.dev` as `_agentpod_ganesha`, where
 * `user_label` strips the bridge's leading-underscore namespace and answers
 * `Ganesha`. `user_label` itself is not exported over FFI, but
 * `peopleLabel(listOf(id))` is exactly it for a single id, and is this
 * app's third occasion to reach for the core instead of re-deriving the
 * rule locally (`people_label`, `decodeDataUri`, now this).
 *
 * `null` while still loading (or a swallowed failure — see [loadAccount]'s
 * own doc) reads "Signed in" rather than guessing at a name; that fallback
 * is this function's alone; everything else is the core's answer.
 */
private fun accountName(account: AccountDto?): String {
    val id = account?.userId ?: return "Signed in"
    return peopleLabel(listOf(id))
}

// The landing site's pages (`AccountPanel.swift:196-202`): the three the
// stores ask an app with user content to link.
private const val SupportUrl = "https://supermessage.dev/support/"
private const val TermsUrl = "https://supermessage.dev/terms/"
private const val PrivacyUrl = "https://supermessage.dev/privacy/"

/**
 * Account → Notifications: one line saying whether notifications reach this
 * phone while supermessage is closed (owner decisions Q1/Q2) *(no iOS copy:
 * iOS has no distributor to be missing)*. Never a prompt. It wraps rather
 * than truncates: at font scale 2 the "Off" reasons run to several lines.
 */
@Composable
internal fun NotificationsRow(status: PushStatus) {
    AccountSectionHeader("Notifications")
    Text(
        status.words(),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("account-notifications"),
    )
}

/** A section's title: `labelMedium` in `contentMuted`. */
@Composable
internal fun AccountSectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = SupermessageTheme.colors.contentMuted,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

/** A section's footer: `bodySmall` in `contentFaint`. */
@Composable
internal fun AccountSectionFooter(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = SupermessageTheme.colors.contentFaint,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun HelpLink(label: String, url: String, tag: String) {
    val uriHandler = LocalUriHandler.current
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button) { uriHandler.openUri(url) }
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(tag),
    )
}
