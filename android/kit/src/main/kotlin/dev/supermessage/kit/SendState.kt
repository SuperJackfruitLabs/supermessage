package dev.supermessage.kit

/**
 * What happened to a message this account sent.
 *
 * The core's vocabulary is a string — `"notSentYet"`, `"sendingFailed"`,
 * `"sent"` — and it stays a string on the wire for the reason
 * `ConnectionStore` gives: a value the core owns can gain a case without this
 * app failing to build. This is the reading of it, with `UNKNOWN` as what
 * that costs.
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
        operator fun invoke(raw: String?): SendState = when (raw) {
            "notSentYet" -> SENDING
            "sendingFailed" -> FAILED
            "sent" -> SENT
            null -> SENT
            else -> UNKNOWN
        }
    }
}
