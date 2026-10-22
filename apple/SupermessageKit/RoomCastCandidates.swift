import SupermessageFFI

extension RoomCast {
    /// The people an `@` query offers, agents first, by the core's matching.
    public func candidates(matching query: String, limit: Int = 6) -> [PersonDto] {
        Array(SupermessageFFI.peopleMatching(people: people, query: query).prefix(limit))
    }
}
