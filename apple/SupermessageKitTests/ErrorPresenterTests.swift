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

    @Test("still-connecting is not worth interrupting anyone for")
    func notReadyIsQuiet() {
        // It happens on every cold start before sync comes up.
        #expect(!ErrorPresenter.isWorthSurfacing(.NotReady))
        #expect(ErrorPresenter.isWorthSurfacing(.Network(detail: "m")))
    }
}
