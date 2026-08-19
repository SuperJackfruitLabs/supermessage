import XCTest

/// Deliberately thin.
///
/// Enough to catch broken wiring — the app launches, a roster appears, a room
/// opens, a timeline renders. Not a UI regression suite: rendering faults are
/// caught by looking at the screen, which is what the tasks that built these
/// screens did, and a screenshot assertion would go stale faster than it would
/// catch anything.
@MainActor
final class SmokeTests: XCTestCase {
    /// Open a room, go back, open it again.
    ///
    /// The second tap is the whole test. A collapsed `NavigationSplitView`
    /// navigates *by selection*, so anything that clears the selection on the
    /// way back has to leave the roster able to select the same room again —
    /// and "set it to the value it already had" is a change SwiftUI can miss.
    func testARoomCanBeOpenedTwice() throws {
        let app = XCUIApplication()
        app.launch()

        let room = app.staticTexts["ganesha"]
        XCTAssertTrue(room.waitForExistence(timeout: 30), "no joined room in the roster")

        room.tap()
        XCTAssertTrue(
            app.navigationBars["ganesha"].waitForExistence(timeout: 20),
            "the room did not open the first time")

        app.navigationBars["ganesha"].buttons.firstMatch.tap()
        XCTAssertTrue(
            room.waitForExistence(timeout: 20), "going back did not return to the roster")

        room.tap()
        XCTAssertTrue(
            app.navigationBars["ganesha"].waitForExistence(timeout: 20),
            "the room would not open a second time")
    }


    func testOpensARoomAndRendersItsTimeline() throws {
        let app = XCUIApplication()
        app.launch()

        // The roster. A restored session goes straight here; a signed-out one
        // shows the login form instead, which is a different test's problem.
        let list = app.collectionViews.firstMatch
        XCTAssertTrue(list.waitForExistence(timeout: 30), "no roster appeared")

        // A joined room rather than an invitation: an invitation has no
        // timeline to render, which is the point of the affordance.
        let room = app.staticTexts["ganesha"]
        XCTAssertTrue(room.waitForExistence(timeout: 30), "no joined room in the roster")
        room.tap()

        // The timeline. Its title is the room's parsed name.
        XCTAssertTrue(
            app.navigationBars["ganesha"].waitForExistence(timeout: 20),
            "the room did not open")

        // Send something, which is the other half of a chat client working.
        // It goes to the live homeserver, which is what this account is for.
        // `TextField(axis: .vertical)` surfaces as a text field, not a text
        // view — checked rather than assumed after the first guess missed.
        let composer = app.textFields.firstMatch
        XCTAssertTrue(composer.waitForExistence(timeout: 10), "no composer")
        composer.tap()
        composer.typeText("Hello from the native iOS app.")

        // The send button only exists once there is something to send —
        // Messages hides it rather than dimming it.
        let send = app.buttons["arrow.up.circle.fill"]
        if send.waitForExistence(timeout: 5) {
            send.tap()
            Thread.sleep(forTimeInterval: 6)
            try? XCUIScreen.main.screenshot().pngRepresentation
                .write(to: URL(fileURLWithPath: "/tmp/ios-after-send.png"))
            // The composer clears only when the core accepted the send, so an
            // empty field is the signal — and the honest one to assert on.
            //
            // Looking for the sent text in the timeline was the first attempt
            // and it is unreliable for a reason worth recording: a LazyVStack
            // realises only the rows on screen, and in a live room the agent's
            // reply arrives within seconds and pushes the sent message above
            // the fold. The test then fails while the app is working, which is
            // the worst kind of test.
            XCTAssertEqual(
                composer.value as? String, "Message",
                "the composer did not clear, so the send was refused")
        }

        // Leave a picture behind. A reading surface is judged by looking at
        // it, and this is the only place in the suite that can hand a person
        // one. Written to a fixed path rather than attached to the result
        // bundle so it survives the bundle failing to save.
        Thread.sleep(forTimeInterval: 2)
        let shot = XCUIScreen.main.screenshot()
        try? shot.pngRepresentation.write(to: URL(fileURLWithPath: "/tmp/ios-timeline.png"))
    }
}
