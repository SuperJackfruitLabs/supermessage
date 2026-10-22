package dev.supermessage

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity

import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import dev.supermessage.kit.stores.StagedAttachment
import java.io.File
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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

    /**
     * A staged attachment is sendable on its own: Send is enabled even when
     * the caption is whitespace only, and the send carries no caption (the
     * caller sends `caption = null`, per `Session.send`).
     */
    @Test
    fun stagedAttachmentWithBlankTextEnablesSend() {
        compose.setContent {
            Composer(
                text = "  ", onTextChange = {}, onSend = {},
                attachment = chipOf(stubAttachment()),
            )
        }
        compose.onNodeWithTag("composer-send").assertIsEnabled()
    }

    /**
     * An edit has no attachment path, so a staged file must not make Save
     * available while the edited body is blank.
     */
    @Test
    fun editingWithAttachmentAndBlankTextDisablesSave() {
        compose.setContent {
            Composer(
                text = "  ", onTextChange = {}, onSend = {},
                editing = EditTarget.Pending(eventId = "e1", body = "original text"),
                attachment = chipOf(stubAttachment()),
            )
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
     * A long sender name must not starve the Cancel button: its box with a
     * name that cannot fit on one line is exactly its box with a short name.
     * Geometry, not existence: the node exists either way, starved or not.
     * (Before the weight on the name's column, an unweighted `Row` measured
     * the button last and left it close to zero width.)
     */
    @Test
    fun theCancelButtonStaysOnOneLineWithALongSenderName() {
        var sender by mutableStateOf("Alice")
        compose.setContent {
            // Narrowed to 320dp so the long name has no way to fit on one
            // line whatever the device's width.
            Box(Modifier.width(320.dp)) {
                Composer(
                    text = "hey",
                    onTextChange = {},
                    onSend = {},
                    replyTo = ReplyTarget.Pending(eventId = "e1", sender = sender, excerpt = null),
                )
            }
        }
        val own = compose.onNodeWithTag("composer-cancel-reply").fetchSemanticsNode().boundsInRoot

        sender = "Alexandria Fitzgerald-Montgomery Wraithborne the Third " +
            "of Barsetshire-upon-Avonlea and its Environs"
        compose.waitForIdle()
        val starved = compose.onNodeWithTag("composer-cancel-reply").fetchSemanticsNode().boundsInRoot

        assertEquals("Cancel's width with a long name", own.width, starved.width, 0.5f)
        assertEquals("Cancel's height with a long name", own.height, starved.height, 0.5f)
        assertTrue("Cancel has a real target (${own.width}x${own.height})", own.width > 0f && own.height > 0f)
    }

    private fun chipOf(file: StagedFile, path: String? = null) = ChipState(file = file, path = path, failure = null)

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
                attachment = chipOf(stubAttachment()),
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
        var attachment by mutableStateOf<ChipState?>(chipOf(stubAttachment()))
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

    /** The strip, the chip and the field are one capsule (iOS `floatingSurface`). */
    @Test
    fun theCapsuleHoldsTheStripChipAndField() {
        compose.setContent {
            Composer(
                text = "hey", onTextChange = {}, onSend = {},
                replyTo = ReplyTarget.Pending(eventId = "e1", sender = "Alice", excerpt = "see you"),
                attachment = chipOf(stubAttachment()),
            )
        }
        val capsule = compose.onNodeWithTag("composer-capsule").fetchSemanticsNode().boundsInRoot
        for (tag in listOf("composer-reply-banner", "composer-attachment-chip", "composer-text", "composer-send", "composer-attach")) {
            val inner = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag $inner is not inside the capsule $capsule",
                inner.left >= capsule.left && inner.right <= capsule.right &&
                    inner.top >= capsule.top && inner.bottom <= capsule.bottom)
        }
    }

    @Test
    fun attachOffersPhotoAndFile() {
        compose.setContent { Composer(text = "", onTextChange = {}, onSend = {}) }
        compose.onNodeWithTag("composer-attach").performClick()
        compose.onNodeWithTag("composer-attach-photo").assertIsDisplayed()
        compose.onNodeWithTag("composer-attach-file").assertIsDisplayed()
        compose.onNodeWithText("Photo").assertIsDisplayed()
        compose.onNodeWithText("File").assertIsDisplayed()
    }

    /** A picture shows itself (C3), not a generic icon. */
    @Test
    fun anImageChipShowsItsThumbnail() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val png = File(context.cacheDir, "thumb-2x2.png")
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also { it.eraseColor(0xFF2356C2.toInt()) }
            .let { b -> png.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        compose.setContent {
            Composer(
                text = "", onTextChange = {}, onSend = {},
                attachment = chipOf(stubAttachment().copy(filename = "thumb-2x2.png", mime = "image/png"), png.absolutePath),
            )
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("composer-chip-thumbnail").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("composer-chip-thumbnail").assertIsDisplayed()
    }

    /** A file that is not a picture does not claim to be one. */
    @Test
    fun aNonImageChipHasNoThumbnail() {
        compose.setContent {
            Composer(text = "", onTextChange = {}, onSend = {},
                attachment = chipOf(stubAttachment().copy(mime = "application/pdf"), "/nonexistent.pdf"))
        }
        compose.waitForIdle()
        compose.onNodeWithTag("composer-chip-thumbnail").assertDoesNotExist()
    }

    /** The reason is on the chip, and the text is held back with it (D12). */
    @Test
    fun aFailedChipSaysWhyAndHoldsSendBack() {
        compose.setContent {
            Composer(
                text = "hi", onTextChange = {}, onSend = {},
                attachment = ChipState(
                    file = null, path = null,
                    failure = StagedAttachment.Failure("big.mov", "That file is too large to send."),
                ),
            )
        }
        compose.onNodeWithTag("composer-chip-failure").assertTextEquals("That file is too large to send.")
        compose.onNodeWithTag("composer-attachment-name").assertTextEquals("big.mov")
        compose.onNodeWithTag("composer-send").assertIsNotEnabled()
    }

    /** A failed send keeps the re-staged file: the reason shows and Send is the retry. */
    @Test
    fun aRestagedFailureSaysWhyAndCanBeRetried() {
        compose.setContent {
            Composer(
                text = "", onTextChange = {}, onSend = {},
                attachment = ChipState(
                    file = stubAttachment(), path = null,
                    failure = StagedAttachment.Failure("vacation.jpg", "Could not reach the homeserver."),
                ),
            )
        }
        compose.onNodeWithTag("composer-chip-failure").assertTextEquals("Could not reach the homeserver.")
        compose.onNodeWithTag("composer-send").assertIsEnabled()
    }

    /** O2: iOS keeps "+" and the chip while editing. */
    @Test
    fun attachStaysVisibleWhileEditing() {
        compose.setContent {
            Composer(
                text = "x", onTextChange = {}, onSend = {},
                editing = EditTarget.Pending(eventId = "e1", body = "x"),
                attachment = chipOf(stubAttachment()),
            )
        }
        compose.onNodeWithTag("composer-attach").assertIsDisplayed()
        compose.onNodeWithTag("composer-attachment-chip").assertIsDisplayed()
    }

    @Test
    fun theControlsHaveLabelsAndTheChipCanBeRemoved() {
        var removed = false
        compose.setContent {
            Composer(
                text = "hi", onTextChange = {}, onSend = {},
                replyTo = ReplyTarget.Pending(eventId = "e1", sender = "Alice", excerpt = null),
                attachment = chipOf(stubAttachment()),
                onDiscardAttachment = { removed = true },
            )
        }
        compose.onNodeWithContentDescription("Attach").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send").assertIsDisplayed()
        compose.onNodeWithContentDescription("Cancel").assertIsDisplayed()
        compose.onNodeWithContentDescription("Remove attachment").performClick()
        assertTrue(removed)
    }

    @Test
    fun theEditControlIsLabelledSaveEdit() {
        compose.setContent {
            Composer(
                text = "x", onTextChange = {}, onSend = {},
                editing = EditTarget.Pending(eventId = "e1", body = "x"),
            )
        }
        compose.onNodeWithContentDescription("Save edit").assertIsDisplayed()
    }

    /** The send control survives a 2x font and a long message (review focus 1). */
    @Test
    fun sendStaysOnScreenAtFontScaleTwo() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                Box(Modifier.requiredWidth(411.dp).testTag("shell")) {
                    Composer(text = "x".repeat(200), onTextChange = {}, onSend = {})
                }
            }
        }
        val root = compose.onNodeWithTag("shell").fetchSemanticsNode().boundsInRoot
        val send = compose.onNodeWithTag("composer-send").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("composer-send").assertIsDisplayed()
        assertTrue("send $send past the right edge of $root", send.right <= root.right)
        val field = compose.onNodeWithTag("composer-text").fetchSemanticsNode().boundsInRoot
        val minWidth = with(Density(compose.density.density)) { 120.dp.toPx() }
        assertTrue("field is only ${field.width}px wide", field.width >= minWidth)
    }

    /** A photo or file whose bytes cannot be read is refused out loud, with iOS's words. */
    @Test
    fun anUnreadablePhotoOrFileIsRefusedWithTheIosWords() {
        val refusals = mutableListOf<Pair<String, String>>()
        val missing = Uri.parse("content://dev.supermessage.nobody/missing.jpg")
        compose.setContent {
            WithPicker(missing) {
                Composer(text = "", onTextChange = {}, onSend = {}, onRefuse = { n, m -> refusals += n to m })
            }
        }
        compose.onNodeWithTag("composer-attach").performClick()
        compose.onNodeWithTag("composer-attach-photo").performClick()
        compose.waitUntil(5_000) { refusals.size == 1 }
        compose.onNodeWithTag("composer-attach").performClick()
        compose.onNodeWithTag("composer-attach-file").performClick()
        compose.waitUntil(5_000) { refusals.size == 2 }
        assertEquals(
            listOf("missing.jpg" to "Couldn't read that photo.", "missing.jpg" to "Couldn't read that file."),
            refusals,
        )
    }

    /** A readable pick is copied into the cache and handed up as a path. */
    @Test
    fun aReadableFilePickIsStagedFromACachedCopy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.filesDir, "picked-report.txt").apply { writeText("hello") }
        val attached = mutableListOf<String>()
        compose.setContent {
            WithPicker(Uri.fromFile(source)) {
                Composer(text = "", onTextChange = {}, onSend = {}, onAttach = { attached += it })
            }
        }
        compose.onNodeWithTag("composer-attach").performClick()
        compose.onNodeWithTag("composer-attach-file").performClick()
        compose.waitUntil(5_000) { attached.isNotEmpty() }
        assertEquals(File(context.cacheDir, "picked-report.txt").absolutePath, attached.single())
        assertEquals("hello", File(attached.single()).readText())
    }

    /** Answers every picker launch with [result], as the system would. */
    @Composable
    private fun WithPicker(result: Uri, content: @Composable () -> Unit) {
        val registry = object : ActivityResultRegistry() {
            override fun <I : Any?, O : Any?> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                @Suppress("UNCHECKED_CAST")
                dispatchResult(requestCode, result as O)
            }
        }
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = registry
        }
        CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner, content = content)
    }
}
