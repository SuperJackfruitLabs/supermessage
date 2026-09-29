// Compiled into SupermessageKit and the Notification Service Extension.

import Foundation
import SupermessageFFI

/// Every write to the widgets' snapshot, each one a core merge
/// (`core::widget`) run under the writer's lock.
///
/// The judgement is all the core's: this turns a call into
/// `WidgetSnapshotWriter.update { stored in core(stored) }` and hands the
/// caller whether WidgetKit is worth asking to reload. The caller reloads —
/// this file imports no WidgetKit, so SupermessageKit stays UI-free.
public struct WidgetFeed: Sendable {
    public let writer: WidgetSnapshotWriter
    /// The clock, in milliseconds since the epoch. A parameter so a test can
    /// step it.
    public var now: @Sendable () -> UInt64

    public init(writer: WidgetSnapshotWriter, now: @escaping @Sendable () -> UInt64 = WidgetFeed.clock) {
        self.writer = writer
        self.now = now
    }

    /// The App Group's feed, or `nil` when this build has no group.
    public static func shared() -> WidgetFeed? {
        WidgetSnapshotWriter.shared().map { WidgetFeed(writer: $0) }
    }

    public static let clock: @Sendable () -> UInt64 = {
        UInt64(max(0, Date().timeIntervalSince1970 * 1000))
    }

    /// Whether the widgets should reload after this write.
    public typealias Reload = Bool

    private func run(_ merge: (String?, UInt64) -> WidgetWrite) -> Reload {
        let at = now()
        let result = writer.update { stored in
            let write = merge(stored, at)
            return .init(json: write.json, changed: write.changed, reload: write.reload)
        }
        return result?.reload ?? false
    }

    /// A push the Notification Service Extension decided.
    @discardableResult
    public func apply(_ note: NotificationDto) -> Reload {
        run { widgetApplyNotification(stored: $0, note: note, nowMs: $1) }
    }

    /// The app's roster, read at `asOf`, with the turns it is watching.
    @discardableResult
    public func apply(rows: [RoomRow], live: [WidgetLiveTurn], asOf: UInt64) -> Reload {
        run { widgetApplyRoster(stored: $0, rows: rows, live: live, asOfMs: asOf, nowMs: $1) }
    }

    /// The open room's loaded timeline.
    @discardableResult
    public func apply(roomId: String, timeline rows: [TimelineRow]) -> Reload {
        run { widgetApplyTimeline(stored: $0, roomId: roomId, rows: rows, nowMs: $1) }
    }

    @discardableResult
    public func markAnswered(roomId: String, eventId: String, optionId: String) -> Reload {
        run {
            widgetMarkAnswered(
                stored: $0, roomId: roomId, eventId: eventId, optionId: optionId, nowMs: $1)
        }
    }

    @discardableResult
    public func clearAnswer(roomId: String, eventId: String) -> Reload {
        run { widgetClearAnswer(stored: $0, roomId: roomId, eventId: eventId, nowMs: $1) }
    }

    @discardableResult
    public func signedOut() -> Reload {
        run { widgetSignedOut(stored: $0, nowMs: $1) }
    }

    /// What a widget button for `optionId` on this decision sends, or `nil`
    /// when the snapshot no longer owes it — resolved, already answered, or
    /// not an option a button may send.
    public func answer(roomId: String, eventId: String, optionId: String) -> WidgetAnswer? {
        var found: WidgetAnswer?
        writer.update { stored in
            found = widgetAnswerFor(
                stored: stored, roomId: roomId, eventId: eventId, optionId: optionId)
            return .init(json: stored ?? "", changed: false, reload: false)
        }
        return found
    }
}
