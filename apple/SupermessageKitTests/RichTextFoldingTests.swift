import Foundation
import Testing

@testable import SupermessageKit
import SupermessageFFI

struct RichTextFoldingTests {
    @Test("nested emphasis keeps its text and its trait")
    func nestedInlinesFold() {
        let folded = RichTextFolding.attributed([
            .text(text: "a "),
            .strong(inlines: [.text(text: "b"), .emphasis(inlines: [.text(text: "c")])]),
        ])
        #expect(String(folded.characters) == "a bc")
        // The text surviving is the half that matters — a fold that dropped
        // the nesting would lose the words inside it, which is exactly the bug
        // the Rust side had in a tight list.
        #expect(folded.runs.contains { $0.inlinePresentationIntent?.contains(.stronglyEmphasized) == true })
    }

    @Test("a link keeps its destination")
    func linkKeepsItsHref() {
        let folded = RichTextFolding.attributed([
            .link(href: "https://e.org/x", inlines: [.text(text: "go")])
        ])
        #expect(String(folded.characters) == "go")
        #expect(folded.runs.contains { $0.link?.absoluteString == "https://e.org/x" })
    }

    @Test("a link with an unusable destination keeps its words")
    func brokenLinkKeepsText() {
        // Losing the target is a degradation; losing the sentence is a bug.
        let folded = RichTextFolding.attributed([
            .link(href: "", inlines: [.text(text: "still readable")])
        ])
        #expect(String(folded.characters) == "still readable")
    }

    @Test("inline code is marked as code, not as plain text")
    func codeIsMarked() {
        let folded = RichTextFolding.attributed([.code(text: "cargo test")])
        #expect(String(folded.characters) == "cargo test")
        #expect(folded.runs.contains { $0.inlinePresentationIntent?.contains(.code) == true })
    }

    @Test("a break is a newline, so a hard-wrapped message stays wrapped")
    func breakIsANewline() {
        let folded = RichTextFolding.attributed([
            .text(text: "one"), .break, .text(text: "two"),
        ])
        #expect(String(folded.characters) == "one\ntwo")
    }

    @Test("an empty run folds to nothing rather than to a space")
    func emptyIsEmpty() {
        #expect(String(RichTextFolding.attributed([]).characters).isEmpty)
    }
}
