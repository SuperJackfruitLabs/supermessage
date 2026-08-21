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
import androidx.compose.runtime.setValue
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.supermessage.kit.Session
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
                    val homeserver by prefs.homeserver.collectAsStateWithLifecycle(initialValue = "")
                    val failure by vm.session.failure.collectAsStateWithLifecycle()
                    var busy by remember { mutableStateOf(false) }
                    val scope = rememberCoroutineScope()

                    RootScaffold(
                        phase = phase,
                        loginHomeserver = homeserver,
                        onLoginHomeserverChange = { scope.launch { prefs.setHomeserver(it) } },
                        loginFailure = failure,
                        loginBusy = busy,
                        onLoginSignIn = { username, password ->
                            // busy lives here, not in Session: it guards this
                            // form against a double tap, it is not part of
                            // what Session itself tracks (see Session.kt's
                            // own `phase`/`failure`, neither of which serves
                            // that purpose while a sign-in is in flight).
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
                }
            }
        }
    }
}
