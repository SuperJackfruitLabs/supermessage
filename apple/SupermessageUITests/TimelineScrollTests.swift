import XCTest

/// Scrolls the long fixture room the way a thumb does, for recording and for
/// catching the problems in the 2026-09-23 screen recording.
///
/// Needs no account: `-fixtureTimeline` opens a local room (DEBUG only). Run
/// it while recording the simulator to see the result:
///
///     xcrun simctl io booted recordVideo out.mp4
@MainActor
final class TimelineScrollTests: XCTestCase {
    func testScrollingTheHistory() {
        let app = XCUIApplication()
        app.launchArguments += ["-fixtureTimeline"]
        app.launch()

        let list = app.collectionViews.firstMatch
        XCTAssertTrue(list.waitForExistence(timeout: 10), "the fixture timeline never appeared")
        // An empty list scrolls without complaint, and the first version of
        // this test passed against one. The history must be on screen first.
        XCTAssertTrue(
            app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "summarise yesterday"))
                .firstMatch.waitForExistence(timeout: 10),
            "the fixture history never loaded — nothing below would be tested")

        // Flicks and drags into history, including ones that curve left —
        // a right thumb flicking up the screen does — which the reveal
        // gesture used to take for a swipe-for-times.
        let middle = list.coordinate(withNormalizedOffset: CGVector(dx: 0.6, dy: 0.35))
        for _ in 0..<3 {
            middle.press(
                forDuration: 0.01,
                thenDragTo: list.coordinate(withNormalizedOffset: CGVector(dx: 0.35, dy: 0.85)),
                withVelocity: .fast, thenHoldForDuration: 0)
            Thread.sleep(forTimeInterval: 0.6)
        }
        list.swipeDown(velocity: .slow)
        Thread.sleep(forTimeInterval: 0.6)
        list.swipeDown(velocity: .fast)
        Thread.sleep(forTimeInterval: 1.0)

        // Whether the reveal fired mid-scroll is judged from a recording,
        // not here: the revealed times are hidden from accessibility, and the
        // reveal springs back when the finger lifts, so nothing is left to
        // query afterwards.

        // Back down, and a collapsed stretch opens on tap.
        list.swipeUp(velocity: .fast)
        list.swipeUp(velocity: .fast)
        Thread.sleep(forTimeInterval: 0.8)
        let stretch = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "membership changes")).firstMatch
        if stretch.waitForExistence(timeout: 3) {
            stretch.tap()
            Thread.sleep(forTimeInterval: 0.8)
        }

        // A deliberate leftward swipe still shows the times.
        list.swipeLeft()
        Thread.sleep(forTimeInterval: 1.0)
    }
}
