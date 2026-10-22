package dev.supermessage.kit

import uniffi.supermessage_core.PersonDto

/**
 * The `@` picker's half of mentions: the query being typed, and inserting a
 * completion (`MentionComposing.swift:11-66`).
 *
 * Host-side on purpose, as `core::mentions` records: caret handling is input
 * UX. What goes on the wire, who a finished message addresses, is the core's
 * `collectMentions`; this object's only obligation to it is to insert exactly
 * the label it will look for.
 */
object MentionComposing {
    /** An `@` being typed at the end of the field, and what follows it. */
    data class Query(
        /** What follows the `@`; empty straight after typing it. */
        val text: String,
        /** Where the `@` is, as a UTF-16 offset. */
        val atOffset: Int,
    )

    /** The longest query still treated as a name being looked up. */
    const val LONGEST_QUERY = 40

    /**
     * The query at the end of [text], or `null` when the reader is not asking
     * for anyone: the last `@` must start the text or follow whitespace, with
     * no whitespace after it and at most [LONGEST_QUERY] characters.
     */
    fun activeQuery(text: String): Query? {
        val at = text.lastIndexOf('@')
        if (at < 0) return null
        if (at > 0) {
            // The character before, as a code point: an astral character is
            // never whitespace, and half of a surrogate pair must not be read.
            val before = text.codePointBefore(at)
            if (!Character.isWhitespace(before)) return null
        }
        val after = text.substring(at + 1)
        if (after.any { it.isWhitespace() }) return null
        if (after.codePointCount(0, after.length) > LONGEST_QUERY) return null
        return Query(text = after, atOffset = at)
    }

    /** What the picker writes after the `@`, and so what `collectMentions` looks for. */
    fun label(person: PersonDto): String = person.name

    /** [text] with [query] replaced by [person]'s mention and a space. */
    fun insert(person: PersonDto, query: Query, text: String): String {
        if (query.atOffset !in 0 until text.length) return text
        return text.substring(0, query.atOffset) + "@" + label(person) + " "
    }
}
