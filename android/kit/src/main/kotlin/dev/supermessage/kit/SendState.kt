package dev.supermessage.kit

import uniffi.supermessage_core.DeliveryState

/**
 * What happened to a message this account sent.
 *
 * The core says it as a typed [DeliveryState] (it used to be a string —
 * `"notSentYet"`, `"sendingFailed"`, `"sent"` — which a typo on either side
 * of the boundary could silently turn into "nothing to show"). This is the
 * reading of it for display. `UNKNOWN` stays as a case so a caller that has
 * no item to hand still has something to say; the enum itself cannot produce
 * it.
 *
 * **Only own messages have one.** A peer's message arrived, which is the
 * only send state a reader could want to know about it.
 */
enum class SendState {
    /** On its way. Worth showing only once it has been a while — a send that
     * lands immediately should not flicker a spinner at anyone. */
    SENDING,

    /** The homeserver has it. */
    SENT,

    /** It did not go. **The one state a reader must never miss**, because
     * the message is sitting on this phone looking exactly like one that
     * landed. */
    FAILED,

    /** A state this build has not been taught. Drawn as nothing rather than
     * guessed at. */
    UNKNOWN;

    /**
     * Whether a reader needs to be told.
     *
     * A message that landed is the unremarkable case and says nothing; every
     * bubble carrying a tick is chrome on the ordinary. Failure always shows.
     */
    val isWorthShowing: Boolean
        get() = when (this) {
            FAILED, SENDING -> true
            SENT, UNKNOWN -> false
        }

    /**
     * The words for it. Plain, because a symbol alone cannot say "tap to try
     * again" and this is the one place ambiguity costs a message.
     */
    val label: String?
        get() = when (this) {
            SENDING -> "Sending…"
            FAILED -> "Not sent"
            SENT, UNKNOWN -> null
        }

    companion object {
        /**
         * `null` is a message that arrived — every peer's message carries it,
         * and reading it as unknown would mark every incoming bubble.
         * Exhaustive over [DeliveryState], so a new core case breaks this
         * build rather than drawing as nothing.
         */
        operator fun invoke(state: DeliveryState?): SendState = when (state) {
            DeliveryState.NOT_SENT_YET -> SENDING
            DeliveryState.SENDING_FAILED -> FAILED
            DeliveryState.SENT -> SENT
            null -> SENT
        }
    }
}
