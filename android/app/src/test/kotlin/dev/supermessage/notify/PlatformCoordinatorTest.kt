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
}
