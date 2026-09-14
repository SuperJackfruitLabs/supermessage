import SwiftUI

extension EnvironmentValues {
    /// Whether this view is being captured as a still frame.
    ///
    /// A view reading this should drop anything that never settles — an
    /// indeterminate `ProgressView`, a looping shimmer — and keep everything
    /// that does. It is not a request to stop animating in general; a
    /// transition still finishes.
    ///
    /// **Why this exists rather than `accessibilityReduceMotion`.** That
    /// value means the right thing and a view honouring it is the better
    /// product. It is also read-only: SwiftUI owns it, and
    /// `.environment(\.accessibilityReduceMotion, true)` does not compile. So
    /// a preview has no way to ask for the settled rendering, and the three
    /// `LiveTurnView` previews stayed outside the snapshot gate for want of a
    /// switch they could reach. `LiveTurnView` honours both: the accessible
    /// behaviour for readers who asked for it, this for the camera.
    ///
    /// Kept deliberately narrow. A flag that means "we are in a test" grows
    /// until the previews stop resembling the app; one that means "no
    /// indeterminate motion" describes a rendering the app can genuinely
    /// produce, and a reader with Reduce Motion on very nearly sees it.
    @Entry var rendersStill: Bool = false
}
