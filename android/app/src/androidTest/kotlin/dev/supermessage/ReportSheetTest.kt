package dev.supermessage

import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.supermessage.kit.ReportSubject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The report sheet (`ReportSheet.swift`), driven through the real
 * `ModalBottomSheet`. Run this class at both 1080x2400 and 1080x1280: the
 * reason field, the toggle and Send all have to stay reachable when the
 * keyboard takes half the screen.
 */
class ReportSheetTest {
    @get:Rule val compose = createComposeRule()

    private class RecordingHaptics : HapticFeedback {
        val played = mutableListOf<HapticFeedbackType>()
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            played += hapticFeedbackType
        }
    }

    private val message = ReportSubject.Message("!r", "\$e", "@s:x", "Sam", isAgent = true)
    private val room = ReportSubject.Room("!r", "Lounge")
    private val person = ReportSubject.User("@p:x", "Pat", isAgent = false)

    private class Sent(val reason: String, val alsoBlock: Boolean)

    private fun show(
        subject: ReportSubject = message,
        blocked: Set<String> = emptySet(),
        haptics: RecordingHaptics = RecordingHaptics(),
        fontScale: Float? = null,
        remaining: (String) -> Long = { 1500L },
        onClose: () -> Unit = {},
        onSend: suspend (String, Boolean) -> String? = { _, _ -> null },
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalHapticFeedback provides haptics,
                LocalDensity provides if (fontScale == null) density else Density(density.density, fontScale),
            ) {
                Box(Modifier.requiredWidth(411.dp)) {
                    ReportSheet(
                        subject = subject,
                        isBlocked = { it in blocked },
                        onSend = onSend,
                        onClose = onClose,
                        remaining = remaining,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun theToggleIsOnByDefaultForAMessage() {
        show(message)
        compose.onNodeWithTag("report-also-block").performScrollTo().assertIsOn()
        compose.onNodeWithText("Also block Sam").assertExists()
        compose.onNodeWithText(ReportCopy.blockConsequence(isAgent = true)).assertExists()
    }

    @Test
    fun aRoomHasNoToggle() {
        show(room)
        compose.onNodeWithTag("report-also-block").assertDoesNotExist()
        compose.onNodeWithTag("report-already-blocked").assertDoesNotExist()
        compose.onNodeWithTag("report-block-footer").assertDoesNotExist()
    }

    @Test
    fun someoneAlreadyBlockedShowsTheLineNotTheToggle() {
        show(person, blocked = setOf("@p:x"))
        compose.onNodeWithTag("report-also-block").assertDoesNotExist()
        compose.onNodeWithTag("report-already-blocked").performScrollTo().assertTextEquals("Pat is blocked")
    }

    @Test
    fun theCounterAppearsOnlyNearTheLimit() {
        show(remaining = { 1500L })
        compose.onNodeWithTag("report-counter").assertDoesNotExist()
    }

    @Test
    fun theCounterShowsWhatIsLeft() {
        show(remaining = { 150L })
        compose.onNodeWithTag("report-counter").performScrollTo().assertTextEquals("150 characters left")
    }

    @Test
    fun overTheLimitSendIsDisabled() {
        show(remaining = { -3L })
        compose.onNodeWithTag("report-counter").performScrollTo().assertTextEquals("3 characters over the limit")
        compose.onNodeWithTag("report-send").assertIsNotEnabled()
    }

    @Test
    fun sendAsksFirst() {
        val sent = mutableListOf<Sent>()
        show(onSend = { reason, block -> sent += Sent(reason, block); null })
        compose.onNodeWithTag("report-send").performClick()
        compose.onNodeWithTag("report-confirm").assertIsDisplayed()
        compose.onNodeWithText("Report and block").assertIsDisplayed()
        compose.onNodeWithText("Report this message?").assertIsDisplayed()
        compose.onNodeWithText("The homeserver's administrators will see it. Sam won't be told.").assertIsDisplayed()
        assertTrue("asked before sending", sent.isEmpty())
    }

    @Test
    fun cancellingTheConfirmationSendsNothing() {
        val sent = mutableListOf<Sent>()
        show(onSend = { reason, block -> sent += Sent(reason, block); null })
        compose.onNodeWithTag("report-send").performClick()
        compose.onNodeWithTag("report-confirm-cancel").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("report-confirm").assertDoesNotExist()
        compose.onNodeWithTag("report-sheet").assertIsDisplayed()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun confirmingSendsWithTheBlockChoice() {
        val sent = mutableListOf<Sent>()
        show(onSend = { reason, block -> sent += Sent(reason, block); null })
        compose.onNodeWithTag("report-reason").performTextInput("why")
        compose.onNodeWithTag("report-also-block").performScrollTo().performClick()
        compose.onNodeWithTag("report-also-block").assertIsOff()
        compose.onNodeWithTag("report-send").performClick()
        compose.onNodeWithTag("report-confirm-send").performClick()
        compose.waitForIdle()
        assertEquals(listOf("why" to false), sent.map { it.reason to it.alsoBlock })
    }

    @Test
    fun aRoomNeverSendsABlock() {
        val sent = mutableListOf<Sent>()
        show(room, onSend = { reason, block -> sent += Sent(reason, block); null })
        compose.onNodeWithTag("report-send").performClick()
        compose.onNodeWithTag("report-confirm-send").performClick()
        compose.waitForIdle()
        assertEquals(listOf("" to false), sent.map { it.reason to it.alsoBlock })
    }

    @Test
    fun aRefusedReportKeepsTheReasonAndSaysWhy() {
        val haptics = RecordingHaptics()
        var closed = 0
        val refusal = "This homeserver doesn't accept person reports. Nothing was sent."
        show(haptics = haptics, onClose = { closed++ }, onSend = { _, _ -> refusal })
        compose.onNodeWithTag("report-reason").performTextInput("they were rude")
        compose.onNodeWithTag("report-send").performClick()
        compose.onNodeWithTag("report-confirm-send").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("report-failure").performScrollTo().assertTextEquals(refusal)
        compose.onNodeWithTag("report-reason").assertTextContains("they were rude")
        assertEquals(listOf(HapticFeedbackType.Reject), haptics.played)
        assertEquals(0, closed)
        // It can be tried again.
        compose.onNodeWithTag("report-send").assertIsEnabled()
    }

    @Test
    fun aLandedReportClosesWithSuccess() {
        val haptics = RecordingHaptics()
        var closed = 0
        show(haptics = haptics, onClose = { closed++ })
        compose.onNodeWithTag("report-send").performClick()
        compose.onNodeWithTag("report-confirm-send").performClick()
        compose.waitForIdle()
        assertEquals(listOf(HapticFeedbackType.Confirm), haptics.played)
        assertEquals(1, closed)
    }

    /** Lesson from desktop M3: a report that landed is never offered again. */
    @Test
    fun aBlockThatFailedAfterALandedReportOffersOnlyDone() {
        var sends = 0
        var closed = 0
        show(
            onClose = { closed++ },
            onSend = { _, _ -> sends++; "Reported, but blocking didn't work: You can't block yourself." },
        )
        compose.onNodeWithTag("report-send").performClick()
        compose.onNodeWithTag("report-confirm-send").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Report sent").assertIsDisplayed()
        compose.onNodeWithTag("report-failure")
            .assertTextEquals("Blocking didn't work: You can't block yourself.")
        compose.onNodeWithTag("report-send").assertDoesNotExist()
        compose.onNodeWithTag("report-cancel").assertDoesNotExist()
        compose.onNodeWithTag("report-also-block").assertDoesNotExist()
        compose.onNodeWithTag("report-reason").assertDoesNotExist()
        assertEquals(1, sends)
        compose.onNodeWithTag("report-done").performClick()
        compose.waitForIdle()
        assertEquals(1, closed)
        assertEquals(1, sends)
    }

    @Test
    fun cancelClosesTheSheet() {
        var closed = 0
        show(onClose = { closed++ })
        compose.onNodeWithTag("report-cancel").performClick()
        compose.waitForIdle()
        assertEquals(1, closed)
    }

    /** The keyboard can close it, as TalkBack's "dismiss" and the scrim do. */
    @Test
    fun escapeClosesTheSheet() {
        var closed = 0
        show(onClose = { closed++ })
        compose.onNodeWithTag("report-cancel").requestFocus()
        compose.onNodeWithTag("report-cancel").performKeyInput { pressKey(Key.Escape) }
        compose.waitForIdle()
        assertEquals(1, closed)
    }

    /** What TalkBack's "dismiss" does to a sheet, and what a keyboard user's Escape asks of it. */
    @Test
    fun theDismissActionClosesTheSheetWhenNothingIsSending() {
        var closed = 0
        show(onClose = { closed++ })
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)).performSemanticsAction(SemanticsActions.Dismiss)
        compose.waitForIdle()
        assertEquals(1, closed)
    }

    /**
     * Back for the sheet, delivered the way the platform would once it routes
     * the key: to the back dispatcher of the sheet's own window (a
     * `ModalBottomSheet` is a separate window, and its back handler registers
     * with the dispatcher of that window, not the activity's). An injected
     * KEYCODE_BACK never reaches the app on the CI AVD, so key routing is left
     * out; the sheet's BackHandler -> dismiss request path is what runs.
     */
    private fun pressSystemBack() {
        val sheetView = (compose.onNodeWithTag("report-sheet").fetchSemanticsNode().root as androidx.compose.ui.platform.ViewRootForTest).view
        val dispatcher = checkNotNull(sheetView.findViewTreeOnBackPressedDispatcherOwner()) {
            "the sheet window has no back dispatcher"
        }.onBackPressedDispatcher
        compose.runOnUiThread { dispatcher.onBackPressed() }
    }

    /** The system back button is the other way out, and it asks the sheet like the scrim does. */
    @Test
    fun systemBackClosesTheSheetWhenNothingIsSending() {
        var closed = 0
        show(onClose = { closed++ })
        pressSystemBack()
        compose.waitForIdle()
        assertEquals(1, closed)
    }

    @Test
    fun theSheetCannotBeDismissedWhileSending() {
        val gate = kotlinx.coroutines.CompletableDeferred<String?>()
        var closed = 0
        show(onClose = { closed++ }, onSend = { _, _ -> gate.await() })
        compose.onNodeWithTag("report-send").performClick()
        compose.onNodeWithTag("report-confirm-send").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("report-sending").assertIsDisplayed()
        compose.onNodeWithTag("report-cancel").assertIsNotEnabled()
        // Neither TalkBack's dismiss action nor the system back button may close it.
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)).performSemanticsAction(SemanticsActions.Dismiss)
        compose.waitForIdle()
        pressSystemBack()
        compose.waitForIdle()
        assertEquals("nothing closes it while sending", 0, closed)
        compose.onNodeWithTag("report-sheet").assertIsDisplayed()
        gate.complete(null)
        compose.waitForIdle()
        assertEquals(1, closed)
    }

    @Test
    fun reportSheetSendsAtFontScaleTwo() {
        val sent = mutableListOf<Sent>()
        show(fontScale = 2f, onSend = { reason, block -> sent += Sent(reason, block); null })
        compose.onNodeWithTag("report-reason").performTextInput("x".repeat(300))
        // The toggle is the last thing in the scrolling body; it has to be reachable too.
        compose.onNodeWithTag("report-also-block").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("report-send").assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithTag("report-confirm-send").performClick()
        compose.waitForIdle()
        assertEquals(1, sent.size)
        assertEquals(300, sent[0].reason.length)
    }
}
