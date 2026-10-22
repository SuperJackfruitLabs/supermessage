package dev.supermessage

import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Reduce Motion. On Android it is the system animator duration scale being 0
 * (Settings, "Remove animations"), which turns every animation off. iOS has a
 * switch of its own (`TimelineChrome.swift:49-55` reads it); this is its
 * counterpart.
 *
 * A test provides this explicitly. Nothing but [systemReducesMotion] reads the
 * device, so a test never depends on how its emulator was started.
 */
val LocalReduceMotion = compositionLocalOf<Boolean?> { null }

/** [full], or a jump when motion is reduced: the spec for an animation that has a finite end. */
internal fun <T> motion(reduce: Boolean, full: androidx.compose.animation.core.FiniteAnimationSpec<T>):
    androidx.compose.animation.core.FiniteAnimationSpec<T> = if (reduce) snap() else full

/** Whether animation is off, from the test's override if any, else the system. */
@Composable
fun reducesMotion(): Boolean = LocalReduceMotion.current ?: systemReducesMotion()

/** The system setting, re-read whenever the app returns to the foreground. */
@Composable
fun systemReducesMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    fun read() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    var reduce by remember { mutableStateOf(read()) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reduce = read()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return reduce
}

/** [full] normally, and a jump to the end when motion is reduced. */
fun <T> motion(reduce: Boolean, full: AnimationSpec<T>): AnimationSpec<T> = if (reduce) snap() else full
