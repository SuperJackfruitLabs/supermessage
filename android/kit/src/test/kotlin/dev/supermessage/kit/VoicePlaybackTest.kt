package dev.supermessage.kit

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.supermessage_core.PlayableAudio

/**
 * The voice-note player's rules: one note at a time, what each tap means,
 * how often the position moves, and what a failure leaves behind.
 *
 * The playback scope is `backgroundScope`, and nothing here calls
 * `advanceUntilIdle`: a playing note ticks forever by design, so "idle" never
 * comes. Time is moved by hand with `advanceTimeBy` / `runCurrent`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoicePlaybackTest {

    private val dir: File = Files.createTempDirectory("voice").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /** A player whose clock the test moves, and which remembers what it was told. */
    private class FakeEngine(var duration: Long? = 7_000) : AudioEngine {
        val opened = mutableListOf<File>()
        var playing = false
        var closes = 0
        var failOpen = false
        var ended: (() -> Unit)? = null
        var errored: (() -> Unit)? = null
        override var positionMs: Long = 0

        override suspend fun open(file: File, onEnded: () -> Unit, onError: () -> Unit): Long? {
            if (failOpen) throw IllegalStateException("cannot open")
            opened += file
            ended = onEnded
            errored = onError
            positionMs = 0
            playing = true
            return duration
        }

        override fun play() { playing = true }
        override fun pause() { playing = false }
        override fun seekTo(positionMs: Long) { this.positionMs = positionMs }
        override fun close() {
            closes++
            playing = false
        }
    }

    private fun audio(ext: String = "ogg") =
        PlayableAudio(data = byteArrayOf(1, 2, 3), mimetype = "audio/ogg", fileExtension = ext, durationMs = 7_000uL)

    private fun TestScope.player(
        engine: AudioEngine,
        fetch: suspend (String) -> PlayableAudio? = { audio() },
    ) = VoicePlayback(
        fetch = fetch,
        cacheDir = dir,
        engine = engine,
        scope = backgroundScope,
        io = StandardTestDispatcher(testScheduler),
        tickMs = 100,
    )

    @Test
    fun `a tap loads, then plays from the start with the player's length`() = runTest {
        val engine = FakeEngine(duration = 9_000)
        val p = player(engine)

        p.toggle("\$a")
        assertEquals(NotePlayback.Loading, p.state.value.of("\$a"))

        runCurrent()
        assertEquals(NotePlayback.Playing(0, 9_000), p.state.value.of("\$a"))
        assertTrue(engine.playing)
        // Written under the hash of the event id, with the core's extension.
        assertEquals("${VoicePlayback.stem("\$a")}.ogg", engine.opened.single().name)
    }

    @Test
    fun `one note at a time - starting another stops the first`() = runTest {
        val engine = FakeEngine()
        val p = player(engine)

        p.toggle("\$a")
        runCurrent()
        val closesWhileA = engine.closes

        p.toggle("\$b")
        runCurrent()

        assertEquals("the first note's player was released", closesWhileA + 1, engine.closes)
        assertEquals("\$b", p.state.value.current)
        assertEquals(NotePlayback.Idle, p.state.value.of("\$a"))
        assertTrue(p.state.value.of("\$b") is NotePlayback.Playing)
        assertEquals(2, engine.opened.size)
    }

    @Test
    fun `play, pause, resume`() = runTest {
        val engine = FakeEngine()
        val p = player(engine)
        p.toggle("\$a")
        runCurrent()

        engine.positionMs = 3_000
        p.toggle("\$a")
        assertEquals(NotePlayback.Paused(3_000, 7_000), p.state.value.of("\$a"))
        assertFalse(engine.playing)

        p.toggle("\$a")
        assertEquals(NotePlayback.Playing(3_000, 7_000), p.state.value.of("\$a"))
        assertTrue(engine.playing)
    }

    @Test
    fun `the position moves ten times a second while playing and not while paused`() = runTest {
        val engine = FakeEngine()
        val p = player(engine)
        p.toggle("\$a")
        runCurrent()

        engine.positionMs = 250
        advanceTimeBy(99)
        runCurrent()
        assertEquals("no tick before 100ms", 0L, (p.state.value.note as NotePlayback.Playing).positionMs)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(250L, (p.state.value.note as NotePlayback.Playing).positionMs)

        p.toggle("\$a") // pause
        engine.positionMs = 900 // a player that drifted would show here
        advanceTimeBy(500)
        runCurrent()
        assertEquals(NotePlayback.Paused(250, 7_000), p.state.value.note)
    }

    @Test
    fun `nothing to fetch is a failure, and a retry clears it`() = runTest {
        val engine = FakeEngine()
        var answer: PlayableAudio? = null
        val p = player(engine) { answer }

        p.toggle("\$a")
        runCurrent()
        assertEquals(NotePlayback.Failed, p.state.value.of("\$a"))
        assertEquals(null, p.state.value.current)

        answer = audio()
        p.toggle("\$a")
        assertEquals(NotePlayback.Loading, p.state.value.of("\$a"))
        runCurrent()
        assertTrue(p.state.value.of("\$a") is NotePlayback.Playing)

        // Played once it worked, so it is no longer remembered as failed.
        p.stop()
        assertEquals(NotePlayback.Idle, p.state.value.of("\$a"))
    }

    @Test
    fun `a failed note stays failed while another plays`() = runTest {
        val engine = FakeEngine()
        val p = player(engine) { id -> if (id == "\$bad") throw RuntimeException("offline") else audio() }

        p.toggle("\$bad")
        runCurrent()
        p.toggle("\$good")
        runCurrent()

        assertEquals(NotePlayback.Failed, p.state.value.of("\$bad"))
        assertTrue(p.state.value.of("\$good") is NotePlayback.Playing)
    }

    @Test
    fun `a file the player refuses is failed and not kept`() = runTest {
        val engine = FakeEngine().apply { failOpen = true }
        val p = player(engine)

        p.toggle("\$a")
        runCurrent()

        assertEquals(NotePlayback.Failed, p.state.value.of("\$a"))
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun `a note that ends goes back to rest`() = runTest {
        val engine = FakeEngine()
        val p = player(engine)
        p.toggle("\$a")
        runCurrent()

        engine.ended!!()
        runCurrent()

        assertEquals(NotePlayback.Idle, p.state.value.of("\$a"))
        assertEquals(null, p.state.value.current)
    }

    @Test
    fun `an ending from a note already left does not stop the next one`() = runTest {
        val engine = FakeEngine()
        val p = player(engine)
        p.toggle("\$a")
        runCurrent()
        val endOfA = engine.ended!!

        p.toggle("\$b")
        runCurrent()
        endOfA()
        runCurrent()

        assertTrue(p.state.value.of("\$b") is NotePlayback.Playing)
    }

    @Test
    fun `a slow fetch for a note already left cannot take over`() = runTest {
        val engine = FakeEngine()
        val slow = CompletableDeferred<PlayableAudio?>()
        val p = player(engine) { id -> if (id == "\$a") slow.await() else audio() }

        p.toggle("\$a")
        runCurrent()
        p.toggle("\$b")
        runCurrent()
        slow.complete(audio())
        runCurrent()

        assertEquals("\$b", p.state.value.current)
        assertEquals(1, engine.opened.size)
    }

    /**
     * A platform prepare cannot be interrupted: `MediaPlayer.prepare()` runs
     * to the end even once the reader has moved on. Its answer must not
     * overwrite the note they moved to.
     */
    @Test
    fun `an open that finishes after the reader moved on cannot take over`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val engine = object : AudioEngine by FakeEngine() {
            override suspend fun open(file: File, onEnded: () -> Unit, onError: () -> Unit): Long? {
                if (file.name.startsWith(VoicePlayback.stem("\$a"))) {
                    withContext(NonCancellable) { gate.await() }
                }
                return 7_000
            }
        }
        val slowB = CompletableDeferred<PlayableAudio?>()
        val p = player(engine) { id -> if (id == "\$b") slowB.await() else audio() }

        p.toggle("\$a")
        runCurrent() // $a is now inside open()
        p.toggle("\$b")
        runCurrent() // $b is fetching
        gate.complete(Unit)
        runCurrent()

        assertEquals("\$b", p.state.value.current)
        assertEquals(NotePlayback.Loading, p.state.value.of("\$b"))
    }

    @Test
    fun `a second tap while loading cancels it`() = runTest {
        val engine = FakeEngine()
        val never = CompletableDeferred<PlayableAudio?>()
        val p = player(engine) { never.await() }

        p.toggle("\$a")
        runCurrent()
        p.toggle("\$a")

        assertEquals(NotePlayback.Idle, p.state.value.of("\$a"))
        assertEquals(null, p.state.value.current)
    }

    @Test
    fun `stop releases the player`() = runTest {
        val engine = FakeEngine()
        val p = player(engine)
        p.toggle("\$a")
        runCurrent()
        val before = engine.closes

        p.stop()

        assertEquals(before + 1, engine.closes)
        assertEquals(NotePlayback.Idle, p.state.value.of("\$a"))
    }

    @Test
    fun `a note played twice is fetched once`() = runTest {
        val engine = FakeEngine()
        var fetches = 0
        val p = player(engine) { fetches++; audio() }

        p.toggle("\$a")
        runCurrent()
        p.stop()
        p.toggle("\$a")
        runCurrent()

        assertEquals(1, fetches)
        assertEquals(engine.opened[0], engine.opened[1])
    }

    @Test
    fun `seeking moves the playing note, and only that one`() = runTest {
        val engine = FakeEngine(duration = 10_000)
        val p = player(engine)
        p.toggle("\$a")
        runCurrent()

        p.seek("\$b", 0.5f)
        assertEquals(0L, engine.positionMs)

        p.seek("\$a", 0.25f)
        assertEquals(2_500L, engine.positionMs)
        assertEquals(NotePlayback.Playing(2_500, 10_000), p.state.value.note)
    }

    // MARK: - VoiceWaveform

    @Test
    fun `no waveform draws an even placeholder`() {
        val bars = VoiceWaveform.bars(null)
        assertEquals(VoiceWaveform.PLACEHOLDER_BARS, bars.size)
        assertTrue(bars.all { it == VoiceWaveform.PLACEHOLDER_LEVEL })
        assertEquals(VoiceWaveform.PLACEHOLDER_BARS, VoiceWaveform.bars(emptyList()).size)
        assertEquals(listOf(0.1f, 0.9f), VoiceWaveform.bars(listOf(0.1f, 0.9f)))
    }

    @Test
    fun `progress fills the bars it has reached`() {
        assertEquals(0, VoiceWaveform.playedBars(0f, 10))
        assertEquals(1, VoiceWaveform.playedBars(0.01f, 10))
        assertEquals(1, VoiceWaveform.playedBars(0.09f, 10))
        assertEquals(2, VoiceWaveform.playedBars(0.1f, 10))
        assertEquals(6, VoiceWaveform.playedBars(0.55f, 10))
        assertEquals(10, VoiceWaveform.playedBars(0.99f, 10))
        assertEquals(10, VoiceWaveform.playedBars(1f, 10))
        assertEquals(0, VoiceWaveform.playedBars(0.5f, 0))
    }

    @Test
    fun `progress is position over length, and nothing when the length is unknown`() {
        assertEquals(0.5f, VoiceWaveform.progress(3_500, 7_000), 0.0001f)
        assertEquals(1f, VoiceWaveform.progress(9_000, 7_000), 0.0001f)
        assertEquals(0f, VoiceWaveform.progress(3_500, null), 0.0001f)
        assertEquals(0f, VoiceWaveform.progress(3_500, 0), 0.0001f)
    }
}
