package dev.supermessage.kit

import uniffi.supermessage_core.RoomRow
import uniffi.supermessage_core.TimelineRow
import uniffi.supermessage_ffi.RoomDiffOp
import uniffi.supermessage_ffi.TimelineDiffOp

/**
 * One change to a list, as the core describes it.
 *
 * UniFFI has no generics, so the boundary carries two monomorphised mirrors —
 * [RoomDiffOp] and [TimelineDiffOp], eleven cases each. This is the generic
 * they were flattened from, restored at the edge so [applyOps] and
 * [DiffTracker] are written once rather than twice.
 *
 * Declared `out T`: the cases that carry no item — [Clear], [PopFront],
 * [PopBack], [Remove] and [Truncate] — never reference `T` at all, so they
 * are written as singletons/plain data classes extending `DiffOp<Nothing>`
 * rather than being re-instantiated per `T`, the way Swift's `case clear`
 * needs no such distinction because Swift generics carry no variance. This
 * is a deliberate difference in shape, not in behaviour.
 */
sealed class DiffOp<out T> {
    data class Append<T>(val values: List<T>) : DiffOp<T>()
    data object Clear : DiffOp<Nothing>()
    data class PushFront<T>(val value: T) : DiffOp<T>()
    data class PushBack<T>(val value: T) : DiffOp<T>()
    data object PopFront : DiffOp<Nothing>()
    data object PopBack : DiffOp<Nothing>()
    data class Insert<T>(val index: Int, val value: T) : DiffOp<T>()
    data class Set<T>(val index: Int, val value: T) : DiffOp<T>()
    data class Remove(val index: Int) : DiffOp<Nothing>()
    data class Truncate(val length: Int) : DiffOp<Nothing>()
    data class Reset<T>(val values: List<T>) : DiffOp<T>()
}

/**
 * Every item an op carries, in order — empty for the ops that only move or
 * drop items.
 *
 * Mirrors `core::dto::op_values`, and exists for the same reason: a caller
 * that needs to look at what a batch *contains* should not have to write a
 * second exhaustive match over [DiffOp] and risk disagreeing with [applyOps]
 * about what an op means.
 */
fun <T> opValues(op: DiffOp<T>): List<T> = when (op) {
    is DiffOp.Append -> op.values
    is DiffOp.Clear -> emptyList()
    is DiffOp.PushFront -> listOf(op.value)
    is DiffOp.PushBack -> listOf(op.value)
    is DiffOp.PopFront -> emptyList()
    is DiffOp.PopBack -> emptyList()
    is DiffOp.Insert -> listOf(op.value)
    is DiffOp.Set -> listOf(op.value)
    is DiffOp.Remove -> emptyList()
    is DiffOp.Truncate -> emptyList()
    is DiffOp.Reset -> op.values
}

/**
 * Apply a batch of ops, returning a new list. Never mutates its input.
 *
 * **This must agree with `core::dto::apply_ops` operation for operation**,
 * including the out-of-range handling below. The core maintains its own
 * resync snapshot by folding the same op stream through that function, so a
 * divergence here does not show up as a crash — it shows up as a resync that
 * silently installs different state than the core believes it sent.
 *
 * The out-of-range rules matter here for the same reason they matter in
 * Swift: a caller must not have to trust that the server never sends a
 * stale index, so every guard below is load-bearing rather than tidy.
 */
fun <T> applyOps(items: List<T>, ops: List<DiffOp<T>>): List<T> {
    var result = items

    for (op in ops) {
        result = when (op) {
            is DiffOp.Append -> result + op.values
            is DiffOp.Clear -> emptyList()
            is DiffOp.PushFront -> listOf(op.value) + result
            is DiffOp.PushBack -> result + op.value
            is DiffOp.PopFront -> if (result.isEmpty()) result else result.subList(1, result.size).toList()
            is DiffOp.PopBack ->
                if (result.isEmpty()) result else result.subList(0, result.size - 1).toList()
            is DiffOp.Insert ->
                // `index == count` is a valid append; beyond it is a no-op.
                if (op.index in 0..result.size) {
                    result.toMutableList().apply { add(op.index, op.value) }
                } else {
                    result
                }
            is DiffOp.Set ->
                if (op.index in 0 until result.size) {
                    result.toMutableList().apply { set(op.index, op.value) }
                } else {
                    result
                }
            is DiffOp.Remove ->
                if (op.index in 0 until result.size) {
                    result.toMutableList().apply { removeAt(op.index) }
                } else {
                    result
                }
            is DiffOp.Truncate ->
                if (op.length in 0 until result.size) result.subList(0, op.length).toList() else result
            is DiffOp.Reset -> op.values
        }
    }

    return result
}

/**
 * The two monomorphised mirrors, folded back into the generic.
 *
 * Written as exhaustive `when` expressions with no `else`, so a new case on
 * the boundary breaks this build rather than being silently dropped on the
 * floor — the same discipline the Rust side of the mirror uses, and for the
 * same reason.
 */
val RoomDiffOp.generic: DiffOp<RoomRow>
    get() = when (this) {
        is RoomDiffOp.Append -> DiffOp.Append(values)
        RoomDiffOp.Clear -> DiffOp.Clear
        is RoomDiffOp.PushFront -> DiffOp.PushFront(value)
        is RoomDiffOp.PushBack -> DiffOp.PushBack(value)
        RoomDiffOp.PopFront -> DiffOp.PopFront
        RoomDiffOp.PopBack -> DiffOp.PopBack
        is RoomDiffOp.Insert -> DiffOp.Insert(index.toInt(), value)
        is RoomDiffOp.Set -> DiffOp.Set(index.toInt(), value)
        is RoomDiffOp.Remove -> DiffOp.Remove(index.toInt())
        is RoomDiffOp.Truncate -> DiffOp.Truncate(length.toInt())
        is RoomDiffOp.Reset -> DiffOp.Reset(values)
    }

/** The timeline's mirror of [RoomDiffOp.generic]. */
val TimelineDiffOp.generic: DiffOp<TimelineRow>
    get() = when (this) {
        is TimelineDiffOp.Append -> DiffOp.Append(values)
        TimelineDiffOp.Clear -> DiffOp.Clear
        is TimelineDiffOp.PushFront -> DiffOp.PushFront(value)
        is TimelineDiffOp.PushBack -> DiffOp.PushBack(value)
        TimelineDiffOp.PopFront -> DiffOp.PopFront
        TimelineDiffOp.PopBack -> DiffOp.PopBack
        is TimelineDiffOp.Insert -> DiffOp.Insert(index.toInt(), value)
        is TimelineDiffOp.Set -> DiffOp.Set(index.toInt(), value)
        is TimelineDiffOp.Remove -> DiffOp.Remove(index.toInt())
        is TimelineDiffOp.Truncate -> DiffOp.Truncate(length.toInt())
        is TimelineDiffOp.Reset -> DiffOp.Reset(values)
    }
