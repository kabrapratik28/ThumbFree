import Testing
import UIKit
@testable import ThumbFree

/// Apple's smart punctuation in ThumbFree's keyboard, which iOS's text views leave to a custom keyboard.
@Suite struct SmartPunctuationTests {
    // Opening quotes at the start, after a space or an opening bracket or quote; closing ones elsewhere, so an apostrophe
    // in a word is a closing single quote.
    @Test func quotesCurlAsApplesDo() {
        #expect(SmartPunctuation.quote("\"", after: "") == "\u{201C}")
        #expect(SmartPunctuation.quote("\"", after: "said ") == "\u{201C}")
        #expect(SmartPunctuation.quote("\"", after: "(") == "\u{201C}")
        #expect(SmartPunctuation.quote("\"", after: "\u{201C}Hi") == "\u{201D}")
        #expect(SmartPunctuation.quote("'", after: "don") == "\u{2019}")
        #expect(SmartPunctuation.quote("'", after: "") == "\u{2018}")
        #expect(SmartPunctuation.quote("'", after: "\u{201C}") == "\u{2018}")
        #expect(SmartPunctuation.quote(".", after: "x") == ".")
        #expect(SmartPunctuation.curly("don't") == "don\u{2019}t")
    }

    // A second hyphen makes Apple's dash; one hyphen stays one.
    @Test func twoHyphensMakeADash() {
        #expect(SmartPunctuation.makesDash("-", after: "wait -"))
        #expect(!SmartPunctuation.makesDash("-", after: "wait "))
        #expect(!SmartPunctuation.makesDash(".", after: "-"))
    }

    // The field decides: on where it asks, off where it turns them off, and by default everywhere but web address, email
    // and ASCII fields.
    @Test func theFieldDecides() {
        #expect(FieldTraits.smartPunctuation(quotes: .default, dashes: .default, type: .default) == (true, true))
        #expect(FieldTraits.smartPunctuation(quotes: nil, dashes: nil, type: nil) == (true, true))
        #expect(FieldTraits.smartPunctuation(quotes: .no, dashes: .yes, type: .default) == (false, true))
        #expect(FieldTraits.smartPunctuation(quotes: .default, dashes: .default, type: .URL) == (false, false))
        #expect(FieldTraits.smartPunctuation(quotes: .yes, dashes: .default, type: .emailAddress) == (true, false))
        #expect(FieldTraits.smartPunctuation(quotes: .default, dashes: .default, type: .asciiCapable) == (false, false))
    }
}
