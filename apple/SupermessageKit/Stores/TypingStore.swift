import Foundation
import Observation
import SupermessageFFI

/// Who is typing in the focused room.
///
/// Scoped to that room because the channel is: the core only reports typing
/// for the room it has focused, so there is nothing to show on a roster row
/// and pretending otherwise would invent it.
@MainActor
@Observable
public final class TypingStore {
    /// Who, by **user id**, and what to call them.
    ///
    /// Keyed on the id rather than the name, because the name is not an
    /// identity: the core hands out `label` ("Super Chotu") for the line and
    /// a message arrives carrying `senderName` ("Super Chotu (Hermes on
    /// Guild)"), and matching one against the other is how the indicator got
    /// stuck. Two strings that describe the same person are not the same
    /// string; the id is.
    public private(set) var typers: [TypingUserDto] = []

    private var roomId: String?

    public init() {}

    public func handle(roomId: String, users: [TypingUserDto]) {
        guard roomId == self.roomId else { return }
        typers = users
    }

    /// Someone spoke, so they are no longer about to.
    ///
    /// Matrix typing notices expire on a server-side timeout, and a sender
    /// that never explicitly retracts one leaves the line up for as long as
    /// that timeout runs — which is why "X is typing…" sat on screen long
    /// after X's message had arrived. The client does not have to wait for
    /// the timeout: the message is better evidence than the notice.
    ///
    /// **Takes user ids.** It used to take display names, and the names it
    /// was given were the timeline's composed attribution while the ones it
    /// held were the raw profile names — so nothing ever matched and nothing
    /// was ever removed. The bug was invisible because the code read as
    /// though it did the right thing.
    ///
    /// Deliberately not latching. An agent that sends one message and starts
    /// writing the next is typing again, and the next notice must be able to
    /// bring the line back.
    public func messagesArrived(from senderIds: [String]) {
        guard !typers.isEmpty else { return }
        typers.removeAll { senderIds.contains($0.userId) }
    }

    public func focus(_ roomId: String?) {
        self.roomId = roomId
        typers = []
        // Signing out focuses nothing, and nothing from that account may
        // survive into the next one.
        if roomId == nil {
            casts.removeAll()
        }
    }

    // MARK: - Who is who

    /// Keyed by room rather than reset by `focus`, because who calls which is
    /// a race: the room opens and the cast loads concurrently, and a `focus`
    /// landing after the cast would otherwise throw it away.
    private var casts: [String: RoomCast] = [:]

    /// Record who is in `roomId`: which of them are agents, and who — if
    /// anyone — the header is naming.
    public func recognise(_ cast: RoomCast, in roomId: String) {
        casts[roomId] = cast
    }

    private var cast: RoomCast? { roomId.flatMap { casts[$0] } }

    /// The core's reading of who is typing: the people's line, and the agents
    /// by name (D11: one person, one name — the header's, when it is about
    /// them). Nothing is decided here.
    private var view: TypingView {
        SupermessageFFI.typingView(
            typers: typers, agentIds: cast?.agentIds ?? [], counterpart: cast?.counterpart)
    }

    /// The agents typing right now, by name. Not part of ``line``: an agent
    /// that is typing is *working*, and that is the acknowledgement dock's
    /// sentence — "Atlas is on it…" — rather than a typing notice.
    public var typingAgents: [String] { view.agents }

    /// The line to show, or `nil` when nobody is typing.
    ///
    /// Names rather than a count: in a room of agents, *which* one is about to
    /// speak is the useful half. **People only** — agents are in
    /// ``typingAgents``, said by the dock.
    public var line: String? { view.line }
}
