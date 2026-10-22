package dev.supermessage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
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
    ) {
        compose.setContent {
            val handler = uriHandler ?: LocalUriHandler.current
            CompositionLocalProvider(LocalUriHandler provides handler) {
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

}
