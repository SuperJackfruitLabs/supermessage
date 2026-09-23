import Foundation
import Observation
import SupermessageFFI

/// Who is in the room on screen, as the composer and the typing line need it.
///
/// Built from two things the core already answers — `knownPeople` (everyone
/// this account shares a room with, agents first, never this account itself)
/// and the room's member ids — and deciding nothing about either. The order
/// is the core's; the matching is the core's (`peopleMatching`); the
/// mentions are the core's (`collectMentions`). What is left is the join.
@MainActor
@Observable
public final class RoomCast {
    /// The one participant a room's header is about, under the header's name.
    public struct Counterpart: Equatable, Sendable {
        public let userId: String
        public let name: String

        public init(userId: String, name: String) {
            self.userId = userId
            self.name = name
        }
    }

    public private(set) var roomId: String?
    /// The room's other members, in the core's order — agents first.
    public private(set) var people: [PersonDto] = []
    /// See ``counterpart(among:headerName:isAgentRoom:)``.
    public private(set) var counterpart: Counterpart?

    public init() {}

    /// Load the cast for `roomId`.
    ///
    /// Closures rather than a client, because the calls are `Session`'s —
    /// `people()` and `roomInfo(_:)` — and a view has a session, not a core.
    /// `memberIds` answering `nil` (the room's detail could not be read)
    /// falls back to everyone known: a picker offering too many people is a
    /// smaller fault than one offering nobody. No counterpart is guessed in
    /// that case, because without the members there is no knowing who is in
    /// the room.
    public func load(
        roomId: String, headerName: String?, isAgentRoom: Bool,
        people allPeople: @MainActor () async -> [PersonDto],
        memberIds: @MainActor () async -> [String]?
    ) async {
        let everyone = await allPeople()
        let members = await memberIds()
        let inRoom: [PersonDto]
        if let members {
            let ids = Set(members)
            inRoom = everyone.filter { ids.contains($0.userId) }
        } else {
            inRoom = everyone
        }
        self.roomId = roomId
        people = inRoom
        counterpart =
            members == nil
            ? nil : Self.counterpart(among: inRoom, headerName: headerName, isAgentRoom: isAgentRoom)
    }

    /// Who the header is naming, if it names one participant.
    ///
    /// An agent's room — one whose topic carries a runtime — is named for its
    /// agent: "Atlas — reviewer" in the header, while the agent's own profile
    /// may say `atlas-bot (claude @ ci)` and the typing notice carried
    /// whatever the member store had cached. That is D11: one agent, three
    /// names. So when the room is an agent's and exactly one agent is in it,
    /// that agent *is* the header, and is called what the header calls it.
    ///
    /// Anything else — a room with two agents, a room of people — has no
    /// counterpart, and everyone keeps the name the core gave them.
    public static func counterpart(
        among people: [PersonDto], headerName: String?, isAgentRoom: Bool
    ) -> Counterpart? {
        guard isAgentRoom, let headerName, !headerName.isEmpty else { return nil }
        let agents = people.filter { $0.runtime != nil }
        guard agents.count == 1 else { return nil }
        return Counterpart(userId: agents[0].userId, name: headerName)
    }

    /// The agents in the room, by id.
    public var agentIds: Set<String> {
        Set(people.filter { $0.runtime != nil }.map(\.userId))
    }

    /// What the typing store needs to know about this room.
    public var cast: TypingStore.Cast {
        TypingStore.Cast(agentIds: agentIds, counterpart: counterpart)
    }

    /// Who a message sent here is *for*, when that is one agent: the
    /// counterpart, or the only agent present. `nil` in a room with no agent
    /// or several — "Sent to" needs one name to be true.
    public var addressee: String? {
        if let counterpart { return counterpart.name }
        let agents = people.filter { $0.runtime != nil }
        return agents.count == 1 ? agents[0].name : nil
    }

    /// The people an `@` query offers, agents first, by the core's matching.
    public func candidates(matching query: String, limit: Int = 6) -> [PersonDto] {
        Array(SupermessageFFI.peopleMatching(people: people, query: query).prefix(limit))
    }

    /// Everyone a message here could address, labelled exactly as the picker
    /// inserts them — `collectMentions` matches on `@` + that label, so the
    /// two must be the same string or a mention written one way is read
    /// another.
    public var mentionables: [Mentionable] {
        people.map { Mentionable(userId: $0.userId, displayName: MentionComposing.label(for: $0)) }
    }
}
