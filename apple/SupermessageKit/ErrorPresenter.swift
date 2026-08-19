import Foundation
import SupermessageFFI

/// What a person is told when the core refuses.
///
/// One place, so no view invents its own wording — and an exhaustive switch
/// with **no `default`**, so a new variant on the boundary breaks this build
/// rather than silently falling through to a generic apology.
public enum ErrorPresenter {
    public static func message(for error: FfiError) -> String {
        switch error {
        case .Auth:
            return "Signed out. Sign in again to continue."
        case let .Network(message):
            // The homeserver's own words when it has any: "connection refused"
            // tells an operator more than "something went wrong" ever will.
            return message.isEmpty ? "Can't reach the homeserver." : message
        case .Store:
            return "Couldn't read this device's local store."
        case .Protocol:
            return "The homeserver sent something this app didn't understand."
        case .NotReady:
            // Ordinary during startup, and not worth alarming anyone over.
            return "Still connecting."
        case .RoomChanged:
            // The guard that stops a message landing in whichever room ended
            // up focused. Nothing was sent, which is the useful half.
            return "That room is no longer open — nothing was sent."
        case let .AttachmentTooLarge(bytes, limit):
            let size = ByteCountFormatter.string(fromByteCount: Int64(bytes), countStyle: .file)
            let cap = ByteCountFormatter.string(fromByteCount: Int64(limit), countStyle: .file)
            return "That file is \(size); the limit is \(cap)."
        case .UnknownAttachment:
            return "That attachment is no longer staged."
        case .UnknownSpace:
            return "That space is no longer in your account."
        }
    }

    /// Whether this error means the session is gone.
    ///
    /// Only `Auth`. A network failure is not a sign-out, and treating it as
    /// one would throw away a working session every time a train enters a
    /// tunnel.
    public static func isAuthFailure(_ error: FfiError) -> Bool {
        if case .Auth = error { return true }
        return false
    }

    /// Whether this is worth telling anyone about at all.
    ///
    /// `NotReady` happens on every cold start before sync comes up, and a
    /// `RoomChanged` is already visible — the room the reader is looking at is
    /// not the one they typed into.
    public static func isWorthSurfacing(_ error: FfiError) -> Bool {
        switch error {
        case .NotReady: return false
        default: return true
        }
    }
}
