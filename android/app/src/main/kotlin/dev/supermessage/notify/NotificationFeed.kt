package dev.supermessage.notify

import androidx.core.app.NotificationManagerCompat
import android.content.Context
import dev.supermessage.AppCore
import dev.supermessage.kit.Session
import dev.supermessage.kit.notify.NotificationCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/** How often the roster and the open timeline are looked at: not per delta, not per room. */
internal const val FEED_THROTTLE_MS = 250L

/**
 * Feeds the [NotificationCoordinator] from a [Session]: the roster, the
 * subscribed timeline, the room on screen and the phase
 * (`PlatformCoordinator.swift:214-250`). It runs on a scope that outlives the
 * Activity, so a change after the Activity stops still posts, and stops with
 * the ViewModel that owns the session.
 *
 * Only the selected space's roster and the one subscribed timeline are ever
 * looked at, and at most once per [FEED_THROTTLE_MS].
 *
 * **The account is known before anything posts.** A note carries the account
 * it was made for, and its buttons refuse without one; so a change is not
 * handed to the coordinator until the poster has the account. A change
 * skipped for that is not lost: the roster's previous snapshot and the
 * timeline's notified set are untouched, so the next look sees it.
 *
 * **Sign-out is handled here, not by an Activity** (RF5). The session's own
 * hook runs on every `SIGNED_IN → SIGNED_OUT`, whether or not an Activity saw
 * it (one recreated by a configuration change does not). It forgets the
 * marks, unregisters from the distributor and clears the push owner
 * ([PushRegistrar.signedOut]), cancels every queued push
 * ([cancelQueuedPushes]), and withdraws what is shown.
 */
@OptIn(FlowPreview::class)
internal class NotificationFeed(
    private val session: Session,
    private val coordinator: NotificationCoordinator,
    private val poster: AndroidPoster,
    private val context: Context,
    private val scope: CoroutineScope,
    /** Push's part of a sign-out; a seam, so tests reach no distributor or scheduler. */
    private val pushSignedOut: () -> Unit = {
        AppCore.registrar(context).signedOut()
        cancelQueuedPushes(context)
    },
) {
    private val jobs = mutableListOf<Job>()
    private var removeHook: (() -> Unit)? = null

    fun start() {
        // Sign-out, wherever it comes from: forget the marks, withdraw what is shown.
        removeHook = session.onSignedOut {
            coordinator.signedOut()
            poster.accountId = null
            // Before the withdrawal, so no push queued or under way posts after it.
            pushSignedOut()
            NotificationManagerCompat.from(context).cancelAll()
        }
        // The sign-out hook above clears the account, synchronously with the phase.
        jobs += session.phase.onEach { phase ->
            if (phase == Session.Phase.SIGNED_IN) accountKnown()
        }.launchIn(scope)
        jobs += session.rooms.selectedId.onEach(coordinator::openRoomChanged).launchIn(scope)
        jobs += session.rooms.rooms
            .sample(FEED_THROTTLE_MS)
            .onEach { rows ->
                if (session.phase.value == Session.Phase.SIGNED_IN && accountKnown()) coordinator.roomsChanged(rows)
            }
            .launchIn(scope)
        jobs += combine(session.timeline.roomId, session.timeline.items) { id, items -> id to items }
            .sample(FEED_THROTTLE_MS)
            .onEach { (id, items) ->
                if (session.phase.value != Session.Phase.SIGNED_IN || !accountKnown()) return@onEach
                val name = id?.let { session.rooms.row(it)?.identity?.name } ?: session.rooms.selectedName ?: ""
                coordinator.timelineChanged(id, name, items)
            }
            .launchIn(scope)
    }

    /** Asks for the account when the poster has none yet; true once it has one. */
    private suspend fun accountKnown(): Boolean {
        if (poster.accountId == null) poster.accountId = session.account()?.userId
        return poster.accountId != null
    }

    fun stop() {
        removeHook?.invoke()
        jobs.forEach { it.cancel() }
        jobs.clear()
    }
}
