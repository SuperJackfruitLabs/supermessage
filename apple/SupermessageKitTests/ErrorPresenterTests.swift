import Testing

@testable import SupermessageKit
import SupermessageFFI

struct ErrorPresenterTests {
    /// Every variant the boundary can produce.
    static let all: [FfiError] = [
        .Auth(detail: "m"), .Network(detail: "m"), .Store(detail: "m"),
        .Protocol(detail: "m"), .NotReady,
        .RoomChanged(requested: "!a:x", focused: "!b:x"),
        .AttachmentTooLarge(bytes: 9_000_000, limit: 5_000_000),
        .UnknownAttachment, .UnknownSpace(spaceId: "!s:x"),
        .ReasonTooLong(length: 2105, limit: 2000),
        .Refused(detail: "This homeserver doesn't accept message reports. Nothing was sent."),
    ]

    @Test("every error variant has something a person can read")
    func everyVariantHasAMessage() {
        // A missing one renders an empty alert, which reads as the app being
        // broken rather than the network being down.
        for error in Self.all {
            #expect(ErrorPresenter.message(for: error).isEmpty == false, "\(error)")
        }
    }

    @Test("only an auth failure means the session is gone")
    func onlyAuthSignsOut() {
        // Treating a network failure as a sign-out throws away a working
        // session every time a train enters a tunnel.
        #expect(ErrorPresenter.isAuthFailure(.Auth(detail: "m")))
        for error in Self.all {
            if case .Auth = error { continue }
            #expect(!ErrorPresenter.isAuthFailure(error), "\(error)")
        }
        #expect(!ErrorPresenter.isAuthFailure(.Network(detail: "m")))
        #expect(!ErrorPresenter.isAuthFailure(.NotReady))
    }

    @Test("a too-large attachment says both numbers")
    func attachmentSizeIsSpecific() {
        // "Too large" without the limit leaves the reader guessing how much to
        // cut. Both numbers is the whole value of the message.
        let text = ErrorPresenter.message(
            for: .AttachmentTooLarge(bytes: 9_000_000, limit: 5_000_000))
        #expect(text.contains("9"))
        #expect(text.contains("5"))
    }

    @Test("a too-long report reason says how far over it is")
    func reasonTooLongSaysByHowMuch() {
        // The text is still in the field; the reader needs to know how much to
        // cut, not only that something is wrong.
        let text = ErrorPresenter.message(for: .ReasonTooLong(length: 2105, limit: 2000))
        // "is 105 ", not "105": the full length, 2105, also contains 105.
        #expect(text.contains("is 105 "))
        #expect(text.contains("2000"))
    }

    @Test("a refusal worded by the core is shown as it is")
    func refusedIsShownVerbatim() {
        // Unlike `Protocol`, whose detail is the SDK's and gets replaced, this
        // one was written for the reader — replacing it would throw away
        // "Nothing was sent".
        let detail = "This homeserver doesn't accept person reports. Nothing was sent."
        #expect(ErrorPresenter.message(for: .Refused(detail: detail)) == detail)
    }

    @Test("still-connecting is not worth interrupting anyone for")
    func notReadyIsQuiet() {
        // It happens on every cold start before sync comes up.
        #expect(!ErrorPresenter.isWorthSurfacing(.NotReady))
        #expect(ErrorPresenter.isWorthSurfacing(.Network(detail: "m")))
    }
}
