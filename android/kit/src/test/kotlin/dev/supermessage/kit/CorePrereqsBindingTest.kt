package dev.supermessage.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import uniffi.supermessage_core.NotificationCategory
import uniffi.supermessage_core.WidgetContent
import uniffi.supermessage_ffi.notificationActions
import uniffi.supermessage_ffi.voiceLongEnough
import uniffi.supermessage_ffi.voiceRecordingSpec
import uniffi.supermessage_ffi.widgetView

/**
 * The core-prerequisite functions (notification policy, voice recording
 * numbers, the widget view) resolve, link and answer across the JNI boundary.
 *
 * Their rules are pinned in Rust; this proves the regenerated Kotlin compiles
 * against the library and the calls get there. Like `TimelineDisplayTest`, it
 * needs a host build (`cargo build -p supermessage-ffi`).
 */
class CorePrereqsBindingTest {

    @Test
    fun gateActionsAreTheCoresInIosOrder() {
        assertEquals(
            listOf("sm.gate.approve", "sm.gate.requestChanges", "sm.gate.reject", "sm.open"),
            notificationActions(NotificationCategory.GATE).map { it.id },
        )
    }

    @Test
    fun theShortestVoiceMessageIs600ms() {
        assertEquals(600uL, voiceRecordingSpec().shortestMs)
        assertFalse(voiceLongEnough(599uL))
    }

    @Test
    fun nothingStoredIsWaiting() {
        assertEquals(WidgetContent.WAITING, widgetView(null, 0uL).content)
    }
}
