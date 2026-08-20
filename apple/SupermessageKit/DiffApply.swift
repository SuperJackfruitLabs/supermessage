import Foundation
import SupermessageFFI

/// One change to a list, as the core describes it.
///
/// UniFFI has no generics, so the boundary carries two monomorphised mirrors —
/// `RoomDiffOp` and `TimelineDiffOp`, eleven cases each. This is the generic
/// they were flattened from, restored at the edge so `applyOps` and
/// `DiffTracker` are written once rather than twice.
public enum DiffOp<T> {
    case append([T])
    case clear
    case pushFront(T)
    case pushBack(T)
    case popFront
    case popBack
    case insert(index: Int, value: T)
    case set(index: Int, value: T)
    case remove(index: Int)
    case truncate(length: Int)
    case reset([T])
}

/// Every item an op carries, in order — empty for the ops that only move or
/// drop items.
///
/// Mirrors `core::dto::op_values`, and exists for the same reason: a caller
/// that needs to look at what a batch *contains* should not have to write a
/// second exhaustive match over `DiffOp` and risk disagreeing with `applyOps`
/// about what an op means.
public func opValues<T>(_ op: DiffOp<T>) -> [T] {
    switch op {
    case let .append(values): return values
    case .clear: return []
    case let .pushFront(value): return [value]
    case let .pushBack(value): return [value]
    case .popFront: return []
    case .popBack: return []
    case let .insert(_, value): return [value]
    case let .set(_, value): return [value]
    case .remove: return []
    case .truncate: return []
    case let .reset(values): return values
    }
}

/// Apply a batch of ops, returning a new array. Never mutates its input.
///
/// **This must agree with `core::dto::apply_ops` operation for operation**,
/// including the out-of-range handling below. The core maintains its own
/// resync snapshot by folding the same op stream through that function, so a
/// divergence here does not show up as a crash — it shows up as a resync that
/// silently installs different state than the core believes it sent.
///
/// The out-of-range rules matter more in Swift than they did in the original.
/// In JavaScript an out-of-bounds splice is quietly harmless; here `items[i]`
/// traps, so every guard below is load-bearing rather than tidy.
public func applyOps<T>(_ items: [T], _ ops: [DiffOp<T>]) -> [T] {
    var result = items

    for op in ops {
        switch op {
        case let .append(values):
            result.append(contentsOf: values)
        case .clear:
            result.removeAll()
        case let .pushFront(value):
            result.insert(value, at: 0)
        case let .pushBack(value):
            result.append(value)
        case .popFront:
            if !result.isEmpty { result.removeFirst() }
        case .popBack:
            if !result.isEmpty { result.removeLast() }
        case let .insert(index, value):
            // `index == count` is a valid append; beyond it is a no-op.
            if index >= 0 && index <= result.count { result.insert(value, at: index) }
        case let .set(index, value):
            if index >= 0 && index < result.count { result[index] = value }
        case let .remove(index):
            if index >= 0 && index < result.count { result.remove(at: index) }
        case let .truncate(length):
            if length >= 0 && length < result.count { result.removeSubrange(length...) }
        case let .reset(values):
            result = values
        }
    }

    return result
}

/// The two monomorphised mirrors, folded back into the generic.
///
/// Written as exhaustive switches with no `default`, so a new case on the
/// boundary breaks this build rather than being silently dropped on the floor
/// — the same discipline the Rust side of the mirror uses, and for the same
/// reason.
extension RoomDiffOp {
    var generic: DiffOp<RoomRow> {
        switch self {
        case let .append(values): return .append(values)
        case .clear: return .clear
        case let .pushFront(value): return .pushFront(value)
        case let .pushBack(value): return .pushBack(value)
        case .popFront: return .popFront
        case .popBack: return .popBack
        case let .insert(index, value): return .insert(index: Int(index), value: value)
        case let .set(index, value): return .set(index: Int(index), value: value)
        case let .remove(index): return .remove(index: Int(index))
        case let .truncate(length): return .truncate(length: Int(length))
        case let .reset(values): return .reset(values)
        }
    }
}

extension TimelineDiffOp {
    var generic: DiffOp<TimelineRow> {
        switch self {
        case let .append(values): return .append(values)
        case .clear: return .clear
        case let .pushFront(value): return .pushFront(value)
        case let .pushBack(value): return .pushBack(value)
        case .popFront: return .popFront
        case .popBack: return .popBack
        case let .insert(index, value): return .insert(index: Int(index), value: value)
        case let .set(index, value): return .set(index: Int(index), value: value)
        case let .remove(index): return .remove(index: Int(index))
        case let .truncate(length): return .truncate(length: Int(length))
        case let .reset(values): return .reset(values)
        }
    }
}
