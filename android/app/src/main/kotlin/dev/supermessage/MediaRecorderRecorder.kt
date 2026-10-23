package dev.supermessage

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioManager
import android.media.MediaRecorder
import dev.supermessage.kit.AudioRoute
import dev.supermessage.kit.Recorder
import java.io.File
import uniffi.supermessage_core.VoiceRecordingSpec

/**
 * The real [Recorder]: the microphone into an Ogg file of Opus, at the core's
 * rate, channel count and bit rate (48 kHz mono, 24 kbit/s, CP2 `voiceRecordingSpec`).
 *
 * iOS can only write Opus into CAF; `MediaRecorder.OutputFormat.OGG` with
 * `AudioEncoder.OPUS` (API 29+, under minSdk 31) writes the Ogg that Matrix
 * wants, which the core sends as it is (`C/attachments.rs:910-918`).
 *
 * One `MediaRecorder` per recording: the platform's cannot be reused after
 * `stop()`, and [stop] always releases it so the microphone is let go.
 */
class MediaRecorderRecorder(
    private val context: Context,
    private val spec: VoiceRecordingSpec,
) : Recorder {
    private var recorder: MediaRecorder? = null

    override fun start(file: File) {
        val next = MediaRecorder(context)
        recorder = next
        try {
            next.setAudioSource(MediaRecorder.AudioSource.MIC)
            next.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            next.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            next.setAudioSamplingRate(spec.sampleRate.toInt())
            next.setAudioChannels(spec.channels.toInt())
            next.setAudioEncodingBitRate(spec.bitRate.toInt())
            next.setOutputFile(file.absolutePath)
            next.prepare()
            next.start()
        } catch (error: Throwable) {
            release()
            throw error
        }
    }

    override fun stop() {
        val current = recorder ?: return
        try {
            current.stop()
        } finally {
            release()
        }
    }

    /**
     * The largest sample since the last call, 0..32767.
     *
     * `MediaRecorder.getMaxAmplitude` is the first choice, but the platform
     * throws "getMaxAmplitude failed" for the Ogg/Opus recorder (seen on the API
     * 36 emulator), so a flat waveform would go out with every note. When it
     * does, a second, metering-only `AudioRecord` on the same microphone takes
     * over (apps may capture concurrently with themselves). If that cannot
     * start either, the level reads as silence: a meter must never end a recording.
     */
    override fun maxAmplitude(): Int {
        if (!meterFallback) {
            try {
                return recorder?.maxAmplitude ?: 0
            } catch (error: RuntimeException) {
                meterFallback = true
                android.util.Log.w("MediaRecorderRecorder", "getMaxAmplitude failed; metering with AudioRecord")
            }
        }
        return fallbackLevel()
    }

    private var meterFallback = false
    private var meter: AudioRecord? = null
    private val chunk = ShortArray(2_048)

    @android.annotation.SuppressLint("MissingPermission")
    private fun fallbackLevel(): Int = try {
        val record = meter ?: AudioRecord(
            MediaRecorder.AudioSource.MIC, METER_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            (METER_RATE / 2).coerceAtLeast(AudioRecord.getMinBufferSize(METER_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)),
        ).also {
            if (it.state != AudioRecord.STATE_INITIALIZED) { it.release(); error("no meter") }
            it.startRecording()
            meter = it
        }
        var peak = 0
        while (true) {
            val n = record.read(chunk, 0, chunk.size, AudioRecord.READ_NON_BLOCKING)
            if (n <= 0) break
            for (k in 0 until n) peak = maxOf(peak, kotlin.math.abs(chunk[k].toInt()))
        }
        peak
    } catch (error: RuntimeException) {
        0
    }

    private fun release() {
        meter?.let { runCatching { it.stop() }; runCatching { it.release() } }
        meter = null
        meterFallback = false
        recorder?.let { runCatching { it.reset() }; runCatching { it.release() } }
        recorder = null
    }
}

/**
 * The audio route while recording: `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`, so
 * music pauses and an incoming call (or another app that takes the focus) is
 * heard as a loss, which discards the recording. Abandoning the request on the
 * way out lets what was paused resume (`VoiceRecorder.swift:193-197`).
 */
class AudioFocusRoute(context: Context) : AudioRoute {
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var request: AudioFocusRequest? = null

    override fun acquire(onLost: () -> Unit) {
        val next = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) onLost()
            }
            .build()
        request = next
        // A refused request still records: the focus is a courtesy, a loss is the signal.
        manager.requestAudioFocus(next)
    }

    override fun release() {
        request?.let { manager.abandonAudioFocusRequest(it) }
        request = null
    }
}

private const val METER_RATE = 16_000
