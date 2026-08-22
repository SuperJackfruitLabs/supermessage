package dev.supermessage

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
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
    ) {
        compose.setContent {
            AccountPanel(
                loadAccount = { account() },
                onSignOut = { onSignOut() },
                onClose = onClose,
            )
        }
    }

    /** Exactly what `Session.account` returns — the local part of the id as a headline, the full id and homeserver beneath it. */
    @Test
    fun theAccountShowsWhoIsSignedIn() {
        panel()
        compose.waitForIdle()

        compose.onNodeWithTag("account-name").assertIsDisplayed()
        compose.onNodeWithText("rakesh").assertIsDisplayed()
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

        compose.onNodeWithTag("account-sign-out").performClick()
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

        compose.onNodeWithTag("account-sign-out").performClick()
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

        compose.onNodeWithTag("account-sign-out").performClick()
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
}
