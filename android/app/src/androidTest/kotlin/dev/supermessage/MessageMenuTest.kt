package dev.supermessage

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.supermessage_core.ItemView
import uniffi.supermessage_core.RichBlock
import uniffi.supermessage_core.RichInline
import uniffi.supermessage_core.TimelineItemDto
import uniffi.supermessage_core.TimelineRow as TimelineRowDto

/**
 * The long-press menu (`TimelineCollectionView.swift:1039-1150`), driven the
 * way a reader reaches it: a long press on a row of a [Timeline]. The rule
 * for what a row offers lives in [messageMenuActions] and is observed through
 * the sheet it opens, not by handing the sheet pre-computed flags.
 */
class MessageMenuTest {
    @get:Rule val compose = createComposeRule()

    private fun row(
        id: String = "1",
        body: String? = "hi",
        isOwn: Boolean = false,
        canReplyOrReact: Boolean = true,
        editable: Boolean = false,
    ): TimelineRowDto {
        val item = TimelineItemDto(
            id = id, eventId = id, kind = "message", msgtype = "m.text", detail = null,
            sender = "@a:x", senderDisplayName = null, senderAvatar = null, body = body,
            formattedBody = null, media = null, customPayload = null, timestampMs = 1_000uL,
            isOwn = isOwn, sendState = null, replyTo = null, edited = false,
            reactions = emptyList(), readBy = emptyList(), editable = editable, membershipSubject = null,
        )
        return TimelineRowDto(
            item = item,
            view = ItemView.Bubble(
                muted = false,
                blocks = listOf(RichBlock.Paragraph(inlines = listOf(RichInline.Text(body.orEmpty())))),
                voice = null,
            ),
            senderName = "Sender", senderShort = "Sender", senderInitial = "?", membershipVerb = null,
            replyQuote = null, canReplyOrReact = canReplyOrReact, replyPreview = null,
        )
    }

    private class FakeClipboard : ClipboardManager {
        var held: AnnotatedString? = null
        override fun setText(annotatedString: AnnotatedString) { held = annotatedString }
        override fun getText(): AnnotatedString? = held
    }

    private class RecordingHaptics : HapticFeedback {
        val played = mutableListOf<HapticFeedbackType>()
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { played += hapticFeedbackType }
    }

    private fun show(
        target: TimelineRowDto,
        clipboard: ClipboardManager = FakeClipboard(),
        haptics: HapticFeedback = RecordingHaptics(),
        onReact: (TimelineRowDto, String) -> Unit = { _, _ -> },
        onDelete: suspend (TimelineRowDto) -> Boolean = { true },
        onReport: (TimelineRowDto) -> Unit = {},
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalClipboardManager provides clipboard, LocalHapticFeedback provides haptics) {
                Timeline(
                    rows = listOf(target),
                    revision = 0uL,
                    typingLine = null,
                    isPaginating = false,
                    canPaginate = false,
                    onPaginate = {},
                    onMarkRead = {},
                    onReact = onReact,
                    onDelete = onDelete,
                    onReport = onReport,
                )
            }
        }
    }

    private fun openMenu() {
        compose.onNodeWithTag("row-1").performTouchInput { longClick() }
        compose.onNodeWithTag("message-menu").assertIsDisplayed()
    }

    @Test
    fun othersReplyableMessageOffersReactReplyCopy() {
        show(row(isOwn = false, canReplyOrReact = true, body = "hi", editable = false))
        openMenu()
        compose.onNodeWithTag("menu-reply").assertIsDisplayed()
        compose.onNodeWithTag("menu-copy").assertIsDisplayed()
        compose.onNodeWithTag("menu-edit").assertDoesNotExist()
        compose.onNodeWithTag("menu-delete").assertDoesNotExist()
        compose.onAllNodes(hasTestTagPrefix("quick-reaction-")).assertCountEquals(4)
    }

    @Test
    fun reportOfferedOnSomeoneElsesSentMessage() {
        val reported = mutableListOf<TimelineRowDto>()
        val target = row(isOwn = false)
        show(target, onReport = { reported += it })
        openMenu()
        compose.onNodeWithTag("menu-report").assertIsDisplayed()
        compose.onNodeWithText("Report…").assertIsDisplayed()
        compose.onNodeWithTag("menu-report").performClick()
        compose.waitForIdle()
        assertEquals(listOf(target), reported)
        compose.onNodeWithTag("message-menu").assertDoesNotExist()
    }

    @Test
    fun reportIsTheLastItem() {
        show(row(isOwn = false))
        openMenu()
        val tops = listOf("menu-reply", "menu-copy", "menu-report").map {
            compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals(tops.sorted(), tops)
    }

    @Test
    fun reportNotOfferedOnYourOwn() {
        show(row(isOwn = true, editable = true))
        openMenu()
        compose.onNodeWithTag("menu-report").assertDoesNotExist()
    }

    @Test
    fun reportNotOfferedWhilePending() {
        show(row(isOwn = false, canReplyOrReact = false, body = "hi"))
        openMenu()
        compose.onNodeWithTag("menu-report").assertDoesNotExist()
        compose.onNodeWithTag("menu-copy").assertIsDisplayed()
    }

    /** No body, not editable, not own, sent: a menu of the strip, Reply and Report… and nothing else. */
    @Test
    fun reportAloneMakesAMenu() {
        val bare = row(isOwn = false, canReplyOrReact = true, body = null, editable = false)
        assertTrue(messageMenuActions(bare).report)
        show(bare)
        openMenu()
        compose.onNodeWithTag("menu-report").assertIsDisplayed()
        compose.onNodeWithTag("menu-reply").assertIsDisplayed()
        compose.onNodeWithTag("menu-copy").assertDoesNotExist()
        compose.onNodeWithTag("menu-edit").assertDoesNotExist()
        compose.onNodeWithTag("menu-delete").assertDoesNotExist()
    }

    @Test
    fun ownMessageOffersEditAndDelete() {
        show(row(isOwn = true, canReplyOrReact = true, editable = true))
        openMenu()
        compose.onNodeWithTag("menu-edit").assertIsDisplayed()
        compose.onNodeWithTag("menu-delete").assertIsDisplayed()
    }

    @Test
    fun pendingOwnMessageOffersCopyOnly() {
        show(row(isOwn = true, canReplyOrReact = false, body = "hi", editable = false))
        openMenu()
        compose.onNodeWithTag("menu-copy").assertIsDisplayed()
        compose.onNodeWithTag("menu-reply").assertDoesNotExist()
        compose.onNodeWithTag("menu-edit").assertDoesNotExist()
        compose.onNodeWithTag("menu-delete").assertDoesNotExist()
        compose.onAllNodes(hasTestTagPrefix("quick-reaction-")).assertCountEquals(0)
    }

    @Test
    fun nothingToOfferOpensNoMenu() {
        val empty = row(canReplyOrReact = false, body = null, editable = false)
        assertTrue(messageMenuActions(empty).isEmpty)
        show(empty)
        compose.onNodeWithTag("row-1").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag("message-menu").assertDoesNotExist()
    }

    @Test
    fun deleteAsksFirst() {
        val calls = mutableListOf<TimelineRowDto>()
        val target = row(isOwn = true, editable = true)
        show(target, onDelete = { calls += it; true })
        openMenu()
        compose.onNodeWithTag("menu-delete").performClick()
        compose.onNodeWithText("Delete message?").assertIsDisplayed()
        compose.onNodeWithText("This removes it for everyone in the room.").assertIsDisplayed()
        assertTrue("asked before deleting", calls.isEmpty())
        compose.onNodeWithTag("delete-confirm-button").performClick()
        compose.waitForIdle()
        assertEquals(listOf(target), calls)
    }

    @Test
    fun cancelDeletesNothing() {
        val calls = mutableListOf<TimelineRowDto>()
        show(row(isOwn = true, editable = true), onDelete = { calls += it; true })
        openMenu()
        compose.onNodeWithTag("menu-delete").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertTrue(calls.isEmpty())
        compose.onNodeWithTag("delete-confirm-button").assertDoesNotExist()
    }

    @Test
    fun aFailedDeleteSaysSoAndClearsAfterFourSeconds() {
        show(row(isOwn = true, editable = true), onDelete = { false })
        openMenu()
        compose.onNodeWithTag("menu-delete").performClick()
        compose.onNodeWithTag("delete-confirm-button").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Not sent. Try again.").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(4_500)
        compose.onNodeWithText("Not sent. Try again.").assertDoesNotExist()
    }

    @Test
    fun copyPutsTheBodyOnTheClipboard() {
        val clipboard = FakeClipboard()
        show(row(body = "hi"), clipboard = clipboard)
        openMenu()
        compose.onNodeWithTag("menu-copy").performClick()
        compose.waitForIdle()
        assertEquals("hi", clipboard.getText()?.text)
    }

    @Test
    fun aMenuReactionIsASelectionHaptic() {
        val haptics = RecordingHaptics()
        val reacted = mutableListOf<String>()
        show(row(), haptics = haptics, onReact = { _, key -> reacted += key })
        openMenu()
        // Opening the menu is itself a long press, which the platform
        // acknowledges; what is asserted is what the *reaction* plays.
        haptics.played.clear()
        compose.onNodeWithTag("quick-reaction-✅").performClick()
        compose.waitForIdle()
        assertEquals(listOf("✅"), reacted)
        assertEquals(listOf(HapticFeedbackType.SegmentTick), haptics.played)
    }
}

/** A node whose test tag starts with [prefix] — counts a family of tagged nodes. */
internal fun hasTestTagPrefix(prefix: String) = SemanticsMatcher("test tag starts with $prefix") {
    it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
}
