package dev.supermessage

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test

/**
 * The recovery screen's two situations.
 *
 * The SDK reports four states; a person is in one of two, and these tests pin
 * the mapping rather than the states. Two of the four are easy to collapse
 * wrongly:
 *
 * - `unknown` must never read as "not set up". Offering a second recovery key
 *   to somebody who already has one is how the first one is orphaned.
 * - `disabled` and `incomplete` must read the *same*, because to a reader they
 *   are the same predicament — the history is not reachable from this device —
 *   and the two buttons are the only difference that matters.
 */
class RecoveryTest {
    @get:Rule val rule = createComposeRule()

    private val key = "EsTb 8Qn4 7rGa 2mVd 9pLx 3kWc 6yHf 1tRj"

    private fun panel(state: String, onReset: suspend (String) -> String = { key }) {
        rule.setContent {
            RecoveryPanel(
                state = state,
                onEnable = { key },
                onRecover = {},
                onReset = onReset,
                onClose = {},
            )
        }
    }

    @Test
    fun before_the_first_sync_it_says_checking_not_not_set_up() {
        panel("unknown")
        rule.onNodeWithText("Checking this account…").assertIsDisplayed()
        rule.onAllNodesWithTagCount("recovery-reset", 0)
    }

    @Test
    fun a_device_missing_its_keys_is_asked_for_one() {
        panel("incomplete")
        rule.onNodeWithText("Recovery key").assertIsDisplayed()
    }

    @Test
    fun an_account_with_no_recovery_reads_the_same_as_one_missing_its_keys() {
        // The collapse this screen is built on: both are "this device cannot
        // read the history", and showing them as two different pages was how a
        // reader ended up being told their device "is incomplete".
        panel("disabled")
        rule.onNodeWithText("Recovery key").assertIsDisplayed()
        rule.onNodeWithTag("recovery-reset").assertIsDisplayed()
    }

    @Test
    fun a_stranded_device_is_never_a_dead_end() {
        // The regression that prompted all of this. Somebody who never had a
        // recovery key used to be shown a field they could not fill and given
        // nothing else — their account was unreachable from their own phone.
        panel("incomplete")
        rule.onNodeWithTag("recovery-reset").assertIsDisplayed()
    }

    @Test
    fun starting_over_asks_for_the_password_before_doing_anything() {
        // Destructive, so it costs a deliberate second step. The reset must not
        // fire on the first tap.
        var reset = false
        panel("incomplete", onReset = {
            reset = true
            key
        })
        rule.onNodeWithTag("recovery-reset").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Your password").assertIsDisplayed()
        assert(!reset) { "the reset fired before the password was given" }
    }

    @Test
    fun starting_over_shows_the_new_key_once() {
        panel("incomplete")
        rule.onNodeWithTag("recovery-reset").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Your password").performTextInput("hunter2")
        rule.onNodeWithTag("recovery-reset-confirm").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("recovery-key").assertIsDisplayed()
    }

    @Test
    fun an_account_already_covered_is_not_offered_a_second_key() {
        // Deliberately no way to redisplay the existing key: an app that can
        // show it again is an app that stored it. And no reset here either —
        // this reader has nothing to fix.
        panel("enabled")
        rule.onAllNodesWithTagCount("recovery-key", 0)
        rule.onAllNodesWithTagCount("recovery-reset", 0)
    }
}

/** `onAllNodesWithTag(tag).assertCountEquals(n)`, named for what it asserts. */
private fun ComposeTestRule.onAllNodesWithTagCount(tag: String, n: Int) {
    onAllNodesWithTag(tag).assertCountEquals(n)
}
