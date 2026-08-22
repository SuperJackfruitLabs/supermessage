package dev.supermessage

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The composer, the shape iOS draws at
 * `apple/Supermessage/Composer/ComposerView.swift`: a text field and a send
 * control, gated on whether there is anything worth sending and on whether a
 * send is already in flight. Reply and edit banners are Task 3's — this
 * suite only exercises the text/send/failure surface Task 2 owns.
 */
class ComposerTest {
    @get:Rule val compose = createComposeRule()

    /** Send is disabled with nothing to send, and enabled once there is. */
    @Test
    fun sendIsDisabledUntilThereIsSomethingToSend() {
        var value by mutableStateOf("")
        compose.setContent {
            Composer(text = value, onTextChange = {}, onSend = {})
        }
        compose.onNodeWithTag("composer-send").assertIsNotEnabled()

        value = "hey"
        compose.waitForIdle()
        compose.onNodeWithTag("composer-send").assertIsEnabled()
    }

    /**
     * Whitespace alone is nothing to send — `Session.send` already trims, so
     * enabling on non-blank rather than non-empty is what keeps the button
     * from offering a send that does nothing.
     */
    @Test
    fun whitespaceAloneDoesNotEnableSend() {
        compose.setContent {
            Composer(text = "   \n  ", onTextChange = {}, onSend = {})
        }
        compose.onNodeWithTag("composer-send").assertIsNotEnabled()
    }

    /** A failure is shown inline rather than swallowed. */
    @Test
    fun aFailureIsShown() {
        compose.setContent {
            Composer(
                text = "hey", onTextChange = {}, onSend = {},
                failure = "Couldn't send that.",
            )
        }
        compose.onNodeWithTag("composer-failure").assertIsDisplayed()
    }

    /**
     * While sending, the control does not accept a second tap — the
     * composer's version of the double-tap guard `LoginScreen` already
     * carries over `busy`.
     */
    @Test
    fun aSecondTapWhileSendingIsIgnored() {
        var calls = 0
        compose.setContent {
            Composer(text = "hey", onTextChange = {}, onSend = { calls++ }, sending = true)
        }
        compose.onNodeWithTag("composer-send").performClick()
        assertEquals(0, calls)
    }
}
