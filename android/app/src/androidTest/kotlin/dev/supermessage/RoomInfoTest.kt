package dev.supermessage

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import uniffi.supermessage_core.NotificationMode
import uniffi.supermessage_core.RoomIdentity
import uniffi.supermessage_core.RoomInfoDto
import uniffi.supermessage_core.RoomMemberDto
import uniffi.supermessage_core.RuntimeDto
import uniffi.supermessage_ffi.FfiException

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

    private fun panel(
        info: () -> RoomInfoDto = { sampleInfo() },
        accountUserId: String? = "@me:example.org",
        onClose: () -> Unit = {},
        onSetNotifications: (NotificationMode) -> Boolean = { true },
        onSetPinned: (Boolean) -> Boolean = { true },
        onLeaveRoom: () -> Unit = {},
    ) {
        compose.setContent {
            RoomInfoPanel(
                roomId = "!room:example.org",
                accountUserId = accountUserId,
                avatarUri = null,
                onClose = onClose,
                loadInfo = { info() },
                onSetNotifications = { mode -> onSetNotifications(mode) },
                onSetPinned = { pinned -> onSetPinned(pinned) },
                onLeaveRoom = { onLeaveRoom() },
            )
        }
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

        compose.onNodeWithText("Ops Room").assertIsDisplayed()
        compose.onNodeWithText("Infra team").assertIsDisplayed()
        compose.onNodeWithText("Where the pager goes").assertIsDisplayed()
        compose.onNodeWithText("#ops:example.org").assertIsDisplayed()
        compose.onNodeWithText("!room:example.org").assertIsDisplayed()

        // Two others, so the plural header with the core's own count —
        // not `members.size`, which would read 2 (self already excluded).
        compose.onNodeWithText("Members (3)").assertIsDisplayed()
        compose.onNodeWithText("Alice").assertIsDisplayed()
        // No display name: falls back to the raw id, never a name this
        // composable invents.
        compose.onNodeWithText("@bob:example.org").assertIsDisplayed()

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

        compose.onNodeWithTag("room-info-leave").performClick()
        compose.waitForIdle()

        assertEquals("tapping Leave must not itself leave", false, left)
        compose.onNodeWithTag("room-info-leave-confirm").assertIsDisplayed()

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

        compose.onNodeWithTag("room-info-leave").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("room-info-leave-cancel").performClick()
        compose.waitForIdle()

        assertEquals(false, left)
        compose.onNodeWithTag("room-info-leave-confirm").assertDoesNotExist()
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
}
