import Testing

@testable import SupermessageKit

/// Closing the markup an unfinished streaming line leaves open.
struct OpenMarkupTests {
    @Test("an open bold is closed, so it is bold from its first word")
    func bold() {
        #expect(OpenMarkup.close("**What I obse") == "**What I obse**")
    }

    @Test("closed markup is left alone")
    func closed() {
        #expect(OpenMarkup.close("**done** and _this_") == "**done** and _this_")
    }

    @Test("closers nest innermost first")
    func nesting() {
        #expect(OpenMarkup.close("**bold and *ital") == "**bold and *ital***")
    }

    @Test("a marker with nothing after it yet is dropped, not closed")
    func bareMarker() {
        #expect(OpenMarkup.close("word **") == "word ")
        #expect(OpenMarkup.close("word *") == "word ")
    }

    @Test("closers go before trailing space, where CommonMark can close")
    func trailingSpace() {
        #expect(OpenMarkup.close("**bold words ") == "**bold words** ")
    }

    @Test("an open code span is closed and nothing inside it counts")
    func code() {
        #expect(OpenMarkup.close("run `make **all") == "run `make **all`")
        #expect(OpenMarkup.close("`a*b` then **x") == "`a*b` then **x**")
    }

    @Test("snake_case is not emphasis")
    func snakeCase() {
        #expect(OpenMarkup.close("call peer_color_index now") == "call peer_color_index now")
        #expect(OpenMarkup.close("set user_id") == "set user_id")
    }

    @Test("a list bullet is not emphasis")
    func bullet() {
        #expect(OpenMarkup.close("* first item") == "* first item")
    }
}
