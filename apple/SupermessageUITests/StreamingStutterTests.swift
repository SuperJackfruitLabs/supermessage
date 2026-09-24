import XCTest

/// Streams an answer into the fixture room and checks it arrives without the
/// stutter of the 2026-09-23 Guild recording.
///
/// That stutter was the list animating its own resizing: each new line was
/// laid out at once and the cells then glided into place, so the history
/// snapped and slid a line at a time. `StreamProbe` (DEBUG, on under
/// `-fixtureStreaming`) counts the frames in which a visible cell is drawn
/// away from its layout position. Measured before the fix: 195 such frames
/// in one streamed answer. After: 13, the live card's own entrance.
@MainActor
final class StreamingStutterTests: XCTestCase {
    func testStreamingGlidesTheHistory() {
        let app = XCUIApplication()
        app.launchArguments += ["-fixtureStreaming"]
        app.launch()

        let probe = app.staticTexts["stream-probe"]
        XCTAssertTrue(probe.waitForExistence(timeout: 10), "the streaming fixture never appeared")

        let finished = NSPredicate(format: "label CONTAINS %@", "finished=true")
        expectation(for: finished, evaluatedWith: probe)
        waitForExpectations(timeout: 30)

        let label = probe.label
        print("stream-probe result:", label)
        // Guard against passing on nothing: the answer must actually have
        // wrapped onto new lines while it streamed.
        XCTAssertGreaterThanOrEqual(
            Self.count("growths", in: label), 8, "the answer barely grew — nothing was tested (\(label))")
        // The history glides as the answer grows; it must never leap. One
        // jump is allowed for the live card first appearing.
        XCTAssertLessThanOrEqual(
            Self.count("jumps", in: label), 1,
            "the history jumped rather than glided while the answer streamed (\(label))")
    }

    /// Scrolled back to read while an answer streams: nothing on screen may
    /// move. The 2026-09-24 recording had the history sliding under the
    /// reader's thumb on every sentence.
    func testScrollingBackWhileStreamingHoldsTheReadingPosition() {
        let app = XCUIApplication()
        app.launchArguments += ["-fixtureStreaming"]
        app.launch()

        let probe = app.staticTexts["stream-probe"]
        XCTAssertTrue(probe.waitForExistence(timeout: 10), "the streaming fixture never appeared")
        // Let the answer start, then scroll back into the history.
        let started = NSPredicate(format: "label MATCHES %@", ".*growths=([2-9]|[1-9][0-9]).*")
        expectation(for: started, evaluatedWith: probe)
        waitForExpectations(timeout: 20)
        // A short, slow drag: back a few lines, with the answer still on
        // screen and growing — the recording's case. A swipe went so far the
        // live card left the screen, nothing grew in view, and the test
        // passed against the bug.
        let list = app.collectionViews.firstMatch
        let from = list.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.45))
        let to = from.withOffset(CGVector(dx: 0, dy: 150))
        from.press(forDuration: 0.05, thenDragTo: to, withVelocity: .slow, thenHoldForDuration: 0.3)

        let finished = NSPredicate(format: "label CONTAINS %@", "finished=true")
        expectation(for: finished, evaluatedWith: probe)
        waitForExpectations(timeout: 30)

        let label = probe.label
        print("stream-probe result:", label)
        XCTAssertGreaterThanOrEqual(
            Self.count("awayFrames", in: label), 60, "never rested scrolled back — nothing was tested (\(label))")
        XCTAssertGreaterThanOrEqual(
            Self.count("awayGrowths", in: label), 3,
            "the answer never grew in view while scrolled back — nothing was tested (\(label))")
        XCTAssertEqual(
            Self.count("awayMoves", in: label), 0,
            "the history moved under a reader scrolled back from the answer (\(label))")
    }

    private static func count(_ key: String, in label: String) -> Int {
        guard let range = label.range(of: "\(key)=") else { return -1 }
        return Int(label[range.upperBound...].prefix { $0.isNumber }) ?? -1
    }
}
