package dev.supermessage

import android.Manifest
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.supermessage.notify.PlatformCoordinator
import dev.supermessage.notify.PushStatus
import dev.supermessage.notify.askForPush
import androidx.core.app.NotificationManagerCompat
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import uniffi.supermessage_ffi.displayInitial
import uniffi.supermessage_ffi.peopleLabel
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.supermessage.kit.RosterArrangement
import dev.supermessage.kit.RosterChoice
import dev.supermessage.kit.Session
import dev.supermessage.kit.stores.ConnectionStore.Connection
import dev.supermessage.kit.VoicePlayback
import java.io.File
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

/** The theme choice's own store, read before the first frame (see [MainActivity.onCreate]). */
private val Context.appearanceDataStore: DataStore<Preferences> by preferencesDataStore(name = "appearance")

class MainActivity : ComponentActivity() {
    /**
     * Registered before the Activity is STARTED, as the contract requires. The
     * answer is not acted on: a refusal is the reader's to make, and the
     * system remembers it.
     */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            coordinator?.permissionAnswered(granted)
        }

    private var coordinator: PlatformCoordinator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val model = ViewModelProvider(this)[SessionViewModel::class.java]
        model.startNotifications()
        // Start and stop of the Activity are the app coming forward and going
        // away (iOS `scenePhase`); the notification lifecycle follows them.
        lifecycle.addObserver(ScenePhaseObserver { active -> model.notifications.activeChanged(active) })
        val registrar = AppCore.registrar(this)
        val coordinator = PlatformCoordinator(
            context = this,
            session = model.session,
            permissionLauncher = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) },
            askForPush = { askForPush(this, registrar) },
            pushSignedOut = registrar::signedOut,
        ).also { coordinator = it }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { coordinator.run() }
        }
        val appearance = AppearancePreferences(applicationContext.appearanceDataStore)
        // Read before `setContent`, so the first frame is drawn in the stored
        // choice rather than in the house palette and then repainted (iOS:
        // `ThemeState.init`). A small local file; the cost is a few
        // milliseconds before the first frame, which is the point.
        val stored = runBlocking { appearance.choice.first() }
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        applySystemBars(stored.resolvesDark(systemDark))
        // The window behind the first frame already agrees with the stored
        // choice, so a Dark choice on a light phone does not flash light.
        window.setBackgroundDrawable(
            ColorDrawable(stored.palette(stored.resolvesDark(systemDark)).surface.toArgb()),
        )
        setContent {
            val choice by appearance.choice.collectAsStateWithLifecycle(initialValue = stored)
            val dark = choice.resolvesDark(isSystemInDarkTheme())
            val surface = choice.palette(dark).surface
            // The status and navigation bars follow the chosen scheme, not the
            // phone's: a Dark choice on a light phone needs light icons.
            DisposableEffect(dark) {
                applySystemBars(dark)
                onDispose {}
            }
            // The window behind the first frame, and behind a transition, agrees with the choice.
            SideEffect { window.setBackgroundDrawable(ColorDrawable(surface.toArgb())) }
            SupermessageTheme(choice) {
                Surface {
                    val vm: SessionViewModel = viewModel()

                    // Built here, not in the ViewModel: RosterPreferences
                    // wraps a DataStore, which needs a Context, and this is
                    // the one place in the tree that has one to give it.
                    val prefs = remember { RosterPreferences(applicationContext.rosterDataStore) }

                    AppRoot(session = vm.session, prefs = prefs, appearance = appearance)
                }
            }
        }
    }
}

/** Edge to edge, with bar icons for the scheme in force rather than the phone's. */
private fun ComponentActivity.applySystemBars(dark: Boolean) {
    val bar = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
    enableEdgeToEdge(statusBarStyle = bar, navigationBarStyle = bar)
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
internal fun AppRoot(session: Session, prefs: RosterPreferences, appearance: AppearancePreferences? = null) {
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

    // The one voice-note player, app-wide — see `VoicePlayback` for why one.
    // Built here because the engine and the cache directory need a Context.
    // Files go under cacheDir, which the system may clear; a cleared note is
    // fetched again the next time it is played.
    val context = LocalContext.current
    val voice = remember(session) {
        VoicePlayback(
            fetch = session::playableAudio,
            cacheDir = File(context.cacheDir, "voice"),
            engine = MediaPlayerEngine(),
            scope = scope,
        )
    }
    // Backgrounded: stop, and let go of the decoder.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { voice.stop() }

    // Foreground and background reach the session, as iOS's scenePhase does:
    // coming back re-seeds the stores, going away clears a typing notice.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(session, lifecycleOwner) {
        val observer = ScenePhaseObserver { active ->
            scope.launch { session.scenePhaseChanged(active) }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
        } else {
            // Not the previous account's, for the toolbar's initial or the info panel.
            accountUserId = null
        }
    }

    // Which of the four places the list pane shows. Per session, saved across a
    // rotation, and back to Chats on sign-out so the next account never opens on
    // the last one's Needs you.
    var destination by rememberSaveable(session) { mutableStateOf(Destination.CHATS) }
    // The search term lives here, not in the panel: the list pane is recomposed on
    // the way back from a result, and the term has to be there when it is.
    var searchTerm by rememberSaveable(session) { mutableStateOf("") }
    var searchNarrowed by rememberSaveable(session) { mutableStateOf(true) }
    // Bumped on sign-out: the scroll positions below belong to the account that left.
    var accountEpoch by remember { mutableIntStateOf(0) }
    LaunchedEffect(phase) {
        if (phase == Session.Phase.SIGNED_OUT) {
            destination = Destination.CHATS
            searchTerm = ""
            searchNarrowed = true
            accountEpoch += 1
        }
    }
    // Hoisted above the destination switch, so Needs you and Agents come back where they were left.
    val needsYouListState = remember(accountEpoch) { LazyListState() }
    val agentsListState = remember(accountEpoch) { LazyListState() }
    val haptic = LocalHapticFeedback.current

    // The key generated at sign-in, shown where the reader already is.
    //
    // Presented from the root rather than from the account panel because the
    // reader has not gone looking for it: it arrives a moment after the first
    // sign-in, and the alternative was an account whose backup could never be
    // opened by anyone, silently.
    val newRecoveryKey by session.newRecoveryKey.collectAsStateWithLifecycle()
    newRecoveryKey?.let { key ->
        NewRecoveryKeyDialog(key = key, onDone = session::clearNewRecoveryKey)
    }

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

    // Whether the roster has been read at all. An empty list before that is not an
    // empty account, and must not be shown as one ("You're all caught up").
    val loaded by session.rooms.loaded.collectAsStateWithLifecycle()
    val selectedRoomId by session.rooms.selectedId.collectAsStateWithLifecycle()
    val roomInProgress by session.live.inProgress.collectAsStateWithLifecycle()
    val showsState by prefs.showsState.collectAsStateWithLifecycle(initialValue = true)

    // The core decides who is in the inbox, and what the badge counts (decisions only).
    // Keyed on what it reads, so an answered decision leaves in the same update.
    val inbox = remember(rooms, now) { RosterArrangement.needsYouInbox(rooms, now) }
    val badgeCount = if (loaded) inbox.badgeCount.toInt() else 0
    val agents = remember(rooms, now, destination == Destination.AGENTS) {
        if (destination == Destination.AGENTS) RosterArrangement.agentDirectory(rooms, now) else emptyList()
    }

    // A pull on Chats, Needs you or Agents: the clock first, then the roster, read again
    // (`RoomListView.swift:175-178`). The indicator stays until the read returns.
    val refresh: suspend () -> Unit = {
        now = Instant.now()
        session.rooms.seed()
    }

    val navigation = ShellNavigation(
        selected = destination,
        onSelect = { next ->
            if (next != destination) {
                haptic.play(Haptic.Selection)
                destination = next
            }
        },
        badgeCount = badgeCount,
    )

    val accountInitial = displayInitial(accountUserId?.let { peopleLabel(listOf(it)) } ?: "Signed in")

    RootScaffold(
        phase = phase,
        navigation = navigation,
        listPaneContent = { _, openDetail, openInfo ->
            // The account panel and a new room share the Extra pane with room
            // info: set which panel `extraPaneContent` below should show, then
            // ask RootScaffold to open it.
            val openPanel: (ExtraPanel) -> Unit = { panel ->
                extraPanel = panel
                openInfo()
            }
            val openRoom: (String) -> Unit = { roomId ->
                session.rooms.select(roomId)
                scope.launch { session.open(roomId) }
                openDetail()
            }
            val railed = LocalNavigationRail.current
            Column(Modifier.fillMaxSize()) {
                // On a tablet the toolbar stays on the sidebar whatever the destination;
                // on a phone it is Chats' alone, and the others show their title.
                if (destination != Destination.CHATS) {
                    if (railed) {
                        ChatsToolbar(
                            accountInitial = accountInitial,
                            onAccount = { openPanel(ExtraPanel.Account) },
                            onCompose = { openPanel(ExtraPanel.NewRoom) },
                            title = { if (destination != Destination.SEARCH) DestinationTitle(destination) },
                        )
                    } else if (destination != Destination.SEARCH) {
                        DestinationTitle(destination, Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    // Always composed, shown only on Chats: leaving would drop its scroll
                    // position and avatars, and coming back would fetch them all again.
                    Box(Modifier.fillMaxSize().shownOnlyIf(destination == Destination.CHATS)) {
                        RosterPane(
                            session = session,
                            prefs = prefs,
                            rooms = rooms,
                            avatarCache = avatarCache,
                            now = now,
                            openDetail = openDetail,
                            openPanel = openPanel,
                            accountInitial = accountInitial,
                            loaded = loaded,
                            onRefresh = refresh,
                        )
                    }
                    when (destination) {
                        Destination.CHATS -> {}
                        Destination.NEEDS_YOU ->
                            NeedsYouPane(
                                inbox = if (loaded) inbox else null,
                                now = now,
                                showsState = showsState,
                                avatarUri = { roomId -> avatarCache[roomId] },
                                onLoadAvatar = { roomId -> scope.launch { session.avatars.load(roomId) } },
                                onOpenRoom = openRoom,
                                onOpenInfo = { roomId -> openPanel(ExtraPanel.RoomInfo(roomId)) },
                                listState = needsYouListState,
                                onRefresh = refresh,
                            )

                        Destination.AGENTS ->
                            AgentsPane(
                                agents = if (loaded) agents else null,
                                now = now,
                                openRoomId = selectedRoomId,
                                openRoomInProgress = roomInProgress,
                                avatarUri = { roomId -> avatarCache[roomId] },
                                onLoadAvatar = { roomId -> scope.launch { session.avatars.load(roomId) } },
                                onOpenRoom = openRoom,
                                listState = agentsListState,
                                onRefresh = refresh,
                            )

                        Destination.SEARCH ->
                            SearchPanel(
                                // The open room where one is selected (a tablet); none on a phone,
                                // where a room being open means the list is not on screen.
                                scope = if (railed) {
                                    rooms.firstOrNull { it.room.id == selectedRoomId }
                                        ?.let { SearchPanelScope(it.room.id, it.identity.name) }
                                } else {
                                    null
                                },
                                onOpen = openRoom,
                                onClose = null,
                                search = session::search,
                                roomName = { roomId -> rooms.firstOrNull { it.room.id == roomId }?.room?.name },
                                hoistedTerm = searchTerm,
                                onTermChange = { searchTerm = it },
                                hoistedNarrowed = searchNarrowed,
                                onNarrowedChange = { searchNarrowed = it },
                            )
                    }
                }
            }
        },
        detailPaneContent = { _, openInfo ->
            RoomPane(
                session = session,
                rooms = rooms,
                voice = voice,
                now = now,
                onOpenRoomInfo = { id ->
                    extraPanel = ExtraPanel.RoomInfo(id)
                    openInfo()
                },
            )
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

                    is ExtraPanel.RoomInfo -> {
                        // Read here so the panel recomposes on the homeserver's echo.
                        val blocked by session.safety.blocked.collectAsStateWithLifecycle()
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
                            isBlocked = { it in blocked },
                            onBlock = session::block,
                            onUnblock = session::unblock,
                            onReport = session::report,
                        )
                    }

                    ExtraPanel.Account -> {
                        val notifications = rememberPushStatus()
                        val blockedNow by session.safety.blocked.collectAsStateWithLifecycle()
                        val storedView by prefs.view.collectAsStateWithLifecycle(initialValue = RosterChoice.WAITING)
                        val showsState by prefs.showsState.collectAsStateWithLifecycle(initialValue = true)
                        AccountPanel(
                            loadAccount = session::account,
                            rosterView = RosterChoice.offeredOrDefault(storedView),
                            onRosterView = prefs::setView,
                            showsState = showsState,
                            onShowsState = prefs::setShowsState,
                            appearance = {
                                if (appearance != null) {
                                    val choice by appearance.choice.collectAsStateWithLifecycle(initialValue = ThemeChoice())
                                    AppearanceSettings(
                                        choice = choice,
                                        onMode = appearance::setMode,
                                        onDarkStyle = appearance::setDarkStyle,
                                        onAccent = appearance::setAccent,
                                    )
                                }
                            },
                            onSignOut = session::signOut,
                            onClose = {
                                extraPanel = null
                                closeInfo()
                            },
                            recoveryState = session::recoveryState,
                            onEnableRecovery = session::enableRecovery,
                            onRecoverWithKey = session::recoverWithKey,
                            onResetRecovery = session::resetRecovery,
                            blockedNow = blockedNow,
                            loadBlocked = session::loadBlockedUsers,
                            onUnblock = session::unblock,
                            notifications = notifications,
                        )
                    }

                    null -> {}
                }
            }
        },
        onBackFromDetail = {
            // The verb RoomsStore.deselect() exists for: on a
            // phone the roster is the previous screen, not a
            // column beside the room, so nothing should stay
            // selected, subscribed or focused once system back has
            // returned to it (the scaffold keeps the detail pane composed
            // at zero size, so anything left pointing at the room would
            // go on acting for it: Session.closeRoom).
            session.closeRoom()
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
 * Which of a new room, room info or the account panel
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
    object Account : ExtraPanel()
}

/** A destination's own title, for the ones that do not draw one themselves (`NeedsYouView.swift:50`, `AgentsView.swift:49`). */
@Composable
internal fun DestinationTitle(destination: Destination, modifier: Modifier = Modifier) {
    Text(
        destination.title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.testTag("destination-title").semantics { heading() },
    )
}

/**
 * Composed always, drawn and heard only when [shown]: nothing of it is placed, touched or
 * offered to TalkBack otherwise, but its state (a list's scroll position, the avatars it
 * loaded) stays where it was.
 */
internal fun Modifier.shownOnlyIf(shown: Boolean): Modifier =
    if (shown) {
        this
    } else {
        this
            .clearAndSetSemantics {}
            .layout { measurable, constraints ->
                measurable.measure(constraints)
                layout(0, 0) {}
            }
    }

/**
 * What Account → Notifications says: "turned off" when the app may not
 * notify at all (looked at again on each resume, since the reader changes it
 * in Settings), otherwise the push registrar's state.
 */
@Composable
private fun rememberPushStatus(): PushStatus? {
    val context = LocalContext.current
    val registrar = remember { AppCore.registrar(context) }
    val push by registrar.status.collectAsStateWithLifecycle()
    var enabled by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    return if (!enabled) PushStatus.NotificationsOff else push
}
