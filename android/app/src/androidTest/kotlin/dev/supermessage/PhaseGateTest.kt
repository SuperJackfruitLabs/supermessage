package dev.supermessage

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dev.supermessage.kit.Session
import org.junit.Rule
import org.junit.Test

/**
 * Which of RootScaffold's three branches renders for each [Session.Phase] —
 * the shape iOS uses at apple/Supermessage/RootView.swift:15-25.
 *
 * This is the gate, not the pane rule: RootScaffoldTest's three geometry
 * tests already pin what happens inside the SIGNED_IN branch, and this suite
 * never touches that. It only asserts which branch is on screen.
 */
class PhaseGateTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun startingShowsProgressAndNoPanes() {
        compose.setContent { RootScaffold(phase = Session.Phase.STARTING) }
        compose.onNodeWithTag("phase-starting").assertIsDisplayed()
        compose.onNodeWithTag("pane-roster").assertDoesNotExist()
    }

    @Test
    fun signedOutShowsLoginAndNoPanes() {
        compose.setContent { RootScaffold(phase = Session.Phase.SIGNED_OUT) }
        compose.onNodeWithTag("login").assertIsDisplayed()
        compose.onNodeWithTag("pane-roster").assertDoesNotExist()
    }

    @Test
    fun signedInShowsThePanes() {
        compose.setContent { RootScaffold(phase = Session.Phase.SIGNED_IN) }
        compose.onNodeWithTag("pane-roster").assertIsDisplayed()
        compose.onNodeWithTag("login").assertDoesNotExist()
    }
}
