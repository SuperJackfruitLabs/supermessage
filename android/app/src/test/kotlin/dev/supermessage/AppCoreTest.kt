package dev.supermessage

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.supermessage.kit.StubCore
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppCoreTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @After
    fun forget() = AppCore.installForTest(null)

    private class Fake : StubCore() {
        var inviterCalls = 0
        override fun roomInviter(roomId: String): String? {
            inviterCalls++
            return "from-the-one-core"
        }
    }

    @Test
    fun aReceiverAndTheViewModelShareOneCore() = runTest {
        val fake = Fake()
        AppCore.installForTest(fake)

        // What a receiver does: no Activity, no ViewModel.
        val forReceiver = AppCore.core(app)
        // What the app does: the real ViewModel constructor, not a test path.
        val vm = SessionViewModel(app)

        assertSame(fake, forReceiver)
        assertSame(forReceiver, AppCore.core(app))
        assertEquals("from-the-one-core", vm.session.inviter(roomId = "!r:x"))
        assertEquals(1, fake.inviterCalls)
    }

    /**
     * Review Minor 3: a failure in background work (a push, an answer) used to
     * reach the thread's uncaught-exception handler, which on a device ends
     * the process. It is logged instead, and the scope keeps working.
     */
    @Test(timeout = 10_000)
    fun aFailureInBackgroundWorkNeverReachesTheUncaughtHandler() {
        val uncaught = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
        try {
            kotlinx.coroutines.runBlocking {
                AppCore.scope.launch { throw NotImplementedError("a stub the test forgot") }.join()
                AppCore.scope.launch { throw IllegalStateException("boom") }.join()
            }
            assertEquals(emptyList<Throwable>(), uncaught.toList())
            var ran = false
            kotlinx.coroutines.runBlocking { AppCore.scope.launch { ran = true }.join() }
            assertTrue("the scope still runs work", ran)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before)
        }
    }
}
