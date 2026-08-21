package dev.supermessage

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
                        listPaneContent = {
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
                                },
                                onLoadAvatar = { roomId -> vm.session.avatars.load(roomId) },
                                modifier = Modifier.fillMaxSize(),
                            )
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
