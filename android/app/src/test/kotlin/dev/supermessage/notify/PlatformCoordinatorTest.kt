package dev.supermessage.notify

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.supermessage.kit.Session.Phase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlatformCoordinatorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun coordinatorOver(phases: MutableStateFlow<Phase>, asks: MutableList<Unit>) =
        PlatformCoordinator(context, phases, permissionLauncher = { asks += Unit }, sdkInt = 36, granted = { false })

    @Test
    fun aFreshSignInAsksOnceAndARestoreNeverDoes() = runTest(UnconfinedTestDispatcher()) {
        val phases = MutableStateFlow(Phase.STARTING)
        val asks = mutableListOf<Unit>()
        val job = launch { coordinatorOver(phases, asks).run() }

        phases.value = Phase.SIGNED_IN // restore: STARTING -> SIGNED_IN
        assertEquals("a restored session must not prompt", 0, asks.size)

        phases.value = Phase.SIGNED_OUT
        phases.value = Phase.SIGNED_IN // fresh sign-in
        assertEquals(1, asks.size)
        job.cancel()
    }

    private class Push {
        var asked = 0
        var signedOut = 0
    }

    private fun pushCoordinator(phases: MutableStateFlow<Phase>, push: Push, granted: Boolean, sdkInt: Int = 36) =
        PlatformCoordinator(
            context, phases, permissionLauncher = {}, sdkInt = sdkInt, granted = { granted },
            askForPush = { push.asked++ }, pushSignedOut = { push.signedOut++ },
        )

    @Test
    fun signedInAndAllowedToNotifyItAsksForPush() = runTest(UnconfinedTestDispatcher()) {
        val phases = MutableStateFlow(Phase.STARTING)
        val push = Push()
        val job = launch { pushCoordinator(phases, push, granted = true).run() }
        assertEquals("not before sign-in", 0, push.asked)
        phases.value = Phase.SIGNED_IN
        assertEquals(1, push.asked)
        job.cancel()

        val denied = Push()
        val other = launch { pushCoordinator(MutableStateFlow(Phase.SIGNED_IN), denied, granted = false).run() }
        assertEquals("not without the permission", 0, denied.asked)
        other.cancel()

        val older = Push()
        val api32 = launch { pushCoordinator(MutableStateFlow(Phase.SIGNED_IN), older, granted = false, sdkInt = 32).run() }
        assertEquals("API 32 has no permission to grant", 1, older.asked)
        api32.cancel()
    }

    @Test
    fun aGrantWhileSignedInAsksForPush() = runTest(UnconfinedTestDispatcher()) {
        val push = Push()
        val coordinator = pushCoordinator(MutableStateFlow(Phase.SIGNED_IN), push, granted = false)
        val job = launch { coordinator.run() }
        coordinator.permissionAnswered(false)
        assertEquals(0, push.asked)
        coordinator.permissionAnswered(true)
        assertEquals(1, push.asked)
        job.cancel()
    }

    /** RF5: sign-out unregisters from the distributor, once. */
    @Test
    fun signingOutUnregistersFromTheDistributor() = runTest(UnconfinedTestDispatcher()) {
        val phases = MutableStateFlow(Phase.STARTING)
        val push = Push()
        val job = launch { pushCoordinator(phases, push, granted = true).run() }
        phases.value = Phase.SIGNED_OUT
        assertEquals("never signed in: nothing to unregister", 0, push.signedOut)
        phases.value = Phase.SIGNED_IN
        phases.value = Phase.SIGNED_OUT
        assertEquals(1, push.signedOut)
        job.cancel()
    }
}
