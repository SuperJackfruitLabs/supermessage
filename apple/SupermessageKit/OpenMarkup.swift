import Foundation

/// Closes the inline markdown left open at the end of an unfinished line.
///
/// A streaming line is parsed before it is whole, so `**What I obse` has a
/// bold that has not been closed yet. Parsed as it is, the reader sees two
/// asterisks that become bold a moment later; closed for the moment, it is
/// bold from its first word.
public enum OpenMarkup {
    /// `line` with its open markers closed, innermost first: an open code
    /// span, then `**`, `__`, `~~`, `*` and `_`, each counted outside code.
    ///
    /// A marker with nothing after it yet is dropped rather than closed:
    /// closing `word **` would render an empty emphasis at best and two
    /// literal asterisks at worst. Closers go before any trailing space,
    /// since CommonMark will not close emphasis after whitespace.
    public static func close(_ line: String) -> String {
        var text = line
        var trailing = ""
        while let last = text.last, last == " " || last == "\t" {
            trailing.insert(last, at: trailing.startIndex)
            text.removeLast()
        }

        // Code first: nothing inside a code span is markup.
        if text.filter({ $0 == "`" }).count % 2 == 1 {
            if text.hasSuffix("`") { text.removeLast() } else { text += "`" }
            return text + trailing
        }

        let outsideCode = stripCode(text)
        var closers = ""
        for marker in ["**", "__", "~~"] where occurrences(of: marker, in: outsideCode) % 2 == 1 {
            if text.hasSuffix(marker) {
                text.removeLast(marker.count)
            } else {
                closers = marker + closers
            }
        }
        let singles = outsideCode
            .replacingOccurrences(of: "**", with: "")
            .replacingOccurrences(of: "__", with: "")
        for marker: Character in ["*", "_"] where singles.filter({ $0 == marker }).count % 2 == 1 {
            // A lone `_` inside a word (snake_case) is not emphasis to
            // CommonMark, and closing it would italicise the rest.
            if marker == "_", !opensEmphasis(singles) { continue }
            // A `*` opening a list item is not emphasis either.
            if marker == "*", singles.hasPrefix("* "), singles.filter({ $0 == "*" }).count == 1 { continue }
            if text.last == marker {
                text.removeLast()
            } else {
                closers = String(marker) + closers
            }
        }
        return text + closers + trailing
    }

    private static func stripCode(_ text: String) -> String {
        var out = ""
        var inCode = false
        for c in text {
            if c == "`" {
                inCode.toggle()
                continue
            }
            if !inCode { out.append(c) }
        }
        return out
    }

    private static func occurrences(of marker: String, in text: String) -> Int {
        text.components(separatedBy: marker).count - 1
    }

    /// Whether the last `_` looks like the start of emphasis: at the start
    /// or after something that is not a letter or digit.
    private static func opensEmphasis(_ text: String) -> Bool {
        guard let last = text.lastIndex(of: "_") else { return false }
        guard last > text.startIndex else { return true }
        let before = text[text.index(before: last)]
        return !(before.isLetter || before.isNumber)
    }
}
