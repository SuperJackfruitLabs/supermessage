package dev.supermessage.kit

/**
 * What happened to a send, told apart by what the composer does next
 * (`Outbox.swift:5-19`).
 */
sealed class SendResult {
    /** Everything that was in the composer went. */
    data object Sent : SendResult()

    /** There was nothing to send: an empty field and no chip. */
    data object NothingToSend : SendResult()

    /**
     * The attachment did not go, **so the text did not either**. The reason is
     * on the chip (`StagedAttachment.failure`), and the draft stays.
     */
    data class AttachmentFailed(val message: String) : SendResult()

    /** The text did not go. Any attachment already had; the draft stays. */
    data class TextFailed(val message: String) : SendResult()

    /** Whether something reached the room, which the send haptic keys off. */
    val delivered: Boolean get() = this is Sent
}
