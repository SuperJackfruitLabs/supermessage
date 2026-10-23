package dev.supermessage

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.supermessage.kit.StubCore
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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
}
