import CoreText
import Testing
import UIKit
@testable import ThumbFree

/// Skin tones: the tone picker's choices and the tone remembered for an emoji.
@MainActor @Suite struct EmojiTonesTests {
    private let thumbsUp = Emoji(text: "\u{1F44D}", name: "thumbs up")

    // Apple's picker: the emoji, then five tones from light to dark, from the base or from one of its variants.
    @Test func anEmojiWithTonesOffersTheBaseAndFiveTones() throws {
        let choices = try #require(EmojiTones.choices(for: thumbsUp.text))
        #expect(choices.map(\.text) == ["\u{1F44D}", "\u{1F44D}\u{1F3FB}", "\u{1F44D}\u{1F3FC}", "\u{1F44D}\u{1F3FD}", "\u{1F44D}\u{1F3FE}", "\u{1F44D}\u{1F3FF}"])
        #expect(choices[3].name == "thumbs up: medium skin tone")
        #expect(EmojiTones.choices(for: "\u{1F44D}\u{1F3FD}") == choices)
        #expect(EmojiTones.choices(for: "\u{1F600}") == nil) // a face has no tones
    }

    // Two people get Apple's two-person picker: a tone for each, [left][right], the same tone twice being the one-tone
    // variant; they leave the one-person picker, and every pair counts as the emoji in Frequently Used.
    @Test func twoPeopleGetATonePerPerson() throws {
        let handshake = try #require(EmojiTones.pairs(for: "\u{1F91D}"))
        #expect(handshake.count == 5 && handshake.allSatisfy { $0.count == 5 })
        #expect(handshake[0][0] == Emoji(text: "\u{1F91D}\u{1F3FB}", name: "handshake: light skin tone"))
        #expect(handshake[0][4] == Emoji(text: "\u{1FAF1}\u{1F3FB}\u{200D}\u{1FAF2}\u{1F3FF}", name: "handshake: light skin tone, dark skin tone"))
        #expect(EmojiTones.choices(for: "\u{1F91D}") == nil)
        #expect(EmojiTones.pairs(for: handshake[3][1].text) == handshake)
        #expect(EmojiTones.base(of: handshake[0][4].text) == "\u{1F91D}")
        let kiss = try #require(EmojiTones.pairs(for: "\u{1F48F}"))
        #expect(kiss[4][4].name == "kiss: dark skin tone")
        #expect(kiss[1][2].name == "kiss: person, person, medium-light skin tone, medium skin tone")
        #expect(EmojiTones.twos.count >= 12) // the handshake, the couples, the kisses, people holding hands, ...
        #expect(EmojiTones.pairs(for: "\u{1F44D}") == nil) // one person
    }

    // Every emoji the tone data lists up to what this iOS draws gets its five (or its 25 pairs), none dropped, and each
    // variant is one character this iOS draws exactly one emoji wide.
    @Test func everyToneSetIsFiveVariantsThisIOSDraws() {
        let font = CTFontCreateWithName("AppleColorEmoji" as CFString, 32, nil)
        func width(_ text: String) -> Double {
            CTLineGetTypographicBounds(CTLineCreateWithAttributedString(NSAttributedString(string: text, attributes: [.font: font])), nil, nil, nil)
        }
        let one = width("\u{1F600}")
        let bases = Set(EmojiData.skinTones.split(separator: "\n").compactMap { line -> String? in
            let parts = line.split(separator: " ", maxSplits: 3)
            return parts.count == 4 && (Double(parts[2]) ?? .infinity) <= EmojiCatalog.drawnVersion ? String(parts[0]) : nil
        })
        #expect(Set(EmojiTones.table.keys).union(EmojiTones.twos.keys) == bases)
        #expect(Set(EmojiTones.table.keys).isDisjoint(with: EmojiTones.twos.keys))
        for (base, choices) in EmojiTones.table {
            #expect(choices.count == 6, "\(base) has \(choices.count - 1) tones")
            for choice in choices {
                #expect(choice.text.count == 1 && width(choice.text) == one, "\(choice.name) does not draw as one emoji")
            }
        }
        for pair in EmojiTones.twos.values.joined().joined() {
            #expect(pair.text.count == 1 && width(pair.text) == one, "\(pair.name) does not draw as one emoji")
        }
    }

    // The grid shows an emoji in its chosen tone; a stored choice that is not a tone of it (another emoji's, or damaged),
    // or none, leaves it as it is. Frequently Used counts every tone of an emoji as that emoji, so it lists it once, in
    // the tone chosen now.
    @Test func theChosenToneIsShownAndCountsAsItsEmoji() {
        #expect(EmojiTones.shown(thumbsUp, chosen: [:]) == thumbsUp)
        let shown = EmojiTones.shown(thumbsUp, chosen: ["\u{1F44D}": "\u{1F44D}\u{1F3FE}"])
        #expect(shown == Emoji(text: "\u{1F44D}\u{1F3FE}", name: "thumbs up: medium-dark skin tone"))
        #expect(EmojiTones.shown(thumbsUp, chosen: ["\u{1F44D}": "not a tone of it"]) == thumbsUp)
        #expect(EmojiTones.shown(thumbsUp, chosen: ["\u{1F44D}": "\u{1F44E}\u{1F3FD}"]) == thumbsUp) // thumbs down's
        #expect(EmojiTones.shown(thumbsUp, chosen: ["\u{1F44D}": "\u{1FAF1}\u{1F3FB}\u{200D}\u{1FAF2}\u{1F3FF}"]) == thumbsUp) // a handshake's
        #expect(EmojiTones.base(of: "\u{1F44D}\u{1F3FE}") == "\u{1F44D}")
        #expect(EmojiTones.base(of: "\u{1F44D}") == "\u{1F44D}")
        #expect(EmojiTones.base(of: "\u{1F600}") == "\u{1F600}")
    }

    // A pick in a tone picker is remembered for its emoji, and picking the plain emoji forgets it: one rule for the
    // picker's grid and the keyboard's store.
    @Test func aPickIsRememberedAndThePlainEmojiForgetsIt() throws {
        let medium = try #require(EmojiTones.choices(for: thumbsUp.text))[3]
        var chosen: [String: String] = [:]
        EmojiTones.choose(medium, for: thumbsUp, in: &chosen)
        #expect(chosen == ["\u{1F44D}": "\u{1F44D}\u{1F3FD}"])
        EmojiTones.choose(thumbsUp, for: thumbsUp, in: &chosen)
        #expect(chosen.isEmpty)
    }
}
