package dev.supermessage.kit

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.supermessage_ffi.AttachmentSendOutcome
import uniffi.supermessage_ffi.StagedFile

/** A finished recording goes: staged, marked as voice, then sent. Only fakes; nothing is sent anywhere. */
class VoiceSendTest {
    private val dir: File = Files.createTempDirectory("voice-send").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private class FakeCore : StubCore() {
        val calls = mutableListOf<String>()
        var refuseMark = false
        var failSend = false
        override fun attachmentStagePath(roomId: String, path: String): StagedFile {
            calls += "stage $roomId ${File(path).name}"
            return StagedFile("tok", File(path).name, 10uL, "audio/ogg", null, null)
        }
        override fun attachmentMarkVoice(roomId: String, token: String, durationMs: ULong, waveform: List<Float>) {
            if (refuseMark) throw IllegalStateException("no")
            calls += "mark $roomId $token $durationMs $waveform"
        }
        override fun attachmentSend(roomId: String, token: String, caption: String?): AttachmentSendOutcome {
            calls += "send $roomId $token caption=$caption"
            return if (failSend) AttachmentSendOutcome.Failed(uniffi.supermessage_ffi.FfiException.Protocol("offline"), null)
            else AttachmentSendOutcome.Sent
        }
        override fun attachmentDiscard(token: String) { calls += "discard $token" }
        override fun setHiddenEvents(eventIds: List<String>) {}
    }

    private fun recording(): VoiceRecording.Recording {
        val folder = File(dir, "voice/x").also { it.mkdirs() }
        val file = File(folder, "Voice message.ogg").also { it.writeText("OggS") }
        return VoiceRecording.Recording(file, 4_250, listOf(0.2f, 0.8f))
    }

    private fun TestScopeSession(core: FakeCore, scope: kotlinx.coroutines.CoroutineScope) =
        Session(CoreClient(core, Dispatchers.Unconfined), scope)

    @Test
    fun aVoiceNoteIsStagedMarkedThenSentWithNoText() = runTest {
        val core = FakeCore()
        val session = TestScopeSession(core, this)
        val note = recording()

        val result = session.sendVoice(note, "!r:x")

        assertEquals(SendResult.Sent, result)
        assertEquals(
            listOf(
                "stage !r:x Voice message.ogg",
                "mark !r:x tok 4250 [0.2, 0.8]",
                "send !r:x tok caption=null",
            ),
            core.calls,
        )
        assertFalse("the recording is deleted once it has gone", note.file.parentFile!!.exists())
    }

    @Test
    fun aRefusedMarkSendsNothingAndDiscardsTheStagedFile() = runTest {
        val core = FakeCore().also { it.refuseMark = true }
        val session = TestScopeSession(core, this)
        val note = recording()

        val result = session.sendVoice(note, "!r:x")

        assertTrue(result is SendResult.AttachmentFailed)
        assertTrue(core.calls.none { it.startsWith("send") })
        assertTrue(core.calls.contains("discard tok"))
    }

    @Test
    fun aFailedSendKeepsTheRecordingForRetry() = runTest {
        val core = FakeCore().also { it.failSend = true }
        val session = TestScopeSession(core, this)
        val note = recording()

        val result = session.sendVoice(note, "!r:x")

        assertTrue(result is SendResult.AttachmentFailed)
        assertTrue(note.file.exists())
    }
}
