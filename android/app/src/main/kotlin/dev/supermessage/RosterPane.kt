package dev.supermessage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.supermessage.kit.RosterArrangement
import dev.supermessage.kit.RosterChoice
import dev.supermessage.kit.Session
import dev.supermessage.kit.stores.ConnectionStore.Connection
import java.time.Instant
import kotlinx.coroutines.launch
import uniffi.supermessage_core.RoomAffordance
import uniffi.supermessage_core.RoomRow as RoomRowDto

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
) {
    val scope = rememberCoroutineScope()
    val rosterView by prefs.view.collectAsStateWithLifecycle(initialValue = RosterChoice.WAITING)
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

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
            Row(Modifier.testTag("roster-title")) {
                SpaceMenu(
                    spaces = spaces,
                    selectedId = selectedSpace,
                    allCount = rooms.size,
                    isInvitation = session.spaces::isInvitation,
                    onSelect = { id -> scope.launch { session.spaces.select(id) } },
                )
            }
        }
        // One line, like iOS's: it sits under the title, not above the roster.
        ConnectionBar(
            state = connectionState,
            message = connectionMessage,
            worthShowing = connectionState != Connection.Live,
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = { openPanel(ExtraPanel.NewRoom) },
                modifier = Modifier.testTag("roster-open-new-room"),
            ) { Text("New room") }
            TextButton(
                onClick = {
                    // Opened with a room selected, search offers that room,
                    // narrowed (`SplitShell.swift:121-127`).
                    val open = session.rooms.selectedRow
                    openPanel(
                        ExtraPanel.Search(open?.let { SearchPanelScope(it.room.id, it.identity.name) }),
                    )
                },
                modifier = Modifier.testTag("roster-open-search"),
            ) { Text("Search") }
            TextButton(
                onClick = { openPanel(ExtraPanel.Account) },
                modifier = Modifier.testTag("roster-open-account"),
            ) { Text("Account") }
        }

        Roster(
            sections = sections,
            invitations = invitations,
            invitationsOpen = invitationsOpen,
            onToggleInvitations = { invitationsOpen = !invitationsOpen },
            now = now,
            avatarUri = { roomId -> avatarCache[roomId] },
            showsState = showsState,
            hidesHost = rosterView == RosterChoice.MACHINE,
            onOpenRoom = { roomId ->
                session.rooms.select(roomId)
                scope.launch { session.open(roomId) }
                openDetail()
            },
            onOpenInfo = { roomId -> openPanel(ExtraPanel.RoomInfo(roomId)) },
            onLoadAvatar = { roomId -> session.avatars.load(roomId) },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}
