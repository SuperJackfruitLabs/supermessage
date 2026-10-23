package dev.supermessage

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.supermessage.kit.RoomToggles
import dev.supermessage.kit.RosterArrangement
import dev.supermessage.kit.RosterChoice
import dev.supermessage.kit.Session
import dev.supermessage.kit.stores.ConnectionStore.Connection
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import uniffi.supermessage_core.NotificationMode
import uniffi.supermessage_core.RoomAffordance
import uniffi.supermessage_core.RosterFilter
import uniffi.supermessage_core.RoomRow as RoomRowDto
import uniffi.supermessage_core.RosterRow

/** A room's notification and pin settings as a swipe last heard them (`RoomListView.swift:312-316`). */
internal data class KnownSettings(val muted: Boolean, val pinned: Boolean)

/**
 * The list pane: the title (a menu of spaces), the connection line under it,
 * the buttons, and the roster with its swipes. Moved out of `AppRoot`.
 */
@Composable
internal fun RosterPane(
    session: Session,
    prefs: RosterPreferences,
    rooms: List<RoomRowDto>,
    avatarCache: Map<String, String>,
    now: Instant,
    openDetail: () -> Unit,
    openPanel: (ExtraPanel) -> Unit,
    /** The account's initial, for the toolbar's leading button. */
    accountInitial: String,
    /** Whether the roster has been read at all; "No rooms yet" waits for it. */
    loaded: Boolean = true,
    /** A pull on the list: the shell takes a fresh `now`, then re-reads the roster. */
    onRefresh: suspend () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val storedView by prefs.view.collectAsStateWithLifecycle(initialValue = RosterChoice.WAITING)
    // A stored arrangement that is no longer offered (Machine) reads as Waiting.
    val rosterView = RosterChoice.offeredOrDefault(storedView)
    val filter by prefs.filter.collectAsStateWithLifecycle(initialValue = RosterFilter.ALL)
    val showsState by prefs.showsState.collectAsStateWithLifecycle(initialValue = true)
    val spaces by session.spaces.spaces.collectAsStateWithLifecycle()
    val selectedSpace by session.spaces.selectedId.collectAsStateWithLifecycle()
    val connectionState by session.connection.state.collectAsStateWithLifecycle()
    val connectionMessage by session.connection.message.collectAsStateWithLifecycle()

    // Not stored: closed again after a restart, as on iOS.
    var invitationsOpen by rememberSaveable { mutableStateOf(false) }

    // Invitations are never in the core's sections here; they are the row above.
    val sections = remember(rooms, rosterView, now) {
        RosterArrangement.sections(rows = rooms, view = rosterView, showsInvitations = false, now = now)
    }
    val invitations = remember(rooms, rosterView, now) {
        RosterArrangement.sections(rows = rooms, view = rosterView, showsInvitations = true, now = now)
            .flatMap { it.rows }
            .filter { it.row.affordance == RoomAffordance.RESPOND_TO_INVITATION }
    }

    // What the last swipe learned, per sign-in: a new session starts empty.
    val known = remember(session) { mutableStateMapOf<String, KnownSettings>() }
    val colors = SupermessageTheme.colors

    Column(Modifier.fillMaxSize()) {
        // The account at the leading edge, compose at the trailing (`AppDestination.swift:48-107`);
        // the title, a menu of spaces, between them.
        ChatsToolbar(
            accountInitial = accountInitial,
            onAccount = { openPanel(ExtraPanel.Account) },
            onCompose = { openPanel(ExtraPanel.NewRoom) },
            title = {
                Row(Modifier.testTag("roster-title")) {
                    SpaceMenu(
                        spaces = spaces,
                        selectedId = selectedSpace,
                        allCount = rooms.size,
                        isInvitation = session.spaces::isInvitation,
                        onSelect = { id -> scope.launch { session.spaces.select(id) } },
                    )
                }
            },
        )
        // One line, like iOS's: it sits under the title, not above the roster.
        ConnectionBar(
            state = connectionState,
            message = connectionMessage,
            worthShowing = connectionState != Connection.Live,
        )

        Refreshable(onRefresh = onRefresh, modifier = Modifier.weight(1f).fillMaxWidth()) {
        Roster(
            sections = sections,
            invitations = invitations,
            invitationsOpen = invitationsOpen,
            onToggleInvitations = { invitationsOpen = !invitationsOpen },
            now = now,
            avatarUri = { roomId -> avatarCache[roomId] },
            showsState = showsState,
            onOpenRoom = { roomId ->
                session.rooms.select(roomId)
                scope.launch { session.open(roomId) }
                openDetail()
            },
            onOpenInfo = { roomId -> openPanel(ExtraPanel.RoomInfo(roomId)) },
            onLoadAvatar = { roomId -> session.avatars.load(roomId) },
            leadingSwipe = { entry ->
                if (entry.row.affordance != RoomAffordance.COMPOSE) null
                else SwipeAction("Mark read", colors.accent) { session.rooms.markRead(entry.row.room.id) }
            },
            trailingSwipes = { entry -> swipesFor(entry, session, known, colors) },
            modifier = Modifier.fillMaxSize(),
            filter = filter,
            onFilter = { next -> scope.launch { prefs.setFilter(next) } },
            loaded = loaded,
        )
        }
    }
}

private fun swipesFor(
    entry: RosterRow,
    session: Session,
    known: MutableMap<String, KnownSettings>,
    colors: SupermessageColorRoles,
): List<SwipeAction> {
    val roomId = entry.row.room.id
    return when (entry.row.affordance) {
        // Declining is leaving a room one was only invited to. iOS asks
        // nothing and plays no haptic for it, so neither does this.
        RoomAffordance.RESPOND_TO_INVITATION -> listOf(
            SwipeAction("Decline", colors.danger, destructive = true) {
                session.leaveRoom(roomId)
                false
            },
        )
        RoomAffordance.COMPOSE -> {
            val settings = known[roomId]
            listOf(
                SwipeAction(if (settings?.muted == true) "Unmute" else "Mute", colors.contentMuted) {
                    // Ask the core first: the row does not carry the setting, and
                    // toggling one this view only believes it knows is how a
                    // "Mute" button unmutes something.
                    val info = readInfo(session, roomId) ?: return@SwipeAction false
                    val next = RoomToggles.nextNotificationMode(info.notifications)
                    val landed = session.setNotifications(next, roomId)
                    if (landed) known[roomId] = KnownSettings(muted = next == NotificationMode.MUTED, pinned = info.pinned)
                    landed
                },
                SwipeAction(if (settings?.pinned == true) "Unpin" else "Pin", colors.ok) {
                    val info = readInfo(session, roomId) ?: return@SwipeAction false
                    val next = !info.pinned
                    val landed = session.setPinned(next, roomId)
                    if (landed) {
                        known[roomId] = KnownSettings(muted = info.notifications == NotificationMode.MUTED, pinned = next)
                    }
                    landed
                },
            )
        }
        else -> emptyList()
    }
}

private suspend fun readInfo(session: Session, roomId: String) = try {
    session.roomInfo(roomId)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}
