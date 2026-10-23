package dev.supermessage

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * `CoreClient`'s `resumeOn` for a test session whose core answers on [dispatcher].
 *
 * Over a real thread pool, a Compose test's effects would otherwise resume on the worker that
 * answered and draw a frame there, where the first `LazyColumn` finds no `Choreographer` ("The
 * current thread must have a looper!", RosterReachabilityTest on CI, 2026-10-23). Back on the main
 * thread they run where the app's do. An unconfined core answers on the caller's own thread, so it
 * needs nothing.
 */
internal fun resumeOnMainFor(dispatcher: CoroutineDispatcher): CoroutineDispatcher? =
    if (dispatcher == Dispatchers.Unconfined) null else Dispatchers.Main.immediate
