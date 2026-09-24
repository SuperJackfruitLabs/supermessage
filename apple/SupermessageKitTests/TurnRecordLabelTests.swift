import Testing

@testable import SupermessageKit

struct TurnRecordLabelTests {
    @Test("reasoning leads, then the steps")
    func thoughtAndSteps() {
        #expect(TurnRecordLabel.text(thought: true, steps: 2, seconds: 12) == "Thought for 12s · 2 steps")
        #expect(TurnRecordLabel.text(thought: true, steps: 1, seconds: 12) == "Thought for 12s · 1 step")
        #expect(TurnRecordLabel.text(thought: true, steps: 0, seconds: 12) == "Thought for 12s")
    }

    @Test("a turn that only used tools says how many and how long")
    func stepsOnly() {
        #expect(TurnRecordLabel.text(thought: false, steps: 3, seconds: 65) == "3 steps · 1m 5s")
    }

    @Test("no time known leaves the time out, rather than inventing one")
    func noTime() {
        #expect(TurnRecordLabel.text(thought: true, steps: 0, seconds: nil) == "Thought")
        #expect(TurnRecordLabel.text(thought: false, steps: 2, seconds: nil) == "2 steps")
    }

    @Test("durations")
    func durations() {
        #expect(TurnRecordLabel.duration(0.2) == "1s")
        #expect(TurnRecordLabel.duration(59.4) == "59s")
        #expect(TurnRecordLabel.duration(125) == "2m 5s")
    }
}
