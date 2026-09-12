import SnapshottingTests

/// Renders every `#Preview` in the app to an image.
///
/// **There are no test functions here, and that is the whole point.**
/// `SnapshotTest` enumerates the host application's preview registry at run
/// time and adds one test per preview it finds, so this class stays four
/// lines while the app has 52 previews and will have more. A file listing
/// them would be a second inventory to keep in step with the first, and
/// nothing would notice when it drifted.
///
/// ## Why this exists
///
/// P2b wrote 100 previews across iOS and Android and **rendered none of
/// them**. Xcode 16.4 here ships the iOS 18.5 SDK against a device on 26.6.1,
/// so the canvas was never available, and a preview that compiles to a blank
/// frame is a plausible undetected outcome. This is what turns them into
/// something a person — or a CI artifact — can actually look at.
///
/// ## What makes a preview snapshottable
///
/// The package's one hard requirement is determinism: no live network, no
/// timers, no `Date()`. The app's previews are built on `PreviewClient`,
/// which answers every call immediately from fixed fixtures and reaches no
/// network, so most of them qualify by construction.
///
/// Two categories do not, and they are excluded below rather than left to
/// produce a diff on every run.
final class PreviewSnapshotTests: SnapshotTest {
    /// Previews whose output changes with the wall clock or with a race.
    ///
    /// **The relative-time ones are a fixture problem, not a view problem.**
    /// `PreviewFixtures` uses absolute timestamps — `1_757_700_000_000` is
    /// 2026-09-12 — and the roster's state word is computed against `Date()`
    /// inside the view, which nothing here can inject. So "14m" today is "3d"
    /// next week and every snapshot diff would be noise. The fix is to make
    /// the fixtures relative to a fixed clock, which needs a seam in the
    /// views that does not exist yet; until then these are excluded rather
    /// than allowed to cry wolf.
    ///
    /// **`RootView` is excluded for a different reason.** Its `.task` sees
    /// `.starting`, calls `start()`, and the stub answers immediately — so
    /// which phase is on screen when the shutter opens is a race. Its
    /// comment in `RootView.swift` already says the frame is transient; that
    /// makes it a poor snapshot even though it is a fair preview.
    override class func excludedSnapshotPreviews() -> [String]? {
        [
            "Supermessage.RootView",
            "Supermessage.SignedInView",
            "Supermessage.RoomListView",
        ]
    }
}
