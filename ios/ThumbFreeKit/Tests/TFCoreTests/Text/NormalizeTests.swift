import Testing
@testable import TFCore

/// Some rows are adapted from MIT-licensed code; see THIRD_PARTY_NOTICES.md.
@Suite struct NormalizeTests {
    @Test(arguments: [
        ("w wh wh wh wh wh wh wh wh wh why", "w wh why"),
        ("I I I I think so so so so", "I think so"),
        ("Check data doc doc doc doc documentation.", "Check data doc documentation."),
        ("No NO no NO no", "No"),
        ("no no is fine", "no no is fine"),
        ("The the the cat", "The cat"),
    ])
    func collapsesTriples(_ text: String, _ expected: String) {
        TextTest.expectSame(Normalize.apply(text), expected)
    }
}
