package dev.supermessage

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.unit.Density
import dev.supermessage.kit.ReportSubject
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.supermessage_core.NotificationMode
import uniffi.supermessage_core.RoomIdentity
import uniffi.supermessage_core.RoomInfoDto
import uniffi.supermessage_core.RoomMemberDto
import uniffi.supermessage_core.RuntimeDto
import uniffi.supermessage_ffi.FfiException
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * [RoomInfoPanel], the port of `apple/Supermessage/Panels/RoomInfoPanel.swift`.
 *
 * Every test here drives the composable directly with fake suspend lambdas
 * standing in for `Session.roomInfo`/`setNotifications`/`setPinned`/
 * `leaveRoom` — the same shape `ComposerTest` already exercises `Composer`
 * with plain callbacks rather than a real `Session`. This panel has no store
 * to fake instead (see [RoomInfoPanel]'s own KDoc on why), so the fakes below
 * are the whole substitute for the core.
 */
class RoomInfoTest {
    @get:Rule val compose = createComposeRule()

    private fun sampleInfo(
        notifications: NotificationMode = NotificationMode.DEFAULT,
        pinned: Boolean = false,
        members: List<RoomMemberDto> = listOf(
            RoomMemberDto(userId = "@me:example.org", displayName = "Me", avatarUrl = null),
            RoomMemberDto(userId = "@alice:example.org", displayName = "Alice", avatarUrl = null),
            RoomMemberDto(userId = "@bob:example.org", displayName = null, avatarUrl = null),
            RoomMemberDto(userId = "@agent_atlas:example.org", displayName = "Atlas", avatarUrl = null, isAgent = true),
        ),
        runtime: RuntimeDto? = null,
    ) = RoomInfoDto(
        roomId = "!room:example.org",
        name = "Ops Room",
        identity = RoomIdentity(glyph = null, name = "Ops Room", role = "Infra team", initial = "O"),
        topic = "Where the pager goes",
        runtime = runtime,
        canonicalAlias = "#ops:example.org",
        altAliases = emptyList(),
        activeMemberCount = 3uL,
        members = members,
        notifications = notifications,
        pinned = pinned,
    )

    private class RecordingHaptics : HapticFeedback {
        val played = mutableListOf<HapticFeedbackType>()
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            played += hapticFeedbackType
        }
    }

    /** What the core says is blocked; the fakes below flip it as the real session's echo would. */
    private var blocked by mutableStateOf(emptySet<String>())
    private val haptics = RecordingHaptics()
    private val reported = mutableListOf<Triple<ReportSubject, String, Boolean>>()

    private fun panel(
        info: () -> RoomInfoDto = { sampleInfo() },
        accountUserId: String? = "@me:example.org",
        onClose: () -> Unit = {},
        onSetNotifications: (NotificationMode) -> Boolean = { true },
        onSetPinned: (Boolean) -> Boolean = { true },
        onLeaveRoom: () -> Unit = {},
        onBlock: suspend (String) -> String? = { blocked = blocked + it; null },
        onUnblock: suspend (String) -> String? = { blocked = blocked - it; null },
        onReport: suspend (ReportSubject, String, Boolean) -> String? = { subject, reason, block ->
            reported += Triple(subject, reason, block)
            null
        },
        fontScale: Float? = null,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            // Rows take focus only in keyboard mode (touch mode makes clickables
            // unfocusable); returning focus is for keyboard and switch users.
            val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
            androidx.compose.runtime.SideEffect { inputMode.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard) }
            CompositionLocalProvider(
                LocalHapticFeedback provides haptics,
                LocalDensity provides if (fontScale == null) density else Density(density.density, fontScale),
            ) {
                Box(Modifier.requiredWidth(411.dp)) {
                    SupermessageTheme {
                        RoomInfoPanel(
                            roomId = "!room:example.org",
                            accountUserId = accountUserId,
                            avatarUri = null,
                            onClose = onClose,
                            loadInfo = { info() },
                            onSetNotifications = { mode -> onSetNotifications(mode) },
                            onSetPinned = { pinned -> onSetPinned(pinned) },
                            onLeaveRoom = { onLeaveRoom() },
                            isBlocked = { it in blocked },
                            onBlock = onBlock,
                            onUnblock = onUnblock,
                            onReport = onReport,
                        )
                    }
                }
            }
        }
    }

    private fun waitForTag(tag: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Focus is handed back from an effect after the sheet leaves; wait for it rather than race it. */
    private fun awaitFocus(tag: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull()
                ?.config?.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Focused) == true
        }
        compose.onNodeWithTag(tag).assertIsFocused()
    }

    private fun openMember(userId: String) {
        compose.waitForIdle()
        compose.onNodeWithTag("member-$userId").performScrollTo().performClick()
        waitForTag("member-actions")
    }

    /**
     * The panel shows exactly what [RoomInfoDto] says — the parsed name, the
     * parsed role, the topic, and the member list minus this account — and
     * derives none of it itself. `"Ops Room"` and `"Infra team"` come from
     * `identity`, never from a raw name this composable would have to split
     * on its own.
     */
    @Test
    fun thePanelShowsWhatRoomInfoSaysAndDerivesNone() {
        panel()
        compose.waitForIdle()

        compose.onNodeWithText("Ops Room").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Infra team").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Where the pager goes").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("#ops:example.org").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("!room:example.org").performScrollTo().assertIsDisplayed()

        // Two others, so the plural header with the core's own count —
        // not `members.size`, which would read 2 (self already excluded).
        compose.onNodeWithText("Members (3)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Alice").performScrollTo().assertIsDisplayed()
        // No display name: falls back to the raw id, never a name this
        // composable invents.
        compose.onNodeWithText("@bob:example.org").performScrollTo().assertIsDisplayed()

        // This account is never in its own member list.
        compose.onNodeWithText("Me").assertDoesNotExist()
    }

    /** A room's runtime — read out of the topic by the core, never re-parsed here. */
    @Test
    fun anAgentsRoomShowsItsHarnessAndMachine() {
        panel(info = { sampleInfo(runtime = RuntimeDto(harness = "Claude Code", host = "Ashram")) })
        compose.waitForIdle()

        compose.onNodeWithText("Claude Code").assertIsDisplayed()
        compose.onNodeWithText("Ashram").assertIsDisplayed()
    }

    /** A room with exactly one other person names them, with no count. */
    @Test
    fun aSoleOtherMemberIsShownWithoutACount() {
        panel(
            info = {
                sampleInfo(
                    members = listOf(
                        RoomMemberDto(userId = "@me:example.org", displayName = "Me", avatarUrl = null),
                        RoomMemberDto(userId = "@alice:example.org", displayName = "Alice", avatarUrl = null),
                    ),
                )
            },
        )
        compose.waitForIdle()

        compose.onNodeWithText("Members").assertIsDisplayed()
        compose.onNodeWithText("Alice").assertIsDisplayed()
        compose.onNodeWithText("Members (2)").assertDoesNotExist()
    }

    /**
     * Mute round-trips through the write path: the switch reflects the
     * optimistic write immediately, the write itself carries the direction
     * the reader chose, and turning mute back off restores the account
     * default rather than some level the panel picked on its own — the same
     * contract `RoomInfoPanel.swift`'s own `muted` binding documents.
     *
     * This is the regression test the brief's mutation targets: making the
     * mute switch ignore its own argument (always writing `MUTED`) makes the
     * second half of this test fail — see this task's report for the actual
     * failure output.
     */
    @Test
    fun muteRoundTrips() {
        var written: NotificationMode? = null
        var serverInfo = sampleInfo(notifications = NotificationMode.DEFAULT)
        panel(
            info = { serverInfo },
            onSetNotifications = { mode ->
                written = mode
                serverInfo = serverInfo.copy(notifications = mode)
                true
            },
        )
        compose.waitForIdle()
        compose.onNodeWithTag("room-info-mute").assertIsOff()

        compose.onNodeWithTag("room-info-mute").performClick()
        compose.waitForIdle()

        assertEquals(NotificationMode.MUTED, written)
        compose.onNodeWithTag("room-info-mute").assertIsOn()

        compose.onNodeWithTag("room-info-mute").performClick()
        compose.waitForIdle()

        assertEquals(NotificationMode.DEFAULT, written)
        compose.onNodeWithTag("room-info-mute").assertIsOff()
    }

    /** Pin round-trips the same way mute does — see [muteRoundTrips]. */
    @Test
    fun pinRoundTrips() {
        var written: Boolean? = null
        var serverInfo = sampleInfo(pinned = false)
        panel(
            info = { serverInfo },
            onSetPinned = { pinned ->
                written = pinned
                serverInfo = serverInfo.copy(pinned = pinned)
                true
            },
        )
        compose.waitForIdle()
        compose.onNodeWithTag("room-info-pinned").assertIsOff()

        compose.onNodeWithTag("room-info-pinned").performClick()
        compose.waitForIdle()

        assertEquals(true, written)
        compose.onNodeWithTag("room-info-pinned").assertIsOn()
    }

    /**
     * Leaving a room is irreversible from this app's point of view, so a
     * single tap on "Leave room" must not itself call [RoomInfoPanel]'s
     * `onLeaveRoom` — only a confirmation does.
     */
    @Test
    fun leavingAsksBeforeItActs() {
        var left = false
        var closed = false
        panel(onLeaveRoom = { left = true }, onClose = { closed = true })
        compose.waitForIdle()

        // Scroll first: the panel is a `verticalScroll` column and Leave sits
        // near its bottom, so whether it is on screen depends on the device's
        // height. It is visible on a 2400px-tall phone and below the fold on
        // CI's shorter default AVD, which is why these two tests passed locally
        // and timed out there waiting for a dialog whose trigger was never hit.
        compose.onNodeWithTag("room-info-leave").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals("tapping Leave must not itself leave", false, left)
        // The confirmation is an `AlertDialog`, which renders in its own
        // window — asserting `isDisplayed` on that wrapper node is not
        // reliable across devices, and `waitForIdle` is not a documented
        // guarantee that a separate window has finished attaching. Wait
        // for, then assert on, content *inside* the dialog: its title and
        // the button the reader actually taps.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("room-info-leave-confirm-button").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Leave Ops Room?").assertIsDisplayed()

        compose.onNodeWithTag("room-info-leave-confirm-button").performClick()
        compose.waitForIdle()

        assertEquals(true, left)
        assertEquals(true, closed)
    }

    /** Cancelling the confirmation leaves the room exactly as it was. */
    @Test
    fun cancellingTheConfirmationDoesNotLeave() {
        var left = false
        panel(onLeaveRoom = { left = true })
        compose.waitForIdle()

        // Scroll first: the panel is a `verticalScroll` column and Leave sits
        // near its bottom, so whether it is on screen depends on the device's
        // height. It is visible on a 2400px-tall phone and below the fold on
        // CI's shorter default AVD, which is why these two tests passed locally
        // and timed out there waiting for a dialog whose trigger was never hit.
        compose.onNodeWithTag("room-info-leave").performScrollTo().performClick()
        compose.waitForIdle()
        // Same reasoning as `leavingAsksBeforeItActs`: wait for the
        // dialog's own content, inside its own window, rather than assume
        // `waitForIdle` alone has it ready to query.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("room-info-leave-cancel").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("room-info-leave-cancel").performClick()
        compose.waitForIdle()

        assertEquals(false, left)
        // Content inside the dialog, not the wrapper: its title is gone
        // once the dialog itself is dismissed.
        compose.onNodeWithText("Leave Ops Room?").assertDoesNotExist()
    }

    /** A failure to load is shown, not swallowed — and nothing crashes on it. */
    @Test
    fun aFailureToLoadIsShown() {
        compose.setContent {
            RoomInfoPanel(
                roomId = "!room:example.org",
                accountUserId = null,
                avatarUri = null,
                onClose = {},
                loadInfo = { throw FfiException.Network("connection refused") },
                onSetNotifications = { true },
                onSetPinned = { true },
                onLeaveRoom = {},
            )
        }
        compose.waitForIdle()

        compose.onNodeWithTag("room-info-failure").assertIsDisplayed()
        compose.onNodeWithText("connection refused").assertIsDisplayed()
    }

    /** A room with no others in it (besides this account) shows no member section at all. */
    @Test
    fun aRoomOfJustThisAccountShowsNoMemberSection() {
        panel(
            info = {
                sampleInfo(members = listOf(RoomMemberDto(userId = "@me:example.org", displayName = "Me", avatarUrl = null)))
            },
        )
        compose.waitForIdle()

        compose.onNodeWithText("Members").assertDoesNotExist()
    }

    // ── Block and report (parity M3, Task 3) ─────────────────────────────

    @Test
    fun aMemberOpensBlockOrReport() {
        panel()
        openMember("@alice:example.org")

        compose.onNodeWithText("Block Alice").assertIsDisplayed()
        compose.onNodeWithText("Report Alice…").assertIsDisplayed()
        compose.onNodeWithText(ReportCopy.blockConsequence(isAgent = false)).assertIsDisplayed()
        compose.onNodeWithTag("member-actions-cancel").assertIsDisplayed()
    }

    @Test
    fun anAgentsActionsSayItKeepsRunning() {
        panel()
        compose.waitForIdle()
        compose.onNodeWithTag("member-@agent_atlas:example.org").performScrollTo()
        // The tag is on the row, which reads as one element for TalkBack.
        compose.onNodeWithTag("member-@agent_atlas:example.org").assertTextContains("Agent")
        openMember("@agent_atlas:example.org")

        compose.onNodeWithText(ReportCopy.blockConsequence(isAgent = true)).assertIsDisplayed()
        compose.onNodeWithText("Block Atlas").assertIsDisplayed()
    }

    @Test
    fun yourOwnRowIsNotAControl() {
        panel()
        compose.waitForIdle()
        // This account is not listed among the others at all; whatever
        // carries its tag must not take a click.
        compose.onAllNodesWithTag("member-@me:example.org").fetchSemanticsNodes().forEach { node ->
            assertTrue(
                "your own row must not be clickable",
                !node.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick),
            )
        }
        compose.onNodeWithText("Me").assertDoesNotExist()
    }

    @Test
    fun blockingSaysBlockedAndPlaysSuccess() {
        panel()
        openMember("@alice:example.org")
        compose.onNodeWithTag("member-block").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("member-blocked-@alice:example.org", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag("member-blocked-@alice:example.org", useUnmergedTree = true).performScrollTo().assertTextEquals("Blocked")
        compose.onAllNodesWithTag("member-actions").assertCountEquals(0)
        assertEquals(listOf(HapticFeedbackType.Confirm), haptics.played)
    }

    @Test
    fun aRefusedBlockIsSaidUnderTheMembers() {
        panel(onBlock = { "You can't block yourself." })
        openMember("@alice:example.org")
        compose.onNodeWithTag("member-block").performClick()
        waitForTag("room-info-safety-failure")

        compose.onNodeWithTag("room-info-safety-failure").performScrollTo().assertTextEquals("You can't block yourself.")
        compose.onNodeWithTag("member-blocked-@alice:example.org", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(emptyList<HapticFeedbackType>(), haptics.played)
    }

    @Test
    fun aRefusalIsClearedWhenAnotherActionOpens() {
        panel(onBlock = { "Nope." })
        openMember("@alice:example.org")
        compose.onNodeWithTag("member-block").performClick()
        waitForTag("room-info-safety-failure")

        openMember("@bob:example.org")
        compose.onNodeWithTag("room-info-safety-failure").assertDoesNotExist()
    }

    @Test
    fun unblockingHereWinsOverAStaleFlag() {
        panel(
            info = {
                sampleInfo(
                    members = listOf(
                        RoomMemberDto(userId = "@alice:example.org", displayName = "Alice", avatarUrl = null, isIgnored = true),
                    ),
                )
            },
        )
        compose.waitForIdle()
        // The member's own flag says blocked while the core's list is silent.
        compose.onNodeWithTag("member-blocked-@alice:example.org", useUnmergedTree = true).performScrollTo().assertIsDisplayed()

        openMember("@alice:example.org")
        compose.onNodeWithTag("member-unblock").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("member-blocked-@alice:example.org", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }

        compose.onNodeWithTag("member-blocked-@alice:example.org", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(listOf(HapticFeedbackType.Confirm), haptics.played)
    }

    @Test
    fun aBlockedMemberOffersUnblockAndSaysWhatItDoes() {
        blocked = setOf("@alice:example.org")
        panel()
        openMember("@alice:example.org")

        compose.onNodeWithText("Unblock Alice").assertIsDisplayed()
        compose.onNodeWithText("Block Alice").assertDoesNotExist()
        compose.onNodeWithText(
            "Unblocking shows their messages again, including ones sent while they were blocked.",
        ).assertIsDisplayed()
    }

    @Test
    fun aWriteInFlightDisablesTheRowsAndTheActions() {
        val gate = kotlinx.coroutines.CompletableDeferred<String?>()
        panel(onBlock = { gate.await() })
        openMember("@alice:example.org")
        compose.onNodeWithTag("member-block").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("member-@bob:example.org").performScrollTo().assertIsNotEnabled()
        gate.complete(null)
        compose.waitForIdle()
        compose.onNodeWithTag("member-@bob:example.org").assertIsEnabled()
    }

    @Test
    fun reportRoomOpensTheSheetForTheRoom() {
        panel()
        compose.waitForIdle()
        compose.onNodeWithTag("room-info-report-room").performScrollTo().performClick()
        waitForTag("report-sheet")

        compose.onNodeWithText("Report room").assertIsDisplayed()
    }

    @Test
    fun reportAMemberOpensTheSheetForThem() {
        panel()
        openMember("@alice:example.org")
        compose.onNodeWithTag("member-report").performClick()
        waitForTag("report-sheet")

        compose.onNodeWithText("Report").assertIsDisplayed()
        compose.onAllNodesWithTag("member-actions").assertCountEquals(0)
    }

    @Test
    fun cancellingTheReportReturnsFocusToTheMemberRow() {
        panel()
        openMember("@alice:example.org")
        compose.onNodeWithTag("member-report").performClick()
        waitForTag("report-sheet")
        compose.onNodeWithTag("report-cancel").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("report-sheet").fetchSemanticsNodes().isEmpty()
        }
        awaitFocus("member-@alice:example.org")
    }

    @Test
    fun cancellingTheMemberActionsReturnsFocusToTheMemberRow() {
        panel()
        openMember("@alice:example.org")
        compose.onNodeWithTag("member-actions-cancel").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("member-actions").fetchSemanticsNodes().isEmpty()
        }
        awaitFocus("member-@alice:example.org")
    }

    @Test
    fun cancellingReportRoomReturnsFocusToIt() {
        panel()
        compose.waitForIdle()
        compose.onNodeWithTag("room-info-report-room").performScrollTo().performClick()
        waitForTag("report-sheet")
        compose.onNodeWithTag("report-cancel").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("report-sheet").fetchSemanticsNodes().isEmpty()
        }
        awaitFocus("room-info-report-room")
    }

    @Test
    fun cancellingLeaveReturnsFocusToLeave() {
        panel()
        compose.waitForIdle()
        compose.onNodeWithTag("room-info-leave").performScrollTo().performClick()
        waitForTag("room-info-leave-cancel")
        compose.onNodeWithTag("room-info-leave-cancel").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("room-info-leave-cancel").fetchSemanticsNodes().isEmpty()
        }
        awaitFocus("room-info-leave")
    }

    @Test
    fun leaveConfirmIsIosWords() {
        panel()
        compose.waitForIdle()
        compose.onNodeWithTag("room-info-leave").performScrollTo().performClick()
        waitForTag("room-info-leave-confirm-button")

        compose.onNodeWithText("Leave Ops Room?").assertIsDisplayed()
        compose.onNodeWithText("You'll stop receiving its messages.").assertIsDisplayed()
        compose.onNodeWithText("Leave room?").assertDoesNotExist()
    }

    @Test
    fun memberActionsFitAtFontScaleTwo() {
        panel(fontScale = 2f)
        openMember("@alice:example.org")

        compose.onNodeWithText("Report Alice…").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("member-actions-cancel").performScrollTo().assertIsDisplayed()
    }
}
