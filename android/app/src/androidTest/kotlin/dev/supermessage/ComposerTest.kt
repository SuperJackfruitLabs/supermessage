package dev.supermessage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.supermessage.kit.stores.EditTarget
import dev.supermessage.kit.stores.ReplyTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.supermessage_ffi.StagedFile

/**
 * The composer, the shape iOS draws at
 * `apple/Supermessage/Composer/ComposerView.swift`: a text field and a send
 * control, gated on whether there is anything worth sending and on whether a
 * send is already in flight. Reply and edit banners are Task 3's, exercised
 * below alongside the text/send/failure surface Task 2 owns.
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

    /** A reply banner names the sender, shows the excerpt, and can be cancelled. */
    @Test
    fun aReplyBannerNamesTheSenderAndCanBeCancelled() {
        var cancelled = false
        compose.setContent {
            Composer(
                text = "hey",
                onTextChange = {},
                onSend = {},
                replyTo = ReplyTarget.Pending(eventId = "e1", sender = "Alice", excerpt = "see you then"),
                onCancelReply = { cancelled = true },
            )
        }

        compose.onNodeWithTag("composer-reply-banner").assertIsDisplayed()
        compose.onNodeWithTag("composer-reply-sender").assertTextEquals("Replying to Alice")
        compose.onNodeWithTag("composer-reply-excerpt").assertTextEquals("see you then")

        compose.onNodeWithTag("composer-cancel-reply").performClick()
        assertEquals(true, cancelled)
    }

    /**
     * Starting an edit fills the field with the message's body — the
     * snapshot `EditTarget.start` hands back for exactly this purpose.
     */
    @Test
    fun startingAnEditFillsTheField() {
        var value by mutableStateOf("")
        var editingState by mutableStateOf<EditTarget.Pending?>(null)
        compose.setContent {
            Composer(text = value, onTextChange = { value = it }, onSend = {}, editing = editingState)
        }

        editingState = EditTarget.Pending(eventId = "e1", body = "original text")
        compose.waitForIdle()

        compose.onNodeWithTag("composer-edit-banner").assertIsDisplayed()
        compose.onNodeWithTag("composer-text").assertTextEquals("original text")
        assertEquals("original text", value)
    }

    /**
     * Cancelling an edit restores whatever draft was half-written before the
     * edit began — losing it would be a data-loss bug, not a UI nit. See
     * [Composer]'s KDoc for where that prior text is held during the edit.
     */
    @Test
    fun cancellingAnEditRestoresThePriorDraft() {
        var value by mutableStateOf("half-written reply")
        var editingState by mutableStateOf<EditTarget.Pending?>(null)
        compose.setContent {
            Composer(
                text = value,
                onTextChange = { value = it },
                onSend = {},
                editing = editingState,
                onCancelEdit = { editingState = null },
            )
        }

        editingState = EditTarget.Pending(eventId = "e1", body = "original text")
        compose.waitForIdle()
        assertEquals("original text", value)

        compose.onNodeWithTag("composer-cancel-edit").performClick()
        compose.waitForIdle()

        assertEquals("half-written reply", value)
    }

    /**
     * A long sender name must not starve the Cancel button of the width (and,
     * downstream of that, the height) it needs to lay out normally — geometry,
     * not existence: the node exists either way, starved or not.
     *
     * [composer-attach] is the reference: an ordinary single-line
     * [androidx.compose.material3.TextButton] elsewhere in this same
     * composable, never touched by this banner's Row, so its width and height
     * are what an unstarved text button in this exact theme looks like. An
     * unweighted `Row` measures its non-weighted children in order, handing
     * each the *remaining* width after the ones before it — so a name long
     * enough to consume the row on its own leaves Cancel measured with
     * (close to) zero width, and therefore zero height too, not a many-lines-
     * tall button. Confirmed empirically: before this test's own fix landed,
     * `cancelHeight` measured here was exactly `0f`, not merely "smaller."
     */
    @Test
    fun theCancelButtonStaysOnOneLineWithALongSenderName() {
        compose.setContent {
            // Narrowed to 320dp so the long name below has no way to fit on
            // one line regardless of the physical device's own width — the
            // fault this test exists to catch depends on the name actually
            // needing to wrap (or fully consume the row) rather than merely
            // being long in characters.
            Box(Modifier.width(320.dp)) {
                Composer(
                    text = "hey",
                    onTextChange = {},
                    onSend = {},
                    replyTo = ReplyTarget.Pending(
                        eventId = "e1",
                        sender = "Alexandria Fitzgerald-Montgomery Wraithborne the Third " +
                            "of Barsetshire-upon-Avonlea and its Environs",
                        excerpt = null,
                    ),
                )
            }
        }

        val cancelBounds = compose.onNodeWithTag("composer-cancel-reply").fetchSemanticsNode().boundsInRoot
        val attachBounds = compose.onNodeWithTag("composer-attach").fetchSemanticsNode().boundsInRoot

        // Close to the reference button's own size, not merely "not
        // enormous": a starved button collapses toward zero on both axes,
        // which a one-sided upper-bound-only check would miss entirely.
        assertTrue(
            "Cancel's width ${cancelBounds.width} starved far below the reference (${attachBounds.width})",
            cancelBounds.width >= attachBounds.width * 0.6f,
        )
        assertTrue(
            "Cancel's height ${cancelBounds.height} starved far below the reference (${attachBounds.height})",
            cancelBounds.height >= attachBounds.height * 0.6f,
        )
    }

    private fun stubAttachment() = StagedFile(
        token = "t1",
        filename = "vacation.jpg",
        sizeBytes = 12_345uL,
        mime = "image/jpeg",
        width = null,
        height = null,
    )

    /**
     * A staged file is shown by name — the composable's half of Task 4's
     * scope, display only. Picking is a platform picker
     * ([androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia])
     * this suite cannot drive headlessly (no compositor to render it
     * deterministically, the same limitation `KeyboardDismissTest` already
     * documented for the IME), so this passes the staged snapshot straight
     * in rather than exercising the picker itself.
     */
    @Test
    fun aStagedAttachmentIsShownByName() {
        compose.setContent {
            Composer(
                text = "hey", onTextChange = {}, onSend = {},
                attachment = stubAttachment(),
            )
        }

        compose.onNodeWithTag("composer-attachment-chip").assertIsDisplayed()
        compose.onNodeWithTag("composer-attachment-name").assertTextEquals("vacation.jpg")
    }

    /**
     * Discarding removes the chip — [onDiscardAttachment] clears the
     * caller's state, and the composable reflects that on the next
     * recomposition, mirroring [cancellingAnEditRestoresThePriorDraft]'s
     * pattern of driving external state through the callback rather than
     * only counting calls.
     */
    @Test
    fun discardingRemovesTheStagedAttachment() {
        var attachment by mutableStateOf<StagedFile?>(stubAttachment())
        compose.setContent {
            Composer(
                text = "hey", onTextChange = {}, onSend = {},
                attachment = attachment,
                onDiscardAttachment = { attachment = null },
            )
        }
        compose.onNodeWithTag("composer-attachment-chip").assertIsDisplayed()

        compose.onNodeWithTag("composer-discard-attachment").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("composer-attachment-chip").assertDoesNotExist()
    }
}
