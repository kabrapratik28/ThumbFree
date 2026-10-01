import Testing
@testable import TFCore

@Suite struct SoundexTests {
    // Codes from the Rust natural 0.5.0 crate. The first letter never merges with the next one: pfister is p123.
    @Test(arguments: [
        ("chargebee", "c621"), ("chargeb", "c621"), ("robert", "r163"), ("rupert", "r163"),
        ("kotlin", "k345"), ("cotlin", "c345"), ("sandy", "s530"), ("hand", "h530"), ("handi", "h530"),
        ("tymczak", "t522"), ("ashcraft", "a261"), ("zendesk", "z532"), ("pfister", "p123"),
    ])
    func matchesNatural(_ word: String, _ code: String) {
        #expect(Soundex.code(word) == code)
    }
}
