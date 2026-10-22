package dev.supermessage

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import dev.supermessage.kit.stores.ConnectionStore.Connection
import org.junit.Rule
import org.junit.Test

/**
 * The slim status row `apple/Supermessage/RootView.swift` calls `ConnectionBar`:
 * a label, an optional muted message, and nothing at all when live.
 */
class ConnectionBarTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun offlineShowsLabelAndMessage() {
        compose.setContent {
            SupermessageTheme {
                ConnectionBar(Connection.Offline, message = "connection refused", worthShowing = true)
            }
        }
        compose.onNodeWithTag("connection-bar").assertIsDisplayed()
        compose.onNodeWithText("offline").assertIsDisplayed()
        compose.onNodeWithText("connection refused").assertIsDisplayed()
    }

    @Test
    fun hiddenWhenNotWorthShowing() {
        compose.setContent {
            SupermessageTheme {
                ConnectionBar(Connection.Live, message = null, worthShowing = false)
            }
        }
        compose.onNodeWithTag("connection-bar").assertDoesNotExist()
    }

    @Test
    fun errorReadsReconnecting() {
        compose.setContent {
            SupermessageTheme {
                ConnectionBar(Connection.Error, message = null, worthShowing = true)
            }
        }
        compose.onNodeWithText("reconnecting…").assertIsDisplayed()
    }

    @Test
    fun unknownShowsTheRawWord() {
        compose.setContent {
            SupermessageTheme {
                ConnectionBar(Connection.Unknown("catching-up"), message = null, worthShowing = true)
            }
        }
        compose.onNodeWithText("catching-up").assertIsDisplayed()
    }
}
