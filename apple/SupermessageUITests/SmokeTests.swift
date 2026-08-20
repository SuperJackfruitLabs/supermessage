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
        let app = launched()

        let room = app.staticTexts["Ganesha"]
        XCTAssertTrue(room.waitForExistence(timeout: 30), "no joined room in the roster")

        room.tap()
        XCTAssertTrue(
            app.navigationBars["Ganesha"].waitForExistence(timeout: 20),
            "the room did not open the first time")

        app.navigationBars["Ganesha"].buttons.firstMatch.tap()
        XCTAssertTrue(
            room.waitForExistence(timeout: 20), "going back did not return to the roster")

        room.tap()
        XCTAssertTrue(
            app.navigationBars["Ganesha"].waitForExistence(timeout: 20),
            "the room would not open a second time")
    }


    /// Long press a message and act on it.
    ///
    /// The affordances the app shipped without for its first weeks: reactions
    /// rendered but could not be tapped, and nothing anywhere started a reply,
    /// even though the core had both and the desktop used them.
    func testAMessageCanBeRepliedTo() throws {
        let app = launched()

        let room = app.staticTexts["Ganesha"]
        XCTAssertTrue(room.waitForExistence(timeout: 30), "no joined room in the roster")
        room.tap()
        XCTAssertTrue(
            app.navigationBars["Ganesha"].waitForExistence(timeout: 20), "the room did not open")

        // Not "the first cell": a timeline row is not always a message. Date
        // dividers, collapsed membership runs and the live turn are rows too,
        // and they correctly offer nothing — so this looks for a row that does
        // rather than assuming the one on top will.
        let reply = app.buttons["Reply"]
        var opened = false
        for index in 0..<min(app.cells.count, 6) {
            let cell = app.cells.element(boundBy: index)
            guard cell.exists else { continue }
            cell.press(forDuration: 1.0)
            if reply.waitForExistence(timeout: 3) {
                opened = true
                break
            }
            // Close whatever did appear before trying the next row.
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.05)).tap()
            Thread.sleep(forTimeInterval: 1)
        }
        XCTAssertTrue(opened, "no row in the timeline offered a reply action")

        // The menu itself, while it is up. A context menu is as much a piece
        // of design as the timeline behind it, and this is the only way to
        // look at one on a device.
        attach(XCUIScreen.main.screenshot(), named: "ios-message-actions")

        reply.tap()

        // The composer says who it is answering. That strip is the whole
        // visible result of starting a reply.
        let replying = app.staticTexts.containing(
            NSPredicate(format: "label BEGINSWITH[c] 'Replying to'"))
        XCTAssertTrue(
            replying.firstMatch.waitForExistence(timeout: 10),
            "the composer never showed what it was replying to")

        attach(XCUIScreen.main.screenshot(), named: "ios-reply")
    }

    /// The roster in each of its three arrangements.
    ///
    /// Not an assertion about pixels — it opens each view and leaves a picture
    /// behind, because a roster is judged by looking at it and this is the only
    /// way to look at one on a device.
    func testTheRosterInEachArrangement() throws {
        let app = launched()

        XCTAssertTrue(
            app.staticTexts["Ganesha"].waitForExistence(timeout: 30), "no roster appeared")

        for arrangement in ["Recent", "Waiting", "Machine"] {
            let button = app.buttons[arrangement]
            XCTAssertTrue(
                button.waitForExistence(timeout: 10), "no \(arrangement) arrangement to choose")
            button.tap()
            Thread.sleep(forTimeInterval: 1.5)
            attach(XCUIScreen.main.screenshot(), named: "roster-\(arrangement.lowercased())")
        }

        // And the sheet that decides which of them the app opens on.
        app.buttons.matching(NSPredicate(format: "label CONTAINS[c] 'options'")).firstMatch.tap()
        Thread.sleep(forTimeInterval: 1.5)
        attach(XCUIScreen.main.screenshot(), named: "roster-settings")
    }

    /// Every panel, photographed.
    ///
    /// Assertion-light on purpose: this exists so the screens can be *looked
    /// at*, which is how a reading surface is judged, and several of them are
    /// only reachable through a toolbar.
    ///
    /// Sheets are dismissed by swiping rather than by tapping Done, because
    /// `.searchable` replaces the toolbar's Done with its own Cancel and a
    /// tour that assumes one button is a tour that stops at the first screen.
    func testEveryPanel() throws {
        let app = launched()
        XCTAssertTrue(
            app.staticTexts["Ganesha"].waitForExistence(timeout: 30), "no roster appeared")

        // The account screen, and the only way out of the app. `signOut` was
        // implemented, tested, and reachable from nowhere.
        app.buttons["Account"].tap()
        Thread.sleep(forTimeInterval: 2)
        // Case-insensitive: `List` uppercases section headers, so the label is
        // "SIGNED IN AS". The exact-match version of this has now caught me
        // twice.
        let signedIn = app.staticTexts.containing(
            NSPredicate(format: "label BEGINSWITH[c] 'Signed in as'"))
        XCTAssertTrue(signedIn.firstMatch.waitForExistence(timeout: 10), "no account screen")
        XCTAssertTrue(app.buttons["Sign out"].exists, "no way to sign out")
        attach(XCUIScreen.main.screenshot(), named: "panel-account")
        dismissSheet(app)

        app.buttons["square.and.pencil"].tap()
        Thread.sleep(forTimeInterval: 2)
        attach(XCUIScreen.main.screenshot(), named: "panel-new-room")
        dismissSheet(app)

        app.staticTexts["Ganesha"].tap()
        XCTAssertTrue(
            app.navigationBars["Ganesha"].waitForExistence(timeout: 20), "the room did not open")
        Thread.sleep(forTimeInterval: 2)
        attach(XCUIScreen.main.screenshot(), named: "panel-room-toolbar")

        let info = app.navigationBars["Ganesha"].buttons["Info"]
        XCTAssertTrue(info.waitForExistence(timeout: 10), "no info button")
        let centre = CGVector(dx: info.frame.midX, dy: info.frame.midY)
        app.coordinate(withNormalizedOffset: .zero).withOffset(centre).tap()
        Thread.sleep(forTimeInterval: 4)
        attach(XCUIScreen.main.screenshot(), named: "panel-room-info")
    }

    /// The timeline, at the bottom and further back.
    ///
    /// The interesting parts of a conversation with an agent are not at the
    /// bottom: turn cards, membership churn and the start of the room only
    /// appear once you scroll, and they are what a reading surface is judged on.
    func testTheTimelineFrontAndBack() throws {
        let app = launched()
        XCTAssertTrue(
            app.staticTexts["Ganesha"].waitForExistence(timeout: 30), "no roster appeared")
        app.staticTexts["Ganesha"].tap()
        XCTAssertTrue(
            app.navigationBars["Ganesha"].waitForExistence(timeout: 20), "the room did not open")
        Thread.sleep(forTimeInterval: 3)
        attach(XCUIScreen.main.screenshot(), named: "timeline-newest")

        let list = app.collectionViews.firstMatch
        for shot in 1...5 {
            list.swipeDown(velocity: .fast)
            list.swipeDown(velocity: .fast)
            Thread.sleep(forTimeInterval: 2)
            attach(XCUIScreen.main.screenshot(), named: "timeline-back-\(shot)")
            // Cell frames, so an unexplained gap can be attributed to a row
            // rather than guessed at.
            let frames = app.cells.allElementsBoundByIndex
                .map { "\(Int($0.frame.minY))..\(Int($0.frame.maxY)) h=\(Int($0.frame.height))" }
                .joined(separator: "\n")
            let note = XCTAttachment(string: frames)
            note.name = "cells-\(shot)"
            note.lifetime = .keepAlways
            add(note)
        }
    }

    /// Pull a sheet down off the screen.
    private func dismissSheet(_ app: XCUIApplication) {
        let top = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.2))
        let bottom = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.95))
        top.press(forDuration: 0.05, thenDragTo: bottom)
        Thread.sleep(forTimeInterval: 1.5)
    }

    func testOpensARoomAndRendersItsTimeline() throws {
        let app = launched()

        // The roster. A restored session goes straight here; a signed-out one
        // shows the login form instead, which is a different test's problem.
        let list = app.collectionViews.firstMatch
        XCTAssertTrue(list.waitForExistence(timeout: 30), "no roster appeared")

        // A joined room rather than an invitation: an invitation has no
        // timeline to render, which is the point of the affordance.
        let room = app.staticTexts["Ganesha"]
        XCTAssertTrue(room.waitForExistence(timeout: 30), "no joined room in the roster")
        room.tap()

        // The timeline. Its title is the room's parsed name.
        XCTAssertTrue(
            app.navigationBars["Ganesha"].waitForExistence(timeout: 20),
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
            attach(XCUIScreen.main.screenshot(), named: "ios-after-send")
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
        attach(XCUIScreen.main.screenshot(), named: "ios-timeline")
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
