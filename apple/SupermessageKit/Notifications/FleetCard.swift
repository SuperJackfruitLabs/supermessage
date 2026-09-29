// Compiled into TWO targets, as FleetActivityAttributes.swift is: the Kit,
// where its tests run, and the SupermessageWidgets extension, which draws the
// card and has no core to ask. Foundation only.

import Foundation

#if canImport(ActivityKit)

/// What the fleet card says, read from the hub's state (spec 2026-09-30, B2):
/// the counts across the top, which agent is the hero, the stage its turn is
/// at and the words under it.
///
/// **Why this is not the core's.** The card is drawn by the widget extension
/// while the app is not running, from a push the hub sent; the extension has
/// no core (it has a ~30 MB ceiling and no keychain group), so nothing here
/// can be asked of `supermessage-core`. What *is* the hub's — which agents are
/// listed, in what order, their state, phase, step and counts — is taken as
/// given; this only picks a hero from that order and words the result. It is
/// kept out of the view, beside the contract it reads, so the Kit's tests pin
/// it and the view only lays it out.
public struct FleetCard: Equatable, Sendable {
    public typealias State = FleetActivityAttributes.ContentState
    public typealias Agent = State.Agent

    /// How a count or an agent is coloured: by meaning, never by matching
    /// words. None of these is amber — that is the decision's alone.
    public enum Tone: Equatable, Sendable {
        case needsYou, working, done, failed
    }

    /// One of the header's counts: "3 working", "1 needs you".
    public struct Count: Equatable, Sendable, Identifiable {
        public var number: Int
        public var word: String
        public var tone: Tone
        public var id: String { word }
    }

    /// The four stops of a turn's track.
    public enum Stage: Int, CaseIterable, Equatable, Sendable {
        case thinking, tools, writing, done

        public var word: String {
            switch self {
            case .thinking: "Thinking"
            case .tools: "Tools"
            case .writing: "Writing"
            case .done: "Done"
            }
        }

        /// Where its stop sits on the track, from 0 to 1.
        public var stop: Double { Double(rawValue) / Double(Stage.allCases.count - 1) }
    }

    /// A turn as a road: Thinking → Tools → Writing → Done.
    public struct Track: Equatable, Sendable {
        /// The stage it is at — for a failed turn, the one it failed in.
        public var stage: Stage
        /// Where the marker sits, from 0 to 1. Tool progress moves it within
        /// the Tools segment.
        public var position: Double
        public var failed: Bool
        /// "3/7" while tools run, so the Tools label and a subtitle can say it.
        public var tools: String?

        /// A stop's label: "Tools 3/7" for the Tools stop when counts are
        /// known, else the stage's word.
        public func label(_ stage: Stage) -> String {
            if stage == .tools, let tools { return "Tools \(tools)" }
            return stage.word
        }

        /// Whether `stage`'s stop is passed or reached.
        public func reached(_ stage: Stage) -> Bool { stage.rawValue <= self.stage.rawValue }

        /// Whether the marker rides the track: only a turn still running has
        /// one; a finished track is filled, a failed one stops where it did.
        public var moving: Bool { !failed && stage != .done }
    }

    public enum Hero: Equatable, Sendable {
        case decision(State.Decision)
        case agent(Agent)
    }

    /// Counts worth saying, needs you first: never a zero.
    public let counts: [Count]
    /// Nothing is running and nothing is owed: every listed turn ended.
    public let finished: Bool
    /// The header's fixed time: when the hub last updated this, or, once
    /// finished, when the last turn ended. It never ticks.
    public let time: Date
    public let hero: Hero?
    /// Everyone listed but the hero, in the hub's order — the compact rows.
    public let rows: [Agent]

    public init(_ state: State) {
        var counts: [Count] = []
        if state.needsYou > 0 {
            counts.append(
                Count(
                    number: state.needsYou, word: state.needsYou == 1 ? "needs you" : "need you",
                    tone: .needsYou))
        }
        if state.working > 0 {
            counts.append(Count(number: state.working, word: "working", tone: .working))
        }
        let done = state.agents.filter { $0.state == .done }.count
        let failed = state.agents.filter { $0.state == .failed }.count
        if done > 0 { counts.append(Count(number: done, word: "done", tone: .done)) }
        if failed > 0 { counts.append(Count(number: failed, word: "failed", tone: .failed)) }
        self.counts = counts

        let ended = state.agents.filter { $0.state == .done || $0.state == .failed }
        let running = state.agents.contains { $0.state == .working || $0.state == .needsYou }
        let finished =
            state.decision == nil && state.working == 0 && state.needsYou == 0 && !running
            && !ended.isEmpty
        let lastEnd = ended.compactMap(\.endedAt).max()
        self.finished = finished
        time = Date(timeIntervalSince1970: finished ? (lastEnd ?? state.updatedAt) : state.updatedAt)

        // The hero: an owed decision; else the first agent that needs the
        // reader or is working, in the hub's order (needs you, then working,
        // then most recent); else the turn that ended last.
        let heroAgent: Agent?
        if let decision = state.decision {
            hero = .decision(decision)
            heroAgent = nil
        } else if let busy = state.agents.first(where: { $0.state == .needsYou || $0.state == .working }) {
            heroAgent = busy
            hero = .agent(busy)
        } else if let last = ended.max(by: { Self.endMoment($0) < Self.endMoment($1) }) {
            heroAgent = last
            hero = .agent(last)
        } else {
            heroAgent = state.agents.first
            hero = heroAgent.map(Hero.agent)
        }
        // With a decision on top, the others are counted, not listed: the
        // decision is what the card is for until it is answered.
        rows = state.decision == nil ? state.agents.filter { $0.roomId != heroAgent?.roomId } : []
    }

    private static func endMoment(_ agent: Agent) -> Double { agent.endedAt ?? agent.since ?? 0 }

    // MARK: - Words

    /// An agent's track, or `nil` for one that is only recently active.
    public static func track(_ agent: Agent) -> Track? {
        let completed = max(0, agent.completed ?? 0)
        let total = max(0, agent.total ?? 0)
        let tools = total > 0 ? "\(min(completed, total))/\(total)" : nil
        let stage: Stage
        switch agent.state {
        case .done:
            return Track(stage: .done, position: 1, failed: false, tools: tools)
        case .active:
            return nil
        case .working, .needsYou, .failed:
            stage = agent.phase.map(Self.stage) ?? (total > 0 ? .tools : .thinking)
        }
        var position = stage.stop
        if stage == .tools, total > 0 {
            // Tools done move the marker across the Tools segment, one
            // segment being a third of the track.
            let segment = 1 / Double(Stage.allCases.count - 1)
            position += Double(min(completed, total)) / Double(total) * segment
        }
        return Track(stage: stage, position: position, failed: agent.state == .failed, tools: tools)
    }

    private static func stage(_ phase: State.Phase) -> Stage {
        switch phase {
        case .thinking: .thinking
        case .tools: .tools
        case .writing: .writing
        }
    }

    /// The hero's headline: what it is doing, or how its turn ended.
    public static func headline(_ agent: Agent) -> String {
        switch agent.state {
        case .done: return "\(agent.name) finished"
        case .failed: return "\(agent.name) failed"
        case .working, .needsYou, .active:
            if let step = agent.step, !step.isEmpty { return step }
            switch agent.state {
            case .needsYou: return "Waiting for you"
            case .working: return track(agent)?.stage.word ?? "Working"
            default: return agent.name
            }
        }
    }

    /// The hero's subtitle. `stages` says whether the stage labels are drawn
    /// under the track: when they are, the subtitle counts steps ("Artistic
    /// Lyra · step 3 of 7"); when they are not, it carries the current
    /// stage's label instead ("Artistic Lyra · Tools 3/7").
    public static func subtitle(_ agent: Agent, stages: Bool) -> String {
        let completed = agent.completed ?? 0
        let total = agent.total ?? 0
        switch agent.state {
        case .working:
            guard let track = Self.track(agent) else { return agent.name }
            if stages {
                return completed > 0 && total > 0
                    ? "\(agent.name) · step \(min(completed, total)) of \(total)" : agent.name
            }
            return "\(agent.name) · \(track.label(track.stage))"
        case .needsYou:
            return "\(agent.name) · needs you"
        case .active:
            return agent.name
        case .done:
            let parts = [
                duration(agent).map { "Done in \($0)" } ?? "Done",
                total > 0 ? steps(total) : nil,
            ]
            return parts.compactMap { $0 }.joined(separator: " · ")
        case .failed:
            let parts = [
                completed > 0 && total > 0 ? "At step \(min(completed, total)) of \(total)" : nil,
                duration(agent).map { "after \($0)" },
            ].compactMap { $0 }
            if parts.isEmpty { return agent.step.flatMap { $0.isEmpty ? nil : $0 } ?? "Failed" }
            let line = parts.joined(separator: " · ")
            return line.prefix(1).uppercased() + line.dropFirst()
        }
    }

    /// A compact row's line after the name: the step it is on, or how its
    /// turn ended, as the hub put it.
    public static func rowLine(_ agent: Agent) -> String {
        if let step = agent.step, !step.isEmpty { return step }
        switch agent.state {
        case .working: return track(agent)?.stage.word ?? "Working"
        case .needsYou: return "Waiting for you"
        case .done, .failed: return subtitle(agent, stages: true)
        case .active: return "Active"
        }
    }

    /// A compact row's progress, from 0 to 1, when its tools are counted.
    public static func progress(_ agent: Agent) -> Double? {
        guard let total = agent.total, total > 0 else { return nil }
        if agent.state == .done { return 1 }
        return Double(min(max(0, agent.completed ?? 0), total)) / Double(total)
    }

    /// "3/7" for a row whose tools are counted.
    public static func toolCount(_ agent: Agent) -> String? {
        track(agent)?.tools ?? (agent.total.flatMap { $0 > 0 ? "\(min(agent.completed ?? 0, $0))/\($0)" : nil })
    }

    /// How long a finished turn took: "3m 57s", "45s", "1h 4m".
    public static func duration(_ agent: Agent) -> String? {
        guard let since = agent.since, let ended = agent.endedAt, ended >= since else { return nil }
        return duration(seconds: Int((ended - since).rounded()))
    }

    public static func duration(seconds: Int) -> String {
        let s = max(0, seconds)
        if s < 60 { return "\(s)s" }
        if s < 3600 { return "\(s / 60)m \(s % 60)s" }
        return "\(s / 3600)h \((s % 3600) / 60)m"
    }

    private static func steps(_ n: Int) -> String { n == 1 ? "1 step" : "\(n) steps" }
}
#endif
