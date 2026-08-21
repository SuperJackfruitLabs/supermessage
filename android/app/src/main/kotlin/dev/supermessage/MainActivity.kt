package dev.supermessage

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.supermessage.kit.Session
import kotlinx.coroutines.flow.first
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

                    // Local state, seeded once — not a continuous collection
                    // of prefs.homeserver.
                    //
                    // A TextField is a controlled component: its `value` is
                    // whatever the caller hands it back. Binding that value
                    // straight to a Flow that this same field also writes to
                    // on every keystroke created a race — a second keystroke
                    // arriving before DataStore's write had re-emitted fired
                    // onValueChange against a *stale* displayed value and
                    // silently overwrote the character in between rather
                    // than appending it. `username` and `password` in
                    // LoginScreen never had this problem because they live
                    // in ordinary `remember` state; `homeserver` needs the
                    // same shape to be correct, while still surviving a
                    // failed sign-in the way RosterPreferences.homeserver's
                    // own doc comment describes — which is what seeding once
                    // from the stored value, then writing through without
                    // reading back, gives it: one read at start, writes
                    // fire-and-forget from then on, the displayed value
                    // never again waits on the store.
                    var homeserverSeeded by rememberSaveable { mutableStateOf(false) }
                    var homeserver by rememberSaveable { mutableStateOf("") }
                    LaunchedEffect(Unit) {
                        if (!homeserverSeeded) {
                            homeserver = prefs.homeserver.first()
                            homeserverSeeded = true
                        }
                    }

                    val failure by vm.session.failure.collectAsStateWithLifecycle()
                    var busy by remember { mutableStateOf(false) }
                    val scope = rememberCoroutineScope()

                    RootScaffold(
                        phase = phase,
                        signedOutContent = {
                            LoginScreen(
                                homeserver = homeserver,
                                onHomeserverChange = { value ->
                                    homeserver = value
                                    scope.launch { prefs.setHomeserver(value) }
                                },
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
                                                homeserver = homeserver,
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
