package dev.supermessage

import android.Manifest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.up
import androidx.compose.ui.test.center
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.supermessage.kit.AudioEngine
import dev.supermessage.kit.CoreClient
import dev.supermessage.kit.NotePlayback
import dev.supermessage.kit.Recorder
import dev.supermessage.kit.SendResult
import dev.supermessage.kit.Session
import dev.supermessage.kit.StubCore
import dev.supermessage.kit.VoicePlayback
import dev.supermessage.kit.VoiceRecording
import java.io.File
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.supermessage_core.PlayableAudio
import uniffi.supermessage_ffi.AttachmentSendOutcome
import uniffi.supermessage_ffi.StagedFile

/**
 * Voice recording in the composer (C2), over a fake microphone and a fake
 * core: nothing is ever sent anywhere. [theRealRecorderWritesOggOpus] is the
 * one test that uses the emulator's own (virtual) microphone.
 */
class VoiceComposerTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val cache = File(context.cacheDir, "voice-composer-test").also { it.mkdirs() }

    @After
    fun cleanUp() {
        cache.deleteRecursively()
    }

    private class FakeRecorder : Recorder {
        var started: File? = null
        var stops = 0
        override fun start(file: File) { started = file; file.writeText("OggS fake") }
        override fun stop() { stops++ }
        override fun maxAmplitude(): Int = 9_000
    }

    private class FakeCore : StubCore() {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun setHiddenEvents(eventIds: List<String>) {}
        override fun attachmentStagePath(roomId: String, path: String): StagedFile {
            calls += "attachmentStagePath $roomId ${File(path).name}"
            return StagedFile("tok", File(path).name, 9uL, "audio/ogg", null, null)
        }
        override fun attachmentMarkVoice(roomId: String, token: String, durationMs: ULong, waveform: List<Float>) {
            calls += "attachmentMarkVoice $roomId $token ${durationMs}ms"
            assertTrue("at most 60 levels in 0..1: $waveform", waveform.size <= 60 && waveform.all { it in 0f..1f })
        }
        override fun attachmentSend(roomId: String, token: String, caption: String?): AttachmentSendOutcome {
            calls += "attachmentSend $roomId $token caption=${caption ?: "null"}"
            return AttachmentSendOutcome.Sent
        }
        override fun attachmentDiscard(token: String) { calls += "attachmentDiscard $token" }
    }

    private class RecordingHaptics : HapticFeedback {
        val played = mutableListOf<HapticFeedbackType>()
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { played += hapticFeedbackType }
    }

    private val mic = FakeRecorder()
    private val core = FakeCore()
    private val haptics = RecordingHaptics()
    private var now = 1_000L
    private val recording = VoiceRecording(
        recorder = mic,
        cacheDir = cache,
        scope = CoroutineScope(Dispatchers.Unconfined),
        clock = { now },
    )
    private val session = Session(CoreClient(core, Dispatchers.Unconfined), CoroutineScope(Dispatchers.Unconfined))
    private val results = Collections.synchronizedList(mutableListOf<SendResult>())
    private var starts = 0
    private var microphoneAnswer = true

    private fun show(
        text: String = "",
        attachment: ChipState? = null,
        fontScale: Float? = null,
        onRecordingStarts: () -> Unit = { starts++ },
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptics, LocalReduceMotion provides true) {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides (if (fontScale != null) Density(density.density, fontScale) else density),
                ) {
                    Box(Modifier.requiredWidth(411.dp).testTag("shell")) {
                        val voice = rememberVoiceControls(
                            recording = recording,
                            session = session,
                            roomId = "!r:x",
                            onRecordingStarts = onRecordingStarts,
                            onSending = {},
                            onResult = { results += it },
                            microphone = { microphoneAnswer },
                        )
                        Composer(
                            text = text, onTextChange = {}, onSend = {},
                            attachment = attachment,
                            voice = voice,
                        )
                    }
                }
            }
        }
    }

    private fun tapTheMic() {
        // A finger down for 100 ms: under the 300 ms hold, so a tap. The clock is
        // stepped by hand, because an up injected in the same instant as the down
        // would be a tap whatever the hold is.
        val before = compose.mainClock.autoAdvance
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("composer-mic").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("composer-mic").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(100)
        compose.mainClock.autoAdvance = before
        compose.waitForIdle()
    }

    private fun holdTheMic(pastTheHold: Boolean = true) {
        compose.onNodeWithTag("composer-mic").performTouchInput { down(center) }
        if (pastTheHold) compose.mainClock.advanceTimeBy(HOLD_MS + 100)
        compose.waitForIdle()
    }

    private fun voiceFolders() = File(cache, VoiceRecording.FOLDER).listFiles().orEmpty().toList()

    @Test
    fun theMicShowsOnlyWhenThereIsNothingToSend() {
        show(text = "")
        compose.onNodeWithTag("composer-mic").assertIsDisplayed()
        compose.onNodeWithContentDescription("Record voice message").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTagCount("composer-send"))
    }

    @Test
    fun aMessageInTheFieldShowsSendNotTheMic() {
        show(text = "hello")
        compose.onNodeWithTag("composer-send").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTagCount("composer-mic"))
    }

    @Test
    fun aStagedFileShowsSendNotTheMic() {
        show(
            attachment = ChipState(
                file = StagedFile("t", "report.pdf", 10uL, "application/pdf", null, null), path = null, failure = null,
            ),
        )
        compose.onNodeWithTag("composer-send").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTagCount("composer-mic"))
    }

    @Test
    fun aTapRecordsAndTheButtonSaysSendVoiceMessage() {
        show()
        tapTheMic()
        compose.onNodeWithTag("composer-recording-pill").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send voice message").assertIsDisplayed()
        compose.onNodeWithContentDescription("Discard recording").assertIsDisplayed()
        compose.onNodeWithTag("composer-recording-elapsed").assertIsDisplayed()
        assertEquals("Recording", textOf("Recording"))
        assertEquals(0, compose.onAllNodesWithTagCount("composer-mic"))
        assertTrue("the fake recorder was started", mic.started!!.exists())
        assertEquals("playback was told", 1, starts)
    }

    @Test
    fun discardRecordingThrowsItAway() {
        show()
        tapTheMic()
        val file = mic.started!!
        compose.onNodeWithContentDescription("Discard recording").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("composer-mic").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTagCount("composer-recording-pill"))
        assertFalse(file.exists())
        assertTrue(voiceFolders().isEmpty())
        assertEquals(1, mic.stops)
        assertTrue("nothing reached the core", core.calls.isEmpty())
    }

    @Test
    fun holdingRecordsAndSlidingLeftCancels() {
        compose.mainClock.autoAdvance = false
        show()
        holdTheMic()
        // Held: "Slide left to cancel", no trash, and the mic stays under the finger.
        compose.onNodeWithText("Slide left to cancel").assertIsDisplayed()
        compose.onNodeWithTag("composer-mic").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTagCount("composer-discard-recording"))
        val file = mic.started!!

        compose.onNodeWithTag("composer-mic").performTouchInput { moveBy(Offset(-density * 100f, 0f)); up() }
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()

        assertEquals(0, compose.onAllNodesWithTagCount("composer-recording-pill"))
        assertFalse(file.exists())
        assertTrue("a cancelled hold sends nothing", core.calls.isEmpty())
    }

    @Test
    fun aShortSlideLeftStillSends() {
        compose.mainClock.autoAdvance = false
        show()
        holdTheMic()
        now += 2_000
        compose.onNodeWithTag("composer-mic").performTouchInput { moveBy(Offset(-density * 40f, 0f)); up() }
        compose.mainClock.advanceTimeBy(100)
        waitForCalls(3)
        assertTrue(core.calls.last().startsWith("attachmentSend"))
    }

    @Test
    fun releasingAHoldSends() {
        compose.mainClock.autoAdvance = false
        show()
        holdTheMic()
        now += 2_000
        compose.onNodeWithTag("composer-mic").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(100)
        waitForCalls(3)
        assertEquals(
            listOf("attachmentStagePath", "attachmentMarkVoice", "attachmentSend"),
            core.calls.map { it.substringBefore(' ') },
        )
    }

    @Test
    fun aSentVoiceNoteIsStagedMarkedThenSent() {
        show()
        tapTheMic()
        now += 3_000
        compose.onNodeWithContentDescription("Send voice message").performClick()
        waitForCalls(3)
        assertEquals(
            listOf(
                "attachmentStagePath !r:x Voice message.ogg",
                "attachmentMarkVoice !r:x tok 3000ms",
                "attachmentSend !r:x tok caption=null",
            ),
            core.calls.toList(),
        )
        assertEquals(listOf<SendResult>(SendResult.Sent), results.toList())
    }

    @Test
    fun aSlipOfTheThumbSendsNothing() {
        show()
        tapTheMic()
        now += 300
        compose.onNodeWithContentDescription("Send voice message").performClick()
        compose.waitForIdle()
        assertTrue(core.calls.isEmpty())
        assertEquals(0, compose.onAllNodesWithTagCount("composer-recording-pill"))
    }

    @Test
    fun aDeliveredVoiceNotePlaysTheSendHaptic() {
        show()
        tapTheMic()
        now += 3_000
        compose.onNodeWithContentDescription("Send voice message").performClick()
        waitForCalls(3)
        compose.waitUntil(5_000) { haptics.played.isNotEmpty() }
        assertEquals(listOf(HapticFeedbackType.ContextClick), haptics.played)
    }

    @Test
    fun aPlayingNoteStopsWhenRecordingStarts() {
        val engine = object : AudioEngine {
            var closes = 0
            override suspend fun open(file: File, onEnded: () -> Unit, onError: () -> Unit): Long? = 7_000
            override fun play() {}
            override fun pause() {}
            override fun seekTo(positionMs: Long) {}
            override val positionMs: Long = 0
            override fun close() { closes++ }
        }
        val playback = VoicePlayback(
            fetch = { PlayableAudio(byteArrayOf(1, 2), "audio/ogg", "ogg", 7_000uL) },
            cacheDir = File(cache, "playback"),
            engine = engine,
            scope = CoroutineScope(Dispatchers.Unconfined),
            io = Dispatchers.Unconfined,
        )
        playback.toggle("\$note")
        compose.waitUntil(5_000) { playback.state.value.note is NotePlayback.Playing }
        show(onRecordingStarts = { playback.stop() })

        tapTheMic()

        assertEquals("the note no longer plays", NotePlayback.Idle, playback.state.value.of("\$note"))
        assertEquals(null, playback.state.value.current)
    }

    @Test
    fun aRefusedMicrophoneShowsTheLineAndRecordsNothing() {
        microphoneAnswer = false
        show()
        tapTheMic()
        compose.onNodeWithTag("composer-failure").assertIsDisplayed()
        assertEquals(
            "Microphone access is off. Turn it on in Settings to record.",
            VoiceRecording.MICROPHONE_OFF,
        )
        compose.onNodeWithText("Microphone access is off. Turn it on in Settings to record.").assertIsDisplayed()
        assertNull(mic.started)
        compose.onNodeWithTag("composer-mic").assertIsDisplayed()
    }

    @Test
    fun thePillFitsAtFontScaleTwo() {
        show(fontScale = 2f)
        tapTheMic()
        val capsule = compose.onNodeWithTag("composer-capsule").fetchSemanticsNode().boundsInRoot
        val discard = compose.onNodeWithTag("composer-discard-recording")
        discard.assertIsDisplayed()
        val bounds = discard.fetchSemanticsNode().boundsInRoot
        assertTrue("discard $bounds outside the composer $capsule", bounds.right <= capsule.right && bounds.left >= capsule.left)
        compose.onNodeWithTag("composer-send").assertIsDisplayed()
    }

    @Test
    fun theRealRecorderWritesOggOpus() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val real = VoiceRecording(
            recorder = MediaRecorderRecorder(context, uniffi.supermessage_ffi.voiceRecordingSpec()),
            cacheDir = cache,
            scope = CoroutineScope(Dispatchers.Default),
        )
        val started = runBlocking { real.start(held = false, microphone = { true }) }
        assertTrue("the emulator's microphone started: ${real.failure.value}", started)
        Thread.sleep(1_200)
        val done = real.finish()
        assertTrue("a 1.2 s recording is long enough", done != null)
        val bytes = done!!.file.readBytes()
        assertEquals("OggS", String(bytes, 0, 4, Charsets.ISO_8859_1))
        assertTrue("OpusHead is in the first page", String(bytes, Charsets.ISO_8859_1).contains("OpusHead"))
        assertEquals("Voice message.ogg", done.file.name)
        android.util.Log.i("VoiceComposerTest", "real recording ${done.durationMs} ms, ${bytes.size} bytes, waveform ${done.waveform}")
    }

    private fun waitForCalls(n: Int) {
        compose.waitUntil(5_000) { core.calls.size >= n }
    }

    private fun textOf(s: String): String {
        compose.onNodeWithText(s).assertIsDisplayed()
        return s
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String): Int =
        onAllNodesWithTag(tag).fetchSemanticsNodes().size
}
