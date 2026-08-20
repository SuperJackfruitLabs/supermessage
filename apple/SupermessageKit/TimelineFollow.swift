import CoreGraphics

/// Whether the timeline should stay pinned to the newest message.
///
/// Ported from `src/lib/components/timelineFollow.ts`, including the fix that
/// came out of using the desktop app rather than out of a test.
///
/// It lives in the Kit rather than beside the view because it is pure
/// arithmetic with no SwiftUI in it — which means a test can exercise *this*
/// rather than a copy of it. A test that restates the rule it is checking
/// passes whatever the app does.
public enum TimelineFollow {
    /// How close to the bottom still counts as "following", in points.
    ///
    /// Not zero: a reader who has nudged the list by a few points has not
    /// asked to stop following, and snapping back from a two-point offset
    /// reads as the list fighting them.
    public static let bottomThreshold: CGFloat = 64

    /// How close to the top starts fetching older messages, in points.
    ///
    /// A threshold rather than "the reader reached the very top", so the rows
    /// land before they are looked at. The desktop settled on 200 and this
    /// matches it deliberately: two clients disagreeing about when history
    /// loads is two different apps.
    public static let topThreshold: CGFloat = 200

    /// Whether to ask the core for older messages.
    ///
    /// The view used to gate this on the topmost visible row being *exactly*
    /// the first row in the list, which is a knife edge a scroll almost never
    /// lands on — so a room with months of history would never load any of
    /// it. Distance is the honest measure, and it is the one the desktop
    /// already used.
    public static func wantsOlderHistory(
        distanceFromTop: CGFloat, canPaginate: Bool, isPaginating: Bool, hasSettled: Bool
    ) -> Bool {
        // `isPaginating` is not belt-and-braces: scroll geometry fires many
        // times a second, and without it a single flick queues a dozen
        // overlapping round trips against one timeline.
        //
        // `hasSettled` is what stops a room from walking itself to its own
        // beginning the moment it opens. Until the view has landed at the
        // newest message, the content is still arriving and the scroll offset
        // is near zero for reasons that have nothing to do with a reader
        // wanting history — so every prepended page re-triggers the next one
        // and the room opens at its oldest message instead of its newest.
        // History is fetched because a reader moved toward it, never because
        // the list has not finished arriving.
        canPaginate && !isPaginating && hasSettled && distanceFromTop < topThreshold
    }

    /// Whether a growth should scroll to the new bottom.
    ///
    /// Only when the reader was already at the bottom. Dragging someone who
    /// has deliberately scrolled back to read is the most annoying thing a
    /// timeline can do.
    public static func shouldRepin(distanceFromBottom: CGFloat, grew: Bool) -> Bool {
        grew && distanceFromBottom <= bottomThreshold
    }

    /// Whether to settle at the bottom on the *first* content a room has.
    ///
    /// This exists because of a real defect, found by using the app. A room
    /// opened mid-history receives its whole backlog in one batch, and that
    /// batch is the only growth there is — so `shouldRepin` discards it, the
    /// way it discards any first observation, having nothing to compare
    /// against. The view stayed stranded wherever the initial layout put it.
    ///
    /// `settled` is whether this room has already been settled once, so a
    /// reader who scrolls up is not dragged back down by a later batch.
    public static func shouldSettleAtBottom(previous: Int, next: Int, settled: Bool) -> Bool {
        !settled && previous == 0 && next > 0
    }
}
