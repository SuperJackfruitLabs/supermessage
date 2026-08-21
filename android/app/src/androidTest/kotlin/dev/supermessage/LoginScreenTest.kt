package dev.supermessage

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The form iOS draws at `apple/Supermessage/LoginView.swift`: homeserver,
 * username, password, a failure line, and a sign-in button that a sign-in
 * already in flight cannot be started twice.
 */
class LoginScreenTest {
    @get:Rule val compose = createComposeRule()

    /** A failure is shown, not swallowed. */
    @Test
    fun theFailureIsVisible() {
        compose.setContent {
            LoginScreen(homeserver = "https://h", onHomeserverChange = {},
                failure = "the homeserver refused those credentials",
                busy = false, onSignIn = { _, _ -> })
        }
        compose.onNodeWithText("the homeserver refused those credentials").assertIsDisplayed()
    }

    /** Signing in hands over what was typed. */
    @Test
    fun signingInPassesTheCredentials() {
        var got: Pair<String, String>? = null
        compose.setContent {
            LoginScreen(homeserver = "https://h", onHomeserverChange = {},
                failure = null, busy = false, onSignIn = { u, p -> got = u to p })
        }
        compose.onNodeWithTag("username").performTextInput("ganesha")
        compose.onNodeWithTag("password").performTextInput("hunter2")
        compose.onNodeWithTag("sign-in").performClick()
        assertEquals("ganesha" to "hunter2", got)
    }

    /**
     * A sign-in already in flight cannot be started twice.
     *
     * Both fields are filled first: empty fields disable the button on
     * their own (see [LoginScreen]'s own comment), so a test that left them
     * empty could pass on that guard alone and never actually exercise
     * `busy`. Filling them isolates `busy` as the only reason left for the
     * button to be disabled — confirmed by mutation: removing the `busy`
     * clause from `enabled` while leaving the fields empty here left this
     * test green, because the empty-field guard alone still blocked the
     * click.
     */
    @Test
    fun aBusyFormDoesNotSubmitAgain() {
        var calls = 0
        compose.setContent {
            LoginScreen(homeserver = "https://h", onHomeserverChange = {},
                failure = null, busy = true, onSignIn = { _, _ -> calls++ })
        }
        compose.onNodeWithTag("username").performTextInput("ganesha")
        compose.onNodeWithTag("password").performTextInput("hunter2")
        compose.onNodeWithTag("sign-in").performClick()
        assertEquals(0, calls)
    }
}
