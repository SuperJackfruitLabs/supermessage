package dev.supermessage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dev.supermessage.kit.Session
import org.junit.Rule
import org.junit.Test

/**
 * Which of RootScaffold's phases renders which content — the shape iOS
 * uses at apple/Supermessage/RootView.swift:15-25.
 *
 * This is the gate, not the screens behind it: RootScaffoldTest's four
 * geometry tests already pin what SIGNED_IN's default list pane looks like,
 * and LoginScreenTest pins what the real sign-in form does. `signedOutContent`
 * here is this suite's own tagged stub, not `LoginScreen` — so a change to
 * what `LoginScreen` renders can never make this suite red for the wrong
 * reason, and a change to *this* suite can never accidentally start
 * asserting on LoginScreen's behavior instead of the gate's.
 */
class PhaseGateTest {

    @get:Rule val compose = createComposeRule()

    private companion object {
        const val StubTag = "stub-signed-out"
    }

    @Test
    fun startingShowsProgressAndNoPanes() {
        compose.setContent { RootScaffold(phase = Session.Phase.STARTING) }
        compose.onNodeWithTag("phase-starting").assertIsDisplayed()
        compose.onNodeWithTag("pane-roster").assertDoesNotExist()
    }

    @Test
    fun signedOutShowsTheSuppliedContentAndNoPanes() {
        compose.setContent {
            RootScaffold(
                phase = Session.Phase.SIGNED_OUT,
                signedOutContent = { Box(Modifier.fillMaxSize().testTag(StubTag)) },
            )
        }
        compose.onNodeWithTag(StubTag).assertIsDisplayed()
        compose.onNodeWithTag("pane-roster").assertDoesNotExist()
    }

    @Test
    fun signedInShowsThePanesAndNotTheStartingProgress() {
        compose.setContent { RootScaffold(phase = Session.Phase.SIGNED_IN) }
        compose.onNodeWithTag("pane-roster").assertIsDisplayed()
        compose.onNodeWithTag("phase-starting").assertDoesNotExist()
    }
}
