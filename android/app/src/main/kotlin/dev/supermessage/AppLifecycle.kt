package dev.supermessage

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Reports whether the app is visible: true from start, false from stop.
 *
 * Start and stop, not resume and pause, match iOS's `scenePhase` `.active`
 * and `.background`: a split-screen window that is visible but unfocused
 * pauses without stopping, and the session should stay live there.
 */
class ScenePhaseObserver(private val onPhase: (active: Boolean) -> Unit) :
    DefaultLifecycleObserver {
    override fun onStart(owner: LifecycleOwner) = onPhase(true)

    override fun onStop(owner: LifecycleOwner) = onPhase(false)
}
