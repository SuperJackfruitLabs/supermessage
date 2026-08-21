package dev.supermessage.kit.stores

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.supermessage_ffi.ConnectionState

/**
 * Whether the core is talking to the homeserver.
 *
 * The vocabulary is the core's — `"live"`, `"connecting"`, `"offline"` — and
 * it is deliberately not re-modelled into anything richer here. A string the
 * core owns can gain a value without this app failing to build, and the
 * [Connection.Unknown] case below is what that costs: one branch instead of
 * a crash.
 *
 * Ported from `apple/SupermessageKit/Stores/ConnectionStore.swift`. Swift's
 * `@MainActor @Observable` becomes [state] and [message] exposed as
 * [StateFlow]s, matching the rest of `stores/` (see [DraftStore]'s doc
 * comment for why `@MainActor` becomes a documented, not checked, invariant
 * here rather than a compiler-enforced one).
 */
class ConnectionStore {
    sealed class Connection {
        data object Live : Connection()
        data object Connecting : Connection()
        data object Offline : Connection()

        /**
         * Sync failed. The core carries the reason in [ConnectionStore.message],
         * and it now retries on a backoff rather than staying broken — this
         * state is what the reader sees while that is happening.
         */
        data object Error : Connection()

        /**
         * Something the core started saying that this build has not been
         * taught. Rendered as connecting, because that is the honest reading
         * of "we do not know yet".
         */
        data class Unknown(val raw: String) : Connection()
    }

    private val _state = MutableStateFlow<Connection>(Connection.Connecting)
    val state: StateFlow<Connection> = _state.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun apply(raw: ConnectionState) {
        _state.value = when (raw.state) {
            "live" -> Connection.Live
            "connecting" -> Connection.Connecting
            "offline" -> Connection.Offline
            // The core emits this on a sync failure and it was missing here,
            // so it fell through to `Unknown("error")` and the bar showed the
            // bare word "error" with no explanation beside it.
            "error" -> Connection.Error
            else -> Connection.Unknown(raw.state)
        }
        _message.value = raw.message
    }

    /**
     * Whether the bar should be on screen at all.
     *
     * Live is the common case and says nothing worth a row of chrome. It is
     * never amber — amber means the operator owes someone an answer, and a
     * flaky connection is not that.
     */
    val isWorthShowing: Boolean
        get() = state.value != Connection.Live
}
