package dev.supermessage.kit

import java.util.Locale
import uniffi.supermessage_ffi.FfiException

/**
 * What a person is told when the core refuses.
 *
 * One place, so no view invents its own wording — and an exhaustive `when`
 * with **no `else`**, so a new variant on the boundary breaks this build
 * rather than silently falling through to a generic apology.
 *
 * Ported from `apple/SupermessageKit/ErrorPresenter.swift`. The boundary type
 * is UniFFI's generated `FfiException` here rather than Swift's `FfiError` —
 * a sealed class extending `Exception`, since UniFFI generates Kotlin errors
 * as `Exception` subclasses. Its detail field is named `detail`, not
 * `message`: `message` is `Throwable.message`, already taken.
 */
object ErrorPresenter {
    fun message(error: FfiException): String = when (error) {
        is FfiException.Auth ->
            // `Auth` carries two very different situations — the core's own
            // doc says so: "Credentials were refused, or the session is no
            // longer valid." The second reads fine as "Signed out"; the first
            // is someone standing at the sign-in screen being told they were
            // signed out, which explains nothing and is not even true.
            //
            // So show the homeserver's own words when it gave any, exactly as
            // the `Network` branch below does and for the same reason. A
            // refused password says "Invalid password"; a deactivated account
            // says so; rate limiting says so. Discarding that left the one
            // screen where a reason matters most with no reason at all.
            if (error.detail.isEmpty()) "Signed out. Sign in again to continue." else error.detail

        is FfiException.Network ->
            // The homeserver's own words when it has any: "connection
            // refused" tells an operator more than "something went wrong"
            // ever will.
            if (error.detail.isEmpty()) "Can't reach the homeserver." else error.detail

        is FfiException.Store -> "Couldn't read this device's local store."

        is FfiException.Protocol -> "The homeserver sent something this app didn't understand."

        // Ordinary during startup, and not worth alarming anyone over.
        is FfiException.NotReady -> "Still connecting."

        // The guard that stops a message landing in whichever room ended up
        // focused. Nothing was sent, which is the useful half.
        is FfiException.RoomChanged -> "That room is no longer open — nothing was sent."

        is FfiException.AttachmentTooLarge -> {
            val size = formatBytes(error.bytes)
            val cap = formatBytes(error.limit)
            "That file is $size; the limit is $cap."
        }

        is FfiException.UnknownAttachment -> "That attachment is no longer staged."

        is FfiException.UnknownSpace -> "That space is no longer in your account."
    }

    /**
     * Whether this error means the session is gone.
     *
     * Only [FfiException.Auth]. A network failure is not a sign-out, and
     * treating it as one would throw away a working session every time a
     * train enters a tunnel.
     */
    fun isAuthFailure(error: FfiException): Boolean = error is FfiException.Auth

    /**
     * Whether this is worth telling anyone about at all.
     *
     * [FfiException.NotReady] happens on every cold start before sync comes
     * up, and a [FfiException.RoomChanged] is already visible — the room the
     * reader is looking at is not the one they typed into.
     */
    fun isWorthSurfacing(error: FfiException): Boolean = when (error) {
        is FfiException.NotReady -> false
        else -> true
    }

    /**
     * A plain decimal byte count — "9 MB", "512 KB" — matching the shape of
     * Swift's `ByteCountFormatter(.file)` closely enough that a reader sees
     * the same two numbers on both platforms. Not a general-purpose
     * formatter: this exists only so [FfiException.AttachmentTooLarge] can
     * say both sizes.
     */
    private fun formatBytes(bytes: ULong): String {
        val units = arrayOf("bytes", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1000.0 && unitIndex < units.lastIndex) {
            value /= 1000.0
            unitIndex += 1
        }
        val rounded = value.toLong()
        // Locale.ROOT: a decimal comma here would be a locale bug this app's
        // tests would never catch, since the JVM test runner's default
        // locale is not every reader's device locale.
        val number = if (value == rounded.toDouble()) {
            rounded.toString()
        } else {
            String.format(Locale.ROOT, "%.1f", value)
        }
        return "$number ${units[unitIndex]}"
    }
}
