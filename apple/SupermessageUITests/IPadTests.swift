import XCTest

/// The two faults that only appear on a regular width class.
///
/// Both shipped through an iPhone-shaped simulator run without a murmur, which
/// is the argument for this file existing at all: `NavigationSplitView` and
/// `ItemView` both behave differently once there is room for two columns.
///
/// Run against an iPad destination. On an iPhone these assertions are still
/// true — a collapsed split view is a stack whose root is the roster — so it
/// costs nothing to leave in the suite.
@MainActor
final class IPadTests: XCTestCase {
    func testTheRosterIsOnScreenWithoutHuntingForAToggle() throws {
        let app = XCUIApplication()
        app.launch()

        // No tap on a sidebar toggle anywhere in this test, and that is the
        // assertion. `NavigationSplitView` defaults to `.automatic`, which on
        // an iPad in portrait hides the sidebar — the app opened on an empty
        // detail pane with the roster behind a control nobody had reason to
        // look for.
        let room = app.staticTexts["ganesha"]
        XCTAssertTrue(
            room.waitForExistence(timeout: 30),
            "the roster was not visible at launch — the sidebar started collapsed")
    }

    func testAnOpenRoomHasNoUnsupportedEventsInIt() throws {
        let app = XCUIApplication()
        app.launch()

        let room = app.staticTexts["ganesha"]
        XCTAssertTrue(room.waitForExistence(timeout: 30), "no joined room in the roster")
        room.tap()
        XCTAssertTrue(
            app.navigationBars["ganesha"].waitForExistence(timeout: 20),
            "the room did not open")

        // Long enough for a screen of history to render. The fault this
        // catches was a date divider — every conversation older than a day has
        // one, so any real room reproduces it.
        Thread.sleep(forTimeInterval: 6)

        // `ItemView` has one legitimate use of this text: an `m.*` event the
        // core genuinely does not know. A kind the core *does* know reaching
        // it means a host forgot a case, which is what happened here —
        // "Unsupported event (dateDivider)" in the middle of a conversation.
        let apology = app.staticTexts.containing(
            NSPredicate(format: "label BEGINSWITH 'Unsupported event'"))
        XCTAssertEqual(
            apology.count, 0,
            "the timeline is showing an unsupported-event placeholder: "
                + apology.allElementsBoundByIndex.map { $0.label }.joined(separator: ", "))

        try? XCUIScreen.main.screenshot().pngRepresentation
            .write(to: URL(fileURLWithPath: "/tmp/ipad-room.png"))
    }

    func testTheInfoPanelDescribesTheRoomThatIsOpen() throws {
        let app = XCUIApplication()
        app.launch()

        let room = app.staticTexts["ganesha"]
        XCTAssertTrue(room.waitForExistence(timeout: 30), "no joined room in the roster")
        room.tap()
        XCTAssertTrue(
            app.navigationBars["ganesha"].waitForExistence(timeout: 20),
            "the room did not open")

        // Tapped by coordinate: `tap()` on this toolbar item makes XCUITest
        // try `AXScrollToVisible` first, which a navigation bar refuses, and
        // the tap fails before the panel ever opens. The element is on screen
        // and hittable — it is the scroll action that cannot complete.
        let info = app.navigationBars["ganesha"].buttons["Info"]
        XCTAssertTrue(info.waitForExistence(timeout: 10), "no info button in the toolbar")
        info.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()

        // The panel used to fail here with "Couldn't load — NotReady", because
        // the core refused to describe a room unless it was the focused one —
        // a guard meant for writes, applied to a read that names its own room.
        let members = app.staticTexts.containing(
            NSPredicate(format: "label BEGINSWITH[c] 'Members'"))
        XCTAssertTrue(
            members.firstMatch.waitForExistence(timeout: 20),
            "the info panel never loaded the member list")

        // Existence is not enough, and asserting only on it hid a real fault:
        // the panel was in the accessibility tree, its member list loaded,
        // and none of it was on screen — the inspector had resolved to zero
        // width. A test that passes while the reader sees nothing is worse
        // than no test.
        let frame = members.firstMatch.frame
        XCTAssertTrue(
            frame.width > 1 && frame.height > 1,
            "the member list is in the tree but has no area on screen: \(frame)")
        XCTAssertTrue(
            app.windows.firstMatch.frame.contains(frame.origin),
            "the info panel is laid out off screen: \(frame)")
        XCTAssertFalse(
            app.staticTexts["Couldn't load"].exists,
            "the info panel refused to describe the room that is open")

        try? XCUIScreen.main.screenshot().pngRepresentation
            .write(to: URL(fileURLWithPath: "/tmp/ipad-info.png"))
    }
}
