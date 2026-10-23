package dev.supermessage.widgets

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import dev.supermessage.AppCore
import dev.supermessage.kit.Session.Phase
import dev.supermessage.kit.widgets.WidgetWriting
import dev.supermessage.notify.PlatformCoordinator
import java.lang.ref.WeakReference
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uniffi.supermessage_core.NotificationDto
import uniffi.supermessage_core.RoomRow
import uniffi.supermessage_core.TimelineRow
import uniffi.supermessage_core.WidgetLiveTurn

/** The widgets' recap and writer belong to the process, not to an Activity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProcessWidgetsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private class Recording : WidgetWriting {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override suspend fun apply(note: NotificationDto, stillFor: suspend () -> Boolean) = false
        override suspend fun apply(rows: List<RoomRow>, live: List<WidgetLiveTurn>, asOfMs: Long) = false
        override suspend fun apply(roomId: String, rows: List<TimelineRow>) = false
        override suspend fun markAnswered(roomId: String, eventId: String, optionId: String) = false
        override suspend fun clearAnswer(roomId: String, eventId: String) = false
        override suspend fun opened(): Boolean = false.also { calls += "opened" }
        override suspend fun signedOut(): Boolean = false.also { calls += "signedOut" }
        override fun holdsDecision(roomId: String) = false
    }

    @After
    fun forget() = AppCore.installWidgetsForTest(null)

    @Test(timeout = 60_000)
    fun theProcessGoingToTheFrontAndBackDrivesTheRecapAndNothingElseDoes() = runBlocking {
        val writer = Recording()
        AppCore.installWidgetsForTest(writer)
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        AppCore.observeProcess(context, owner.lifecycle)
        AppCore.observeProcess(context, owner.lifecycle) // once per process
        val publisher = AppCore.widgetPublisher(context)
        publisher.phaseChanged(Phase.SIGNED_IN)

        owner.registry.currentState = Lifecycle.State.STARTED
        publisher.writesDone()
        assertEquals("the app came forward", listOf("opened"), writer.calls.toList())

        owner.registry.currentState = Lifecycle.State.CREATED
        publisher.writesDone()
        assertEquals("the app went away", listOf("opened", "opened"), writer.calls.toList())
    }

    @Test(timeout = 60_000)
    fun aRecreatedActivityDoesNotRestartTheRecap() = runBlocking {
        val writer = Recording()
        AppCore.installWidgetsForTest(writer)
        val publisher = AppCore.widgetPublisher(context)
        publisher.activeChanged(true)
        publisher.phaseChanged(Phase.SIGNED_IN)
        assertEquals(listOf("opened"), publisher.callsAfter(writer))
        // The Activity is destroyed and its replacement watches the same session: the
        // same phase is read again, and the process never left the front.
        publisher.stopWatching()
        publisher.phaseChanged(Phase.SIGNED_IN)
        assertEquals("no second recap", listOf("opened"), publisher.callsAfter(writer))
    }

    private suspend fun WidgetPublisher.callsAfter(writer: Recording): List<String> {
        writesDone()
        return writer.calls.toList()
    }

    @Test(timeout = 60_000)
    fun aDestroyedActivityIsNotHeldByTheWidgetWriter() = runBlocking {
        val writer = Recording()
        AppCore.installWidgetsForTest(writer)
        val publisher = AppCore.widgetPublisher(context)
        var screen: Any? = Any()
        val seen = WeakReference(screen)
        // What an Activity hands its coordinator: lambdas that capture it.
        var coordinator: PlatformCoordinator? = coordinatorCapturing(screen!!)
        publisher.phaseChanged(Phase.SIGNED_IN)
        publisher.activeChanged(true)
        publisher.writesDone()

        coordinator = null
        screen = null
        repeat(20) {
            System.gc()
            if (seen.get() == null) return@repeat
            Thread.sleep(50)
        }
        assertNull("the destroyed Activity is collectable", seen.get())
        check(coordinator == null)
    }

    private fun coordinatorCapturing(screen: Any) = PlatformCoordinator(
        context,
        MutableStateFlow(Phase.STARTING),
        permissionLauncher = { screen.hashCode() },
        askForPush = { screen.hashCode() },
    )
}
