package dev.supermessage

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

/**
 * The recovery screen's four states.
 *
 * Worth pinning because two of them are easy to collapse into one and the
 * collapse is harmful: `unknown` must never read as "not set up", since
 * offering a second recovery key to somebody who already has one is how the
 * first one is orphaned.
 */
class RecoveryTest {
    @get:Rule val rule = createComposeRule()

    private val key = "EsTb 8Qn4 7rGa 2mVd 9pLx 3kWc 6yHf 1tRj"

    @Test
    fun an_account_with_no_recovery_is_offered_it() {
        rule.setContent {
            RecoveryPanel(state = "disabled", onEnable = { key }, onRecover = {}, onClose = {})
        }
        rule.onNodeWithTag("recovery-enable").assertIsDisplayed()
    }

    @Test
    fun before_the_first_sync_it_says_checking_not_not_set_up() {
        rule.setContent {
            RecoveryPanel(state = "unknown", onEnable = { key }, onRecover = {}, onClose = {})
        }
        rule.onNodeWithText("Checking this account…").assertIsDisplayed()
        rule.onAllNodesWithTagCount("recovery-enable", 0)
    }

    @Test
    fun the_key_is_shown_once_after_enabling() {
        rule.setContent {
            RecoveryPanel(state = "disabled", onEnable = { key }, onRecover = {}, onClose = {})
        }
        rule.onNodeWithTag("recovery-enable").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("recovery-key").assertIsDisplayed()
    }

    @Test
    fun a_device_missing_its_keys_is_asked_for_one() {
        rule.setContent {
            RecoveryPanel(state = "incomplete", onEnable = { key }, onRecover = {}, onClose = {})
        }
        rule.onNodeWithText("Recovery key").assertIsDisplayed()
    }

    @Test
    fun an_account_already_covered_is_not_offered_a_second_key() {
        // No "Set up recovery" here, and deliberately no way to redisplay the
        // existing key: an app that can show it again is an app that stored it.
        rule.setContent {
            RecoveryPanel(state = "enabled", onEnable = { key }, onRecover = {}, onClose = {})
        }
        rule.onAllNodesWithTagCount("recovery-enable", 0)
        rule.onAllNodesWithTagCount("recovery-key", 0)
    }
}

/** `onAllNodesWithTag(tag).assertCountEquals(n)`, named for what it asserts. */
private fun ComposeTestRule.onAllNodesWithTagCount(tag: String, n: Int) {
    onAllNodesWithTag(tag).assertCountEquals(n)
}
