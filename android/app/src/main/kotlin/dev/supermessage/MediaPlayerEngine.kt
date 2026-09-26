package dev.supermessage

import android.media.AudioAttributes
import android.media.MediaPlayer
import dev.supermessage.kit.AudioEngine
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [AudioEngine] on the platform's own `MediaPlayer` — no new dependency.
 *
 * `MediaPlayer` opens Ogg/Opus (what Element and this app send) and AAC/m4a
 * natively, which is why Android asks the core for the file as it is
 * (`opusInCaf = false`) rather than the CAF remux iOS needs.
 *
 * Preparing reads the file, so it happens on [Dispatchers.IO]. A player
 * created on a thread without a `Looper` delivers its callbacks on the main
 * one, which is where [dev.supermessage.kit.VoicePlayback] lives.
 *
 * [close] can land while a prepare is still running on the IO thread (the
 * reader tapped a different note). [opened] counts closes, and a prepare that
 * finishes after one releases its own player instead of keeping it.
 */
class MediaPlayerEngine : AudioEngine {
    private val lock = Any()
    private var player: MediaPlayer? = null
    private var opened = 0

    override suspend fun open(file: File, onEnded: () -> Unit, onError: () -> Unit): Long? {
        val ticket = synchronized(lock) { opened }
        val prepared = withContext(Dispatchers.IO) {
            val p = MediaPlayer()
            try {
                p.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                p.setDataSource(file.absolutePath)
                p.prepare()
            } catch (e: Exception) {
                p.release()
                throw e
            }
            p
        }
        synchronized(lock) {
            if (opened != ticket) {
                prepared.release()
                throw CancellationException("closed while opening")
            }
            player = prepared
        }
        prepared.setOnCompletionListener { onEnded() }
        prepared.setOnErrorListener { _, _, _ ->
            onError()
            true
        }
        prepared.start()
        return prepared.duration.toLong().takeIf { it > 0 }
    }

    override fun play() {
        synchronized(lock) { player }?.start()
    }

    override fun pause() {
        synchronized(lock) { player }?.takeIf { it.isPlaying }?.pause()
    }

    override fun seekTo(positionMs: Long) {
        synchronized(lock) { player }?.seekTo(positionMs, MediaPlayer.SEEK_CLOSEST)
    }

    override val positionMs: Long
        get() = synchronized(lock) { player }?.currentPosition?.toLong() ?: 0L

    override fun close() {
        val p = synchronized(lock) {
            opened++
            player.also { player = null }
        }
        p?.release()
    }
}
