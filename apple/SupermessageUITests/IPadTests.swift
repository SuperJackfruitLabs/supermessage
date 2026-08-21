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
        let app = launched()

        // No tap on a sidebar toggle anywhere in this test, and that is the
        // assertion. `NavigationSplitView` defaults to `.automatic`, which on
        // an iPad in portrait hides the sidebar — the app opened on an empty
        // detail pane with the roster behind a control nobody had reason to
        // look for.
        let room = app.staticTexts["Ganesha"]
        XCTAssertTrue(
            room.waitForExistence(timeout: 30),
            "the roster was not visible at launch — the sidebar started collapsed")
    }

    func testAnOpenRoomHasNoUnsupportedEventsInIt() throws {
        let app = launched()

        let room = app.staticTexts["Ganesha"]
        XCTAssertTrue(room.waitForExistence(timeout: 30), "no joined room in the roster")
        room.tap()
        XCTAssertTrue(
            app.navigationBars["Ganesha"].waitForExistence(timeout: 20),
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

        attach(XCUIScreen.main.screenshot(), named: "ipad-room")
    }

    func testTheInfoPanelDescribesTheRoomThatIsOpen() throws {
        let app = launched()

        let room = app.staticTexts["Ganesha"]
        XCTAssertTrue(room.waitForExistence(timeout: 30), "no joined room in the roster")
        room.tap()
        XCTAssertTrue(
            app.navigationBars["Ganesha"].waitForExistence(timeout: 20),
            "the room did not open")

        // The room *header* is the control, not a ⓘ button beside it. Once the
        // header became a button (`RootView`'s `.principal` toolbar item, which
        // shows the agent's liveness as well as its name) a separate info
        // button was a second door to the same room, and it was removed. This
        // test still asks the same question — does the panel describe the room
        // that is open — only the way in changed.
        let info = app.navigationBars["Ganesha"].buttons["Ganesha, about this room"]
        XCTAssertTrue(info.waitForExistence(timeout: 10), "no room-info control in the toolbar")
        // Existence is not reach. A toolbar button can be in the tree and
        // unreachable — something laid over the bar swallowing its touches —
        // and then the rest of this test fails somewhere misleading.
        XCTAssertTrue(info.isHittable, "the room-info control is on screen but not hittable")

        // Tapped at an absolute window coordinate. `tap()` on this element
        // makes XCUITest attempt `AXScrollToVisible` first, which a navigation
        // bar refuses, and the call fails before the panel ever opens.
        let centre = CGVector(dx: info.frame.midX, dy: info.frame.midY)
        app.coordinate(withNormalizedOffset: .zero).withOffset(centre).tap()

        // Taken before the assertions, not after: a failing element query
        // *throws*, so a screenshot written at the end of the test is exactly
        // the one that never appears when it would be most useful.
        Thread.sleep(forTimeInterval: 4)
        attach(XCUIScreen.main.screenshot(), named: "ipad-info")

        // Section headers are uppercased by `List`, hence the case-insensitive
        // match — asserting on "Members" exactly passed nothing and failed
        // everything for a while.
        let members = app.staticTexts.containing(
            NSPredicate(format: "label BEGINSWITH[c] 'Members'"))
        XCTAssertTrue(
            members.firstMatch.waitForExistence(timeout: 20),
            "the info panel never loaded the member list")
        XCTAssertFalse(
            app.staticTexts["Couldn't load"].exists,
            "the info panel refused to describe the room that is open")

        // Existence is not visibility, and asserting only on existence hid a
        // real fault: the panel was in the accessibility tree with its member
        // list loaded, laid out at x=850.5 on an 834-point screen — entirely
        // off the side of the window. A test that passes while the reader sees
        // nothing is worse than no test.
        let frame = members.firstMatch.frame
        XCTAssertTrue(
            frame.width > 1 && frame.height > 1,
            "the member list is in the tree but has no area on screen: \(frame)")
        // The window's own frame is in the message because without it this
        // failure is unreadable: "laid out off screen: (850.5, …)" says
        // nothing about what it is off the side *of*, and the answer decides
        // whether the panel is misplaced or the device is simply narrower
        // than the layout assumed.
        let window = app.windows.firstMatch.frame
        XCTAssertTrue(
            window.contains(frame),
            "the info panel is laid out off screen: \(frame) is outside \(window)")
    }

    /// Screenshots travel in the result bundle, not through `/tmp`.
    ///
    /// On the simulator `/tmp` is the host's and a file written there is
    /// readable straight away. On a real phone it is inside the app's sandbox,
    /// so the same line silently produced nothing. An attachment works on both
    /// and survives the run either way.
    private func attach(_ screenshot: XCUIScreenshot, named name: String) {
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    /// Launch with the roster in a known state.
    ///
    /// The arrangement is remembered in `UserDefaults`, so without this a run
    /// inherits whatever the *previous* run last tapped — and a test that
    /// passes or fails depending on the order of earlier tests is not a test.
    /// `UserDefaults` reads `-key value` pairs straight off the command line,
    /// so no test-only code has to exist in the app for this.
    func launched(roster: String = "recent") -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments += [
            "-roster.view", roster,
            "-roster.showsInvitations", "NO",
            "-roster.showsState", "YES",
        ]
        app.launch()
        return app
    }
}
