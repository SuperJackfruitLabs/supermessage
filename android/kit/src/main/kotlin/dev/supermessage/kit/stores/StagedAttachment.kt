package dev.supermessage.kit.stores

import dev.supermessage.kit.CoreClient
import dev.supermessage.kit.ErrorPresenter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.supermessage_ffi.AttachmentSendOutcome
import uniffi.supermessage_ffi.FfiException
import uniffi.supermessage_ffi.StagedFile

/**
 * The one file waiting to be sent.
 *
 * One, not many: multiple attachments in a single send are out of scope, and
 * the strip shows a single chip. A second pick replaces the first rather than
 * queueing, so what is on screen is always what will be sent.
 *
 * Ported from `apple/SupermessageKit/Stores/StagedAttachment.swift`, which has
 * no Swift test of its own. See [DraftStore]'s doc comment for why
 * `@MainActor` becomes a documented, not checked, invariant here.
 *
 * It also remembers *failure*: a file that could not be staged or sent leaves
 * a [Failure] for the chip to draw, and a failed send keeps the file the core
 * re-staged (`AttachmentSendOutcome.Failed.restaged`), so Retry has a live
 * token. The re-stage itself is the core's (M1.7); nothing here repeats it.
 *
 * A typed refusal from the core is turned into per-case wording by
 * [ErrorPresenter] — "that file is 12 MB; the limit is 10 MB", and so on —
 * the same as Swift's `ErrorPresenter.message(for:)`. Anything the core did
 * *not* throw as its own [FfiException] (a coroutine-machinery failure, say)
 * falls back to a fixed apology instead, since there is no per-case wording
 * for a failure the core never described.
 */
class StagedAttachment(private val client: CoreClient) {
    /** What the chip is about when the attachment could not be staged or sent. */
    data class Failure(val filename: String, val message: String)

    private val _file = MutableStateFlow<StagedFile?>(null)
    val file: StateFlow<StagedFile?> = _file.asStateFlow()

    /** Where [file] was staged from, so the chip can draw a thumbnail. */
    private val _path = MutableStateFlow<String?>(null)
    val path: StateFlow<String?> = _path.asStateFlow()

    /**
     * The room it was staged for. The core binds a token to one room, and a
     * chip that followed the reader into another room could not be sent there.
     */
    private val _roomId = MutableStateFlow<String?>(null)
    val roomId: StateFlow<String?> = _roomId.asStateFlow()

    /** Why the last stage or send did not work, drawn on the chip. */
    private val _failure = MutableStateFlow<Failure?>(null)
    val failure: StateFlow<Failure?> = _failure.asStateFlow()

    /** Whether there is a chip to draw in [roomId] at all: a file, a failure, or both. */
    fun isPresent(roomId: String): Boolean =
        _roomId.value == roomId && (_file.value != null || _failure.value != null)

    /**
     * Whether the chip in [roomId] stands for an attachment that cannot go.
     * The composer must not send the text on its own while this is true.
     */
    fun blocksSend(roomId: String): Boolean =
        _roomId.value == roomId && _file.value == null && _failure.value != null

    /**
     * Hand the core a path and keep the token it returns.
     *
     * The core does the rest — sniffing the mime from the file's *content*
     * rather than trusting its extension, reading dimensions from the header,
     * bounding the size. Returns a message when it refuses, or `null`; the
     * same message is kept as [failure] for the chip.
     */
    suspend fun stage(path: String, roomId: String): String? {
        // Replacing rather than queueing: discard whatever was staged
        // first, so a token cannot be orphaned in the core.
        discard()
        _roomId.value = roomId
        return try {
            _file.value = client.attachmentStagePath(roomId = roomId, path = path)
            _path.value = path
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val message = messageFor(error, otherwise = "Couldn't attach that file.")
            _failure.value = Failure(filenameOf(path), message)
            message
        }
    }

    /**
     * Record a refusal that happened before the core was ever asked: the
     * picker's bytes could not be read, or copied.
     */
    suspend fun refuse(filename: String, message: String, roomId: String) {
        discard()
        _roomId.value = roomId
        _failure.value = Failure(filename, message)
    }

    /**
     * Send it, consuming the token. Returns a message on refusal.
     *
     * [caption] is what the reader typed with the file; it travels in the same
     * event (MSC2530) rather than as a second message. A chip that is a
     * failure with no file behind it refuses without asking the core anything.
     */
    suspend fun send(roomId: String, caption: String? = null): String? {
        val sent = _file.value ?: return _failure.value?.message
        return try {
            when (val outcome =
                client.attachmentSend(roomId = roomId, token = sent.token, caption = caption)) {
                is AttachmentSendOutcome.Sent -> {
                    // Clear only if the chip still holds the file that went;
                    // a newer pick made mid-send stays.
                    if (_file.value?.token == sent.token) clear()
                    null
                }
                is AttachmentSendOutcome.Failed -> {
                    val message = ErrorPresenter.message(outcome.error)
                    val restaged = outcome.restaged
                    // The send suspends, so the reader may have picked another
                    // file meanwhile. Adopt the re-staged file only if the
                    // tracker is empty or still holds the file that was sent;
                    // otherwise release the fresh token and leave the newer
                    // pick alone (the caller still gets the message).
                    if (stillOurs(sent)) {
                        _file.value = restaged
                        _failure.value = Failure(sent.filename, message)
                    } else {
                        restaged?.let { client.attachmentDiscard(token = it.token) }
                    }
                    message
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // Refused before the core took the token.
            val message = messageFor(error, otherwise = "Couldn't send that file.")
            if (error is FfiException.UnknownAttachment && _file.value?.token == sent.token) {
                // The core no longer holds the token and there is no path to
                // stage again: nothing can be sent from this chip.
                _file.value = null
            }
            if (stillOurs(sent)) {
                _failure.value = Failure(sent.filename, message)
            }
            message
        }
    }

    suspend fun discard() {
        _file.value?.let { client.attachmentDiscard(token = it.token) }
        clear()
    }

    /**
     * Whether the tracker is empty or still holds [sent]. A newer pick, or a
     * newer pick's own failure, means the reader moved on mid-send, and the
     * old send must not overwrite it.
     */
    private fun stillOurs(sent: StagedFile): Boolean {
        val current = _file.value
        return if (current != null) current.token == sent.token else _failure.value == null
    }

    private fun clear() {
        _file.value = null
        _path.value = null
        _failure.value = null
        _roomId.value = null
    }

    private fun messageFor(error: Throwable, otherwise: String): String =
        if (error is FfiException) ErrorPresenter.message(error) else otherwise

    private fun filenameOf(path: String): String =
        path.substringAfterLast('/').ifEmpty { "Attachment" }
}
