import Testing
@testable import TFCore

@Suite struct UnicodeTextTests {
    @Test func whitespaceIsTheUnicodeWhiteSpaceSet() {
        let rust: [UInt32] = Array(0x09...0x0D) + [0x20, 0x85, 0xA0, 0x1680] + Array(0x2000...0x200A)
            + [0x2028, 0x2029, 0x202F, 0x205F, 0x3000]
        let found = (UInt32(0)...0x10FFFF).compactMap { Unicode.Scalar($0) }.filter(UnicodeText.isWhitespace).map(\.value)
        #expect(found == rust)
        // Java's isWhitespace gets these wrong: U+0085, U+00A0, U+2007 and U+202F split words, U+001C does not.
        #expect(UnicodeText.splitWhitespace(" a\u{85}b\u{A0}c\u{2007}d\u{202F}e\u{1C}f ") == ["a", "b", "c", "d", "e\u{1C}f"])
    }

    @Test(arguments: [
        ("a", true), ("É", true), ("你", true), ("5", true),
        ("Ⅻ", true), // letter number (Nl)
        ("\u{10348}", true), // a letter number outside the BMP
        ("²", true), ("½", true), ("①", true), // other numbers (No)
        ("\u{0903}", true), // Devanagari visarga, a mark that is Alphabetic
        ("\u{0301}", false), // combining acute, a mark that is not
        ("_", false), ("-", false), (" ", false), ("。", false),
    ])
    func alphanumericIsAlphabeticOrAnyNumber(_ c: String, _ expected: Bool) throws {
        #expect(UnicodeText.isAlphanumeric(try #require(c.unicodeScalars.first)) == expected)
    }

    @Test(arguments: [
        ("a", true), ("ä", true), ("х", true), ("5", true), ("Ⅻ", true),
        ("_", true), // connector punctuation
        ("\u{0301}", true), // any mark
        ("\u{200D}", true), // zero width joiner
        ("²", false), // other number
        ("-", false), (" ", false), ("。", false), ("😀", false),
    ])
    func wordCharIsRustRegexW(_ c: String, _ expected: Bool) throws {
        #expect(UnicodeText.isWordChar(try #require(c.unicodeScalars.first)) == expected)
    }

    @Test func trimLowercaseNfcAndSame() {
        TextTest.expectSame(UnicodeText.trim("\u{A0} a b\u{85}\n"), "a b")
        TextTest.expectSame(UnicodeText.trim(" \u{3000} "), "")
        TextTest.expectSame(UnicodeText.lowercase("ΑΘΉΝΑΣ"), "αθήνας") // final sigma, as Java lowercases
        TextTest.expectSame(UnicodeText.lowercase("İ"), "i\u{307}")
        TextTest.expectSame(UnicodeText.nfc("re\u{301}sume\u{301}"), "r\u{E9}sum\u{E9}")
        #expect(!UnicodeText.same("\u{E9}", "e\u{301}"))
        #expect(UnicodeText.same("abc", "abc"))
    }
}
