package dev.supermessage.kit

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recorder's rules (`VoiceRecorder.swift:18-190`) over a fake microphone
 * and a clock the test moves. The numbers it must use (600 ms, 60 points) are
 * the core's, read through the real bindings.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceRecordingTest {
    private val cache: File = Files.createTempDirectory("voice-recording").toFile()

    @After
    fun cleanUp() {
        cache.deleteRecursively()
    }

    private class FakeRecorder : Recorder {
        var started: File? = null
        var stops = 0
        var amplitude = 12_000
        var failStart = false
        var reads = 0
        override fun start(file: File) {
            if (failStart) throw IllegalStateException("no microphone")
            started = file
            file.writeText("OggS fake")
        }
        override fun stop() { stops++ }
        override fun maxAmplitude(): Int { reads++; return amplitude }
    }

    private class FakeRoute : AudioRoute {
        var acquired = 0
        var released = 0
        var lost: (() -> Unit)? = null
        override fun acquire(onLost: () -> Unit) { acquired++; lost = onLost }
        override fun release() { released++ }
    }

    private val granted: suspend () -> Boolean = { true }

    private fun TestScope.recording(recorder: FakeRecorder, route: AudioRoute = AudioRoute.None) =
        VoiceRecording(recorder, cache, backgroundScope, clock = { testScheduler.currentTime }, route = route)

    private fun voiceFolders() = File(cache, VoiceRecording.FOLDER).listFiles().orEmpty().toList()

    @Test
    fun aSlipOfTheThumbIsThrownAway() = runTest {
        val mic = FakeRecorder()
        val voice = recording(mic)
        assertTrue(voice.start(held = true, microphone = granted))
        val file = mic.started!!
        assertTrue(file.exists())

        advanceTimeBy(500)
        assertNull("500 ms is not a message", voice.finish())

        assertFalse("the file is deleted", file.exists())
        assertTrue("and its folder", voiceFolders().isEmpty())
        assertEquals(VoiceRecording.Phase.Idle, voice.phase.value)
    }

    @Test
    fun aRecordingLongEnoughIsKept() = runTest {
        val mic = FakeRecorder()
        val voice = recording(mic)
        voice.start(held = false, microphone = granted)
        advanceTimeBy(600)
        val done = voice.finish()
        assertNotNull(done)
        assertEquals(600L, done!!.durationMs)
        assertEquals("Voice message.ogg", done.file.name)
        assertTrue(done.file.exists())
        assertEquals("it lives under cacheDir/voice/<id>/", File(cache, "voice"), done.file.parentFile!!.parentFile)
    }

    @Test
    fun aRecordingHasItsLengthAndAtMostSixtyLevels() = runTest {
        val mic = FakeRecorder()
        val voice = recording(mic)
        voice.start(held = false, microphone = granted)
        // 90 s at the core's 100 ms meter interval is 900 readings, quiet then loud.
        repeat(900) { i ->
            mic.amplitude = if (i < 450) 600 else 20_000
            advanceTimeBy(100)
        }
        val done = voice.finish()!!
        assertEquals(90_000L, done.durationMs)
        assertEquals(60, done.waveform.size)
        assertTrue("within 0..1: ${done.waveform}", done.waveform.all { it in 0f..1f })
        assertTrue("quiet first, loud last", done.waveform.first() < done.waveform.last())
    }

    @Test
    fun silenceIsNegativeInfinityNotAnError() {
        assertEquals(Float.NEGATIVE_INFINITY, VoiceRecording.decibelsOf(0))
        assertEquals(0f, VoiceRecording.decibelsOf(32767), 0.0001f)
        assertEquals(-6.02f, VoiceRecording.decibelsOf(16384), 0.01f)
    }

    @Test
    fun liftingDuringThePermissionPromptAbandonsTheStart() = runTest {
        val mic = FakeRecorder()
        val route = FakeRoute()
        val voice = recording(mic, route)
        val prompt = CompletableDeferred<Boolean>()
        var result: Boolean? = null
        launch { result = voice.start(held = true, microphone = { prompt.await() }) }
        runCurrent()

        voice.abandonPendingStart()
        prompt.complete(true)
        runCurrent()

        assertEquals(false, result)
        assertNull("nothing was ever opened", mic.started)
        assertEquals(VoiceRecording.Phase.Idle, voice.phase.value)
        assertEquals(0, route.acquired)
        assertNull("and no refusal is shown for a start nobody wanted", voice.failure.value)
    }

    @Test
    fun cancelDuringThePermissionPromptNeverRecordsAndALaterStartStillCan() = runTest {
        val mic = FakeRecorder()
        val voice = recording(mic)
        val prompt = CompletableDeferred<Boolean>()
        var first: Boolean? = null
        launch { first = voice.start(held = false, microphone = { prompt.await() }) }
        runCurrent()

        voice.cancel() // a room switch, a sign-out, the pane leaving
        // A second start while the first prompt is still up is allowed, and is the live one.
        assertTrue(voice.start(held = false, microphone = granted))
        voice.cancel()
        prompt.complete(true)
        runCurrent()

        assertEquals(false, first)
        assertEquals("the stale grant did not start a second recording", 1, mic.stops)
        assertEquals(VoiceRecording.Phase.Idle, voice.phase.value)
        assertTrue(voiceFolders().isEmpty())
    }

    @Test
    fun cancelDeletesTheFolder() = runTest {
        val mic = FakeRecorder()
        val route = FakeRoute()
        val voice = recording(mic, route)
        voice.start(held = false, microphone = granted)
        val file = mic.started!!
        assertTrue(file.exists())

        voice.cancel()

        assertFalse(file.exists())
        assertFalse(file.parentFile!!.exists())
        assertEquals(1, mic.stops)
        assertEquals("the audio route is handed back", 1, route.released)
        assertEquals(VoiceRecording.Phase.Idle, voice.phase.value)
    }

    @Test
    fun aSecondStartWhileRecordingIsRefused() = runTest {
        val mic = FakeRecorder()
        val voice = recording(mic)
        assertTrue(voice.start(held = false, microphone = granted))
        val first = mic.started
        assertFalse(voice.start(held = true, microphone = granted))
        assertEquals("the first recording goes on", first, mic.started)
        assertEquals(VoiceRecording.Phase.Recording(startedAt = 0, held = false), voice.phase.value)
    }

    @Test
    fun aRefusedMicrophoneSaysSo() = runTest {
        val mic = FakeRecorder()
        val voice = recording(mic)
        assertFalse(voice.start(held = false, microphone = { false }))
        assertEquals("Microphone access is off. Turn it on in Settings to record.", voice.failure.value)
        assertNull(mic.started)
        // The next attempt clears it.
        voice.start(held = false, microphone = granted)
        assertNull(voice.failure.value)
    }

    @Test
    fun aRecorderThatCannotStartSaysSoAndLeavesNothingBehind() = runTest {
        val mic = FakeRecorder().also { it.failStart = true }
        val route = FakeRoute()
        val voice = recording(mic, route)
        assertFalse(voice.start(held = false, microphone = granted))
        assertEquals("Couldn't start recording.", voice.failure.value)
        assertTrue(voiceFolders().isEmpty())
        assertEquals("the route acquired for it is released", route.released, route.acquired)
        assertEquals(VoiceRecording.Phase.Idle, voice.phase.value)
    }

    @Test
    fun losingTheAudioFocusDiscardsTheRecording() = runTest {
        val mic = FakeRecorder()
        val route = FakeRoute()
        val voice = recording(mic, route)
        voice.start(held = false, microphone = granted)
        val file = mic.started!!

        route.lost!!.invoke() // an incoming call took the microphone

        assertEquals(VoiceRecording.Phase.Idle, voice.phase.value)
        assertFalse(file.exists())
        assertEquals(1, route.released)
    }

    @Test
    fun theMeterStopsWhenTheRecordingEnds() = runTest {
        val mic = FakeRecorder()
        val voice = recording(mic)
        voice.start(held = false, microphone = granted)
        advanceTimeBy(1_000)
        voice.cancel()
        val before = mic.reads
        advanceTimeBy(5_000)
        assertEquals(before, mic.reads)
    }

    @Test
    fun sweepRemovesWhatADeadProcessLeft() = runTest {
        val leftover = File(cache, "voice/abc/Voice message.ogg").also { it.parentFile!!.mkdirs(); it.writeText("x") }
        recording(FakeRecorder()).sweep()
        assertFalse(leftover.exists())
        assertFalse(File(cache, "voice").exists())
    }
}
