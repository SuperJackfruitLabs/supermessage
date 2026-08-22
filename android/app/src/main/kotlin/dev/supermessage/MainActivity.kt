package dev.supermessage

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
            MaterialTheme {
                Surface {
                    val vm: SessionViewModel = viewModel()
                    val phase by vm.session.phase.collectAsStateWithLifecycle()
                    LaunchedEffect(Unit) {
                        if (phase == Session.Phase.STARTING) vm.session.start()
                    }

                    // Built here, not in the ViewModel: RosterPreferences
                    // wraps a DataStore, which needs a Context, and this is
                    // the one place in the tree that has one to give it.
                    val prefs = remember { RosterPreferences(applicationContext.rosterDataStore) }

                    // Seeded once from prefs.homeserver, write-through from
                    // then on — see SeededHomeserver.kt for why this is its
                    // own seam rather than inline state here.
                    val homeserverField = rememberSeededHomeserver(prefs)

                    val failure by vm.session.failure.collectAsStateWithLifecycle()
                    var busy by remember { mutableStateOf(false) }
                    val scope = rememberCoroutineScope()

                    // The roster's own three remembered choices — see
                    // RosterPreferences for why each defaults the way it does.
                    val rosterView by prefs.view.collectAsStateWithLifecycle(initialValue = RosterChoice.WAITING)
                    val showsInvitations by prefs.showsInvitations.collectAsStateWithLifecycle(initialValue = false)
                    val showsState by prefs.showsState.collectAsStateWithLifecycle(initialValue = true)

                    // Reactive, not a one-time snapshot: collectAsStateWithLifecycle
                    // is what makes a later RoomsDiff actually redraw this
                    // tree — see Roster.kt's own KDoc for the failure mode a
                    // plain `.value` read here would reproduce.
                    val rooms by vm.session.rooms.rooms.collectAsStateWithLifecycle()
                    val avatarCache by vm.session.avatars.cache.collectAsStateWithLifecycle()

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
                            Roster(
                                sections = sections,
                                hiddenInvitations = hiddenInvitations,
                                now = now,
                                avatarUri = { roomId -> avatarCache[roomId] },
                                showsState = showsState,
                                hidesHost = rosterView == RosterChoice.MACHINE,
                                onOpenRoom = { roomId ->
                                    vm.session.rooms.select(roomId)
                                    scope.launch { vm.session.open(roomId) }
                                    openDetail()
                                },
                                // A dead affordance since A1 (see RoomRow's own
                                // KDoc): nothing ever passed a handler for it,
                                // so tapping an avatar did nothing. This task
                                // is what it was for — the roomId isn't
                                // threaded any further yet because the info
                                // pane itself is still RootScaffold's default
                                // placeholder; a later panel task is what
                                // gives it real content to be about.
                                onOpenInfo = { openInfo() },
                                onLoadAvatar = { roomId -> vm.session.avatars.load(roomId) },
                                modifier = Modifier.fillMaxSize(),
                            )
                        },
                        detailPaneContent = { _ ->
                            // The room this pane is showing, reactively:
                            // `TimelineStore.roomId` is set the moment
                            // `Session.open` subscribes, before the first
                            // diff for it has even arrived.
                            val roomId by vm.session.timeline.roomId.collectAsStateWithLifecycle()

                            // Trigger 1 of spec §6's two: on room change,
                            // mark it read. Trigger 2 — on any history
                            // change while at the newest end — is Timeline's
                            // own job (see its class doc): it can see `rows`
                            // and `isAtNewest` together, neither of which
                            // this call site has reason to duplicate.
                            LaunchedEffect(roomId) {
                                if (roomId != null) vm.session.timeline.markRead()
                            }

                            val currentRoomId = roomId
                            if (currentRoomId == null) {
                                Box(
                                    Modifier.fillMaxSize().testTag("pane-timeline"),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("Select a room")
                                }
                            } else {
                                val items by vm.session.timeline.items.collectAsStateWithLifecycle()
                                val isPaginating by vm.session.timeline.isPaginating.collectAsStateWithLifecycle()
                                val canPaginate by vm.session.timeline.canPaginate.collectAsStateWithLifecycle()

                                // `TypingStore.line` is a computed property,
                                // not a StateFlow — `typers` is what is
                                // actually observable, so that is what is
                                // collected; `line` is re-read from it every
                                // time `typers` changes.
                                val typers by vm.session.typing.typers.collectAsStateWithLifecycle()
                                val typingLine = remember(typers) { vm.session.typing.line }

                                val liveAnswer by vm.session.live.answer.collectAsStateWithLifecycle()
                                val liveThought by vm.session.live.thought.collectAsStateWithLifecycle()
                                val liveTools by vm.session.live.tools.collectAsStateWithLifecycle()
                                val liveFinished by vm.session.live.finished.collectAsStateWithLifecycle()

                                // Reply/edit/attachment: reactive so a long
                                // press elsewhere (Timeline's onRowLongPress,
                                // below) or a picked photo shows up here the
                                // moment the store records it.
                                val replyTargets by vm.session.replies.targets.collectAsStateWithLifecycle()
                                val editTargets by vm.session.edits.targets.collectAsStateWithLifecycle()
                                val attachment by vm.session.staged.file.collectAsStateWithLifecycle()
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
                                    mutableStateOf(vm.session.drafts.draft(currentRoomId))
                                }
                                var sending by remember(currentRoomId) { mutableStateOf(false) }
                                var composerFailure by remember(currentRoomId) { mutableStateOf<String?>(null) }

                                Column(modifier = Modifier.fillMaxSize().testTag("pane-timeline")) {
                                    Timeline(
                                        rows = items,
                                        typingLine = typingLine,
                                        isPaginating = isPaginating,
                                        canPaginate = canPaginate,
                                        onPaginate = { scope.launch { vm.session.timeline.paginateBack() } },
                                        onMarkRead = { scope.launch { vm.session.timeline.markRead() } },
                                        liveAnswer = liveAnswer,
                                        liveThought = liveThought,
                                        liveTools = liveTools,
                                        liveFinished = liveFinished,
                                        onReact = { row, key ->
                                            scope.launch {
                                                vm.session.toggleReaction(row.item.eventId, key, currentRoomId)
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
                                                vm.session.edits.start(row, currentRoomId)
                                            } else if (row.canReplyOrReact) {
                                                vm.session.replies.start(row, currentRoomId)
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
                                                vm.session.drafts.set(next, currentRoomId)
                                            }
                                            scope.launch { vm.session.setTyping(next.isNotBlank(), currentRoomId) }
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
                                                        val ok = vm.session.edit(
                                                            eventId = currentEdit.eventId,
                                                            body = text,
                                                            roomId = currentRoomId,
                                                        )
                                                        if (ok) {
                                                            composerFailure = null
                                                            vm.session.edits.cancel(currentRoomId)
                                                            text = vm.session.drafts.draft(currentRoomId)
                                                        } else {
                                                            composerFailure = "Couldn't save that edit."
                                                        }
                                                    } else {
                                                        val refusal = vm.session.send(text, currentRoomId)
                                                        if (refusal == null) {
                                                            composerFailure = null
                                                            text = ""
                                                            vm.session.drafts.clear(currentRoomId)
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
                                        onCancelReply = { vm.session.replies.cancel(currentRoomId) },
                                        editing = editing,
                                        onCancelEdit = { vm.session.edits.cancel(currentRoomId) },
                                        attachment = attachment,
                                        onAttach = { path ->
                                            scope.launch {
                                                val refusal = vm.session.staged.stage(path, currentRoomId)
                                                if (refusal != null) composerFailure = refusal
                                            }
                                        },
                                        onDiscardAttachment = { scope.launch { vm.session.staged.discard() } },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        },
                        onBackFromDetail = {
                            // The verb RoomsStore.deselect() exists for: on a
                            // phone the roster is the previous screen, not a
                            // column beside the room, so nothing should stay
                            // selected once system back has returned to it.
                            vm.session.rooms.deselect()
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
                                            vm.session.signIn(
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
            }
        }
    }
}
