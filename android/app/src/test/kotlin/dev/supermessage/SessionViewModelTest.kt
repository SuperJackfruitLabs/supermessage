package dev.supermessage

import dev.supermessage.kit.StubCore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import uniffi.supermessage_ffi.ConnectionState
import uniffi.supermessage_ffi.CoreInterface
import uniffi.supermessage_ffi.EventSink

/**
 * `SessionViewModel` is the one place in the Android app that constructs a
 * real `Core` — which means opening SQLite against a real data directory,
 * something no plain JVM unit test can do and Robolectric is deliberately
 * not reached for here (see the Task 1 brief). So this suite does not touch
 * the public `SessionViewModel(app: Application)` constructor at all: it
 * drives the same `Session`-building wiring through
 * [SessionViewModel.Companion.forTest], the seam that exists so this file
 * can prove the wiring without an `Application`, a `.so`, or a device.
 */
class SessionViewModelTest {

    /** The session is built once and handed out, not rebuilt per read. */
    @Test
    fun theSessionIsStable() = runTest {
        val vm = SessionViewModel.forTest(FakeCore())
        assertNotNull(vm.session)
        assertEquals(vm.session, vm.session)
    }

    /**
     * [SessionViewModel.build] wires the `CoreInterface` it is handed all
     * the way through to the `Session` it returns — this is a `Session`
     * that actually talks to the core it was built with, not one that
     * looks real but reaches nothing.
     *
     * This replaces an earlier `clearingSignsOut` test that asserted
     * clearing the ViewModel calls the fake's `logout()`. That behaviour
     * does not exist in production — see [SessionViewModel.onCleared]'s own
     * KDoc for why it must not — so the old test's mutation could only ever
     * break the test's own harness, never a real code path. It has been
     * removed rather than kept as a decorative pass.
     */
    @Test
    fun theSessionReachesTheCoreItWasBuiltWith() = runTest {
        val core = FakeCore()
        val vm = SessionViewModel.forTest(core)

        val inviter = vm.session.inviter(roomId = "room-1")

        assertEquals("the-fake-core-answered", inviter)
        assertEquals(1, core.roomInviterCalls)
    }

    /**
     * A [CoreInterface] that never touches Rust, following the house
     * pattern `:kit`'s `CoreClientTest` establishes: `private`, nested
     * inside the test class it belongs to, and every method this test does
     * not configure throws rather than returning a default that happens to
     * work. [roomInviter] is the one exception — `Session.inviter` calls it
     * directly, with no `try`/`catch` swallowing a wrong answer into a
     * default, which is exactly why it is the call
     * [theSessionReachesTheCoreItWasBuiltWith] uses to prove the wiring:
     * a `NotImplementedError` here (an `Error`, not an `Exception` —
     * `:kit`'s `TimelineStoreTest` documents the same trap) would fail
     * loudly rather than being caught and hidden, and a wrong return value
     * would be visible directly in the assertion, not laundered through a
     * `catch (e: Exception) { null }` the way most of `Session`'s other
     * passthroughs are.
     */
    private class FakeCore : StubCore() {
        var roomInviterCalls: Int = 0
            private set

        override fun roomInviter(roomId: String): String? {
            roomInviterCalls++
            return "the-fake-core-answered"
        }

    }
}
