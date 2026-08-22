package dev.supermessage

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.supermessage.kit.ErrorPresenter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import uniffi.supermessage_core.NotificationMode
import uniffi.supermessage_core.RoomInfoDto
import uniffi.supermessage_core.RoomMemberDto
import uniffi.supermessage_ffi.FfiException

/**
 * Who is in a room, and what it is called — the port of
 * `apple/Supermessage/Panels/RoomInfoPanel.swift`, filling the pane Task 1
 * made openable but left as a placeholder.
 *
 * ## No store behind this one
 *
 * Every other panel this phase built (`Roster`, `Timeline`, `Composer`) reads
 * a `StateFlow` some `:kit` store publishes and a diff keeps current. There is
 * no `RoomInfoStore` for this one, because room info is not diff-driven —
 * `roomInfo` is a request the core answers once, not a stream this pane could
 * subscribe to. So this composable holds its own `info`/`failure` state and
 * calls the core directly, in a `LaunchedEffect` keyed on [roomId], the exact
 * shape iOS's `.task(id: roomId)` already takes at
 * `apple/Supermessage/Panels/RoomInfoPanel.swift:151-155`. Documented here
 * because it is a real divergence from every other panel's idiom in this
 * phase, not an oversight: a reviewer comparing this file to `Roster.kt`
 * should find the difference explained, not rediscover it.
 *
 * ## What this panel does not decide
 *
 * Every string on screen — the name, the parsed role, the single-character
 * avatar fallback, whether a topic line is the bridge's runtime rather than
 * prose — is [RoomInfoDto] as the core already resolved it. This file filters
 * the reader themselves out of the member list (a display choice, not a core
 * one — iOS makes the identical choice in its own `others(_:)`, in-view) and
 * lays out what it is handed. It parses no name, sorts no member list, and
 * derives no initial.
 *
 * @param roomId The room this panel is about.
 * @param accountUserId This account's own id, so it can be left out of the
 *   member list — `null` while it is still loading, in which case nobody is
 *   excluded rather than guessing.
 * @param avatarUri The room's picture as a `data:` URI, from the same cache
 *   the roster already reads — see [dev.supermessage.decodeDataUri]. `null`
 *   shows the initial the core derived instead.
 * @param loadInfo Fetches [RoomInfoDto] for [roomId]. Called once per room
 *   (re-called if [roomId] changes) and again after every mute/notify/pin
 *   write below, to reconcile the optimistic local edit with what the
 *   homeserver actually accepted — the same unconditional `await load()`
 *   iOS's own `apply`/`pinned` bindings run after every write.
 * @param onSetNotifications Writes a new [NotificationMode]. Returns whether
 *   it landed; a caller that returns `false` here has the honest option to
 *   leave the reader looking at a value [loadInfo]'s reconciliation will
 *   correct, exactly as a rejected edit would.
 * @param onSetPinned Writes the pinned tag. Same contract as
 *   [onSetNotifications].
 * @param onLeaveRoom Actually leaves — never called except from behind the
 *   confirmation dialog below. Irreversible from this app's own point of
 *   view, which is why nothing here calls it on a single tap.
 * @param onClose What "Done" (or a completed leave) does to the pane that
 *   holds this panel — this file does not know whether that is a
 *   `ListDetailPaneScaffold`'s extra pane or anything else.
 */
@Composable
fun RoomInfoPanel(
    roomId: String,
    accountUserId: String?,
    avatarUri: String?,
    onClose: () -> Unit,
    loadInfo: suspend () -> RoomInfoDto,
    onSetNotifications: suspend (NotificationMode) -> Boolean,
    onSetPinned: suspend (Boolean) -> Boolean,
    onLeaveRoom: suspend () -> Unit,
    modifier: Modifier = Modifier,
) {
    var info by remember(roomId) { mutableStateOf<RoomInfoDto?>(null) }
    var failure by remember(roomId) { mutableStateOf<String?>(null) }
    var showsLeaveConfirm by remember(roomId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        try {
            info = loadInfo()
            failure = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: FfiException) {
            failure = ErrorPresenter.message(e)
        } catch (e: Exception) {
            failure = "Couldn't load that room."
        }
    }

    LaunchedEffect(roomId) {
        info = null
        failure = null
        reload()
    }

    fun applyNotifications(mode: NotificationMode) {
        info = info?.copy(notifications = mode)
        scope.launch {
            onSetNotifications(mode)
            reload()
        }
    }

    fun applyPinned(pinned: Boolean) {
        info = info?.copy(pinned = pinned)
        scope.launch {
            onSetPinned(pinned)
            reload()
        }
    }

    Box(modifier.fillMaxSize()) {
        val currentInfo = info
        when {
            currentInfo != null -> RoomInfoBody(
                info = currentInfo,
                accountUserId = accountUserId,
                avatarUri = avatarUri,
                onClose = onClose,
                onSetNotifications = ::applyNotifications,
                onSetPinned = ::applyPinned,
                onLeaveTapped = { showsLeaveConfirm = true },
            )

            failure != null ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .testTag("room-info-failure"),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Couldn't load", style = MaterialTheme.typography.titleMedium)
                    Text(failure ?: "", style = MaterialTheme.typography.bodyMedium)
                }

            else ->
                Box(
                    Modifier.fillMaxSize().testTag("room-info-loading"),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
        }
    }

    if (showsLeaveConfirm) {
        AlertDialog(
            // Named `-dialog`, not `-confirm`, so it cannot be mistaken for
            // `room-info-leave-confirm-button` below — a near-collision that
            // once made a CI-only failure hard to read. This tag exists for
            // completeness; tests assert on content *inside* the dialog
            // (its title, its buttons) rather than this wrapper, because an
            // `AlertDialog` renders in its own window and `isDisplayed` on
            // the wrapper node is not reliable across devices.
            modifier = Modifier.testTag("room-info-leave-dialog"),
            onDismissRequest = { showsLeaveConfirm = false },
            title = { Text("Leave room?") },
            text = { Text("You will stop receiving messages from this room.") },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("room-info-leave-confirm-button"),
                    onClick = {
                        showsLeaveConfirm = false
                        scope.launch {
                            onLeaveRoom()
                            onClose()
                        }
                    },
                ) { Text("Leave") }
            },
            dismissButton = {
                TextButton(
                    modifier = Modifier.testTag("room-info-leave-cancel"),
                    onClick = { showsLeaveConfirm = false },
                ) { Text("Cancel") }
            },
        )
    }
}

/**
 * The loaded state: everything [RoomInfoDto] carries, laid out. Split out of
 * [RoomInfoPanel] so the loading/failure/optimistic-write plumbing above
 * cannot leak into what is, underneath, a pure function of [info].
 */
@Composable
private fun RoomInfoBody(
    info: RoomInfoDto,
    accountUserId: String?,
    avatarUri: String?,
    onClose: () -> Unit,
    onSetNotifications: (NotificationMode) -> Unit,
    onSetPinned: (Boolean) -> Unit,
    onLeaveTapped: () -> Unit,
) {
    // Everyone in the room who is not this account. A display choice, not a
    // core one — see this file's class doc.
    val others = remember(info.members, accountUserId) {
        info.members.filter { it.userId != accountUserId }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Room info", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onClose, modifier = Modifier.testTag("room-info-done")) { Text("Done") }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderAvatar(initial = info.identity.initial, avatarUri = avatarUri)
            Column {
                Text(info.identity.name, style = MaterialTheme.typography.headlineSmall)
                info.identity.role?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }

        // The runtime, when this room is an agent's — read out of the topic
        // by the core, in structured form. Nothing here parses a topic line.
        info.runtime?.let { runtime ->
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                LabeledRow("Harness", runtime.harness)
                LabeledRow("Machine", runtime.host)
            }
        }

        info.topic?.takeIf { it.isNotEmpty() }?.let { topic ->
            Text(
                topic,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        SectionHeader("Notifications")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Mute")
            Switch(
                modifier = Modifier.testTag("room-info-mute"),
                checked = info.notifications == NotificationMode.MUTED,
                // Turning mute off restores the account default rather than
                // picking a level on the reader's behalf — the same choice
                // iOS's own `muted` binding makes.
                onCheckedChange = { on -> onSetNotifications(if (on) NotificationMode.MUTED else NotificationMode.DEFAULT) },
            )
        }
        NotifyModeRow(mode = info.notifications, onSelect = onSetNotifications)

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Pin to top")
            Switch(
                modifier = Modifier.testTag("room-info-pinned"),
                checked = info.pinned,
                onCheckedChange = onSetPinned,
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        // A room with one agent and you in it does not need a list — it
        // needs the *other* participant named, and the count for anything
        // larger. Zero others (a room of one) shows neither section, the same
        // as iOS's `others(info).first` returning `nil`.
        if (others.size > 1) {
            SectionHeader("Members (${info.activeMemberCount})")
            others.forEach { MemberRow(it) }
        } else if (others.size == 1) {
            SectionHeader("Members")
            MemberRow(others[0])
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        SectionHeader("Address")
        info.canonicalAlias?.let { CopyableRow(label = "Alias", value = it, testTag = "room-info-copy-alias") }
        CopyableRow(label = "Room id", value = info.roomId, testTag = "room-info-copy-room-id")

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        TextButton(
            onClick = onLeaveTapped,
            modifier = Modifier.padding(horizontal = 16.dp).testTag("room-info-leave"),
        ) { Text("Leave room", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun HeaderAvatar(initial: String, avatarUri: String?) {
    val bitmap = remember(avatarUri) { avatarUri?.decodeDataUri() }
    Box(
        Modifier
            .size(56.dp)
            .testTag("room-info-avatar")
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
            Text(initial, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun LabeledRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.outline)
        Text(value)
    }
}

/** One member: their name, and the id beneath it — never this account's own row. */
@Composable
private fun MemberRow(member: RoomMemberDto) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(member.displayName ?: member.userId, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (member.displayName != null) {
            Text(
                member.userId,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A label and a value you can take away with you. */
@Composable
private fun CopyableRow(label: String, value: String, testTag: String) {
    val clipboard = LocalClipboardManager.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(
            modifier = Modifier.testTag(testTag),
            onClick = { clipboard.setText(AnnotatedString(value)) },
        ) { Text("Copy") }
    }
}

/**
 * The full four-way choice — "Everything" / "Mentions only" / "Account
 * default" / "Nothing" — beneath the Mute switch, which is only ever a
 * shorthand for two of these four. Named for what each does, not for what it
 * is: "Account default" is a word about the reader's account, the same
 * naming choice `RoomInfoPanel.swift`'s own picker documents making.
 */
@Composable
private fun NotifyModeRow(mode: NotificationMode, onSelect: (NotificationMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .testTag("room-info-notify-mode")
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Notify me about")
            Text(notifyLabel(mode), color = MaterialTheme.colorScheme.outline)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            NotificationMode.entries.forEach { entry ->
                DropdownMenuItem(
                    modifier = Modifier.testTag("room-info-notify-${entry.name}"),
                    text = { Text(notifyLabel(entry)) },
                    onClick = {
                        expanded = false
                        onSelect(entry)
                    },
                )
            }
        }
    }
}

private fun notifyLabel(mode: NotificationMode): String = when (mode) {
    NotificationMode.ALL_MESSAGES -> "Everything"
    NotificationMode.MENTIONS_ONLY -> "Mentions only"
    NotificationMode.DEFAULT -> "Account default"
    NotificationMode.MUTED -> "Nothing"
}
