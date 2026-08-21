package dev.supermessage.kit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported from `apple/SupermessageKitTests/GapSyncTests.swift`. Each of the
 * three hazards below cost a real incident on the desktop app; the comments
 * say which.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GapSyncTest {

    /**
     * A resync whose completion the test controls, so the in-flight window
     * can be held open and driven deliberately.
     *
     * Swift's `Gate` needed `@MainActor` isolation because `CheckedContinuation`
     * plus a plain `var` is only safe if nothing else can touch it concurrently.
     * Here every call — the test body and the coroutine `GapSync` launches on
     * the scope passed to it — runs on the single thread `runTest`'s
     * `TestDispatcher` drives, so the same mutual exclusion holds without an
     * actor.
     */
    private class Gate {
        private var pending: CompletableDeferred<Snapshot<Int>>? = null
        var callCount = 0
            private set

        suspend fun resync(): Snapshot<Int> {
            callCount += 1
            val deferred = CompletableDeferred<Snapshot<Int>>()
            pending = deferred
            return deferred.await()
        }

        fun resolve(subject: String = "s", seq: ULong, items: List<Int>) {
            val deferred = pending
            pending = null
            deferred?.complete(Snapshot(subject = subject, seq = seq, items = items))
        }
    }

    /** "applies sequential envelopes and publishes the running list" */
    @Test
    fun appliesSequentialEnvelopesAndPublishesTheRunningList() = runTest {
        val published = mutableListOf<List<Int>>()
        val sync =
            GapSync(
                scope = this,
                resync = { Snapshot(subject = "s", seq = 0uL, items = emptyList()) },
                onUpdate = { published.add(it) },
            )

        sync.handle(subject = "s", seq = 1uL, ops = listOf(DiffOp.Append(listOf(1))))
        sync.handle(subject = "s", seq = 2uL, ops = listOf(DiffOp.Append(listOf(2))))

        assertEquals(listOf(listOf(1), listOf(1, 2)), published)
    }

    /** "suspends applying envelopes while a resync is in flight, then resumes from the snapshot" */
    @Test
    fun suspendsWhileResyncingThenResumesFromTheSnapshot() = runTest {
        // Hazard 1. While the round trip is in flight the core keeps emitting;
        // applying those against the pre-reset tracker would rediscover the
        // same gap and ask again, forever.
        val gate = Gate()
        val published = mutableListOf<List<Int>>()
        val sync = GapSync(scope = this, resync = { gate.resync() }, onUpdate = { published.add(it) })

        sync.handle(subject = "s", seq = 1uL, ops = listOf(DiffOp.Append(listOf(1))))
        sync.handle(subject = "s", seq = 5uL, ops = listOf(DiffOp.Append(listOf(5)))) // gap -> resync
        testScheduler.runCurrent() // let the launched resync reach its suspension point
        sync.handle(subject = "s", seq = 6uL, ops = listOf(DiffOp.Append(listOf(6)))) // ignored, in flight

        assertEquals("an envelope was applied during the resync", listOf(listOf(1)), published)

        gate.resolve(seq = 6uL, items = listOf(1, 2, 3))
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(1, 2, 3), published.last())
        // The next live envelope is seq + 1 and must resume normally.
        sync.handle(subject = "s", seq = 7uL, ops = listOf(DiffOp.Append(listOf(7))))
        assertEquals(listOf(1, 2, 3, 7), published.last())
    }

    /** "never issues two overlapping resyncs for the same channel" */
    @Test
    fun neverIssuesTwoOverlappingResyncsForTheSameChannel() = runTest {
        val gate = Gate()
        // backgroundScope, not `this`: this test deliberately never resolves
        // the gate, so the first resync is still suspended when the test
        // ends. A child of `this` would fail the test for staying
        // incomplete; a child of `backgroundScope` is simply cancelled.
        val sync = GapSync(scope = backgroundScope, resync = { gate.resync() }, onUpdate = { _ -> })

        sync.handle(subject = "s", seq = 5uL, ops = listOf(DiffOp.Append(listOf(5))))
        testScheduler.runCurrent()
        sync.handle(subject = "s", seq = 9uL, ops = listOf(DiffOp.Append(listOf(9))))
        testScheduler.runCurrent()

        assertEquals(1, gate.callCount)
    }

    /** "a reset publishes an empty list and discards a resync already in flight" */
    @Test
    fun aResetPublishesAnEmptyListAndDiscardsAResyncAlreadyInFlight() = runTest {
        // Hazard 3. A slow resync landing after the context changed would roll
        // the new room's state back to the old room's data.
        val gate = Gate()
        val published = mutableListOf<List<Int>>()
        val sync = GapSync(scope = this, resync = { gate.resync() }, onUpdate = { published.add(it) })

        sync.handle(subject = "s", seq = 5uL, ops = listOf(DiffOp.Append(listOf(5)))) // gap -> resync
        testScheduler.runCurrent()

        sync.resetForNewSubscription()
        assertEquals("a reset must publish an empty list immediately", emptyList<Int>(), published.last())

        gate.resolve(seq = 9uL, items = listOf(7, 8, 9))
        testScheduler.advanceUntilIdle()

        assertEquals("a stale resync landed and clobbered the new context", emptyList<Int>(), published.last())
    }

    /** "a fresh gap in the new context still triggers its own resync" */
    @Test
    fun aFreshGapInTheNewContextStillTriggersItsOwnResync() = runTest {
        val gate = Gate()
        val published = mutableListOf<List<Int>>()
        val sync = GapSync(scope = this, resync = { gate.resync() }, onUpdate = { published.add(it) })

        sync.handle(subject = "s", seq = 5uL, ops = listOf(DiffOp.Append(listOf(5))))
        testScheduler.runCurrent()
        sync.resetForNewSubscription()
        gate.resolve(seq = 9uL, items = listOf(7))
        testScheduler.advanceUntilIdle()

        // The stale one has cleared; the new context must be able to recover.
        sync.handle(subject = "s", seq = 4uL, ops = listOf(DiffOp.Append(listOf(4))))
        testScheduler.runCurrent()
        gate.resolve(seq = 4uL, items = listOf(1, 2))
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(1, 2), published.last())
    }

    /** "drops an envelope for another subject instead of treating it as a gap" */
    @Test
    fun dropsAnEnvelopeForAnotherSubjectInsteadOfTreatingItAsAGap() = runTest {
        // Hazard 2, and the one that cost the worst incident: treating this as
        // a gap resyncs off the *previous* room's still-installed handle and
        // installs that room's messages under the new room's header, where
        // they stay until the next room switch.
        val gate = Gate()
        val published = mutableListOf<List<Int>>()
        val sync =
            GapSync(
                scope = this,
                resync = { gate.resync() },
                accepts = { it == "!b:x.org" },
                onUpdate = { published.add(it) },
            )

        sync.handle(subject = "!a:x.org", seq = 12uL, ops = listOf(DiffOp.Append(listOf(99))))
        testScheduler.runCurrent()

        assertEquals("somebody else's envelope triggered a resync", 0, gate.callCount)
        assertTrue("somebody else's data was published", published.isEmpty())
    }

    /** "discards a resync snapshot belonging to another subject" */
    @Test
    fun discardsAResyncSnapshotBelongingToAnotherSubject() = runTest {
        // The core serves a resync out of whichever subscription is currently
        // installed, which during a room switch is still the previous room's.
        // Its generation can match ours, so the subject is the only thing that
        // can tell us this snapshot is not ours.
        val gate = Gate()
        val published = mutableListOf<List<Int>>()
        val sync =
            GapSync(
                scope = this,
                resync = { gate.resync() },
                accepts = { it == "!b:x.org" },
                onUpdate = { published.add(it) },
            )

        sync.handle(subject = "!b:x.org", seq = 5uL, ops = listOf(DiffOp.Append(listOf(5)))) // gap -> resync
        testScheduler.runCurrent()
        gate.resolve(subject = "!a:x.org", seq = 12uL, items = listOf(98, 99))
        testScheduler.advanceUntilIdle()

        assertTrue("another room's snapshot was installed", published.isEmpty())
    }

    /** "seeding fetches a snapshot without waiting for a gap" */
    @Test
    fun seedingFetchesASnapshotWithoutWaitingForAGap() = runTest {
        // On iOS this is not an edge case: it is every return from background.
        // The channel only speaks when something changes, so a store that came
        // back to a quiet account would sit empty until the next message.
        val gate = Gate()
        val published = mutableListOf<List<Int>>()
        val sync = GapSync(scope = this, resync = { gate.resync() }, onUpdate = { published.add(it) })

        launch { sync.seed() }
        testScheduler.runCurrent()
        gate.resolve(seq = 3uL, items = listOf(1, 2, 3))
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(1, 2, 3), published.last())
    }

    /** "a stopped sync applies nothing further" */
    @Test
    fun aStoppedSyncAppliesNothingFurther() = runTest {
        val published = mutableListOf<List<Int>>()
        val sync =
            GapSync(
                scope = this,
                resync = { Snapshot(subject = "s", seq = 0uL, items = emptyList()) },
                onUpdate = { published.add(it) },
            )

        sync.stop()
        sync.handle(subject = "s", seq = 1uL, ops = listOf(DiffOp.Append(listOf(1))))

        assertTrue(published.isEmpty())
    }

    /**
     * "resume undoes stop, so handle applies envelopes again" — not from
     * Swift, which has no [GapSync.resume] to test at all. Direct coverage
     * for the half of `RoomsStore.resume`'s recovery this class actually
     * owns: without it, the *first* `stop()` in a process's life — every
     * `signOut` — would leave `handle`'s own `if (stopped) return` guard
     * permanently tripped, and a later sign-in's roster or timeline would
     * stay empty forever, silently.
     */
    @Test
    fun resumeAfterStopLetsHandleApplyEnvelopesAgain() = runTest {
        val published = mutableListOf<List<Int>>()
        val sync =
            GapSync(
                scope = this,
                resync = { Snapshot(subject = "s", seq = 0uL, items = emptyList()) },
                onUpdate = { published.add(it) },
            )

        sync.stop()
        sync.handle(subject = "s", seq = 1uL, ops = listOf(DiffOp.Append(listOf(1))))
        assertTrue("stop should still be in effect", published.isEmpty())

        sync.resume()
        // The tracker's own sequence was never advanced by the ignored
        // envelope above — `handle`'s `stopped` guard returns before
        // `tracker.apply` ever runs — so the next live envelope is still
        // seq 1, not seq 2.
        sync.handle(subject = "s", seq = 1uL, ops = listOf(DiffOp.Append(listOf(1))))

        assertEquals("resume must let handle apply again", listOf(listOf(1)), published)
    }

    /**
     * "a resync already in flight when stop/resume run is still discarded
     * on landing" — the generation bump [GapSync.resume]'s own KDoc
     * documents and calls out as easy to get wrong: a resync launched
     * before [GapSync.stop] can still be genuinely in flight when
     * [GapSync.resume] runs, and simply clearing `stopped` would let that
     * stale resync land later with its *original* captured generation still
     * matching the current one, rolling a fresh session back to a torn-down
     * one's snapshot.
     */
    @Test
    fun aResyncInFlightAcrossStopAndResumeIsStillDiscarded() = runTest {
        val gate = Gate()
        val published = mutableListOf<List<Int>>()
        val sync = GapSync(scope = this, resync = { gate.resync() }, onUpdate = { published.add(it) })

        sync.handle(subject = "s", seq = 5uL, ops = listOf(DiffOp.Append(listOf(5)))) // gap -> resync
        testScheduler.runCurrent()

        // The teardown/recovery `Session` drives on a sign-out/sign-in that
        // races a still-in-flight resync: `stop()` on the old session,
        // `resume()` beginning the new one, all before the old resync's
        // `resync()` call has returned.
        sync.stop()
        sync.resume()

        gate.resolve(seq = 9uL, items = listOf(7, 8, 9))
        testScheduler.advanceUntilIdle()

        assertTrue(
            "a resync started before stop/resume must not land in the new generation",
            published.isEmpty(),
        )

        // The new generation must still be able to recover on its own.
        sync.handle(subject = "s", seq = 4uL, ops = listOf(DiffOp.Append(listOf(4))))
        testScheduler.runCurrent()
        gate.resolve(seq = 4uL, items = listOf(1, 2))
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(1, 2), published.last())
    }
}
