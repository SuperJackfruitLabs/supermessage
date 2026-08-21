package dev.supermessage.kit.stores

import dev.supermessage.kit.CoreClient
import dev.supermessage.kit.ErrorPresenter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * A typed refusal from the core is turned into per-case wording by
 * [ErrorPresenter] — "that file is 12 MB; the limit is 10 MB", and so on —
 * the same as Swift's `ErrorPresenter.message(for:)`. Anything the core did
 * *not* throw as its own [FfiException] (a coroutine-machinery failure, say)
 * falls back to a fixed apology instead, since there is no per-case wording
 * for a failure the core never described.
 */
class StagedAttachment(private val client: CoreClient) {
    private val _file = MutableStateFlow<StagedFile?>(null)
    val file: StateFlow<StagedFile?> = _file.asStateFlow()

    /**
     * Hand the core a path and keep the token it returns.
     *
     * The core does the rest — sniffing the mime from the file's *content*
     * rather than trusting its extension, reading dimensions from the header,
     * bounding the size. Returns a message when it refuses, or `null`.
     */
    suspend fun stage(path: String, roomId: String): String? {
        // Replacing rather than queueing: discard whatever was staged
        // first, so a token cannot be orphaned in the core.
        discard()
        return try {
            _file.value = client.attachmentStagePath(roomId = roomId, path = path)
            null
        } catch (error: FfiException) {
            ErrorPresenter.message(error)
        } catch (error: Throwable) {
            "Couldn't attach that file."
        }
    }

    /** Send it, consuming the token. Returns a message on refusal. */
    suspend fun send(roomId: String): String? {
        val staged = _file.value ?: return null
        return try {
            client.attachmentSend(roomId = roomId, token = staged.token)
            _file.value = null
            null
        } catch (error: FfiException) {
            ErrorPresenter.message(error)
        } catch (error: Throwable) {
            "Couldn't send that file."
        }
    }

    suspend fun discard() {
        val staged = _file.value ?: return
        client.attachmentDiscard(token = staged.token)
        _file.value = null
    }
}
