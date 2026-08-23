package dev.supermessage.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.supermessage_ffi.FfiException

/**
 * Ported from `apple/SupermessageKitTests/ErrorPresenterTests.swift`.
 */
class ErrorPresenterTest {

    /** Every variant the boundary can produce. */
    private val all: List<FfiException> = listOf(
        FfiException.Auth("m"),
        FfiException.Network("m"),
        FfiException.Store("m"),
        FfiException.Protocol("m"),
        FfiException.NotReady(),
        FfiException.RoomChanged(requested = "!a:x", focused = "!b:x"),
        FfiException.AttachmentTooLarge(bytes = 9_000_000uL, limit = 5_000_000uL),
        FfiException.UnknownAttachment(),
        FfiException.UnknownSpace("!s:x"),
    )

    /** "every error variant has something a person can read" */
    @Test
    fun everyVariantHasAMessage() {
        // A missing one renders an empty alert, which reads as the app being
        // broken rather than the network being down.
        for (error in all) {
            assertFalse("$error", ErrorPresenter.message(error).isEmpty())
        }
    }

    /** "only an auth failure means the session is gone" */
    @Test
    fun onlyAuthSignsOut() {
        // Treating a network failure as a sign-out throws away a working
        // session every time a train enters a tunnel.
        assertTrue(ErrorPresenter.isAuthFailure(FfiException.Auth("m")))
        for (error in all) {
            if (error is FfiException.Auth) continue
            assertFalse("$error", ErrorPresenter.isAuthFailure(error))
        }
        assertFalse(ErrorPresenter.isAuthFailure(FfiException.Network("m")))
        assertFalse(ErrorPresenter.isAuthFailure(FfiException.NotReady()))
    }

    /** "a too-large attachment says both numbers" */
    @Test
    fun attachmentSizeIsSpecific() {
        // "Too large" without the limit leaves the reader guessing how much
        // to cut. Both numbers is the whole value of the message.
        val text = ErrorPresenter.message(
            FfiException.AttachmentTooLarge(bytes = 9_000_000uL, limit = 5_000_000uL),
        )
        assertTrue(text.contains("9"))
        assertTrue(text.contains("5"))
    }

    /** "still-connecting is not worth interrupting anyone for" */
    @Test
    fun notReadyIsQuiet() {
        // It happens on every cold start before sync comes up.
        assertFalse(ErrorPresenter.isWorthSurfacing(FfiException.NotReady()))
        assertTrue(ErrorPresenter.isWorthSurfacing(FfiException.Network("m")))
    }

    /** An Auth failure with no detail still reads as a session expiry. */
    @Test
    fun anAuthFailureWithoutDetailKeepsTheSignedOutWording() {
        assertEquals(
            "Signed out. Sign in again to continue.",
            ErrorPresenter.message(FfiException.Auth("")),
        )
    }

    /** With a detail, the homeserver's own reason is what the reader sees. */
    @Test
    fun anAuthFailureWithDetailShowsTheReason() {
        assertEquals("Invalid password", ErrorPresenter.message(FfiException.Auth("Invalid password")))
    }
}
