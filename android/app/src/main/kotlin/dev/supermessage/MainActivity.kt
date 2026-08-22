package dev.supermessage

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.supermessage.kit.RosterArrangement
import dev.supermessage.kit.RosterChoice
import dev.supermessage.kit.Session
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uniffi.supermessage_core.RoomAffordance

/**
 * The one `DataStore<Preferences>` this app opens, named for what it stores
 * (not `dev.supermessage.kit.RosterChoice`'s DataStore in any other module —
 * `:kit` declares no dependency on androidx.datastore, by design). The
 * `preferencesDataStore` delegate hands back the same instance for the
 * lifetime of the process no matter how many times this property is read, so
 * constructing a fresh [RosterPreferences] from it on every recomposition
 * (below) is cheap: it always wraps the one underlying store.
 */
private val Context.rosterDataStore: DataStore<Preferences> by preferencesDataStore(name = "roster")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SupermessageTheme {
                Surface {
                    val vm: SessionViewModel = viewModel()

                    // Built here, not in the ViewModel: RosterPreferences
                    // wraps a DataStore, which needs a Context, and this is
                    // the one place in the tree that has one to give it.
                    val prefs = remember { RosterPreferences(applicationContext.rosterDataStore) }

                    AppRoot(session = vm.session, prefs = prefs)
                }
            }
        }
    }
}

/**
 * Everything `MainActivity.onCreate` used to build directly inside
 * `setContent`, pulled out so it can be composed against a real [Session] —
 * one backed by a fake `CoreInterface`, in tests — rather than only ever the
 * real one [SessionViewModel] constructs. Nothing here changed shape in the
 * extraction: every callback below still reaches [session] the same way it
 * always did, just spelled `session` instead of `vm.session`.
 *
 * This is what makes the reachability tests in `RosterReachabilityTest`
 * possible at all: they call this function directly, the same way
 * `RootScaffoldTest` calls `RootScaffold` directly, rather than constructing
 * `NewRoomPanel` or `InvitationView` in isolation — the shape the brief for
 * this task calls out as the exact thing that let both panels sit unreachable
 * behind 432 lines of green tests. `internal`, not `private`: androidTest
 * shares this module's friend path, the same way `AndroidSecretStoreTest`
 * already reaches `AndroidSecretStore.rawForTest`.
 */
@Composable
internal fun AppRoot(session: Session, prefs: RosterPreferences) {
    val phase by session.phase.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        if (phase == Session.Phase.STARTING) session.start()
    }

    // Seeded once from prefs.homeserver, write-through from
    // then on — see SeededHomeserver.kt for why this is its
    // own seam rather than inline state here.
    val homeserverField = rememberSeededHomeserver(prefs)

    val failure by session.failure.collectAsStateWithLifecycle()

    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // What the extra pane (see RootScaffold's own KDoc on
    // `extraPaneContent`) is currently showing — at most one
    // of room info, search or the account panel at a time,
    // since `ListDetailPaneScaffold` has exactly one Extra
    // slot. Room info is set when a roster row's avatar is
    // tapped (RoomRow.kt's own onOpenInfo); search and
    // account are reached from the two buttons above the
    // roster below. Cleared alongside each panel's own
    // "Done"/"Cancel" tap so a widened shell does not show a
    // stale panel nobody asked about again.
    var extraPanel by remember { mutableStateOf<ExtraPanel?>(null) }

    // This account's own id, read once per sign-in — see
    // RoomInfoPanel's own KDoc for why it needs this at all
    // (excluding the reader from their own member list, the
    // one thing that panel decides for itself rather than
    // taking from the core).
    var accountUserId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(phase) {
        if (phase == Session.Phase.SIGNED_IN) {
            accountUserId = session.account()?.userId
        }
    }

    // The roster's own three remembered choices — see
    // RosterPreferences for why each defaults the way it does.
    val rosterView by prefs.view.collectAsStateWithLifecycle(initialValue = RosterChoice.WAITING)
    val showsInvitations by prefs.showsInvitations.collectAsStateWithLifecycle(initialValue = false)
    val showsState by prefs.showsState.collectAsStateWithLifecycle(initialValue = true)

    // Reactive, not a one-time snapshot: collectAsStateWithLifecycle
    // is what makes a later RoomsDiff actually redraw this
    // tree — see Roster.kt's own KDoc for the failure mode a
    // plain `.value` read here would reproduce.
    val rooms by session.rooms.rooms.collectAsStateWithLifecycle()
    val avatarCache by session.avatars.cache.collectAsStateWithLifecycle()

    // Re-read every 30 seconds so a roster that said "3m" says
    // "4m" without anything else having changed — the clock
    // ticks and is injected, never read fresh inside Roster.
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = Instant.now()
        }
    }

    val sections = remember(rooms, rosterView, showsInvitations, now) {
        RosterArrangement.sections(
            rows = rooms, view = rosterView, showsInvitations = showsInvitations, now = now,
        )
    }
    val hiddenInvitations = remember(rooms, showsInvitations) {
        RosterArrangement.hiddenInvitations(rows = rooms, showsInvitations = showsInvitations)
    }

    RootScaffold(
        phase = phase,
        listPaneContent = { _, openDetail, openInfo ->
            Column(Modifier.fillMaxSize()) {
                // Search and the account panel share the
                // Extra pane with room info, so both are
                // reached the same way room info already is:
                // set which panel `extraPaneContent` below
                // should show, then ask RootScaffold to open
                // it. Plain text buttons, not icons — this
                // app declares no material-icons-extended
                // dependency, and these two are the whole
                // affordance for now.
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    // The new-room affordance: unreachable until this task
                    // (see AppRoot's own KDoc) — `NewRoomPanel` shared the
                    // Extra pane's ExtraPanel machinery already built for
                    // room info/search/account, but nothing ever set
                    // `extraPanel` to show it.
                    TextButton(
                        onClick = { extraPanel = ExtraPanel.NewRoom; openInfo() },
                        modifier = Modifier.testTag("roster-open-new-room"),
                    ) { Text("New room") }
                    TextButton(
                        onClick = { extraPanel = ExtraPanel.Search; openInfo() },
                        modifier = Modifier.testTag("roster-open-search"),
                    ) { Text("Search") }
                    TextButton(
                        onClick = { extraPanel = ExtraPanel.Account; openInfo() },
                        modifier = Modifier.testTag("roster-open-account"),
                    ) { Text("Account") }
                }

                Roster(
                    sections = sections,
                    hiddenInvitations = hiddenInvitations,
                    now = now,
                    avatarUri = { roomId -> avatarCache[roomId] },
                    showsState = showsState,
                    hidesHost = rosterView == RosterChoice.MACHINE,
                    onOpenRoom = { roomId ->
                        session.rooms.select(roomId)
                        scope.launch { session.open(roomId) }
                        openDetail()
                    },
                    // A dead affordance from A1 until Task 2:
                    // nothing threaded the tapped room any
                    // further, because the info pane itself was
                    // still RootScaffold's default placeholder.
                    // `extraPanel` is what `extraPaneContent`
                    // below reads to know which room it is about.
                    onOpenInfo = { roomId ->
                        extraPanel = ExtraPanel.RoomInfo(roomId)
                        openInfo()
                    },
                    onLoadAvatar = { roomId -> session.avatars.load(roomId) },
                    // The other half of this task: the roster already says
                    // out loud that invitations are being withheld (see
                    // Roster.kt's own KDoc on this parameter) — this is what
                    // lets tapping that admission actually do something
                    // about it, rather than only ever naming a count nobody
                    // can act on.
                    onRevealInvitations = { scope.launch { prefs.setShowsInvitations(true) } },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        },
        detailPaneContent = { _ ->
            // The room this pane is showing, reactively:
            // `TimelineStore.roomId` is set the moment
            // `Session.open` subscribes, before the first
            // diff for it has even arrived.
            val roomId by session.timeline.roomId.collectAsStateWithLifecycle()

            // What the core already decided about this room — never
            // re-derived here. An invited room shows InvitationView in
            // place of the composer (and InvitationEmptyTimeline in place
            // of history) purely because its own row already carries
            // `RoomAffordance.RESPOND_TO_INVITATION`; this file adds no
            // second opinion about which rooms are invitations.
            val currentRow = roomId?.let { id -> rooms.firstOrNull { it.room.id == id } }
            val isInvitation = currentRow?.affordance == RoomAffordance.RESPOND_TO_INVITATION

            // Trigger 1 of spec §6's two: on room change,
            // mark it read. Trigger 2 — on any history
            // change while at the newest end — is Timeline's
            // own job (see its class doc): it can see `rows`
            // and `isAtNewest` together, neither of which
            // this call site has reason to duplicate.
            //
            // Not for an invitation: membership is `invite`, so there is no
            // history here for "read" to mean anything about yet (see
            // InvitationEmptyTimeline's own KDoc).
            LaunchedEffect(roomId, isInvitation) {
                if (roomId != null && !isInvitation) session.timeline.markRead()
            }

            val currentRoomId = roomId
            if (currentRoomId == null) {
                Box(
                    Modifier.fillMaxSize().testTag("pane-timeline"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Select a room")
                }
            } else if (isInvitation) {
                Column(modifier = Modifier.fillMaxSize().testTag("pane-timeline")) {
                    InvitationEmptyTimeline(modifier = Modifier.weight(1f).fillMaxWidth())
                    InvitationView(
                        roomId = currentRoomId,
                        roomName = currentRow?.room?.name ?: currentRoomId,
                        inviter = session::inviter,
                        joinRoom = session::joinRoom,
                        leaveRoom = session::leaveRoom,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                val items by session.timeline.items.collectAsStateWithLifecycle()
                val isPaginating by session.timeline.isPaginating.collectAsStateWithLifecycle()
                val canPaginate by session.timeline.canPaginate.collectAsStateWithLifecycle()

                // `TypingStore.line` is a computed property,
                // not a StateFlow — `typers` is what is
                // actually observable, so that is what is
                // collected; `line` is re-read from it every
                // time `typers` changes.
                val typers by session.typing.typers.collectAsStateWithLifecycle()
                val typingLine = remember(typers) { session.typing.line }

                val liveAnswer by session.live.answer.collectAsStateWithLifecycle()
                val liveThought by session.live.thought.collectAsStateWithLifecycle()
                val liveTools by session.live.tools.collectAsStateWithLifecycle()
                val liveFinished by session.live.finished.collectAsStateWithLifecycle()

                // Reply/edit/attachment: reactive so a long
                // press elsewhere (Timeline's onRowLongPress,
                // below) or a picked photo shows up here the
                // moment the store records it.
                val replyTargets by session.replies.targets.collectAsStateWithLifecycle()
                val editTargets by session.edits.targets.collectAsStateWithLifecycle()
                val attachment by session.staged.file.collectAsStateWithLifecycle()
                val editing = editTargets[currentRoomId]

                // The composer's own text, held here rather
                // than mirrored live off `DraftStore` — the
                // same shape `ComposerView.swift`'s `@State
                // private var text` takes, seeded once per
                // room (`remember(currentRoomId)` standing in
                // for that file's `.task(id: roomId)`) rather
                // than re-derived from the store on every
                // recomposition. That distinction is what
                // makes editing work at all: see
                // `onTextChange` below for why a live mirror
                // would fight it.
                var text by remember(currentRoomId) {
                    mutableStateOf(session.drafts.draft(currentRoomId))
                }
                var sending by remember(currentRoomId) { mutableStateOf(false) }
                var composerFailure by remember(currentRoomId) { mutableStateOf<String?>(null) }

                Column(modifier = Modifier.fillMaxSize().testTag("pane-timeline")) {
                    Timeline(
                        rows = items,
                        typingLine = typingLine,
                        isPaginating = isPaginating,
                        canPaginate = canPaginate,
                        onPaginate = { scope.launch { session.timeline.paginateBack() } },
                        onMarkRead = { scope.launch { session.timeline.markRead() } },
                        liveAnswer = liveAnswer,
                        liveThought = liveThought,
                        liveTools = liveTools,
                        liveFinished = liveFinished,
                        onReact = { row, key ->
                            scope.launch {
                                session.toggleReaction(row.item.eventId, key, currentRoomId)
                            }
                        },
                        onRowLongPress = { row ->
                            // Own + editable rewrites the
                            // message; anything else a long
                            // press has something to offer
                            // for is a reply — see Timeline's
                            // own class doc for why the
                            // gesture only exists at all when
                            // one of the two is true.
                            if (row.item.isOwn && row.item.editable) {
                                session.edits.start(row, currentRoomId)
                            } else if (row.canReplyOrReact) {
                                session.replies.start(row, currentRoomId)
                            }
                        },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )

                    Composer(
                        text = text,
                        onTextChange = { next ->
                            text = next
                            // Not while editing: the composer
                            // is holding an existing message,
                            // and writing that over the draft
                            // would destroy whatever was
                            // being written before the edit
                            // began — the exact guard
                            // `ComposerView.swift`'s own
                            // `onChange(of: text)` carries.
                            if (editing == null) {
                                session.drafts.set(next, currentRoomId)
                            }
                            scope.launch { session.setTyping(next.isNotBlank(), currentRoomId) }
                        },
                        onSend = {
                            scope.launch {
                                sending = true
                                try {
                                    val currentEdit = editing
                                    if (currentEdit != null) {
                                        // The reader's text stays in the
                                        // composer when this fails — an
                                        // edit that vanished into an error
                                        // would have silently discarded
                                        // what they wrote.
                                        val ok = session.edit(
                                            eventId = currentEdit.eventId,
                                            body = text,
                                            roomId = currentRoomId,
                                        )
                                        if (ok) {
                                            composerFailure = null
                                            session.edits.cancel(currentRoomId)
                                            text = session.drafts.draft(currentRoomId)
                                        } else {
                                            composerFailure = "Couldn't save that edit."
                                        }
                                    } else {
                                        val refusal = session.send(text, currentRoomId)
                                        if (refusal == null) {
                                            composerFailure = null
                                            text = ""
                                            session.drafts.clear(currentRoomId)
                                        } else {
                                            composerFailure = refusal
                                        }
                                    }
                                } finally {
                                    sending = false
                                }
                            }
                        },
                        sending = sending,
                        failure = composerFailure,
                        replyTo = replyTargets[currentRoomId],
                        onCancelReply = { session.replies.cancel(currentRoomId) },
                        editing = editing,
                        onCancelEdit = { session.edits.cancel(currentRoomId) },
                        attachment = attachment,
                        onAttach = { path ->
                            scope.launch {
                                val refusal = session.staged.stage(path, currentRoomId)
                                if (refusal != null) composerFailure = refusal
                            }
                        },
                        onDiscardAttachment = { scope.launch { session.staged.discard() } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        extraPaneContent = { _, closeInfo ->
            Box(Modifier.fillMaxSize().testTag("pane-info")) {
                // Exhaustive over ExtraPanel's own four
                // cases plus `null` (nothing asked for it
                // yet) — not a core sealed class, so an
                // `else` here would be fine, but there is
                // nothing this file wants to fold together.
                when (val panel = extraPanel) {
                    ExtraPanel.NewRoom ->
                        NewRoomPanel(
                            onOpen = { roomId ->
                                session.rooms.select(roomId)
                                scope.launch { session.open(roomId) }
                            },
                            onClose = {
                                extraPanel = null
                                closeInfo()
                            },
                            loadPeople = session::people,
                            openConversation = session::openConversation,
                            joinByAlias = session::joinByAlias,
                        )

                    is ExtraPanel.RoomInfo ->
                        RoomInfoPanel(
                            roomId = panel.roomId,
                            accountUserId = accountUserId,
                            avatarUri = avatarCache[panel.roomId],
                            onClose = {
                                extraPanel = null
                                closeInfo()
                            },
                            loadInfo = { session.roomInfo(panel.roomId) },
                            onSetNotifications = { mode ->
                                session.setNotifications(mode, panel.roomId)
                            },
                            onSetPinned = { pinned ->
                                session.setPinned(pinned, panel.roomId)
                            },
                            onLeaveRoom = { session.leaveRoom(panel.roomId) },
                        )

                    ExtraPanel.Search ->
                        SearchPanel(
                            scope = null,
                            onOpen = { roomId ->
                                session.rooms.select(roomId)
                                scope.launch { session.open(roomId) }
                            },
                            onClose = {
                                extraPanel = null
                                closeInfo()
                            },
                            search = session::search,
                            roomName = { roomId -> rooms.firstOrNull { it.room.id == roomId }?.room?.name },
                        )

                    ExtraPanel.Account ->
                        AccountPanel(
                            loadAccount = session::account,
                            onSignOut = session::signOut,
                            onClose = {
                                extraPanel = null
                                closeInfo()
                            },
                        )

                    null -> {}
                }
            }
        },
        onBackFromDetail = {
            // The verb RoomsStore.deselect() exists for: on a
            // phone the roster is the previous screen, not a
            // column beside the room, so nothing should stay
            // selected once system back has returned to it.
            session.rooms.deselect()
        },
        signedOutContent = {
            LoginScreen(
                homeserver = homeserverField.value,
                onHomeserverChange = homeserverField::onChange,
                failure = failure,
                busy = busy,
                onSignIn = { username, password ->
                    // busy lives here, not in Session: it
                    // guards this form against a double tap,
                    // it is not part of what Session itself
                    // tracks (see Session.kt's own
                    // `phase`/`failure`, neither of which
                    // serves that purpose while a sign-in is
                    // in flight).
                    scope.launch {
                        busy = true
                        try {
                            session.signIn(
                                homeserver = homeserverField.value,
                                username = username,
                                password = password,
                            )
                        } finally {
                            busy = false
                        }
                    }
                },
            )
        },
    )
}

/**
 * Which of a new room, room info, search or the account panel
 * `RootScaffold`'s single Extra pane is currently showing — `null` when none
 * of the four was asked for. An app-level type, not a core sealed class: the
 * `when` over it above may use `else` freely, though it does not.
 *
 * `internal`, not `private`: `AppRoot` (this file's own KDoc explains why it
 * is `internal` too) is driven directly from `RosterReachabilityTest`, and
 * that `when` needs to see every case of this type to stay exhaustive there
 * exactly as it does here.
 */
internal sealed class ExtraPanel {
    object NewRoom : ExtraPanel()
    data class RoomInfo(val roomId: String) : ExtraPanel()
    object Search : ExtraPanel()
    object Account : ExtraPanel()
}
