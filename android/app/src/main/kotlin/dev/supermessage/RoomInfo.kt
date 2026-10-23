package dev.supermessage

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import dev.supermessage.kit.ReportSubject
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.supermessage.kit.ErrorPresenter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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
    /** Whether the core's list has this user blocked; read in composition, so it must be snapshot state. */
    isBlocked: (String) -> Boolean = { false },
    /** Each write answers `null` when it landed, or the refusal in words. */
    onBlock: suspend (String) -> String? = { null },
    onUnblock: suspend (String) -> String? = { null },
    onReport: suspend (ReportSubject, String, Boolean) -> String? = { _, _, _ -> null },
) {
    var info by remember(roomId) { mutableStateOf<RoomInfoDto?>(null) }
    var failure by remember(roomId) { mutableStateOf<String?>(null) }
    var showsLeaveConfirm by remember(roomId) { mutableStateOf(false) }
    // The member whose actions are open, the report sheet's subject, and what
    // opened them (a user id, or [ROOM_OPENER] / [LEAVE_OPENER]) so focus can
    // come back to it when the sheet or dialog closes.
    var actingOn by remember(roomId) { mutableStateOf<RoomMemberDto?>(null) }
    var reporting by remember(roomId) { mutableStateOf<ReportSubject?>(null) }
    var opener by remember(roomId) { mutableStateOf<String?>(null) }
    var safetyFailure by remember(roomId) { mutableStateOf<String?>(null) }
    var safetyBusy by remember(roomId) { mutableStateOf(false) }
    // Members unblocked from this panel: the member's own flag lags the
    // homeserver's echo, so what was just undone wins over it.
    val unblockedHere = remember(roomId) { mutableStateMapOf<String, Unit>() }
    val focusers = remember(roomId) { mutableMapOf<String, FocusRequester>() }
    fun focuserFor(key: String) = focusers.getOrPut(key) { FocusRequester() }
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    // Focus returns to whatever opened a sheet or dialog once none is open.
    val somethingOpen = actingOn != null || reporting != null || showsLeaveConfirm
    LaunchedEffect(somethingOpen) {
        val key = opener
        if (!somethingOpen && key != null) {
            opener = null
            // A closing sheet's window is still handing focus back, so a first
            // request can find nothing to take it: try again for a moment.
            repeat(FOCUS_ATTEMPTS) {
                if (runCatching { focuserFor(key).requestFocus() }.getOrDefault(false)) return@LaunchedEffect
                delay(FOCUS_RETRY_MS)
            }
        }
    }

    fun blockedNow(member: RoomMemberDto): Boolean =
        isBlocked(member.userId) || (member.isIgnored && member.userId !in unblockedHere)

    fun write(member: RoomMemberDto, unblocking: Boolean) {
        actingOn = null
        safetyBusy = true
        scope.launch {
            try {
                val refused = if (unblocking) onUnblock(member.userId) else onBlock(member.userId)
                if (refused != null) {
                    safetyFailure = refused
                } else {
                    if (unblocking) unblockedHere[member.userId] = Unit else unblockedHere.remove(member.userId)
                    haptics.play(Haptic.Success)
                }
            } finally {
                safetyBusy = false
            }
        }
    }

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
                onLeaveTapped = {
                    opener = LEAVE_OPENER
                    showsLeaveConfirm = true
                },
                blockedNow = ::blockedNow,
                safetyFailure = safetyFailure,
                safetyBusy = safetyBusy,
                focuserFor = ::focuserFor,
                onMemberTapped = { member ->
                    safetyFailure = null
                    opener = member.userId
                    actingOn = member
                },
                onReportRoom = {
                    opener = ROOM_OPENER
                    reporting = ReportSubject.Room(roomId, currentInfo.identity.name)
                },
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
            title = { Text("Leave ${info?.name ?: "this room"}?") },
            text = { Text("You'll stop receiving its messages.") },
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

    actingOn?.let { member ->
        val name = member.displayName ?: member.userId
        val blocked = blockedNow(member)
        MemberActionsSheet(
            name = name,
            isAgent = member.isAgent,
            blocked = blocked,
            onBlockOrUnblock = { write(member, unblocking = blocked) },
            onReport = {
                actingOn = null
                reporting = ReportSubject.User(member.userId, name, member.isAgent)
            },
            onDismiss = { actingOn = null },
        )
    }

    reporting?.let { subject ->
        ReportSheet(
            subject = subject,
            isBlocked = isBlocked,
            onSend = { reason, block -> onReport(subject, reason, block) },
            onClose = { reporting = null },
        )
    }
}

private const val FOCUS_ATTEMPTS = 20
private const val FOCUS_RETRY_MS = 50L
private const val ROOM_OPENER = "\u0000report-room"
private const val LEAVE_OPENER = "\u0000leave"

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
    blockedNow: (RoomMemberDto) -> Boolean,
    safetyFailure: String?,
    safetyBusy: Boolean,
    focuserFor: (String) -> FocusRequester,
    onMemberTapped: (RoomMemberDto) -> Unit,
    onReportRoom: () -> Unit,
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
        } else if (others.size == 1) {
            SectionHeader("Members")
        }
        others.forEach { member ->
            val blocked = blockedNow(member)
            MemberRow(
                member = member,
                blocked = blocked,
                onClick = { onMemberTapped(member) },
                enabled = !safetyBusy,
                modifier = Modifier.focusRequester(focuserFor(member.userId)),
            )
        }
        safetyFailure?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag("room-info-safety-failure"),
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        SectionHeader("Address")
        info.canonicalAlias?.let { CopyableRow(label = "Alias", value = it, testTag = "room-info-copy-alias") }
        CopyableRow(label = "Room id", value = info.roomId, testTag = "room-info-copy-room-id")

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        TextButton(
            onClick = onLeaveTapped,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .focusRequester(focuserFor(LEAVE_OPENER))
                .testTag("room-info-leave"),
        ) { Text("Leave room", color = MaterialTheme.colorScheme.error) }
        // Beside Leave: both are ways of being done with a room.
        Text(
            "Report room…",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .focusRequester(focuserFor(ROOM_OPENER))
                .clickable(onClickLabel = REPORT_ROOM_HINT, role = Role.Button, onClick = onReportRoom)
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 14.dp)
                .testTag("room-info-report-room"),
        )
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

private const val REPORT_ROOM_HINT = "Reports this room to your homeserver's administrators"

/**
 * One member: their name, and the id beneath it — and, when it applies, that
 * they are an agent or that this account has blocked them. One element for
 * TalkBack. With an [onClick] it opens the member's actions; without, it is
 * not a control.
 */
@Composable
internal fun MemberRow(
    member: RoomMemberDto,
    blocked: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = SupermessageTheme.colors
    val hint = if (blocked) "Unblock or report" else "Block or report"
    Column(
        modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(enabled = enabled, onClickLabel = hint, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) {}
            .testTag("member-${member.userId}"),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                member.displayName ?: member.userId,
                color = if (blocked) colors.contentMuted else colors.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (member.isAgent) AgentTag()
            if (blocked) {
                Text(
                    "Blocked",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("member-blocked-${member.userId}"),
                )
            }
        }
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

/**
 * What can be done with a member: block or unblock, report, or nothing. The
 * title is the name, the message says what the block does. Scrolls, so every
 * action stays reachable at the largest font.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemberActionsSheet(
    name: String,
    isAgent: Boolean,
    blocked: Boolean,
    onBlockOrUnblock: () -> Unit,
    onReport: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("member-actions"),
    ) {
        MemberActionsBody(name, isAgent, blocked, onBlockOrUnblock, onReport, onDismiss)
    }
}

/** The sheet's contents, apart from the sheet chrome, so a preview can draw them. */
@Composable
internal fun MemberActionsBody(
    name: String,
    isAgent: Boolean,
    blocked: Boolean,
    onBlockOrUnblock: () -> Unit,
    onReport: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 8.dp),
    ) {
        Text(
            name,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 4.dp)
                .semantics { heading() }
                .testTag("member-actions-title"),
        )
        Text(
            if (blocked) {
                "Unblocking shows their messages again, including ones sent while they were blocked."
            } else {
                ReportCopy.blockConsequence(isAgent)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
        )
        MemberAction(
            if (blocked) "Unblock $name" else "Block $name",
            destructive = !blocked,
            tag = if (blocked) "member-unblock" else "member-block",
            onClick = onBlockOrUnblock,
        )
        MemberAction("Report $name…", destructive = true, tag = "member-report", onClick = onReport)
        MemberAction("Cancel", destructive = false, tag = "member-actions-cancel", onClick = onCancel)
    }
}

@Composable
private fun MemberAction(label: String, destructive: Boolean, tag: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .testTag(tag),
    )
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
