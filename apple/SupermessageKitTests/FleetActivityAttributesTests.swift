import Foundation
import Testing

@testable import SupermessageKit

/// The hub's Live Activity state (spec 2026-09-29, A4), as this app reads it.
///
/// The fixtures are treaty's cases for `fleet-content-state.v1`
/// (`vendor/treaty/fixtures/agentpod-live-activity/examples.json`, held to
/// the pinned release by `treaty verify`): the hub's producer tests validate
/// what it pushes against the same schema, so a key renamed on either side
/// fails a test on that side.
struct FleetActivityAttributesTests {
    typealias State = FleetActivityAttributes.ContentState

    private final class BundleToken {}

    /// AgentPod's v1 and v2 fixtures, as treaty labels them.
    static let v1 = "fleet-content-state.json, AgentPod's v1 fixture"
    static let v2 = "fleet-content-state-v2.json, AgentPod's v2 fixture with the shape the hub emits"

    /// treaty's cases for the content state: `valid` keyed by label,
    /// `invalid` as `(why, body)`.
    static func cases() throws -> (valid: [String: Data], invalid: [(String, Data)]) {
        let url = try #require(
            Bundle(for: BundleToken.self).url(forResource: "examples", withExtension: "json"))
        let corpus = try #require(
            try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        let schemas = try #require(corpus["schemas"] as? [String: Any])
        let state = try #require(schemas["fleet-content-state.v1"] as? [String: Any])
        let valid = try #require(state["valid"] as? [String: Any])
        let invalid = try #require(state["invalid"] as? [[String: Any]])
        return (
            try valid.mapValues { try JSONSerialization.data(withJSONObject: $0) },
            try invalid.map { entry in
                (entry["why"] as? String ?? "", try JSONSerialization.data(withJSONObject: entry["body"] ?? NSNull()))
            }
        )
    }

    /// One valid case, by its label.
    static func fixture(_ label: String = v1) throws -> Data {
        try #require(try cases().valid[label], "treaty has no case \(label)")
    }

    @Test("every case treaty says the hub may send decodes")
    func everyValidCase() throws {
        let valid = try Self.cases().valid
        #expect(valid.count >= 6)
        for (label, body) in valid {
            #expect(throws: Never.self, "\(label)") { try JSONDecoder().decode(State.self, from: body) }
        }
    }

    /// The producer's schema is strict; this reader is lenient on purpose
    /// (see `ContentState`). So a case the schema refuses must still never
    /// fail the update.
    @Test("every case the schema refuses is still read, leniently")
    func everyInvalidCase() throws {
        let invalid = try Self.cases().invalid
        #expect(invalid.count >= 10)
        for (why, body) in invalid {
            #expect(throws: Never.self, "\(why)") { try JSONDecoder().decode(State.self, from: body) }
        }
    }

    @Test("the v1 fixture has none of the 2026-09-30 additions, and says so as none")
    func v1HasNoAdditions() throws {
        let state = try JSONDecoder().decode(State.self, from: Self.fixture())
        #expect(state.agents.allSatisfy { $0.mxid == nil && $0.phase == nil && $0.endedAt == nil })
    }

    @Test("the v2 fixture decodes, with each agent's user id, phase and finish")
    func decodesTheV2Fixture() throws {
        let state = try JSONDecoder().decode(State.self, from: Self.fixture(Self.v2))
        #expect(state.agents.map(\.name) == ["Artistic Lyra", "Coder Kai", "Writer Quill"])
        #expect(
            state.agents.map(\.mxid) == [
                "@agent_artistic-lyra:hs", "@agent_coder-kai:hs", "@agent_writer-quill:hs",
            ])
        #expect(state.agents.map(\.phase) == [.tools, .thinking, nil])
        #expect(state.agents.map(\.state) == [.working, .working, .failed])
        let quill = state.agents[2]
        #expect(quill.endedAt == 1_790_669_472)
        #expect(quill.endedDate == Date(timeIntervalSince1970: 1_790_669_472))
        #expect(quill.since == 1_790_669_400)
        #expect(state.agents[0].endedAt == nil)
        #expect(state.decision?.eventId == "$perm1")
        #expect(state.working == 2)
        let again = try JSONDecoder().decode(State.self, from: JSONEncoder().encode(state))
        #expect(again == state)
    }

    @Test("a phase this build does not know is none, not an unreadable agent")
    func unknownPhase() throws {
        let json = #"{"agents": [{"roomId": "!a", "name": "Atlas", "state": "working", "phase": "dreaming", "endedAt": "soon"}]}"#
        let state = try JSONDecoder().decode(State.self, from: Data(json.utf8))
        #expect(state.agents.count == 1)
        #expect(state.agents[0].phase == nil)
        #expect(state.agents[0].endedAt == nil)
    }

    @Test("the shared fixture decodes, every field")
    func decodesTheFixture() throws {
        let state = try JSONDecoder().decode(State.self, from: Self.fixture())
        #expect(state.agents.map(\.name) == ["Research Ray", "Artistic Lyra", "Writer Quill"])
        #expect(state.agents.map(\.state) == [.needsYou, .working, .failed])
        let lyra = state.agents[1]
        #expect(lyra.roomId == "!lyra:hs")
        #expect(lyra.step == "Running the tests")
        #expect(lyra.completed == 3)
        #expect(lyra.total == 7)
        #expect(lyra.sinceDate == Date(timeIntervalSince1970: 1_790_669_880))
        #expect(state.agents[0].completed == nil, "a missing count is none")
        #expect(state.more == 1)
        let decision = try #require(state.decision)
        #expect(decision.roomId == "!ray:hs")
        #expect(decision.eventId == "$perm1")
        #expect(decision.agent == "Research Ray")
        #expect(decision.kind == .permission)
        #expect(decision.question == "Run git push origin main?")
        #expect(decision.options.map(\.id) == ["Allow once", "Reject"])
        #expect(decision.options.map(\.declines) == [false, true])
        #expect(state.needsYou == 1)
        #expect(state.working == 1)
        // Unix seconds, not the default decoder's seconds-since-2001.
        #expect(state.updated == Date(timeIntervalSince1970: 1_790_670_123))
        #expect(state.pulse == "1 working · 1 needs you")
    }

    @Test("what this app encodes, it decodes to the same state")
    func roundTrips() throws {
        let state = try JSONDecoder().decode(State.self, from: Self.fixture())
        let again = try JSONDecoder().decode(State.self, from: JSONEncoder().encode(state))
        #expect(again == state)
    }

    @Test("decoded leniently: the hub may add, omit, and grow")
    func lenient() throws {
        let json = """
            {"agents": [
                {"roomId": "!a", "name": "Atlas", "state": "sleeping", "mood": "fine"},
                {"roomId": "!b"},
                {"roomId": "!c", "name": "Quill", "state": "done", "total": "seven"}
             ],
             "decision": {"roomId": "!a", "eventId": "$e", "kind": "vote",
                          "options": [{"id": "Allow once"}, {"label": "no id"}]},
             "somethingNew": {"x": 1}}
            """
        let state = try JSONDecoder().decode(State.self, from: Data(json.utf8))
        // An unknown state reads as active; an agent without a name is
        // skipped, not fatal; a count of the wrong type is none.
        #expect(state.agents.map(\.name) == ["Atlas", "Quill"])
        #expect(state.agents[0].state == .active)
        #expect(state.agents[1].total == nil)
        #expect(state.more == 0)
        #expect(state.needsYou == 0)
        #expect(state.updatedAt == 0)
        let decision = try #require(state.decision)
        #expect(decision.kind == .permission)
        #expect(decision.options.map(\.label) == ["Allow once"])
        #expect(decision.question == "")
    }

    @Test("an unreadable decision is no decision, not an unreadable card")
    func unreadableDecision() throws {
        let json = #"{"agents": [], "decision": {"question": "no ids"}, "working": 2}"#
        let state = try JSONDecoder().decode(State.self, from: Data(json.utf8))
        #expect(state.decision == nil)
        #expect(state.working == 2)
    }

    @Test("the fleet in one line, worded as the Home Screen's")
    func pulse() {
        func line(working: Int, needsYou: Int) -> String {
            State(agents: [], needsYou: needsYou, working: working, updatedAt: 0).pulse
        }
        #expect(line(working: 0, needsYou: 0) == "All quiet")
        #expect(line(working: 2, needsYou: 0) == "2 working")
        #expect(line(working: 0, needsYou: 1) == "1 needs you")
        #expect(line(working: 0, needsYou: 3) == "3 need you")
        #expect(line(working: 2, needsYou: 1) == "2 working · 1 needs you")
    }
}
