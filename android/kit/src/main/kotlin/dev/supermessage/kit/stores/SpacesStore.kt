package dev.supermessage.kit.stores

import dev.supermessage.kit.CoreClient
import dev.supermessage.kit.ErrorPresenter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.supermessage_core.Membership
import uniffi.supermessage_core.SpaceSummary
import uniffi.supermessage_ffi.FfiException

/**
 * The spaces the account belongs to, and which one filters the roster.
 *
 * Not diff-driven — `spaces_list` is a plain call, refreshed when the
 * roster changes shape. A space is named by the same convention a room is,
 * so it arrives with its `identity` already split.
 *
 * Ported from `apple/SupermessageKit/Stores/SpacesStore.swift`, which has no
 * Swift test of its own. Swift's `@MainActor @Observable` becomes [spaces],
 * [selectedId] and [failure] exposed as [StateFlow]s, matching the rest of
 * `stores/`.
 *
 * **Deliberate difference from Swift:** every `catch` below re-throws
 * [CancellationException] before falling into the broad `catch (Exception)`
 * arm. Swift's untyped `catch {}` and Kotlin's `catch (e: Exception)` are
 * not the same width — `CancellationException` is itself an `Exception` on
 * the JVM, so a Kotlin catch this broad would otherwise swallow a coroutine
 * cancellation and report it as an ordinary failure, which breaks
 * structured concurrency for whatever cancelled this scope. `GapSync`
 * documents the same hazard for the same reason.
 */
class SpacesStore(private val client: CoreClient) {
    private val _spaces = MutableStateFlow<List<SpaceSummary>>(emptyList())
    val spaces: StateFlow<List<SpaceSummary>> = _spaces.asStateFlow()

    /** `null` is "All rooms", which is a real choice rather than an absent one. */
    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    private val _failure = MutableStateFlow<String?>(null)
    val failure: StateFlow<String?> = _failure.asStateFlow()

    suspend fun refresh() {
        try {
            _spaces.value = client.spacesList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: FfiException) {
            // A rail that cannot load is not worth an alert: the roster
            // still works unfiltered, which is the state it was already in.
            _failure.value = if (ErrorPresenter.isWorthSurfacing(e)) ErrorPresenter.message(e) else null
        } catch (e: Exception) {
            _failure.value = null
        }
    }

    /** Filter the roster to [spaceId], or clear the filter with `null`. */
    suspend fun select(spaceId: String?) {
        try {
            client.spaceSelect(spaceId)
            _selectedId.value = spaceId
        } catch (e: CancellationException) {
            throw e
        } catch (e: FfiException) {
            _failure.value = ErrorPresenter.message(e)
        } catch (e: Exception) {
            _failure.value = "Couldn't switch space."
        }
    }

    /**
     * An invitation is not a filter: tapping one has to offer Accept rather
     * than pretending to scope a roster the account cannot see into.
     */
    fun isInvitation(space: SpaceSummary): Boolean = space.membership == Membership.INVITED

    val selectedName: String?
        get() {
            val id = selectedId.value ?: return null
            return spaces.value.firstOrNull { it.id == id }?.identity?.name
        }

    fun clear() {
        _spaces.value = emptyList()
        _selectedId.value = null
        _failure.value = null
    }
}
