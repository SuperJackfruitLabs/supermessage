package dev.supermessage.kit

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.supermessage_core.PlayableAudio

/**
 * The platform player, reduced to what [VoicePlayback] asks of it.
 *
 * An interface so the state below is testable on a plain JVM: `:app` backs it
 * with `android.media.MediaPlayer` (`MediaPlayerEngine`), and the tests with a
 * fake whose clock they move by hand. One engine plays one file at a time;
 * [open] replaces whatever was open.
 */
interface AudioEngine {
    /**
     * Open [file] and start playing it from the beginning. Returns the length
     * the player read from the file, or `null` when it could not say.
     * Throws when the platform cannot open the file at all.
     *
     * [onEnded] and [onError] may be called later, on any thread the platform
     * chooses; [VoicePlayback] only reads state from them.
     */
    suspend fun open(file: File, onEnded: () -> Unit, onError: () -> Unit): Long?
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    val positionMs: Long

    /** Stop and let go of the file and the decoder. Safe to call twice. */
    fun close()
}

/**
 * What one note is doing, from the reader's side of it.
 *
 * Only one note is ever [Loading], [Playing] or [Paused] — see
 * [VoicePlayback]. Every other note is [Idle], or [Failed] if it could not be
 * played the last time someone asked.
 */
sealed interface NotePlayback {
    data object Idle : NotePlayback
    data object Loading : NotePlayback
    data class Playing(val positionMs: Long, val durationMs: Long?) : NotePlayback
    data class Paused(val positionMs: Long, val durationMs: Long?) : NotePlayback
    data object Failed : NotePlayback
}

/**
 * Plays voice notes, one at a time, app-wide.
 *
 * Starting a note stops whichever note was playing — there is exactly one
 * [AudioEngine] and one [current] note, so two playing at once cannot be
 * expressed rather than merely being avoided. The caller stops it outright
 * ([stop]) when the room changes, when the timeline leaves composition and
 * when the app goes to the background.
 *
 * The bytes come from the core ([fetch], `Session.playableAudio`) and are
 * written to `cacheDir/<sha-256 of the event id>.<extension>`, because a
 * platform player opens a file, not a byte array. A note played twice is
 * fetched once: the file is looked for before the core is asked.
 *
 * Position is sampled every [tickMs] while playing (a tenth of a second), which
 * is what moves the waveform's fill and the elapsed clock.
 *
 * **Call it from one thread** (the main thread in the app, the test
 * dispatcher in tests). The engine's callbacks may arrive elsewhere; they
 * are hopped onto [scope] before they touch anything.
 */
class VoicePlayback(
    private val fetch: suspend (eventId: String) -> PlayableAudio?,
    private val cacheDir: File,
    private val engine: AudioEngine,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val tickMs: Long = 100,
) {
    /** Which note holds the engine, and what it is doing. */
    data class State(
        val current: String? = null,
        val note: NotePlayback = NotePlayback.Idle,
        val failed: Set<String> = emptySet(),
    ) {
        /** What [eventId]'s player should show. */
        fun of(eventId: String): NotePlayback = when {
            eventId == current -> note
            eventId in failed -> NotePlayback.Failed
            else -> NotePlayback.Idle
        }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null
    private var ticker: Job? = null

    /**
     * Bumped on every start and stop, so a fetch or an engine callback that
     * belongs to a note the reader has since left cannot write over the one
     * they moved to.
     */
    private var generation = 0

    /** The one control a note has: play, pause, resume, or retry. */
    fun toggle(eventId: String) {
        val now = _state.value
        if (now.current == eventId) {
            when (val note = now.note) {
                is NotePlayback.Playing -> {
                    engine.pause()
                    stopTicking()
                    _state.value = now.copy(note = NotePlayback.Paused(engine.positionMs, note.durationMs))
                }
                is NotePlayback.Paused -> {
                    engine.play()
                    _state.value = now.copy(note = NotePlayback.Playing(note.positionMs, note.durationMs))
                    startTicking()
                }
                // A second tap while it loads is "never mind".
                NotePlayback.Loading -> stop()
                NotePlayback.Idle, NotePlayback.Failed -> start(eventId)
            }
        } else {
            start(eventId)
        }
    }

    /**
     * Move [eventId] to [fraction] of its length. Only the note that holds the
     * engine can seek, and only once its length is known.
     */
    fun seek(eventId: String, fraction: Float) {
        val now = _state.value
        if (now.current != eventId) return
        val note = now.note
        val duration = when (note) {
            is NotePlayback.Playing -> note.durationMs
            is NotePlayback.Paused -> note.durationMs
            else -> null
        } ?: return
        val target = (duration * fraction.coerceIn(0f, 1f)).toLong()
        engine.seekTo(target)
        _state.value = now.copy(
            note = when (note) {
                is NotePlayback.Playing -> note.copy(positionMs = target)
                is NotePlayback.Paused -> note.copy(positionMs = target)
                else -> note
            },
        )
    }

    /** Stop whatever is playing or loading, and release the player. */
    fun stop() {
        generation++
        job?.cancel()
        job = null
        stopTicking()
        engine.close()
        _state.value = _state.value.copy(current = null, note = NotePlayback.Idle)
    }

    private fun start(eventId: String) {
        stop()
        val mine = generation
        _state.value = _state.value.let {
            it.copy(current = eventId, note = NotePlayback.Loading, failed = it.failed - eventId)
        }
        job = scope.launch {
            try {
                val file = cached(eventId) ?: fetch(eventId)?.let { write(eventId, it) }
                if (file == null) {
                    fail(eventId, mine)
                    return@launch
                }
                val duration = try {
                    engine.open(
                        file = file,
                        onEnded = { scope.launch { if (generation == mine) stop() } },
                        onError = { scope.launch { if (generation == mine) fail(eventId, mine) } },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A file the player refuses is not kept: a retry fetches
                    // it again rather than failing on the same bytes forever.
                    withContext(io) { file.delete() }
                    throw e
                }
                if (generation != mine) return@launch
                _state.value = _state.value.copy(note = NotePlayback.Playing(0, duration?.takeIf { it > 0 }))
                startTicking()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(eventId, mine)
            }
        }
    }

    private fun fail(eventId: String, mine: Int) {
        if (generation != mine) return
        stop()
        _state.value = _state.value.let { it.copy(failed = it.failed + eventId) }
    }

    private fun startTicking() {
        stopTicking()
        ticker = scope.launch {
            while (isActive) {
                delay(tickMs)
                val now = _state.value
                val note = now.note as? NotePlayback.Playing ?: break
                _state.value = now.copy(note = note.copy(positionMs = engine.positionMs))
            }
        }
    }

    private fun stopTicking() {
        ticker?.cancel()
        ticker = null
    }

    private suspend fun cached(eventId: String): File? = withContext(io) {
        val stem = stem(eventId)
        cacheDir.listFiles()?.firstOrNull { it.nameWithoutExtension == stem && it.length() > 0 }
    }

    private suspend fun write(eventId: String, audio: PlayableAudio): File = withContext(io) {
        cacheDir.mkdirs()
        val target = File(cacheDir, "${stem(eventId)}.${audio.fileExtension}")
        // Written beside and renamed, so a note cut off mid-write is never
        // found by [cached] as a complete file.
        val partial = File(cacheDir, "${target.name}.partial")
        partial.writeBytes(audio.data)
        if (!partial.renameTo(target)) {
            partial.delete()
            throw java.io.IOException("could not keep ${target.name}")
        }
        target
    }

    companion object {
        /** An event id names a file only through its hash: it holds `$` and `:`. */
        internal fun stem(eventId: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(eventId.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

/**
 * The waveform's arithmetic, kept out of the view so it can be tested.
 *
 * The bars themselves are the core's (`AudioView.waveform`, 0..1, at most
 * 120). What is decided here is only how a host draws the lack of them and
 * how much of them a position has filled.
 */
object VoiceWaveform {
    /** How many even bars stand in for a note that sent no waveform. */
    const val PLACEHOLDER_BARS = 40

    /** Their height: flat and quiet, so nothing reads as a shape that was invented. */
    const val PLACEHOLDER_LEVEL = 0.3f

    /** The bars to draw: the note's own, or an even placeholder set. */
    fun bars(waveform: List<Float>?): List<Float> =
        waveform?.takeIf { it.isNotEmpty() } ?: List(PLACEHOLDER_BARS) { PLACEHOLDER_LEVEL }

    /** How far through a note [positionMs] is, 0..1. Zero when the length is unknown. */
    fun progress(positionMs: Long, durationMs: Long?): Float {
        if (durationMs == null || durationMs <= 0) return 0f
        return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    /**
     * How many of [barCount] bars are drawn as played at [progress]. A bar
     * fills once playback has reached its start, so the first bar lights the
     * moment a note begins and the last one by the time it ends.
     */
    fun playedBars(progress: Float, barCount: Int): Int {
        if (barCount <= 0 || progress <= 0f) return 0
        if (progress >= 1f) return barCount
        return (kotlin.math.floor(progress * barCount).toInt() + 1).coerceAtMost(barCount)
    }
}
