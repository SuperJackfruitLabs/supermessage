package dev.supermessage

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLifecycleTest {
    private class Owner : LifecycleOwner {
        override val lifecycle: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
    }

    private fun observed(vararg states: Lifecycle.State): List<Boolean> {
        val calls = mutableListOf<Boolean>()
        val owner = Owner()
        owner.lifecycle.addObserver(ScenePhaseObserver { calls += it })
        states.forEach { owner.lifecycle.currentState = it }
        return calls
    }

    @Test
    fun startReportsActive() {
        assertEquals(listOf(true), observed(Lifecycle.State.STARTED))
    }

    @Test
    fun stopReportsInactive() {
        assertEquals(
            listOf(true, false),
            observed(Lifecycle.State.STARTED, Lifecycle.State.CREATED),
        )
    }

    @Test
    fun resumePauseDoNotReport() {
        assertEquals(
            listOf(true),
            observed(Lifecycle.State.STARTED, Lifecycle.State.RESUMED, Lifecycle.State.STARTED),
        )
    }
}
