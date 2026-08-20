import Foundation
import SupermessageFFI

/// Inline runs, folded into an `AttributedString`.
///
/// In the Kit rather than beside the view, for the same reason
/// `TimelineFollow` is: it is the part with decisions in it, and a test can
/// reach it here. The block layout is a view's job and stays in the app.
///
/// Everything the core hands over is already parsed — this never looks at
/// markdown or HTML, only at the tree it was given.
public enum RichTextFolding {
    public static func attributed(_ inlines: [RichInline]) -> AttributedString {
        var result = AttributedString()
        for inline in inlines {
            result.append(fold(inline))
        }
        return result
    }

    private static func fold(_ inline: RichInline) -> AttributedString {
        switch inline {
        case let .text(text):
            return AttributedString(text)

        case let .emphasis(inlines):
            var inner = attributed(inlines)
            inner.inlinePresentationIntent = .emphasized
            return inner

        case let .strong(inlines):
            var inner = attributed(inlines)
            inner.inlinePresentationIntent = .stronglyEmphasized
            return inner

        case let .code(text):
            var inner = AttributedString(text)
            inner.inlinePresentationIntent = .code
            return inner

        case let .link(href, inlines):
            var inner = attributed(inlines)
            // A link with an unparseable destination keeps its text and loses
            // its target, rather than disappearing. The core already restricted
            // the schemes it will emit.
            if let url = URL(string: href) {
                inner.link = url
            }
            return inner

        case .break:
            return AttributedString("\n")
        }
    }
}
