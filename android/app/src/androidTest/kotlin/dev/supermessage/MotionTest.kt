package dev.supermessage

import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MotionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun reduceMotionSnaps() {
        var reduced: Any? = null
        var full: Any? = null
        compose.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                reduced = motion(reducesMotion(), tween<Float>(300))
            }
            CompositionLocalProvider(LocalReduceMotion provides false) {
                full = motion(reducesMotion(), tween<Float>(300))
            }
        }
        compose.waitForIdle()
        assertTrue("Reduce Motion must snap", reduced is SnapSpec<*>)
        assertFalse("Full motion must not snap", full is SnapSpec<*>)
    }
}
