// Compiled into TWO targets: SupermessageKit, and the SupermessageWidgets
// extension (see apple/SupermessageWidgets/extensions.yml). ActivityKit
// matches an activity to its widget configuration by this type's name — the
// hub's push-to-start names it too (`attributes-type`) — so both sides must
// declare the very same shape, and one file is how they cannot drift.
// Foundation and ActivityKit only: nothing here may reach the core.

import Foundation

#if canImport(ActivityKit)
import ActivityKit

/// The fleet, on the Lock Screen: what needs the reader and what is happening
/// now. **Pushed by the hub**, never updated by the app — the hub starts it
/// with the push-to-start token, updates it with each activity's update token
/// (both sent by `FleetActivityController` through the core), and ends it
/// once every agent has been quiet for fifteen minutes (spec 2026-09-29).
public struct FleetActivityAttributes: ActivityAttributes {
    /// The hub's `ContentState` (spec A4), mirrored.
    ///
    /// **Decoded leniently**, because it is the hub's to extend: an unknown
    /// key is ignored, a missing optional one means none, an agent or option
    /// that cannot be read is skipped rather than failing the whole update,
    /// and a state this build does not know reads as `active`.
    ///
    /// Times are **plain Unix seconds**, kept as numbers: ActivityKit decodes
    /// a push's `content-state` with a default `JSONDecoder`, whose `Date` is
    /// seconds since 2001, so a `Date` field here would be thirty-one years
    /// off.
    public struct ContentState: Codable, Hashable, Sendable {
        public enum AgentState: String, Codable, Hashable, Sendable {
            case working
            case needsYou = "needs_you"
            case active, done, failed

            public init(from decoder: Decoder) throws {
                let raw = try decoder.singleValueContainer().decode(String.self)
                self = AgentState(rawValue: raw) ?? .active
            }
        }

        public struct Agent: Codable, Hashable, Sendable, Identifiable {
            public var roomId: String
            public var name: String
            public var state: AgentState
            /// The step it is on, or how its turn ended. At most 60 characters.
            public var step: String?
            /// Tool calls done and made so far.
            public var completed: Int?
            public var total: Int?
            /// When its turn started, or when it was last active — Unix seconds.
            public var since: Double?

            public var id: String { roomId }
            public var sinceDate: Date? { since.map(Date.init(timeIntervalSince1970:)) }

            public init(
                roomId: String, name: String, state: AgentState, step: String? = nil,
                completed: Int? = nil, total: Int? = nil, since: Double? = nil
            ) {
                self.roomId = roomId
                self.name = name
                self.state = state
                self.step = step
                self.completed = completed
                self.total = total
                self.since = since
            }

            enum CodingKeys: String, CodingKey {
                case roomId, name, state, step, completed, total, since
            }

            public init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: CodingKeys.self)
                roomId = try c.decode(String.self, forKey: .roomId)
                name = try c.decode(String.self, forKey: .name)
                state = (try? c.decodeIfPresent(AgentState.self, forKey: .state)) ?? .active
                step = try? c.decodeIfPresent(String.self, forKey: .step)
                completed = try? c.decodeIfPresent(Int.self, forKey: .completed)
                total = try? c.decodeIfPresent(Int.self, forKey: .total)
                since = try? c.decodeIfPresent(Double.self, forKey: .since)
            }
        }

        public struct Option: Codable, Hashable, Sendable, Identifiable {
            /// What is sent: a permission option's name, or a gate's decision id.
            public var id: String
            public var label: String
            /// It refuses rather than grants: the quieter button.
            public var declines: Bool

            public init(id: String, label: String, declines: Bool) {
                self.id = id
                self.label = label
                self.declines = declines
            }

            enum CodingKeys: String, CodingKey { case id, label, declines }

            public init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: CodingKeys.self)
                id = try c.decode(String.self, forKey: .id)
                label = (try? c.decodeIfPresent(String.self, forKey: .label)) ?? id
                declines = (try? c.decodeIfPresent(Bool.self, forKey: .declines)) ?? false
            }
        }

        /// The oldest decision the reader owes.
        public struct Decision: Codable, Hashable, Sendable {
            public enum Kind: String, Codable, Hashable, Sendable {
                case permission, gate

                public init(from decoder: Decoder) throws {
                    let raw = try decoder.singleValueContainer().decode(String.self)
                    self = Kind(rawValue: raw) ?? .permission
                }
            }

            public var roomId: String
            public var eventId: String
            public var agent: String
            public var kind: Kind
            /// At most 120 characters.
            public var question: String
            /// At most two, and only those a button may send.
            public var options: [Option]

            public init(
                roomId: String, eventId: String, agent: String, kind: Kind, question: String,
                options: [Option]
            ) {
                self.roomId = roomId
                self.eventId = eventId
                self.agent = agent
                self.kind = kind
                self.question = question
                self.options = options
            }

            enum CodingKeys: String, CodingKey {
                case roomId, eventId, agent, kind, question, options
            }

            public init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: CodingKeys.self)
                roomId = try c.decode(String.self, forKey: .roomId)
                eventId = try c.decode(String.self, forKey: .eventId)
                agent = (try? c.decodeIfPresent(String.self, forKey: .agent)) ?? ""
                kind = (try? c.decodeIfPresent(Kind.self, forKey: .kind)) ?? .permission
                question = (try? c.decodeIfPresent(String.self, forKey: .question)) ?? ""
                options = Lossy.decode([Option].self, from: c, forKey: .options)
            }
        }

        /// At most three: needs you first, then working, then most recent.
        public var agents: [Agent]
        /// Active agents not listed.
        public var more: Int
        public var decision: Decision?
        /// Decisions pending in all.
        public var needsYou: Int
        public var working: Int
        /// When the hub built this — Unix seconds.
        public var updatedAt: Double

        public var updated: Date { Date(timeIntervalSince1970: updatedAt) }

        public init(
            agents: [Agent], more: Int = 0, decision: Decision? = nil, needsYou: Int = 0,
            working: Int = 0, updatedAt: Double
        ) {
            self.agents = agents
            self.more = more
            self.decision = decision
            self.needsYou = needsYou
            self.working = working
            self.updatedAt = updatedAt
        }

        enum CodingKeys: String, CodingKey {
            case agents, more, decision, needsYou, working, updatedAt
        }

        public init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            agents = Lossy.decode([Agent].self, from: c, forKey: .agents)
            more = (try? c.decodeIfPresent(Int.self, forKey: .more)) ?? 0
            decision = try? c.decodeIfPresent(Decision.self, forKey: .decision)
            needsYou = (try? c.decodeIfPresent(Int.self, forKey: .needsYou)) ?? 0
            working = (try? c.decodeIfPresent(Int.self, forKey: .working)) ?? 0
            updatedAt = (try? c.decodeIfPresent(Double.self, forKey: .updatedAt)) ?? 0
        }

        /// The fleet in one line: "2 working · 1 needs you", "All quiet" —
        /// worded as `core::widget`'s pulse words the Home Screen's.
        public var pulse: String {
            let asks: String? =
                switch needsYou {
                case 0: nil
                case 1: "1 needs you"
                default: "\(needsYou) need you"
                }
            switch (working, asks) {
            case (0, nil): return "All quiet"
            case (0, let asks?): return asks
            case (let n, nil): return "\(n) working"
            case (let n, let asks?): return "\(n) working · \(asks)"
            }
        }
    }

    /// The Matrix user whose fleet this is — the station owner the hub pushes
    /// for.
    public var readerId: String

    public init(readerId: String) {
        self.readerId = readerId
    }
}

/// An array whose unreadable elements are skipped rather than failing the
/// whole decode — one malformed agent must not blank the card.
private enum Lossy {
    private struct Element<T: Decodable>: Decodable {
        let value: T?
        init(from decoder: Decoder) throws { value = try? T(from: decoder) }
    }

    static func decode<T: Decodable, K: CodingKey>(
        _: [T].Type, from container: KeyedDecodingContainer<K>, forKey key: K
    ) -> [T] {
        guard let elements = try? container.decodeIfPresent([Element<T>].self, forKey: key) else {
            return []
        }
        return elements.compactMap(\.value)
    }
}
#endif
