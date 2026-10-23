package dev.supermessage.kit

import java.io.File
import java.util.UUID
import kotlin.math.log10
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uniffi.supermessage_core.VoiceRecordingSpec
import uniffi.supermessage_ffi.voiceLongEnough
import uniffi.supermessage_ffi.voiceRecordingSpec
import uniffi.supermessage_ffi.voiceWaveform

/**
 * The platform recorder, reduced to what [VoiceRecording] asks of it.
 *
 * An interface so the state machine is testable on a plain JVM: `:app` backs it
 * with `MediaRecorder` (Ogg container, Opus encoder, the core's rate, channels
 * and bit rate), the tests with a fake. [maxAmplitude] is the platform's own
 * meter: the largest 16-bit sample since it was last asked, 0..32767.
 */
interface Recorder {
    /** Start writing [file]. Throws when the platform cannot record. */
    fun start(file: File)

    /** Stop and let go of the microphone. Safe to call after a failed [start]. */
    fun stop()

    fun maxAmplitude(): Int
}

/**
 * The audio route while recording: on Android, `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`
 * (so music pauses and an incoming call or another app taking the microphone
 * is heard as a loss). [acquire] calls [onLost] if another app takes the focus;
 * [release] hands it back so what was paused resumes
 * (`VoiceRecorder.swift:193-197`).
 */
interface AudioRoute {
    fun acquire(onLost: () -> Unit)
    fun release()

    object None : AudioRoute {
        override fun acquire(onLost: () -> Unit) {}
        override fun release() {}
    }
}

/**
 * Records a voice message, in a file of its own, with its length and a waveform.
 *
 * Ported from `apple/Supermessage/Composer/VoiceRecorder.swift:18-190`. The
 * numbers (rate, channels, bit rate, meter interval, waveform bins, the
 * shortest message) are the core's `voiceRecordingSpec()`, so every client
 * keeps the same ones. What happens next is the ordinary attachment path:
 * stage the file, mark it as voice with the length and waveform taken here,
 * send with empty text ([Session.sendVoice]).
 *
 * ## A start can be cancelled while it is still asking for the microphone
 *
 * The first time, the gap between asking and recording is the permission
 * prompt, long enough for a finger to lift, the room to change or the account
 * to sign out. Every [cancel] and [abandonPendingStart] bumps a generation
 * token, and a start whose token is stale never records, so a start cancelled
 * during the grant cannot leave a hot microphone (desktop's I2, #144).
 *
 * **Call it from one thread** (the main thread in the app; the test
 * dispatcher in tests).
 */
class VoiceRecording(
    private val recorder: Recorder,
    private val cacheDir: File,
    private val scope: CoroutineScope,
    /** Milliseconds; the app's clock for elapsed time too. */
    val clock: () -> Long = System::currentTimeMillis,
    private val route: AudioRoute = AudioRoute.None,
) {
    sealed interface Phase {
        data object Idle : Phase

        /** [held]: the reader is holding the mic down and releasing sends. */
        data class Recording(val startedAt: Long, val held: Boolean) : Phase
    }

    /** A finished recording. */
    class Recording(val file: File, val durationMs: Long, val waveform: List<Float>) {
        /** Delete the recording's own folder. */
        fun delete() {
            file.parentFile?.deleteRecursively()
        }
    }

    private val spec: VoiceRecordingSpec = voiceRecordingSpec()

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** Why recording could not start, until the next attempt. */
    private val _failure = MutableStateFlow<String?>(null)
    val failure: StateFlow<String?> = _failure.asStateFlow()

    val isRecording: Boolean get() = _phase.value is Phase.Recording

    private var file: File? = null
    private val decibels = mutableListOf<Float>()
    private var meter: Job? = null

    /** Bumped by every cancel, so a start that was cancelled in flight stays dead. */
    private var generation = 0
    private var startingGeneration: Int? = null

    /**
     * Start recording. [microphone] asks for the permission (first use) and
     * says whether it was granted. False when refused, when a recording is
     * already under way, when the start was cancelled meanwhile, or when the
     * platform could not record; [failure] says why for the refusals the
     * reader should hear about.
     */
    suspend fun start(held: Boolean, microphone: suspend () -> Boolean): Boolean {
        if (_phase.value != Phase.Idle || startingGeneration != null) return false
        val mine = ++generation
        startingGeneration = mine
        _failure.value = null
        try {
            val granted = microphone()
            // Cancelled, abandoned or superseded while the prompt was up: never record.
            if (mine != generation) return false
            if (!granted) {
                _failure.value = MICROPHONE_OFF
                return false
            }
            val folder = File(File(cacheDir, FOLDER), UUID.randomUUID().toString())
            val target = File(folder, "${spec.fileStem}.ogg")
            try {
                route.acquire(onLost = ::cancel)
                if (!folder.mkdirs()) throw IllegalStateException("no folder")
                recorder.start(target)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                runCatching { recorder.stop() }
                folder.deleteRecursively()
                route.release()
                _failure.value = COULD_NOT_START
                return false
            }
            file = target
            decibels.clear()
            startMetering()
            _phase.value = Phase.Recording(startedAt = clock(), held = held)
            return true
        } finally {
            if (startingGeneration == mine) startingGeneration = null
        }
    }

    /** A hold ended before recording had begun: see the class doc. */
    fun abandonPendingStart() {
        if (startingGeneration != null) {
            generation++
            startingGeneration = null
        }
    }

    /**
     * Stop, and hand back the recording, or null when it was too short to be
     * anything (it is deleted) or when nothing was recording.
     */
    fun finish(): Recording? {
        val phase = _phase.value as? Phase.Recording ?: return null
        val target = file ?: return null
        val durationMs = (clock() - phase.startedAt).coerceAtLeast(0)
        val sampled = decibels.toList()
        val stopped = runCatching { recorder.stop() }.isSuccess
        reset()
        // Shorter than this is a slip of the thumb, not a message.
        if (!stopped || !voiceLongEnough(durationMs.toULong())) {
            target.parentFile?.deleteRecursively()
            return null
        }
        return Recording(target, durationMs, voiceWaveform(sampled, spec.waveformBins))
    }

    /** Stop and throw the recording away, and kill a start still in flight. */
    fun cancel() {
        generation++
        startingGeneration = null
        val target = file
        if (_phase.value is Phase.Recording) runCatching { recorder.stop() }
        target?.parentFile?.deleteRecursively()
        reset()
    }

    /** One meter reading, appended as dBFS. The meter calls this every `meterIntervalMs`. */
    internal fun sample() {
        if (!isRecording) return
        decibels += decibelsOf(recorder.maxAmplitude())
    }

    private fun startMetering() {
        meter?.cancel()
        meter = scope.launch {
            while (isActive) {
                delay(spec.meterIntervalMs.toLong())
                sample()
            }
        }
    }

    private fun reset() {
        meter?.cancel()
        meter = null
        file = null
        val was = _phase.value
        _phase.value = Phase.Idle
        if (was is Phase.Recording) route.release()
    }

    /**
     * Delete recordings a dead process left behind. Called once at launch,
     * before anything can be recording.
     */
    fun sweep() {
        if (isRecording) return
        File(cacheDir, FOLDER).deleteRecursively()
    }

    companion object {
        /** Under the cache directory, one folder per recording so the file keeps a plain name. */
        const val FOLDER = "voice"
        const val MICROPHONE_OFF = "Microphone access is off. Turn it on in Settings to record."
        const val COULD_NOT_START = "Couldn't start recording."

        /** 20 log10(amp / 32767); silence (0) is negative infinity, which the core reads as silence. */
        fun decibelsOf(amplitude: Int): Float =
            if (amplitude <= 0) Float.NEGATIVE_INFINITY
            else (20.0 * log10(amplitude.coerceAtMost(32767) / 32767.0)).toFloat()
    }
}
