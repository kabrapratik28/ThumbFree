import Testing
@testable import TFCore

@Suite struct CursorFormatterTests {
    struct Row: Sendable, CustomTestStringConvertible {
        let text: String, before: String?, after: String?, caps: Bool?
        let field: CursorFormatter.Field, trailing: Bool, expected: String
        var testDescription: String { "\(text.debugDescription) before \(before.debugDescription) after \(after.debugDescription)" }
        init(_ text: String, _ before: String?, _ after: String?, _ caps: Bool?, _ field: CursorFormatter.Field,
             _ trailing: Bool, _ expected: String) {
            (self.text, self.before, self.after, self.caps) = (text, before, after, caps)
            (self.field, self.trailing, self.expected) = (field, trailing, expected)
        }
    }

    static let rows: [Row] = [
        Row("hello world", "", "", true, .text, false, "Hello world"),
        Row("Hello", "I said.", "", true, .text, false, " Hello"),
        Row("hello", "I said. ", "", true, .text, false, "Hello"),
        Row("there", "Hello", "", false, .text, false, " there"),
        Row("Paris", "I went to", "", false, .text, false, " Paris"),
        Row("world", "Hello ", "", false, .text, false, "world"),
        Row("big", "Hello ", "world", false, .text, false, "big "),
        Row("big", "Hello ", " world", false, .text, false, "big"),
        Row("quote", "(", ")", false, .text, false, "quote"),
        Row(", okay", "Yes", "", false, .text, false, ", okay"),
        Row("mid", "abc", "def", false, .text, false, "mid"),
        Row("example.com", "www.", "", false, .url, false, "example.com"),
        Row("me@x.com", "mail ", "", false, .email, false, "me@x.com"),
        Row("done", "", "", nil, .text, true, "done "),
        Row("done", "", " next", nil, .text, true, "done"),
        Row("  padded  ", "", "", nil, .text, false, "padded"),
        Row("hi", nil, nil, nil, .text, false, "hi"),
        Row("hello", "line\n", "", true, .text, false, "Hello"),
        Row("😀 ok", "Hi", "", false, .text, false, " 😀 ok"),
        Row("iPhone", "", "", true, .text, false, "IPhone"), // a sentence start can change a brand's casing (accepted)
    ]

    @Test(arguments: rows)
    func payload(_ row: Row) {
        let actual = CursorFormatter.payload(text: row.text, before: row.before, after: row.after, capsExpected: row.caps,
                                             field: row.field, trailingSpace: row.trailing)
        TextTest.expectSame(actual, row.expected)
    }

    @Test func tableHasTheTwentyAndroidRows() {
        #expect(Self.rows.count == 20)
    }
}
