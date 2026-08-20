import XCTest

/// The timeline's acceptance rules, asserted as geometry.
///
/// `docs/superpowers/specs/2026-08-19-timeline-behaviour-design.md` names
/// these, and says why they are geometric: a UI test once asserted the
/// room-info panel's member list **existed** and passed while the panel was
/// laid out at x=850.5 on an 834-point screen — present in the accessibility
/// tree, invisible to the reader. Asserting it had area on screen is what
/// caught the real fault.
///
/// So nothing here asserts existence alone. On screen, with area, and where
/// it belongs relative to the composer.
@MainActor
final class TimelineGeometryTests: XCTestCase {
    /// **Opening a room.** Lands on the newest message with it fully visible,
    /// not under the composer.
    ///
    /// The failure this catches is the one that shipped for weeks: an
    /// inverted list that opened somewhere in the middle of its content, and
    /// a newest row whose bottom edge sat behind the composer so the last
    /// thing said was the one thing you could not read.
    func testOpeningARoomLandsOnTheNewestMessageAboveTheComposer() throws {
        let app = openGanesha()

        let composer = app.textFields.firstMatch
        XCTAssertTrue(composer.waitForExistence(timeout: 20), "no composer")

        let newest = newestCell(app)
        XCTAssertTrue(newest.exists, "no rows in the timeline")

        // Area, not existence. A zero-height or off-screen cell satisfies
        // `exists` and shows the reader nothing.
        XCTAssertGreaterThan(newest.frame.height, 1, "the newest row has no height")
        XCTAssertGreaterThan(newest.frame.width, 1, "the newest row has no width")

        XCTAssertLessThanOrEqual(
            newest.frame.maxY, composer.frame.minY + 1,
            "the newest message is under the composer — \(newest.frame) vs \(composer.frame)")

        let screen = app.frame
        XCTAssertGreaterThanOrEqual(
            newest.frame.minY, screen.minY,
            "the newest message is off the top of the screen")

        // **And it really is the newest.** The three assertions above are
        // true of the bottom-most *visible* row at any scroll position, so on
        // their own they pass for a room that opened halfway up its history —
        // which is exactly the bug this rule exists to catch. The app's own
        // answer to "are we at the newest message" is whether it is offering
        // a way back to it, so the absence of that button is the assertion
        // with teeth.
        XCTAssertFalse(
            app.buttons["Jump to newest"].exists,
            "the room offered a way back to the newest message, so it did not open there")
    }

    /// **Growing the composer.** A newline changes the composer's height and
    /// moves nothing else.
    ///
    /// The reported fault was worse than movement: pressing return for a new
    /// line made the whole timeline disappear. Asserting the row count and the
    /// newest row's identity across the newline is what would have caught it.
    func testANewlineGrowsTheComposerAndMovesNothingElse() throws {
        let app = openGanesha()

        let composer = app.textFields.firstMatch
        XCTAssertTrue(composer.waitForExistence(timeout: 20), "no composer")

        XCTAssertGreaterThan(app.cells.count, 0, "no rows to keep still")
        let newestBefore = newestCell(app).identifier
        let composerHeightBefore = composer.frame.height

        composer.tap()
        composer.typeText("one")
        // A literal newline in the field, which is what the reader pressing
        // return produces in a `TextField(axis: .vertical)`.
        composer.typeText("\n")
        composer.typeText("two")
        Thread.sleep(forTimeInterval: 1.5)

        XCTAssertGreaterThan(
            composer.frame.height, composerHeightBefore,
            "the composer did not grow, so this test is not testing what it says")

        // Deliberately *not* a row count. The keyboard takes half the screen
        // and XCUITest only reports the cells in the hierarchy, so the count
        // drops the moment the field is tapped — through the app working
        // exactly as it should. Identity and area are the honest measures.
        XCTAssertEqual(
            newestCell(app).identifier, newestBefore,
            "the newest row changed identity when the composer grew")

        let newest = newestCell(app)
        XCTAssertGreaterThan(newest.frame.height, 1, "the timeline collapsed when the composer grew")
        XCTAssertLessThanOrEqual(
            newest.frame.maxY, composer.frame.minY + 1,
            "the grown composer is now covering the newest message")

        // Leave the composer as it was found, so a later test in the same
        // session does not inherit a half-written message.
        clear(composer, app: app)
    }

    /// **Receiving, while at the bottom.** A row that arrives while the reader
    /// is at the newest message appears and stays visible.
    ///
    /// Driven by sending, which is the only arrival this test can cause: the
    /// local echo is a row appearing at the bottom while the reader is at it,
    /// and the rule is the same one.
    func testASentMessageLandsVisibleAboveTheComposer() throws {
        let app = openGanesha()

        let composer = app.textFields.firstMatch
        XCTAssertTrue(composer.waitForExistence(timeout: 20), "no composer")

        composer.tap()
        composer.typeText("Geometry check from the UI tests.")

        // The send button, not return: in a `TextField(axis: .vertical)`
        // return inserts a newline, which is the whole point of the test
        // above. The button exists only once there is something to send.
        let send = app.buttons["arrow.up.circle.fill"]
        XCTAssertTrue(send.waitForExistence(timeout: 10), "no send button appeared")
        send.tap()

        // The composer clears only when the core accepted the send.
        let cleared = NSPredicate(format: "value == %@", "Message")
        expectation(for: cleared, evaluatedWith: composer)
        waitForExpectations(timeout: 30)

        let newest = newestCell(app)
        XCTAssertGreaterThan(newest.frame.height, 1, "the sent row has no height")
        XCTAssertLessThanOrEqual(
            newest.frame.maxY, composer.frame.minY + 1,
            "the sent message landed under the composer")

        // **And it stays put.** A row that lands correctly and then jumps a
        // second later is the fault a reader actually notices.
        //
        // Only the geometry is asserted here. Whether the *identity* survives
        // the local-echo-to-confirmed transition is the same rule, but the
        // window between the two is shorter than XCUITest can reliably
        // sample — a UI test for it passed whether the rule held or not, so
        // it lives in `DiffApplyTests` where applying the batch is
        // deterministic. The timeline spec assigns it to the Kit for exactly
        // this reason.
        let frameAfterEcho = newest.frame
        Thread.sleep(forTimeInterval: 5)

        let settled = newestCell(app)
        XCTAssertEqual(
            settled.frame.maxY, frameAfterEcho.maxY, accuracy: 1,
            "the newest row moved after it landed")
        XCTAssertLessThanOrEqual(
            settled.frame.maxY, composer.frame.minY + 1,
            "the newest row slid under the composer after it landed")
    }

    // MARK: - Helpers

    /// The newest row: in an inverted list that is the one nearest the
    /// composer, which is the greatest `maxY`, not index 0.
    private func newestCell(_ app: XCUIApplication) -> XCUIElement {
        app.cells.allElementsBoundByIndex
            .filter { $0.frame.height > 1 }
            .max { $0.frame.maxY < $1.frame.maxY }
            ?? app.cells.firstMatch
    }

    private func clear(_ field: XCUIElement, app: XCUIApplication) {
        guard let text = field.value as? String, text != "Message" else { return }
        field.tap()
        for _ in 0..<text.count {
            app.keys["delete"].tap()
        }
    }

    private func openGanesha() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments += [
            "-roster.view", "recent",
            "-roster.showsInvitations", "NO",
            "-roster.showsState", "YES",
        ]
        app.launch()

        let room = app.staticTexts["Ganesha"]
        XCTAssertTrue(room.waitForExistence(timeout: 30), "no joined room in the roster")
        room.tap()
        XCTAssertTrue(
            app.navigationBars["Ganesha"].waitForExistence(timeout: 20), "the room did not open")
        // The first layout pass settles before anything here measures it.
        Thread.sleep(forTimeInterval: 3)
        return app
    }
}
