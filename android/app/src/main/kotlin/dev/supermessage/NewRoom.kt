package dev.supermessage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.supermessage.kit.Session
import uniffi.supermessage_core.PersonDto
import uniffi.supermessage_ffi.peopleMatching
import kotlinx.coroutines.launch

/**
 * Start a conversation — the port of
 * `apple/Supermessage/Panels/NewRoomPanel.swift`.
 *
 * ## No store behind this one
 *
 * The same shape [RoomInfoPanel] and [SearchPanel] already document: the
 * directory and an address join are each a request-response round trip, not
 * a diff this pane could subscribe to, so this composable holds its own
 * `people`/`query`/`failure` state rather than reading a `StateFlow`.
 *
 * ## Filtering is the core's
 *
 * [peopleMatching] — [uniffi.supermessage_ffi.peopleMatching] — is called
 * directly on every keystroke, exactly as `NewRoomPanel.swift`'s own
 * `matches` computed property calls it. It matches on more than a person's
 * display name (see `supermessage-core::people::matching`): a query that
 * only hits an agent's harness or host still has to surface that row, so a
 * `.filter { it.name.contains(query) }` written here instead would quietly
 * disagree with the core about what "matches" means — the reason this file
 * has no local filter of its own.
 *
 * ## Direct vs. new
 *
 * [openConversation] stands in for `Session.openConversation`, which already
 * tries `directRoomWith` before ever calling `createRoom` — this file makes
 * that same one call per tap and does not re-decide it: there is no second
 * `createRoom(name, invite = [person.userId])` written here for the case
 * `directRoomWith` already answered.
 *
 * @param onOpen Opens the room a chosen person (or a joined address) lands in.
 * @param onClose Abandons the screen — Cancel, and also what a successful
 *   open does once the room is open behind it.
 * @param loadPeople Everyone this account already shares a room with —
 *   `Session.people()`, which already swallows a core failure into an empty
 *   list, so this file adds no second failure path over the directory load.
 * @param openConversation Reuses the existing direct room with a person, or
 *   creates one — `Session.openConversation`.
 * @param joinByAlias Joins a room named by alias or id — `Session.joinByAlias`.
 */
@Composable
fun NewRoomPanel(
    onOpen: (roomId: String) -> Unit,
    onClose: () -> Unit,
    loadPeople: suspend () -> List<PersonDto>,
    openConversation: suspend (PersonDto) -> Session.Outcome,
    joinByAlias: suspend (String) -> Session.Outcome,
    modifier: Modifier = Modifier,
) {
    var people by remember { mutableStateOf<List<PersonDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var busyWith by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var showsAddress by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // See this file's KDoc: the core's own matcher, not a re-derived one.
    val matches = remember(people, query) { peopleMatching(people = people, query = query) }

    LaunchedEffect(Unit) {
        people = loadPeople()
        loading = false
    }

    suspend fun open(person: PersonDto) {
        busyWith = person.userId
        failure = null
        when (val outcome = openConversation(person)) {
            is Session.Outcome.Success -> {
                onOpen(outcome.roomId)
                onClose()
            }
            is Session.Outcome.Failure -> failure = outcome.message
        }
        busyWith = null
    }

    Column(modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (showsAddress) "Join a room" else "New conversation",
                style = MaterialTheme.typography.titleMedium,
            )
            TextButton(onClick = onClose, modifier = Modifier.testTag("new-room-cancel")) { Text("Cancel") }
        }

        failure?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp).testTag("new-room-failure"),
            )
        }

        if (showsAddress) {
            JoinByAddressBody(
                joinByAlias = joinByAlias,
                onOpen = onOpen,
                onBack = { showsAddress = false },
            )
        } else {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("new-room-query"),
                placeholder = { Text("Name, machine, or @user:server") },
                singleLine = true,
            )

            Box(Modifier.fillMaxSize()) {
                when {
                    loading ->
                        Column(
                            Modifier.fillMaxSize().padding(24.dp).testTag("new-room-loading"),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CircularProgressIndicator()
                            Text(
                                "Looking for who you know",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                        }

                    matches.isEmpty() ->
                        Column(
                            Modifier.fillMaxSize().padding(24.dp).testTag("new-room-empty"),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                if (query.isEmpty()) "Nobody yet" else "No one matching $query",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                if (query.isEmpty()) {
                                    "Agents and people you share a room with appear here."
                                } else {
                                    "Try a name, a machine, or a full address."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }

                    else ->
                        LazyColumn(Modifier.fillMaxSize().testTag("new-room-list")) {
                            items(matches, key = { it.userId }) { person ->
                                PersonRow(
                                    person = person,
                                    busy = busyWith == person.userId,
                                    enabled = busyWith == null,
                                    onClick = { coroutineScope.launch { open(person) } },
                                )
                            }
                        }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .testTag("new-room-join-by-address")
                    .clickable { showsAddress = true }
                    .padding(16.dp),
            ) {
                Text("Join by address", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** One row of the directory: who they are, and where they run. */
@Composable
private fun PersonRow(person: PersonDto, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .testTag("new-room-person-${person.userId}")
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(person.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle(person),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (busy) CircularProgressIndicator(modifier = Modifier.testTag("new-room-person-busy"))
    }
}

/**
 * The runtime where there is one, the address where there is not — the same
 * choice `NewRoomPanel.swift`'s own `PersonRow.subtitle` makes.
 */
private fun subtitle(person: PersonDto): String {
    val runtime = person.runtime ?: return person.userId
    return "${runtime.harness} on ${runtime.host}"
}

/**
 * Join a room you already know the address of — its own section rather than
 * its own screen, since Compose has no equivalent of a SwiftUI sheet this
 * file needs to reach for.
 */
@Composable
private fun JoinByAddressBody(
    joinByAlias: suspend (String) -> Session.Outcome,
    onOpen: (roomId: String) -> Unit,
    onBack: () -> Unit,
) {
    var address by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    suspend fun join() {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return
        busy = true
        failure = null
        when (val outcome = joinByAlias(trimmed)) {
            is Session.Outcome.Success -> onOpen(outcome.roomId)
            is Session.Outcome.Failure -> failure = outcome.message
        }
        busy = false
    }

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            "An alias like #general:supermessage.dev, or a room id starting with !.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("new-room-address-field"),
            placeholder = { Text("#general:supermessage.dev") },
            singleLine = true,
        )
        failure?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp).testTag("new-room-address-failure"),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("new-room-address-back")) { Text("Back") }
            TextButton(
                onClick = { coroutineScope.launch { join() } },
                enabled = !busy && address.isNotBlank(),
                modifier = Modifier.testTag("new-room-address-join"),
            ) {
                if (busy) CircularProgressIndicator(modifier = Modifier.testTag("new-room-address-busy"))
                else Text("Join")
            }
        }
    }
}
