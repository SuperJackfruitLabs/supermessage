package dev.supermessage.notify

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.supermessage.kit.Session.Phase
import dev.supermessage.kit.widgets.WidgetWriting
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uniffi.supermessage_core.NotificationDto
import uniffi.supermessage_core.RoomRow
import uniffi.supermessage_core.TimelineRow
import uniffi.supermessage_core.WidgetLiveTurn

/**
 * When the coordinator asks the widgets' writer to merge
 * (`PlatformCoordinator.swift:85-99, 142, 152, 222-225, 271-311`). What a
 * merge does is the core's and is pinned in `WidgetFeedTest`; here the writer
 * is a recording stand-in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlatformCoordinatorWidgetsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private class Recording : WidgetWriting {
        val calls = mutableListOf<String>()
        val decisionRooms = mutableSetOf<String>()
        var reload = false

        override suspend fun apply(note: NotificationDto): Boolean = false.also { calls += "note" }

        override suspend fun apply(rows: List<RoomRow>, live: List<WidgetLiveTurn>, asOfMs: Long): Boolean {
            calls += "roster ${live.joinToString { "${it.roomId}:${it.step}" }}"
            return reload
        }

        override suspend fun apply(roomId: String, rows: List<TimelineRow>): Boolean =
            false.also { calls += "timeline $roomId" }

        override suspend fun markAnswered(roomId: String, eventId: String, optionId: String): Boolean =
            false.also { calls += "answered $roomId $eventId $optionId" }

        override suspend fun clearAnswer(roomId: String, eventId: String): Boolean = false
        override suspend fun opened(): Boolean = false.also { calls += "opened" }
        override suspend fun signedOut(): Boolean = false.also { calls += "signedOut" }
        override fun holdsDecision(roomId: String): Boolean = roomId in decisionRooms
    }

    private class Harness(context: Context, val writer: Recording, scope: kotlinx.coroutines.CoroutineScope) {
        val redraws = mutableListOf<Boolean>()
        val coordinator = PlatformCoordinator(
            context, MutableStateFlow(Phase.STARTING), permissionLauncher = {}, sdkInt = 36, granted = { false },
            widgets = writer, widgetsWritten = { redraws += it }, widgetScope = scope, now = { 1_000L },
        )

        suspend fun calls(): List<String> {
            coordinator.widgetWritesDone()
            return writer.calls.toList()
        }
    }

    private fun harness(scope: kotlinx.coroutines.CoroutineScope) = Harness(context, Recording(), scope)

    @Test(timeout = 60_000)
    fun becomingActiveAndLeavingBothRestartTheRecap() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(backgroundScope)
        h.coordinator.widgetPhaseChanged(Phase.SIGNED_IN)
        assertEquals("signed in but not in front: no recap yet", emptyList<String>(), h.calls())
        h.coordinator.activeChanged(true)
        h.coordinator.activeChanged(false)
        assertEquals(listOf("opened", "opened"), h.calls())
    }

    @Test(timeout = 60_000)
    fun aSignInWhileInFrontStartsTheRecap() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(backgroundScope)
        h.coordinator.activeChanged(true)
        assertEquals("nobody is signed in: nothing to recap", emptyList<String>(), h.calls())
        h.coordinator.widgetPhaseChanged(Phase.SIGNED_IN)
        assertEquals(listOf("opened"), h.calls())
    }

    @Test(timeout = 60_000)
    fun theTimelineIsReadOnlyForARoomWithADecision() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(backgroundScope)
        h.writer.decisionRooms += "!owes:x"
        h.coordinator.widgetPhaseChanged(Phase.SIGNED_IN)
        h.coordinator.timelineChanged("!quiet:x", emptyList())
        assertEquals("no decision for that room, no timeline call", emptyList<String>(), h.calls())
        h.coordinator.timelineChanged("!owes:x", emptyList())
        assertEquals(listOf("timeline !owes:x"), h.calls())
    }

    @Test(timeout = 60_000)
    fun aCardAnswerMarksTheWidgetsAnswered() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(backgroundScope)
        h.coordinator.decisionAnswered("!r:x", "\$e1", "approve")
        assertEquals(listOf("answered !r:x \$e1 approve"), h.calls())
    }

    @Test(timeout = 60_000)
    fun aLiveTurnIsWrittenOnlyWhenItChanges() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(backgroundScope)
        h.coordinator.widgetPhaseChanged(Phase.SIGNED_IN)
        h.coordinator.liveChanged("!r:x", "Reading files")
        h.coordinator.liveChanged("!r:x", "Reading files")
        assertEquals(listOf("roster !r:x:Reading files"), h.calls())
        h.coordinator.liveChanged("!r:x", "Running tests")
        h.coordinator.liveChanged(null, null)
        h.coordinator.liveChanged(null, null)
        assertEquals(
            listOf("roster !r:x:Reading files", "roster !r:x:Running tests", "roster "),
            h.calls(),
        )
    }

    @Test(timeout = 60_000)
    fun signingOutWritesSignedOut() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(backgroundScope)
        h.coordinator.widgetPhaseChanged(Phase.SIGNED_OUT)
        assertEquals("never signed in: nothing to reset", emptyList<String>(), h.calls())
        h.coordinator.widgetPhaseChanged(Phase.SIGNED_IN)
        h.coordinator.widgetPhaseChanged(Phase.SIGNED_OUT)
        assertEquals(listOf("signedOut"), h.calls())
    }

    @Test(timeout = 60_000)
    fun aRosterIsOnlyWrittenWhileSignedInAndTheCoreSaysWhetherToRedraw() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(backgroundScope)
        h.coordinator.roomsChanged(emptyList())
        assertEquals("signed out: no roster", emptyList<String>(), h.calls())
        h.coordinator.widgetPhaseChanged(Phase.SIGNED_IN)
        h.coordinator.roomsChanged(emptyList())
        h.calls()
        assertEquals("the core said no reload", 0, h.redraws.count { it })
        h.writer.reload = true
        h.coordinator.roomsChanged(emptyList())
        h.calls()
        assertEquals("the core said reload", 1, h.redraws.count { it })
    }
}
