import Testing
import UIKit
@testable import ThumbFree

// URL and email fields get the text as is; the auto-capitalization and the text before the cursor decide the capital.
@Suite struct FieldTraitsTests {
    /// `documentIdentifier` can be nil although it is declared non-optional; reading it straight from Swift then traps and
    /// kills the keyboard (seen on an iPhone 16, iOS 26.5.2, while iOS reset the document of a keyboard in the background).
    @Test func aMissingDocumentIdentifierReadsAsNilInsteadOfCrashing() {
        final class NoIdentifier: NSObject { @objc var documentIdentifier: NSUUID? { nil } }
        final class SomeIdentifier: NSObject {
            let id = UUID()
            @objc var documentIdentifier: NSUUID? { id as NSUUID }
        }
        #expect(FieldTraits.documentID(of: NoIdentifier()) == nil)
        let some = SomeIdentifier()
        #expect(FieldTraits.documentID(of: some) == some.id)
        #expect(FieldTraits.documentID(of: NSObject()) == nil) // no such property at all
    }

    // Autocorrection: on unless the field turns it off, and never in web address or email fields (ThumbFree's own
    // rule).
    @Test func theFieldDecidesWhetherWordsAreCorrected() {
        #expect(FieldTraits.corrects(.default, autocorrection: .default))
        #expect(FieldTraits.corrects(.twitter, autocorrection: .yes))
        #expect(FieldTraits.corrects(nil, autocorrection: nil))
        #expect(!FieldTraits.corrects(.default, autocorrection: .no))
        #expect(!FieldTraits.corrects(.URL, autocorrection: .yes))
        #expect(!FieldTraits.corrects(.emailAddress, autocorrection: .default))
    }

    @Test func fieldTraitsShapeTheText() {
        #expect(FieldTraits.field(.URL) == .url)
        #expect(FieldTraits.field(.emailAddress) == .email)
        #expect(FieldTraits.field(.webSearch) == .text)
        #expect(FieldTraits.field(nil) == .text)
        #expect(FieldTraits.capsExpected(.sentences, before: "") == true)
        #expect(FieldTraits.capsExpected(.sentences, before: "I said. ") == true)
        #expect(FieldTraits.capsExpected(.sentences, before: "I said.") == true)
        #expect(FieldTraits.capsExpected(.sentences, before: "line\n") == true)
        #expect(FieldTraits.capsExpected(.sentences, before: "Hello ") == false)
        #expect(FieldTraits.capsExpected(.words, before: "Hello ") == true)
        #expect(FieldTraits.capsExpected(.words, before: "Hello") == false)
        #expect(FieldTraits.capsExpected(.allCharacters, before: "x") == true)
        #expect(FieldTraits.capsExpected(UITextAutocapitalizationType.none, before: "") == false)
        #expect(FieldTraits.capsExpected(nil, before: "") == nil)
    }

    // A typed key: a sentence ends only once a space or a new line follows its full stop, as on Apple's keyboard, so
    // "example." then a letter stays lowercase ("example.com"). A take's text still counts "I said." as an end.
    @Test func typedKeysCapitalizeOnlyAfterTheSpace() {
        #expect(FieldTraits.shiftExpected(.sentences, before: "") == true)
        #expect(FieldTraits.shiftExpected(.sentences, before: "I said. ") == true)
        #expect(FieldTraits.shiftExpected(.sentences, before: "I said.") == false)
        #expect(FieldTraits.shiftExpected(.sentences, before: "Really?  ") == true)
        #expect(FieldTraits.shiftExpected(.sentences, before: "line\n") == true)
        #expect(FieldTraits.shiftExpected(.sentences, before: "Hello ") == false)
        #expect(FieldTraits.shiftExpected(.sentences, before: "Hello") == false)
        #expect(FieldTraits.shiftExpected(.words, before: "Hello ") == true)
        #expect(FieldTraits.shiftExpected(.words, before: "Hello") == false)
        #expect(FieldTraits.shiftExpected(.allCharacters, before: "x") == true)
        #expect(FieldTraits.shiftExpected(UITextAutocapitalizationType.none, before: "") == false)
        #expect(FieldTraits.shiftExpected(nil, before: "") == nil)
    }

    // An opening quote or bracket keeps the capital a sentence's start asks for, as Apple's keyboard does.
    @Test func anOpeningQuoteKeepsTheSentencesCapital() {
        #expect(FieldTraits.shiftExpected(.sentences, before: "\u{201C}") == true)
        #expect(FieldTraits.shiftExpected(.sentences, before: "Done. (") == true)
        #expect(FieldTraits.shiftExpected(.sentences, before: "He said \u{201C}") == false)
        #expect(FieldTraits.shiftExpected(.words, before: "Hello \u{201C}") == true)
        #expect(FieldTraits.shiftExpected(.sentences, before: "don'") == false)
    }
}
