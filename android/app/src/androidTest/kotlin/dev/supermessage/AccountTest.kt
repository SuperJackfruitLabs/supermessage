package dev.supermessage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import uniffi.supermessage_core.RoomMemberDto
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import dev.supermessage.kit.RosterChoice
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import dev.supermessage.notify.PushStatus
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.supermessage_core.AccountDto

/**
 * [AccountPanel], the port of `apple/Supermessage/Panels/AccountPanel.swift`.
 *
 * Every test drives the composable directly with fake suspend lambdas
 * standing in for `Session.account`/`Session.signOut` — the same shape
 * `RoomInfoTest` already exercises `RoomInfoPanel` with, and for the same
 * reason: this panel has no store to fake instead (see [AccountPanel]'s own
 * KDoc). `onSignOut` is never invoked against a real session here — only
 * against these fakes, never the device's own signed-in one.
 */
class AccountTest {
    @get:Rule val compose = createComposeRule()

    private class RecordingHaptics : HapticFeedback {
        val played = mutableListOf<HapticFeedbackType>()
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            played += hapticFeedbackType
        }
    }

    private val haptics = RecordingHaptics()

    private fun person(id: String, name: String?, agent: Boolean = false) =
        RoomMemberDto(userId = id, displayName = name, avatarUrl = null, isIgnored = true, isAgent = agent)

    private val priya = person("@priya:example.org", "Priya")
    private val atlas = person("@agent_atlas:example.org", "Atlas", agent = true)

    private fun panel(
        account: () -> AccountDto? = { AccountDto(userId = "@rakesh:id.agentpod.dev", homeserver = "https://id.agentpod.dev") },
        onSignOut: () -> Unit = {},
        onClose: () -> Unit = {},
        rosterView: RosterChoice = RosterChoice.WAITING,
        onRosterView: (RosterChoice) -> Unit = {},
        showsState: Boolean = true,
        onShowsState: (Boolean) -> Unit = {},
        uriHandler: UriHandler? = null,
        appearance: @Composable () -> Unit = {},
        loadBlocked: suspend () -> List<RoomMemberDto>? = { emptyList() },
        onUnblock: suspend (String) -> String? = { null },
        blockedNow: (() -> Set<String>?)? = null,
        notifications: () -> PushStatus? = { null },
        fontScale: Float? = null,
    ) {
        compose.setContent {
            val handler = uriHandler ?: LocalUriHandler.current
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalUriHandler provides handler,
                LocalHapticFeedback provides haptics,
                LocalDensity provides if (fontScale == null) density else Density(density.density, fontScale),
            ) {
                // The width a phone has, never a height: the panel must scroll
                // inside whatever height the device gives it.
                Box(Modifier.requiredWidth(411.dp).fillMaxHeight()) {
                    SupermessageTheme {
                        AccountPanel(
                            loadAccount = { account() },
                            onSignOut = { onSignOut() },
                            onClose = onClose,
                            rosterView = rosterView,
                            onRosterView = { onRosterView(it) },
                            showsState = showsState,
                            onShowsState = { onShowsState(it) },
                            appearance = appearance,
                            loadBlocked = loadBlocked,
                            onUnblock = onUnblock,
                            blockedNow = blockedNow?.invoke(),
                            notifications = notifications(),
                        )
                    }
                }
            }
        }
    }

    /** The Chats settings call back with the choice made. */
    @Test
    fun chatsSettingsChangeTheRoster() {
        var view: RosterChoice? = null
        var state: Boolean? = null
        panel(
            rosterView = RosterChoice.WAITING,
            onRosterView = { view = it },
            showsState = true,
            onShowsState = { state = it },
        )
        compose.waitForIdle()

        compose.onNodeWithTag("account-order-recent").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(RosterChoice.RECENT, view)

        compose.onNodeWithTag("account-agent-state").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(false, state)
    }

    /** The stored arrangement is the one marked selected. */
    @Test
    fun theStoredArrangementIsMarkedSelected() {
        panel(rosterView = RosterChoice.RECENT)
        compose.waitForIdle()

        compose.onNodeWithTag("account-order-recent").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("account-order-waiting").assertIsNotSelected()
    }

    /** Each help row opens its page on the site, through the system's own handler. */
    @Test
    fun helpLinksOpenTheSite() {
        val opened = mutableListOf<String>()
        panel(uriHandler = object : UriHandler {
            override fun openUri(uri: String) { opened += uri }
        })
        compose.waitForIdle()

        compose.onNodeWithTag("account-link-support").performScrollTo().performClick()
        compose.onNodeWithTag("account-link-terms").performScrollTo().performClick()
        compose.onNodeWithTag("account-link-privacy").performScrollTo().performClick()

        assertEquals(
            listOf(
                "https://supermessage.dev/support/",
                "https://supermessage.dev/terms/",
                "https://supermessage.dev/privacy/",
            ),
            opened,
        )
    }

    /** Sign out is the last row; on a short screen it is reached by scrolling, and still asks first. */
    @Test
    fun signOutIsReachableAtTheShortHeight() {
        panel()
        compose.waitForIdle()

        compose.onNodeWithTag("account-sign-out").performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("account-sign-out-confirm").assertIsDisplayed()
    }

    /** iOS's footers, word for word. */
    @Test
    fun theFootersAreIosWords() {
        panel()
        compose.waitForIdle()

        compose.onNodeWithText(
            "Waiting first puts what needs an answer at the top. Agent state is the dot and word beside an agent's name.",
        ).performScrollTo().assertExists()
        compose.onNodeWithText("Signing out removes this account and its messages from this device.")
            .performScrollTo().assertExists()
    }

    /** The Appearance slot is where Task 14's section appears, between the account and Chats. */
    @Test
    fun theAppearanceSlotIsRendered() {
        panel(appearance = { Text("slot-content") })
        compose.waitForIdle()

        compose.onNodeWithText("slot-content").performScrollTo().assertExists()
    }

    /** Choosing an accent stores it; the first swatch is the house violet and is named so. */
    @Test
    fun choosingAnAccentStoresIt() {
        var stored: String? = "unset"
        panel(appearance = {
            AppearanceSettings(
                choice = ThemeChoice(),
                onMode = {},
                onDarkStyle = {},
                onAccent = { stored = it },
            )
        })
        compose.waitForIdle()

        compose.onNodeWithTag("accent-violet").performScrollTo().assertContentDescriptionEquals("Violet")
        compose.onNodeWithTag("accent-pink").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("pink", stored)

        compose.onNodeWithTag("accent-violet").performClick()
        compose.waitForIdle()
        assertEquals(null, stored)
    }

    /** The mode and dark-style segments report the choice made. */
    @Test
    fun choosingAModeAndADarkStyleStoresThem() {
        var mode: AppearanceMode? = null
        var style: DarkStyle? = null
        panel(appearance = {
            AppearanceSettings(
                choice = ThemeChoice(),
                onMode = { mode = it },
                onDarkStyle = { style = it },
                onAccent = {},
            )
        })
        compose.waitForIdle()

        compose.onNodeWithTag("appearance-mode-dark").performScrollTo().performClick()
        compose.onNodeWithTag("appearance-dark-black").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(AppearanceMode.DARK, mode)
        assertEquals(DarkStyle.BLACK, style)
    }

    /** The Appearance section ends with iOS's footer. */
    @Test
    fun theAppearanceFooterIsIosWords() {
        panel(appearance = {
            AppearanceSettings(choice = ThemeChoice(), onMode = {}, onDarkStyle = {}, onAccent = {})
        })
        compose.waitForIdle()

        compose.onNodeWithText(
            "Black is true black in dark mode, for OLED screens. The accent colours buttons, links and your own highlights.",
        ).performScrollTo().assertExists()
    }

    /**
     * The headline is `peopleLabel`'s answer for the id, not the raw local
     * part — `@rakesh:id.agentpod.dev` reads "Rakesh", title-cased the same
     * way every other name in this app is (see `Account.kt`'s own
     * `accountName` for why this delegates to the core rather than
     * re-deriving the rule locally). The full id and homeserver beneath it
     * are shown exactly as `Session.account` returned them.
     */
    @Test
    fun theAccountShowsWhoIsSignedIn() {
        panel()
        compose.waitForIdle()

        compose.onNodeWithTag("account-name").assertIsDisplayed()
        compose.onNodeWithText("Rakesh").assertIsDisplayed()
        compose.onNodeWithText("@rakesh:id.agentpod.dev").assertIsDisplayed()
        compose.onNodeWithText("https://id.agentpod.dev").assertIsDisplayed()
    }

    /** "Done" closes the panel without ever touching sign-out. */
    @Test
    fun doneClosesWithoutSigningOut() {
        var closed = false
        var signedOut = false
        panel(onSignOut = { signedOut = true }, onClose = { closed = true })
        compose.waitForIdle()

        compose.onNodeWithTag("account-done").performClick()

        assertEquals(true, closed)
        assertEquals(false, signedOut)
    }

    /**
     * Tapping "Sign out" asks first — it must not call the destructive
     * action on a single tap. The confirmation dialog is the only door to
     * [AccountPanel]'s `onSignOut`.
     */
    @Test
    fun signOutAsksForConfirmationFirst() {
        var signedOut = false
        panel(onSignOut = { signedOut = true })
        compose.waitForIdle()

        compose.onNodeWithTag("account-sign-out").performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("account-sign-out-confirm").assertIsDisplayed()
        assertEquals(false, signedOut)
    }

    /** Confirming actually signs out, and closes the panel behind it. */
    @Test
    fun confirmingSignsOutAndCloses() {
        var signedOut = false
        var closed = false
        panel(onSignOut = { signedOut = true }, onClose = { closed = true })
        compose.waitForIdle()

        compose.onNodeWithTag("account-sign-out").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("account-sign-out-confirm-button").performClick()
        compose.waitForIdle()

        assertEquals(true, signedOut)
        assertEquals(true, closed)
    }

    /** Canceling the confirmation leaves the account signed in. */
    @Test
    fun cancelingLeavesTheAccountSignedIn() {
        var signedOut = false
        panel(onSignOut = { signedOut = true })
        compose.waitForIdle()

        compose.onNodeWithTag("account-sign-out").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("account-sign-out-cancel").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("account-sign-out-confirm").assertDoesNotExist()
        assertEquals(false, signedOut)
    }

    /** `loadAccount` returning `null` (still loading, or a swallowed failure) reads "Signed in" rather than guessing at a name. */
    @Test
    fun aMissingAccountShowsAPlaceholderName() {
        panel(account = { null })
        compose.waitForIdle()

        compose.onNodeWithText("Signed in").assertIsDisplayed()
    }

    /**
     * The third `user_label` reimplementation found on this branch
     * (`people_label`, `decodeDataUri`, and — before this task — `Account.kt`'s
     * own `accountName`, which read the substring between `@` and `:`
     * verbatim). For a bridge's puppet the leading underscore is the entire
     * signal that a segment is a namespace, not a person's name:
     * `@_agentpod_ganesha:id.agentpod.dev` names Ganesha, not
     * `_agentpod_ganesha`. `peopleLabel(listOf(id))` already gets this right
     * today because it delegates to the core's own `display_name::user_label`
     * — this test protects that delegation, not a local guess at the rule.
     */
    @Test
    fun aBridgedAccountNameStripsTheNamespaceNotJustThePunctuation() {
        panel(
            account = {
                AccountDto(userId = "@_agentpod_ganesha:id.agentpod.dev", homeserver = "https://id.agentpod.dev")
            },
        )
        compose.waitForIdle()

        compose.onNodeWithText("Ganesha").assertIsDisplayed()
    }

    /** The text on the `account-initial` node, or `null` if it is not composed. */
    private fun initialText(): String? {
        val nodes = compose.onAllNodesWithTag("account-initial").fetchSemanticsNodes()
        if (nodes.isEmpty()) return null
        return nodes.first().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
    }

    /**
     * An emoji-initial account name renders the emoji, not half of it.
     *
     * `Account.kt` used to do `name.take(1).uppercase()`, and Kotlin's `take`
     * counts UTF-16 code units — for an astral character that is the high half
     * of a surrogate pair standing alone, which renders as tofu. It is the same
     * defect `room_identity`'s header records shipping once already, when a
     * Svelte view took `name[0]`.
     *
     * The fix was to export the core's code-point-aware rule rather than
     * hand-roll a fifth re-derivation of something the core already decides.
     */
    @Test
    fun anEmojiInitialRendersWholeRatherThanHalfASurrogate() {
        panel(
            account = {
                AccountDto(userId = "@\uD83E\uDDE0lead:id.agentpod.dev", homeserver = "https://id.agentpod.dev")
            },
        )
        compose.waitForIdle()

        val initial = initialText()
        assertEquals("\uD83E\uDDE0", initial)
    }

    // ── The blocked list and the report footer (parity M3, Task 4) ───────

    @Test
    fun noBlockedSectionWhenNobodyIsBlocked() {
        panel()
        compose.waitForIdle()

        compose.onNodeWithTag("account-blocked").assertDoesNotExist()
        compose.onNodeWithTag("account-blocked-footer").assertDoesNotExist()
        compose.onNodeWithText("Blocked").assertDoesNotExist()
    }

    @Test
    fun theBlockedListShowsAgentsAndPeople() {
        panel(loadBlocked = { listOf(priya, atlas) })
        compose.waitForIdle()

        compose.onNodeWithTag("account-blocked").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("member-@priya:example.org").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("member-@agent_atlas:example.org").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("member-@agent_atlas:example.org").assertTextContains("Agent")
        compose.onNodeWithTag("account-blocked-footer").performScrollTo().assertTextEquals(
            "Their messages are hidden in every room, on all your devices. A blocked agent keeps running. " +
                "Block someone from a room's member list.",
        )
    }

    @Test
    fun unblockingRemovesTheRowWithSuccess() {
        panel(loadBlocked = { listOf(priya, atlas) })
        compose.waitForIdle()

        compose.onNodeWithTag("account-unblock-@priya:example.org").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("member-@priya:example.org").fetchSemanticsNodes().isEmpty()
        }

        compose.onNodeWithTag("member-@agent_atlas:example.org").assertExists()
        assertEquals(listOf(HapticFeedbackType.Confirm), haptics.played)
    }

    /** The core's list is its local store: it still holds an unblocked id until sync echoes it. */
    @Test
    fun anUnblockThatLandedStaysGoneWhileTheCoresListIsStale() {
        val now = mutableStateOf(setOf(priya.userId, atlas.userId))
        var loads = 0
        panel(
            loadBlocked = { loads++; listOf(priya, atlas) },
            blockedNow = { now.value },
        )
        compose.waitForIdle()
        compose.onNodeWithTag("member-@priya:example.org").assertExists()

        // The session notes the unblock; the core still lists Priya.
        now.value = setOf(atlas.userId)
        compose.waitForIdle()

        compose.onAllNodesWithTag("member-@priya:example.org").assertCountEquals(0)
        compose.onNodeWithTag("member-@agent_atlas:example.org").assertExists()
        assertEquals(1, loads)
    }

    @Test
    fun aBlockMadeElsewhereReloadsTheList() {
        val now = mutableStateOf(setOf(priya.userId))
        var core = listOf(priya)
        panel(loadBlocked = { core }, blockedNow = { now.value })
        compose.waitForIdle()
        compose.onAllNodesWithTag("member-@agent_atlas:example.org").assertCountEquals(0)

        core = listOf(priya, atlas)
        now.value = setOf(priya.userId, atlas.userId)
        compose.waitForIdle()

        compose.onNodeWithTag("member-@agent_atlas:example.org").assertExists()
    }

    @Test
    fun aFailedReloadKeepsTheListAlreadyShown() {
        val now = mutableStateOf(setOf(priya.userId))
        var fail = false
        panel(
            loadBlocked = { if (fail) null else listOf(priya) },
            blockedNow = { now.value },
        )
        compose.waitForIdle()
        compose.onNodeWithTag("member-@priya:example.org").assertExists()

        fail = true
        now.value = setOf(priya.userId, atlas.userId)
        compose.waitForIdle()

        compose.onNodeWithTag("account-blocked").assertExists()
        compose.onNodeWithTag("member-@priya:example.org").assertExists()
    }

    @Test
    fun aRefusedUnblockReplacesTheFooter() {
        panel(loadBlocked = { listOf(priya) }, onUnblock = { "Can't reach the homeserver." })
        compose.waitForIdle()

        compose.onNodeWithTag("account-unblock-@priya:example.org").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("account-blocked-failure").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag("account-blocked-failure").performScrollTo().assertTextEquals("Can't reach the homeserver.")
        compose.onNodeWithTag("member-@priya:example.org").assertExists()
        compose.onNodeWithTag("account-blocked-footer").assertDoesNotExist()
        assertEquals(emptyList<HapticFeedbackType>(), haptics.played)
    }

    @Test
    fun anUnblockInFlightCannotBeTappedAgain() {
        val gate = kotlinx.coroutines.CompletableDeferred<String?>()
        var calls = 0
        panel(loadBlocked = { listOf(priya) }, onUnblock = { calls++; gate.await() })
        compose.waitForIdle()

        compose.onNodeWithTag("account-unblock-@priya:example.org").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("account-unblock-@priya:example.org").assertIsNotEnabled()
        gate.complete("Nope.")
        compose.waitForIdle()

        assertEquals(1, calls)
        compose.onNodeWithTag("account-unblock-@priya:example.org").assertIsEnabled()
    }

    @Test
    fun theReportFooterIsIosWords() {
        panel()
        compose.waitForIdle()

        compose.onNodeWithTag("account-report-footer").performScrollTo().assertTextEquals(
            "To report abuse, long-press a message or open a room's info. " +
                "Reports go to your homeserver's administrators.",
        )
    }

    /** Run at 1080x1280 as well: the last unblock is only reachable by scrolling. */
    @Test
    fun unblockIsReachableAtTheShortHeight() {
        val three = listOf(priya, atlas, person("@zed:example.org", "Zed"))
        var unblocked: String? = null
        panel(loadBlocked = { three }, onUnblock = { unblocked = it; null })
        compose.waitForIdle()

        compose.onNodeWithTag("account-unblock-@zed:example.org").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals("@zed:example.org", unblocked)
    }

    // MARK: - Notifications (M6 Task 4, Q1/Q2, no iOS copy)

    private fun notificationsRowSays(status: PushStatus, words: String) {
        panel(notifications = { status })
        compose.waitForIdle()
        compose.onNodeWithTag("account-notifications").performScrollTo().assertIsDisplayed().assertTextEquals(words)
        compose.onNodeWithText("Notifications").assertIsDisplayed()
    }

    @Test
    fun theNotificationsRowSaysPushIsOnThroughTheDistributor() =
        notificationsRowSays(PushStatus.On("ntfy"), "On, through ntfy")

    @Test
    fun theNotificationsRowSaysWhyPushIsOff() = notificationsRowSays(
        PushStatus.NoDistributor,
        "Off: install a UnifiedPush app such as ntfy to get notifications while supermessage is closed",
    )

    @Test
    fun theNotificationsRowSaysTheServerCantTakeMatrix() = notificationsRowSays(
        PushStatus.NoMatrixGateway,
        "Off: your UnifiedPush server can't take Matrix notifications",
    )

    @Test
    fun theNotificationsRowSaysNotificationsAreTurnedOff() = notificationsRowSays(
        PushStatus.NotificationsOff,
        "Off: notifications are turned off for supermessage",
    )

    @Test
    fun theNotificationsRowIsAbsentUntilPushIsKnown() {
        panel(notifications = { null })
        compose.waitForIdle()
        compose.onNodeWithTag("account-notifications").assertDoesNotExist()
    }

    /** RF3: 411dp at font scale 2, every state, every line shown and none cut short. */
    @Test
    fun theNotificationsRowFitsAtFontScaleTwo() {
        val status = mutableStateOf<PushStatus>(PushStatus.On("ntfy"))
        panel(notifications = { status.value }, fontScale = 2f)
        for (state in listOf(
            PushStatus.On("ntfy"), PushStatus.NoDistributor, PushStatus.NoMatrixGateway, PushStatus.NotificationsOff,
        )) {
            status.value = state
            compose.waitForIdle()
            val row = compose.onNodeWithTag("account-notifications").performScrollTo().assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            row.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            val layout = layouts.single()
            assertFalse("$state overflows", layout.hasVisualOverflow)
            for (line in 0 until layout.lineCount) {
                assertFalse("$state line $line is ellipsised", layout.isLineEllipsized(line))
            }
            val bounds = row.fetchSemanticsNode().boundsInRoot
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            assertTrue("$state runs off the screen: $bounds in $root", bounds.bottom <= root.bottom && bounds.top >= root.top)
        }
    }
}
