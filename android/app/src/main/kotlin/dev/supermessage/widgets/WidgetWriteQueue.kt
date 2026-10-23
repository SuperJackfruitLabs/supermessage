package dev.supermessage.widgets

import android.util.Log
import dev.supermessage.kit.widgets.WidgetWriting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * The widgets' snapshot writes, in the order they were asked, one at a time.
 *
 * **Process-wide, and it holds no Activity.** It owns the one consumer, on the
 * process's [scope]; the Activity's coordinator used to own it, and a consumer
 * that is never cancelled kept every destroyed Activity alive through the
 * lambdas the coordinator held. Everything here is the writer and a callback
 * that the process owns (`AppCore`).
 *
 * Each write is a core merge made by [WidgetWriting] (file IO and FFI, so [scope]
 * should be an IO scope) and returns the core's `reload`, which goes to [written].
 */
internal class WidgetWriteQueue(
    private val writer: WidgetWriting,
    private val written: suspend (reload: Boolean) -> Unit,
    scope: CoroutineScope,
) {
    private val writes = Channel<suspend (WidgetWriting) -> Boolean>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (write in writes) {
                try {
                    written(write(writer))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A failed write is the next one's to repair: every merge starts from
                    // what is stored. It is not silent, though: a disk that stays full shows here.
                    Log.w(TAG, "a widget snapshot write failed", e)
                }
            }
        }
    }

    fun submit(write: suspend (WidgetWriting) -> Boolean) {
        writes.trySend(write)
    }

    /** Returns once every write asked so far has been made. */
    suspend fun done() {
        val done = CompletableDeferred<Unit>()
        writes.send {
            done.complete(Unit)
            false
        }
        done.await()
    }

    private companion object {
        const val TAG = "WidgetWriteQueue"
    }
}
