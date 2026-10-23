package dev.supermessage.kit.notify

import dev.supermessage.kit.Session.Phase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionAskTest {
    @Test
    fun asksAfterAFreshSignIn() {
        assertTrue(shouldAskForNotifications(Phase.SIGNED_OUT, Phase.SIGNED_IN, sdkInt = 33, granted = false))
        assertTrue(shouldAskForNotifications(Phase.SIGNED_OUT, Phase.SIGNED_IN, sdkInt = 36, granted = false))
    }

    @Test
    fun neverAsksOnARestore() {
        assertFalse(shouldAskForNotifications(Phase.STARTING, Phase.SIGNED_IN, sdkInt = 36, granted = false))
        assertFalse(shouldAskForNotifications(null, Phase.SIGNED_IN, sdkInt = 36, granted = false))
        assertFalse(shouldAskForNotifications(Phase.STARTING, Phase.SIGNED_OUT, sdkInt = 36, granted = false))
        assertFalse(shouldAskForNotifications(Phase.SIGNED_IN, Phase.SIGNED_OUT, sdkInt = 36, granted = false))
    }

    @Test
    fun neverAsksBelowApi33() {
        assertFalse(shouldAskForNotifications(Phase.SIGNED_OUT, Phase.SIGNED_IN, sdkInt = 32, granted = false))
        assertFalse(shouldAskForNotifications(Phase.SIGNED_OUT, Phase.SIGNED_IN, sdkInt = 31, granted = false))
    }

    @Test
    fun neverAsksWhenAlreadyGranted() {
        assertFalse(shouldAskForNotifications(Phase.SIGNED_OUT, Phase.SIGNED_IN, sdkInt = 36, granted = true))
    }
}
