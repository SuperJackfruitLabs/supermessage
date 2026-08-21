package dev.supermessage.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Ported from `apple/SupermessageKitTests/DiffApplyTests.swift`, case for
 * case — itself ported from `src/lib/stores/diff.test.ts`.
 *
 * The spec calls this file "the most valuable tests in the Kit."
 */
class DiffApplyTest {

    // --- ApplyOpsTests ------------------------------------------------------

    /** "appends" */
    @Test
    fun appends() {
        assertEquals(listOf(1, 2, 3), applyOps(listOf(1), listOf(DiffOp.Append(listOf(2, 3)))))
    }

    /** "clears" */
    @Test
    fun clears() {
        assertEquals(emptyList<Int>(), applyOps(listOf(1, 2), listOf(DiffOp.Clear)))
    }

    /** "pushes front" */
    @Test
    fun pushesFront() {
        assertEquals(listOf(1, 2), applyOps(listOf(2), listOf(DiffOp.PushFront(1))))
    }

    /** "pushes back" */
    @Test
    fun pushesBack() {
        assertEquals(listOf(1, 2), applyOps(listOf(1), listOf(DiffOp.PushBack(2))))
    }

    /** "pops front" */
    @Test
    fun popsFront() {
        assertEquals(listOf(2), applyOps(listOf(1, 2), listOf(DiffOp.PopFront)))
    }

    /** "pops back" */
    @Test
    fun popsBack() {
        assertEquals(listOf(1), applyOps(listOf(1, 2), listOf(DiffOp.PopBack)))
    }

    /** "inserts" */
    @Test
    fun inserts() {
        assertEquals(listOf(1, 2, 3), applyOps(listOf(1, 3), listOf(DiffOp.Insert(1, 2))))
    }

    /** "sets" */
    @Test
    fun sets() {
        assertEquals(listOf(1, 2), applyOps(listOf(1, 9), listOf(DiffOp.Set(1, 2))))
    }

    /** "removes" */
    @Test
    fun removes() {
        assertEquals(listOf(1, 3), applyOps(listOf(1, 2, 3), listOf(DiffOp.Remove(1))))
    }

    /** "truncates" */
    @Test
    fun truncates() {
        assertEquals(listOf(1, 2), applyOps(listOf(1, 2, 3), listOf(DiffOp.Truncate(2))))
    }

    /** "resets" */
    @Test
    fun resets() {
        assertEquals(listOf(9), applyOps(listOf(1, 2), listOf(DiffOp.Reset(listOf(9)))))
    }

    /** "applies a batch in order" */
    @Test
    fun appliesABatchInOrder() {
        // Order is the whole contract: the ops address positions in the list
        // as it stands *after* the previous op, not as it arrived.
        val result = applyOps(listOf(1), listOf(DiffOp.PushBack(2), DiffOp.Insert(0, 0), DiffOp.PopBack))
        assertEquals(listOf(0, 1), result)
    }

    /** "does not mutate its input" */
    @Test
    fun doesNotMutateInput() {
        val original = listOf(1, 2, 3)
        applyOps(original, listOf(DiffOp.Clear, DiffOp.Append(listOf(9))))
        assertEquals(listOf(1, 2, 3), original)
    }

    // --- ApplyOpsOutOfRangeTests ---------------------------------------------
    //
    // The arms that matter most, and matter here for a related but not
    // identical reason to Swift: Swift's `items[i]` traps on an out-of-range
    // index, where Kotlin's `MutableList.get`/`removeAt`/`set` throw an
    // `IndexOutOfBoundsException` instead of trapping — but a thrown
    // exception from inside `applyOps` is just as much a crash the caller
    // did not ask for, so every guard below is load-bearing here too.

    /** "ignores set and remove with an out-of-bounds index rather than trapping" */
    @Test
    fun outOfBoundsSetAndRemove() {
        assertEquals(listOf(1, 2, 3), applyOps(listOf(1, 2, 3), listOf(DiffOp.Set(99, 7))))
        assertEquals(listOf(1, 2, 3), applyOps(listOf(1, 2, 3), listOf(DiffOp.Remove(99))))
        assertEquals(listOf(1, 2, 3), applyOps(listOf(1, 2, 3), listOf(DiffOp.Set(-1, 7))))
        assertEquals(listOf(1, 2, 3), applyOps(listOf(1, 2, 3), listOf(DiffOp.Remove(-1))))
    }

    /** "ignores an out-of-range insert, but permits index == count as an append" */
    @Test
    fun insertBoundary() {
        // The boundary is the point: one past the end is an append and must
        // work; two past is a no-op and must not throw.
        assertEquals(listOf(1, 2, 3), applyOps(listOf(1, 2), listOf(DiffOp.Insert(2, 3))))
        assertEquals(listOf(1, 2), applyOps(listOf(1, 2), listOf(DiffOp.Insert(3, 3))))
        assertEquals(listOf(1, 2), applyOps(listOf(1, 2), listOf(DiffOp.Insert(-1, 3))))
    }

    /** "ignores a pop on an empty list rather than trapping" */
    @Test
    fun popOnEmpty() {
        assertEquals(emptyList<Int>(), applyOps(emptyList(), listOf(DiffOp.PopFront)))
        assertEquals(emptyList<Int>(), applyOps(emptyList(), listOf(DiffOp.PopBack)))
    }

    /** "a truncate longer than the list leaves it alone" */
    @Test
    fun truncateBeyondEnd() {
        assertEquals(listOf(1, 2), applyOps(listOf(1, 2), listOf(DiffOp.Truncate(9))))
        assertEquals(emptyList<Int>(), applyOps(listOf(1, 2), listOf(DiffOp.Truncate(0))))
    }

    // --- DiffTrackerTests -----------------------------------------------------

    /** "accepts sequential envelopes" */
    @Test
    fun acceptsSequential() {
        val tracker = DiffTracker<Int>()
        assertEquals(DiffOutcome.OK, tracker.apply(listOf(DiffOp.Append(listOf(1))), 1uL))
        assertEquals(DiffOutcome.OK, tracker.apply(listOf(DiffOp.Append(listOf(2))), 2uL))
        assertEquals(listOf(1, 2), tracker.items)
    }

    /** "reports a gap and leaves state untouched when an envelope is missed" */
    @Test
    fun reportsGap() {
        // Untouched is the assertion. The ops address positions in a list the
        // sender believes the caller has, and after a gap the caller does not
        // have it — folding them in anyway is exactly the corruption this
        // prevents.
        val tracker = DiffTracker<Int>()
        assertEquals(DiffOutcome.OK, tracker.apply(listOf(DiffOp.Append(listOf(1))), 1uL))
        assertEquals(DiffOutcome.GAP, tracker.apply(listOf(DiffOp.Append(listOf(3))), 3uL))
        assertEquals(listOf(1), tracker.items)
    }

    /** "recovers after a resync" */
    @Test
    fun recoversAfterResync() {
        val tracker = DiffTracker<Int>()
        tracker.apply(listOf(DiffOp.Append(listOf(1))), 1uL)
        tracker.apply(listOf(DiffOp.Append(listOf(3))), 3uL)

        tracker.reset(listOf(1, 2, 3), 3uL)
        assertEquals(listOf(1, 2, 3), tracker.items)
        // The next live envelope is seq + 1, and must be accepted.
        assertEquals(DiffOutcome.OK, tracker.apply(listOf(DiffOp.Append(listOf(4))), 4uL))
        assertEquals(listOf(1, 2, 3, 4), tracker.items)
    }

    /** "ignores a duplicate envelope rather than applying it twice" */
    @Test
    fun ignoresDuplicate() {
        // The core can re-send after a reconnect. Applying one twice would
        // double an insert, which reads as a duplicated message.
        val tracker = DiffTracker<Int>()
        tracker.apply(listOf(DiffOp.Append(listOf(1))), 1uL)
        assertEquals(DiffOutcome.OK, tracker.apply(listOf(DiffOp.Append(listOf(1))), 1uL))
        assertEquals(listOf(1), tracker.items)
    }

    /** "sequences start at one" */
    @Test
    fun sequencesStartAtOne() {
        // `dto::SeqCounter` starts at 1, so a tracker that expected 0 would
        // read the very first envelope as a gap and resync on every startup.
        val tracker = DiffTracker<Int>()
        assertEquals(DiffOutcome.OK, tracker.apply(listOf(DiffOp.Append(listOf(1))), 1uL))
        assertEquals(listOf(1), tracker.items)
    }

    /**
     * "a confirmed message keeps its place and its identity"
     *
     * **This pins only `applyOps`' half of the rule.** Asserting that
     * applying `Set(1, …)` leaves the list's shape and order unchanged holds
     * for *any* `Set` that writes index 1 — including one that, at a layer
     * above this one, arrived by removing the row and reinserting it under a
     * new key. The rule's real enforcement is the core's projection (which
     * must actually emit a `set` at a stable index rather than a
     * remove-then-insert) and `:app`'s row `key` (which must actually be
     * that stable identity, not the event id). Neither is this test's to
     * prove; it only proves `applyOps` itself does not introduce movement or
     * renaming that was not already in the ops it was given.
     */
    @Test
    fun aConfirmationDoesNotMoveOrRenameTheRow() {
        // The rule the timeline spec assigns to this layer: when the server
        // confirms a message this account sent, the row **does not move,
        // flicker, or change identity**. The core projects that confirmation
        // as a single `set` at the same index carrying the same
        // `TimelineItemDto.id` — identity, not the event id — and applying it
        // must leave the id sequence untouched.
        //
        // Asserted here rather than in a UI test because the window between
        // the local echo and the confirmation is shorter than an
        // instrumentation test can reliably sample: a UI test that looked
        // passed whether the rule held or not, which is worse than no test.
        val before = listOf("unique-1", "unique-2", "unique-3")
        val after = applyOps(before, listOf(DiffOp.Set(1, "unique-2")))

        assertEquals("the confirmation changed the row order or an id", before, after)
        assertEquals("the confirmation added or removed a row", before.size, after.size)
    }

    /**
     * "a confirmation that renames a row is a visible flicker"
     *
     * **Also only half the rule, for the same reason as the test above.**
     * This shows `applyOps` faithfully surfaces a `Set` that changes the id
     * at an index — which is necessary for the rule above to mean anything,
     * but is not itself proof that the core never emits such a `Set` for a
     * real confirmation, or that `:app`'s row `key` would actually flicker
     * if it did. Both of those live outside `applyOps` and outside what this
     * file can assert on.
     */
    @Test
    fun renamingOnConfirmationIsCaught() {
        // The failure mode, stated: keyed by the event's *address* rather
        // than its identity, a message leaves and rejoins the list at the
        // moment it is confirmed. This is what the assertion above is
        // protecting, written out so it cannot be mistaken for a tautology.
        val before = listOf("unique-1", "unique-2", "unique-3")
        val renamed = applyOps(before, listOf(DiffOp.Set(1, "\$event-2:example.org")))

        assertNotEquals("this test asserts nothing if a rename is invisible", before, renamed)
    }
}
