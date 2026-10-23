package dev.supermessage.kit.notify

import dev.supermessage.kit.Session

/**
 * Whether to put the system's notification-permission prompt in front of the
 * reader now.
 *
 * Ports `Supermessage/Platform/PlatformCoordinator.swift:124-131` and
 * `:161-173`: the ask happens **after a fresh sign-in, never at launch**.
 * iOS spells that `justSignedIn = lastPhase == .signedOut`; a restored session
 * goes `STARTING → SIGNED_IN` and so never prompts.
 *
 * `POST_NOTIFICATIONS` is a runtime permission only from API 33. On 31 and 32
 * (this app's minSdk is 31) notifications are allowed without one, so there is
 * nothing to ask.
 */
fun shouldAskForNotifications(
    previous: Session.Phase?,
    now: Session.Phase,
    sdkInt: Int,
    granted: Boolean,
): Boolean =
    previous == Session.Phase.SIGNED_OUT &&
        now == Session.Phase.SIGNED_IN &&
        sdkInt >= 33 &&
        !granted
