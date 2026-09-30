import Foundation
import Testing

@testable import SupermessageKit

/// What the fleet card says (spec 2026-09-30, B2): the header, the hero, the
/// track and the words, read from the hub's state.
struct FleetCardTests {
    typealias State = FleetActivityAttributes.ContentState
    typealias Agent = State.Agent

    static let at: Double = 1_790_670_135

    static let lyra = Agent(
        roomId: "!lyra", mxid: "@lyra:hs", name: "Artistic Lyra", state: .working, phase: .tools,
        step: "Running the tests", completed: 3, total: 7, since: at - 124)
    static let kai = Agent(
        roomId: "!kai", name: "Coder Kai", state: .working, phase: .thinking, step: "Reading widget.rs",
        completed: 1, total: 4, since: at - 60)
    static let ray = Agent(
        roomId: "!ray", name: "Research Ray", state: .needsYou, step: "Waiting for you", since: at - 30)
    static let doneLyra = Agent(
        roomId: "!lyra", name: "Artistic Lyra", state: .done, step: "Done · 7 steps", completed: 7,
        total: 7, since: at - 400, endedAt: at - 163)
    static let failedQuill = Agent(
        roomId: "!quill", name: "Writer Quill", state: .failed, step: "Failed at step 4 of 7",
        completed: 4, total: 7, since: at - 600, endedAt: at - 528)
    static let decision = State.Decision(
        roomId: "!ray", eventId: "$p", agent: "Research Ray", kind: .permission,
        question: "Run git push origin main?",
        options: [.init(id: "Allow once", label: "Allow once", declines: false)])

    // MARK: - Header

    @Test("the header counts what is non-zero, needs you first, and never a zero")
    func counts() {
        let card = FleetCard(
            State(agents: [Self.ray, Self.lyra, Self.failedQuill], decision: Self.decision,
                  needsYou: 1, working: 2, updatedAt: Self.at))
        #expect(card.counts.map(\.number) == [1, 2, 1])
        #expect(card.counts.map(\.word) == ["needs you", "working", "failed"])
        #expect(card.counts.map(\.tone) == [.needsYou, .working, .failed])
        let two = FleetCard(State(agents: [], needsYou: 2, updatedAt: Self.at))
        #expect(two.counts.map(\.word) == ["need you"])
        #expect(FleetCard(State(agents: [], updatedAt: Self.at)).counts.isEmpty)
    }

    @Test("the time is the update's while live, and the last finish once everything ended")
    func time() {
        let live = FleetCard(State(agents: [Self.lyra, Self.failedQuill], working: 1, updatedAt: Self.at))
        #expect(!live.finished)
        #expect(live.time == Date(timeIntervalSince1970: Self.at))

        let ended = FleetCard(State(agents: [Self.doneLyra, Self.failedQuill], updatedAt: Self.at))
        #expect(ended.finished)
        #expect(ended.time == Date(timeIntervalSince1970: Self.at - 163), "the later of the two ends")
        #expect(ended.counts.map(\.word) == ["done", "failed"])

        // A turn that ended before the hub sent `endedAt` (today's hub).
        var old = Self.doneLyra
        old.endedAt = nil
        let noEnd = FleetCard(State(agents: [old], updatedAt: Self.at))
        #expect(noEnd.finished)
        #expect(noEnd.time == Date(timeIntervalSince1970: Self.at))

        // A decision still owed is not finished, whatever the rows say.
        let owed = FleetCard(
            State(agents: [Self.doneLyra], decision: Self.decision, needsYou: 1, updatedAt: Self.at))
        #expect(!owed.finished)
    }

    // MARK: - Hero

    @Test("a decision is the hero, and the others are counted rather than listed")
    func decisionHero() {
        let card = FleetCard(
            State(agents: [Self.ray, Self.lyra], decision: Self.decision, needsYou: 1, working: 1,
                  updatedAt: Self.at))
        #expect(card.hero == .decision(Self.decision))
        #expect(card.rows.isEmpty)
    }

    @Test("else the first agent that needs you or is working, in the hub's order")
    func busyHero() {
        let card = FleetCard(
            State(agents: [Self.failedQuill, Self.kai, Self.lyra], working: 2, updatedAt: Self.at))
        #expect(card.hero == .agent(Self.kai), "a finished row listed first is not the hero")
        #expect(card.rows.map(\.roomId) == ["!quill", "!lyra"])

        let asking = FleetCard(State(agents: [Self.ray, Self.lyra], needsYou: 1, working: 1, updatedAt: Self.at))
        #expect(asking.hero == .agent(Self.ray))
    }

    @Test("else the turn that ended last")
    func finishedHero() {
        let card = FleetCard(State(agents: [Self.failedQuill, Self.doneLyra], updatedAt: Self.at))
        #expect(card.hero == .agent(Self.doneLyra))
        #expect(card.rows.map(\.roomId) == ["!quill"])
    }

    // MARK: - Track

    @Test("the track stops at the phase, and tools move the marker across their segment")
    func trackPositions() throws {
        let tools = try #require(FleetCard.track(Self.lyra))
        #expect(tools.stage == .tools)
        #expect(abs(tools.position - (1.0 / 3 + 3.0 / 7 / 3)) < 1e-9)
        #expect(tools.label(.tools) == "Tools 3/7")
        #expect(tools.label(.writing) == "Writing")
        #expect(tools.moving && !tools.failed)
        #expect(tools.reached(.thinking) && tools.reached(.tools) && !tools.reached(.writing))

        let thinking = try #require(FleetCard.track(Self.kai))
        #expect(thinking.stage == .thinking)
        #expect(thinking.position == 0, "tool counts move nothing outside the Tools segment")

        var writing = Self.lyra
        writing.phase = .writing
        #expect(FleetCard.track(writing)?.position == 2.0 / 3)
    }

    @Test("without a phase (today's hub), the counts say which stage")
    func trackWithoutPhase() {
        var counted = Self.lyra
        counted.phase = nil
        #expect(FleetCard.track(counted)?.stage == .tools)
        var uncounted = Self.lyra
        uncounted.phase = nil
        uncounted.total = 0
        #expect(FleetCard.track(uncounted)?.stage == .thinking)
    }

    @Test("a done track is full and still; a failed one stops, red, where the turn did")
    func trackEnds() throws {
        let done = try #require(FleetCard.track(Self.doneLyra))
        #expect(done.stage == .done && done.position == 1 && !done.moving && !done.failed)
        let failed = try #require(FleetCard.track(Self.failedQuill))
        #expect(failed.failed && !failed.moving)
        #expect(failed.stage == .tools)
        #expect(abs(failed.position - (1.0 / 3 + 4.0 / 7 / 3)) < 1e-9)
        #expect(FleetCard.track(Agent(roomId: "!a", name: "A", state: .active)) == nil)
    }

    // MARK: - Words

    @Test("the hero's words while working, with and without the stage labels")
    func workingWords() {
        #expect(FleetCard.headline(Self.lyra) == "Running the tests")
        #expect(FleetCard.subtitle(Self.lyra, stages: true) == "Artistic Lyra · step 3 of 7")
        #expect(FleetCard.subtitle(Self.lyra, stages: false) == "Artistic Lyra · Tools 3/7")
        var bare = Self.kai
        bare.step = nil
        bare.completed = 0
        #expect(FleetCard.headline(bare) == "Thinking")
        #expect(FleetCard.subtitle(bare, stages: true) == "Coder Kai")
        #expect(FleetCard.subtitle(bare, stages: false) == "Coder Kai · Thinking")
    }

    @Test("a finish reads like a delivery's: done in how long, or where it failed")
    func finishWords() {
        #expect(FleetCard.headline(Self.doneLyra) == "Artistic Lyra finished")
        #expect(FleetCard.subtitle(Self.doneLyra, stages: true) == "Done in 3m 57s · 7 steps")
        #expect(FleetCard.headline(Self.failedQuill) == "Writer Quill failed")
        #expect(FleetCard.subtitle(Self.failedQuill, stages: true) == "At step 4 of 7 · after 1m 12s")

        var unknown = Self.failedQuill
        unknown.endedAt = nil
        unknown.completed = 0
        #expect(FleetCard.subtitle(unknown, stages: true) == "Failed at step 4 of 7", "the hub's words")
        var noEnd = Self.doneLyra
        noEnd.endedAt = nil
        #expect(FleetCard.subtitle(noEnd, stages: true) == "Done · 7 steps")
    }

    @Test("a row says the hub's step, its progress and its tool count")
    func rowWords() {
        #expect(FleetCard.rowLine(Self.kai) == "Reading widget.rs")
        #expect(FleetCard.progress(Self.kai) == 0.25)
        #expect(FleetCard.toolCount(Self.kai) == "1/4")
        #expect(FleetCard.rowLine(Self.failedQuill) == "Failed at step 4 of 7")
        #expect(FleetCard.progress(Self.doneLyra) == 1)
        #expect(FleetCard.toolCount(Agent(roomId: "!a", name: "A", state: .working)) == nil)
    }

    @Test("durations read as a person says them")
    func durations() {
        #expect(FleetCard.duration(seconds: 45) == "45s")
        #expect(FleetCard.duration(seconds: 237) == "3m 57s")
        #expect(FleetCard.duration(seconds: 3840) == "1h 4m")
        #expect(FleetCard.duration(seconds: -5) == "0s")
    }

    @Test("the v2 fixture's card: a decision on top, over two working agents")
    func v2Card() throws {
        let state = try JSONDecoder().decode(
            State.self, from: FleetActivityAttributesTests.fixture("fleet-content-state-v2"))
        let card = FleetCard(state)
        guard case .decision(let decision) = card.hero else {
            Issue.record("expected the decision on top")
            return
        }
        #expect(decision.agent == "Research Ray")
        #expect(card.counts.map(\.word) == ["needs you", "working", "failed"])
        var answered = state
        answered.decision = nil
        answered.needsYou = 0
        #expect(FleetCard(answered).hero.flatMap { hero -> String? in
            if case .agent(let agent) = hero { return agent.name } else { return nil }
        } == "Artistic Lyra")
    }
}
