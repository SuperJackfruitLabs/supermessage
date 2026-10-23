package dev.supermessage.kit

/**
 * Where a streaming answer stops being markdown and becomes the line still
 * being written. Mirrors `StreamingRichView.split` in
 * `apple/Supermessage/Timeline/LiveTurnView.swift`.
 *
 * This only decides *where to cut*; the markdown itself is parsed by the
 * core's `richBlocksFromMarkdown`, never here.
 */
object StreamingMarkdown {
    private val blockOpeners = listOf("#", "- ", "* ", "+ ", ">", "```", "|")

    /**
     * Everything before the arriving chunk's paragraph (`first`), and that
     * paragraph (`second`).
     *
     * Splits at the last newline *before* the arriving chunk, not the last
     * newline: a sentence that ends its paragraph arrives with its line
     * break, and cutting after it would send it straight into the parsed
     * blocks without it ever fading in. Block markup is the exception, since
     * it cannot be shown as plain text without reading as raw markup, so
     * such a line settles as soon as it is whole.
     */
    fun split(text: String, arriving: Int): Pair<String, String> {
        val start = text.length - arriving.coerceIn(0, text.length)
        val newline = text.lastIndexOf('\n', startIndex = start - 1)
        if (newline < 0) {
            return if (blockMarkup(text)) splitAtLastNewline(text) else "" to trimmedTail(text)
        }
        val tail = text.substring(newline + 1)
        if (blockMarkup(tail)) return splitAtLastNewline(text)
        return text.substring(0, newline).trim('\n', '\r') to trimmedTail(tail)
    }

    /** Whether any line of [text] opens a block. */
    fun blockMarkup(text: String): Boolean = text.split('\n').any { raw ->
        val line = raw.trimStart(' ')
        if (blockOpeners.any { line.startsWith(it) }) return@any true
        val digits = line.takeWhile { it.isDigit() }
        digits.isNotEmpty() && line.substring(digits.length).startsWith(". ")
    }

    private fun splitAtLastNewline(text: String): Pair<String, String> {
        val newline = text.lastIndexOf('\n')
        if (newline < 0) return "" to text
        return text.substring(0, newline).trim('\n', '\r') to text.substring(newline + 1)
    }

    /** A tail's trailing line breaks would draw as empty lines under it. */
    private fun trimmedTail(tail: String): String = tail.trimEnd('\n')
}
