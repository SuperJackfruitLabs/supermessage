import SupermessageFFI

// Every DTO the core hands across the boundary, declared `Sendable`.
//
// **Why this file exists.** `SupermessageFFI` compiles in Swift 5 mode, so
// nothing in it carries a concurrency annotation. The Kit compiles under
// Swift 6 strict concurrency, where a value returned out of `Task.detached`
// must be `Sendable` — so without these the actor cannot return a room list.
//
// **Why it is sound.** Every type below is a value type whose stored
// properties are `String`, integers, `Bool`, `Data`, or arrays and optionals
// of the same. A copy crosses; nothing is shared. The `var` on a UniFFI
// record's fields is not shared mutable state — it is a mutable copy, which
// is exactly what `Sendable` permits.
//
// **Why `@unchecked`.** A retroactive conformance across a module boundary
// cannot be checked by the compiler, so it has to be asserted. That is a real
// promise, and the paragraph above is what backs it: if a future DTO gains a
// reference type — a class, a closure — it must not be added to this list.
//
// **`Core` is the one reference type here, and it belongs.** It is a class,
// so the value-type argument above does not apply to it — a different one
// does. UniFFI requires an exported object to be `Send + Sync` on the Rust
// side: its methods take `&self` and it is handed out behind an `Arc`, so
// calling one from several threads at once is what the binding is built for.
// `CoreClient` serialises calls anyway, by being an actor; this conformance
// is what lets it hand the object to `Task.detached` to get them off the main
// thread in the first place.
extension Core: @retroactive @unchecked Sendable {}

extension ConnectionState: @retroactive @unchecked Sendable {}
extension CustomEventDecision: @retroactive @unchecked Sendable {}
extension CustomEventDecisionOption: @retroactive @unchecked Sendable {}
extension CustomEventField: @retroactive @unchecked Sendable {}
extension CustomEventView: @retroactive @unchecked Sendable {}
extension FfiError: @retroactive @unchecked Sendable {}
extension FfiEvent: @retroactive @unchecked Sendable {}
extension ItemView: @retroactive @unchecked Sendable {}
extension MatrixLinkTarget: @retroactive @unchecked Sendable {}
extension MediaFileLabel: @retroactive @unchecked Sendable {}
extension NotificationMode: @retroactive @unchecked Sendable {}
extension MediaMetaDto: @retroactive @unchecked Sendable {}
extension Membership: @retroactive @unchecked Sendable {}
extension Mentionable: @retroactive @unchecked Sendable {}
extension ReactionDto: @retroactive @unchecked Sendable {}
extension ReplyQuoteView: @retroactive @unchecked Sendable {}
extension ReplyToDto: @retroactive @unchecked Sendable {}
extension RichBlock: @retroactive @unchecked Sendable {}
extension RichInline: @retroactive @unchecked Sendable {}
extension RichListItem: @retroactive @unchecked Sendable {}
extension RichTableCell: @retroactive @unchecked Sendable {}
extension RichTableRow: @retroactive @unchecked Sendable {}
extension RoomAffordance: @retroactive @unchecked Sendable {}
extension RoomDiffEnvelope: @retroactive @unchecked Sendable {}
extension RoomDiffOp: @retroactive @unchecked Sendable {}
extension RoomIdentity: @retroactive @unchecked Sendable {}
extension RoomInfoDto: @retroactive @unchecked Sendable {}
extension RuntimeDto: @retroactive @unchecked Sendable {}
extension AccountDto: @retroactive @unchecked Sendable {}
extension RoomMemberDto: @retroactive @unchecked Sendable {}
extension RoomPreview: @retroactive @unchecked Sendable {}
extension RoomRow: @retroactive @unchecked Sendable {}
extension RoomsSnapshot: @retroactive @unchecked Sendable {}
extension RoomSummary: @retroactive @unchecked Sendable {}
extension SearchResultDto: @retroactive @unchecked Sendable {}
extension SpaceSummary: @retroactive @unchecked Sendable {}
extension StagedFile: @retroactive @unchecked Sendable {}
extension TimelineDiffEnvelope: @retroactive @unchecked Sendable {}
extension TimelineDiffOp: @retroactive @unchecked Sendable {}
extension TimelineItemDto: @retroactive @unchecked Sendable {}
extension TimelineRow: @retroactive @unchecked Sendable {}
extension TimelineSnapshot: @retroactive @unchecked Sendable {}
extension TypingUserDto: @retroactive @unchecked Sendable {}
