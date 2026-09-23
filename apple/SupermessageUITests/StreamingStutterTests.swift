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
    func testStreamingMovesTheHistoryOnlyInSteps() {
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
        // The live card animating in is allowed (a fraction of a second);
        // the list gliding after every line is not.
        XCTAssertLessThan(
            Self.count("animating", in: label), 30,
            "the list animated its resizing while the answer streamed (\(label))")
    }

    private static func count(_ key: String, in label: String) -> Int {
        guard let range = label.range(of: "\(key)=") else { return -1 }
        return Int(label[range.upperBound...].prefix { $0.isNumber }) ?? -1
    }
}
