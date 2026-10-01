import CoreGraphics
import Testing
import UIKit
@testable import ThumbFree

/// The Apple-style keyboard's pure model: the rows of each layer, the layout's shape, the nearest-key rule, shift, the
/// return label and double space.
@MainActor @Suite struct KeyplaneTests {
    @Test func theLettersLayerHasApplesRows() {
        let rows = Keyplane.rows(.letters, showsGlobe: true)
        #expect(rows.count == 4)
        #expect(rows[0] == "qwertyuiop".map(Key.letter))
        #expect(rows[1] == "asdfghjkl".map(Key.letter))
        #expect(rows[2] == [.shift] + "zxcvbnm".map(Key.letter) + [.delete])
        #expect(rows[3] == [.toNumbers, .globe, .emoji, .space, .ret])
    }

    // On a Face ID iPhone iOS draws the globe below the keyboard, so the keyboard drops its own: Apple's bottom row there
    // is 123, the emoji key, space and return.
    @Test func withoutTheGlobeTheBottomRowHasFourKeys() {
        #expect(Keyplane.rows(.letters, showsGlobe: false)[3] == [.toNumbers, .emoji, .space, .ret])
        #expect(Keyplane.rows(.numbers, showsGlobe: false)[3] == [.toLetters, .emoji, .space, .ret])
    }

    // The emoji key sits between the layer key (and the globe, when shown) and space on every layer, a function key a
    // little wider than a letter, with space still the widest key.
    @Test(arguments: [KeyLayer.letters, .numbers, .symbols], [false, true])
    func theEmojiKeySitsBeforeSpaceOnEveryLayer(_ layer: KeyLayer, showsGlobe: Bool) throws {
        let bottom = try #require(Keyplane.rows(layer, showsGlobe: showsGlobe).last)
        let emoji = try #require(bottom.firstIndex(of: .emoji))
        #expect(bottom[emoji + 1] == .space)
        #expect(bottom[emoji - 1] == (showsGlobe ? .globe : (layer == .letters ? .toNumbers : .toLetters)))
        let layout = KeyLayout(layer: layer, showsGlobe: showsGlobe, bounds: CGRect(x: 0, y: 0, width: 375, height: 212))
        let frame = { (key: Key) in try #require(layout.frames.first { $0.key == key }).frame }
        let letter = try #require(layout.frames.first).frame
        #expect(try frame(.emoji).width > letter.width)
        #expect(try frame(.space).width > frame(.emoji).width * 2)
        #expect(try frame(.emoji).maxX < frame(.space).minX)
        #expect(Key.emoji.isCharacter == false) // a function key: the medium haptic, no popup
    }

    @Test func theNumbersAndSymbolsLayersMatchApple() {
        let numbers = Keyplane.rows(.numbers, showsGlobe: true)
        #expect(numbers[0] == "1234567890".map(Key.symbol))
        #expect(numbers[2] == [.toSymbols] + ".,?!'".map(Key.symbol) + [.delete])
        let symbols = Keyplane.rows(.symbols, showsGlobe: true)
        #expect(symbols[0] == "[]{}#%^*+=".map(Key.symbol))
        #expect(symbols[2] == [.toNumbers] + ".,?!'".map(Key.symbol) + [.delete])
    }

    // While the keys type into the emoji search, their bottom row is Apple's search keys': no emoji key, and 123 and the
    // done key each a quarter of the row, the space bar between them twice as wide (measured on the iPhone 16 Simulator).
    @Test func theSearchKeysHaveNoEmojiKey() throws {
        #expect(Keyplane.rows(.letters, showsGlobe: false, showsEmoji: false)[3] == [.toNumbers, .space, .ret])
        #expect(Keyplane.rows(.numbers, showsGlobe: true, showsEmoji: false)[3] == [.toLetters, .globe, .space, .ret])
        let layout = KeyLayout(layer: .letters, showsGlobe: false, showsEmoji: false, bounds: CGRect(x: 0, y: 0, width: 393, height: 212))
        #expect(!layout.frames.contains { $0.key == .emoji })
        let frame = { (key: Key) in try #require(layout.frames.first { $0.key == key }).frame }
        #expect(try abs(frame(.toNumbers).width - frame(.ret).width) < 0.01)
        #expect(try abs(frame(.space).width - 2 * frame(.ret).width) < 0.01)
        #expect(try frame(.toNumbers).minX == KeyLayout.sideInset && abs(frame(.ret).maxX - (393 - KeyLayout.sideInset)) < 0.01)
    }

    @Test func theLayoutFillsItsBoundsWithoutOverlap() throws {
        let bounds = CGRect(x: 0, y: 0, width: 390, height: 216)
        let layout = KeyLayout(layer: .letters, showsGlobe: true, bounds: bounds)
        #expect(layout.frames.count == 10 + 9 + 9 + 5)
        for f in layout.frames {
            #expect(bounds.insetBy(dx: -1, dy: -1).contains(f.frame))
            #expect(f.frame.width > 0 && f.frame.height > 0)
        }
        // No two keys overlap (the bottom row's space must not grow over the layer or globe key).
        for (i, a) in layout.frames.enumerated() {
            for b in layout.frames[(i + 1)...] where a.frame.intersects(b.frame.insetBy(dx: 0.5, dy: 0.5)) {
                Issue.record("keys overlap: \(a.key) and \(b.key)")
            }
        }
        // Shift and delete are wider than a letter; space is the widest key.
        let letterWidth = try #require(layout.frames.first { $0.key == .letter("q") }).frame.width
        let shiftWidth = try #require(layout.frames.first { $0.key == .shift }).frame.width
        let spaceWidth = try #require(layout.frames.first { $0.key == .space }).frame.width
        #expect(shiftWidth > letterWidth)
        #expect(spaceWidth > shiftWidth)
    }

    // Landscape: a shorter, wider area lays out with the same key count and still fits.
    @Test func theLayoutFitsInLandscape() {
        let bounds = CGRect(x: 0, y: 0, width: 844, height: 150)
        let layout = KeyLayout(layer: .letters, showsGlobe: false, bounds: bounds)
        #expect(layout.frames.count == 10 + 9 + 9 + 4) // row 2 is shift + 7 letters + delete
        for f in layout.frames { #expect(bounds.insetBy(dx: -1, dy: -1).contains(f.frame)) }
    }

    @Test func aTouchInAGapHitsTheNearestKey() throws {
        let bounds = CGRect(x: 0, y: 0, width: 390, height: 216)
        let layout = KeyLayout(layer: .letters, showsGlobe: true, bounds: bounds)
        let q = try #require(layout.frames.first { $0.key == .letter("q") }).frame
        let w = try #require(layout.frames.first { $0.key == .letter("w") }).frame
        #expect(layout.hitKey(at: CGPoint(x: q.midX, y: q.midY)) == .letter("q"))
        // A point in the gap between q and w, a touch closer to q:
        let gapNearQ = CGPoint(x: q.maxX + (w.minX - q.maxX) * 0.25, y: q.midY)
        #expect(layout.hitKey(at: gapNearQ) == .letter("q"))
        // Below the last row still routes to the nearest key, never nothing.
        #expect(layout.hitKey(at: CGPoint(x: 5, y: bounds.maxY + 40)) != nil)
    }

    @Test func shiftGivesOneCapitalThenCapsLock() {
        let t0 = Date(timeIntervalSinceReferenceDate: 0)
        var shift = ShiftKey()
        shift.tap(at: t0)
        #expect(shift.state == .oneShot)
        shift.typedLetter()
        #expect(shift.state == .off)         // one capital, then lowercase
        shift.tap(at: t0 + 2)
        shift.tap(at: t0 + 2.2)              // a quick double tap
        #expect(shift.state == .capsLock)
        shift.typedLetter()
        #expect(shift.state == .capsLock)    // caps lock stays
        shift.tap(at: t0 + 4)
        #expect(shift.state == .off)
        shift.tap(at: t0 + 6)
        shift.tap(at: t0 + 6.9)              // a slow second tap just turns it off
        #expect(shift.state == .off)
        #expect(ShiftState.off.isUpper == false)
        #expect(ShiftState.oneShot.isUpper && ShiftState.capsLock.isUpper)
    }

    // At a sentence start the capital is already on, so the double tap's first tap turns it off: the second still locks.
    // So does a double tap from the user's own one capital. From caps lock, a double tap goes off, then one capital.
    @Test func aDoubleTapLocksCapsFromAnyOneCapital() {
        let t0 = Date(timeIntervalSinceReferenceDate: 0)
        var shift = ShiftKey()
        shift.follow(capsExpected: true, context: "")
        #expect(shift.state == .oneShot)
        shift.tap(at: t0)
        shift.tap(at: t0 + 0.2)
        #expect(shift.state == .capsLock)
        shift.tap(at: t0 + 2)
        shift.tap(at: t0 + 2.2)
        #expect(shift.state == .oneShot)
        shift.tap(at: t0 + 4)
        shift.tap(at: t0 + 4.2)
        #expect(shift.state == .capsLock)
    }

    // Two shift taps with another key between them are two single taps; so are two taps when the clock went back.
    @Test func aKeyBetweenTwoShiftTapsIsNoDoubleTap() {
        let t0 = Date(timeIntervalSinceReferenceDate: 0)
        var shift = ShiftKey()
        shift.tap(at: t0)
        shift.otherKey()
        shift.tap(at: t0 + 0.1)
        #expect(shift.state == .off)
        shift.tap(at: t0 + 5)
        shift.tap(at: t0 + 4)
        #expect(shift.state == .off)
    }

    // An automatic capital follows the field down as well as up: after a take or a delete, away from a sentence start, it
    // goes off. The user's own one capital and caps lock stay wherever the text goes.
    @Test func anAutomaticCapitalFollowsTheFieldBothWays() {
        let t0 = Date(timeIntervalSinceReferenceDate: 0)
        var shift = ShiftKey()
        shift.follow(capsExpected: true, context: "Hi. ")
        #expect(shift.state == .oneShot)
        shift.follow(capsExpected: false, context: "Hi.")
        #expect(shift.state == .off)
        shift.follow(capsExpected: true, context: "Hi. ")
        shift.follow(capsExpected: false, context: "Hi. Ask not what your country can do for you")
        #expect(shift.state == .off)
        shift.follow(capsExpected: nil, context: "a field that does not say")
        #expect(shift.state == .off)
        shift.tap(at: t0)
        shift.follow(capsExpected: false, context: "x")
        #expect(shift.state == .oneShot)
        shift.tap(at: t0 + 2)
        shift.tap(at: t0 + 2.1)
        shift.follow(capsExpected: false, context: "y")
        #expect(shift.state == .capsLock)
    }

    // A capital the user turned off stays off while the text and the caret stay where they are (a status refresh, a late
    // notice of the same text); once they move, the field decides again.
    @Test func aCapitalTheUserTurnedOffStaysOffUntilTheTextMoves() {
        var shift = ShiftKey()
        shift.follow(capsExpected: true, context: "")
        shift.tap(at: Date(timeIntervalSinceReferenceDate: 0))
        #expect(shift.state == .off)
        shift.follow(capsExpected: true, context: "")
        #expect(shift.state == .off)
        shift.follow(capsExpected: true, context: "Hi. ")
        #expect(shift.state == .oneShot)
    }

    // An automatic capital never carries over between fields: two empty fields in one document, one that capitalizes
    // sentences and one that does not, are two contexts, so moving to the second one takes its own shift.
    @Test func anAutomaticCapitalNeverCarriesOverBetweenFields() {
        let document = UUID()
        let sentences = ShiftKey.context(document: document, capitalization: .sentences, before: "")
        let plain = ShiftKey.context(document: document, capitalization: UITextAutocapitalizationType.none, before: "")
        #expect(sentences != plain)
        var shift = ShiftKey()
        shift.follow(capsExpected: FieldTraits.shiftExpected(.sentences, before: ""), context: sentences)
        #expect(shift.state == .oneShot)
        shift.follow(capsExpected: FieldTraits.shiftExpected(UITextAutocapitalizationType.none, before: ""), context: plain)
        #expect(shift.state == .off)
    }

    // Apple's layer rules: a switch key goes where it says, and a space, a return or an apostrophe (for contractions
    // like "don't") on the 123 or #+= layer goes back to letters; a space or return does so only once a key was typed
    // there (Apple's 123 then space stays on 123). Any other key stays on its layer.
    @Test func aSpaceReturnOrApostropheGoesBackToLetters() {
        for layer in [KeyLayer.numbers, .symbols] {
            #expect(layer.after(.space) == .letters)
            #expect(layer.after(.ret) == .letters)
            #expect(layer.after(.symbol("'")) == .letters)
            #expect(layer.after(.space, typedHere: false) == layer)
            #expect(layer.after(.ret, typedHere: false) == layer)
            #expect(layer.after(.symbol("'"), typedHere: false) == .letters)
            #expect(layer.after(.symbol(".")) == layer)
            #expect(layer.after(.symbol("5")) == layer)
            #expect(layer.after(.delete) == layer)
        }
        #expect(KeyLayer.letters.after(.toNumbers) == .numbers)
        #expect(KeyLayer.numbers.after(.toSymbols) == .symbols)
        #expect(KeyLayer.symbols.after(.toNumbers) == .numbers)
        #expect(KeyLayer.numbers.after(.toLetters) == .letters)
        #expect(KeyLayer.letters.after(.letter("a")) == .letters)
        #expect(KeyLayer.letters.after(.shift) == .letters)
    }

    // Apple's quick slide starts from 123 on the letters, ABC, #+= on 123, and shift; not from 123 on #+= (Apple's acts on
    // release there, so a slide from it types the #+= key under the finger). After a letter slid to from shift, shift is as
    // the press found it: off stays off, and a capital that was on (a sentence start) stays on, as on Apple's.
    @Test func quickSlidesStartWhereApplesDo() {
        #expect(KeyLayer.letters.slidesFrom(.toNumbers) && KeyLayer.letters.slidesFrom(.shift))
        #expect(KeyLayer.numbers.slidesFrom(.toLetters) && KeyLayer.symbols.slidesFrom(.toLetters))
        #expect(KeyLayer.numbers.slidesFrom(.toSymbols))
        #expect(!KeyLayer.symbols.slidesFrom(.toNumbers))
        #expect(!KeyLayer.letters.slidesFrom(.letter("a")) && !KeyLayer.letters.slidesFrom(.emoji) && !KeyLayer.letters.slidesFrom(.space))
        var shift = ShiftKey()
        shift.follow(capsExpected: true, context: "")
        shift.typedLetter()
        shift.restore(.oneShot)
        #expect(shift.state == .oneShot)
        shift.follow(capsExpected: false, context: "A")
        #expect(shift.state == .oneShot) // the user's own now: the field does not take it back
        shift.restore(.off)
        #expect(shift.state == .off)
    }

    @Test func theReturnKeyIsLabelledForTheField() {
        #expect(ReturnLabel.label(for: .default) == "return")
        #expect(ReturnLabel.label(for: .go) == "Go")
        #expect(ReturnLabel.label(for: .search) == "Search")
        #expect(ReturnLabel.label(for: .send) == "Send")
        #expect(ReturnLabel.label(for: .done) == "Done")
        #expect(ReturnLabel.label(for: .google) == "Search")
        // Apple's return key turns blue when it acts (go, search, send, join, route, done) and stays gray otherwise.
        for type in [UIReturnKeyType.go, .google, .join, .route, .search, .send, .yahoo, .done] { #expect(ReturnLabel.isBlue(for: type)) }
        for type in [UIReturnKeyType.default, .next, .continue, .emergencyCall] { #expect(!ReturnLabel.isBlue(for: type)) }
    }

    // Apple's iOS 26 return key draws a symbol for each type (read on the Simulator); the ones that act stay blue.
    @Test func theReturnKeyDrawsApplesSymbol() {
        #expect(ReturnLabel.symbol(for: .default) == "return.left")
        for type in [UIReturnKeyType.go, .join, .route] { #expect(ReturnLabel.symbol(for: type) == "arrow.right") }
        #expect(ReturnLabel.symbol(for: .send) == "arrow.up")
        #expect(ReturnLabel.symbol(for: .search) == "magnifyingglass")
        #expect(ReturnLabel.symbol(for: .done) == "checkmark")
        #expect(ReturnLabel.symbol(for: .next) == "chevron.right")
        #expect(ReturnLabel.symbol(for: .continue) == "chevron.right")
        #expect(ReturnLabel.symbol(for: .emergencyCall) == nil) // keeps its words
        for type in [UIReturnKeyType.default, .go, .send, .search, .done, .next] {
            #expect(ReturnLabel.symbol(for: type).flatMap { UIImage(systemName: $0) } != nil, "no SF Symbol for return type \(type.rawValue)")
        }
    }

    // Apple's third row: shift, #+= or 123 at the left edge and delete at the right edge, the keys between sharing the
    // rest, so the five punctuation keys widen instead of leaving the row's ends floating inward.
    @Test(arguments: [KeyLayer.letters, .numbers, .symbols])
    func theThirdRowReachesBothEdges(_ layer: KeyLayer) throws {
        let bounds = CGRect(x: 0, y: 0, width: 375, height: 216)
        let layout = KeyLayout(layer: layer, showsGlobe: false, bounds: bounds)
        let row = Keyplane.rows(layer, showsGlobe: false)[2]
        let placed = row.compactMap { key in layout.frames.first { $0.key == key }?.frame }
        #expect(placed.count == row.count)
        let first = try #require(placed.first), last = try #require(placed.last)
        #expect(abs(first.minX - KeyLayout.sideInset) < 0.01) // Apple's 6.7 pt margins
        #expect(abs(last.maxX - (bounds.width - KeyLayout.sideInset)) < 0.01)
        let between = placed.dropFirst().dropLast().map(\.width)
        #expect(between.allSatisfy { abs($0 - between[0]) < 0.01 })
        let topKey = try #require(layout.frames.first).frame.width
        #expect(between[0] >= topKey)
        if layer != .letters { #expect(between[0] > topKey * 1.3) } // Apple's wide punctuation keys
        for (i, a) in placed.enumerated() {
            for b in placed[(i + 1)...] where a.intersects(b) { Issue.record("keys overlap in the third row: \(a) and \(b)") }
        }
    }

    // Apple's held delete: a character at the touch, the first repeat after half a second, then a character every tenth of
    // a second until twenty are gone (the touch's one too), then whole words; the emoji picker's delete keeps its own pace,
    // one emoji at a time. A word is the one before the cursor with the spaces before it.
    @Test func holdingDeleteGoesOnToWholeWords() {
        #expect(DeleteRepeat.step(1) == (.milliseconds(500), false))
        #expect(DeleteRepeat.step(2) == (.milliseconds(100), false))
        #expect(DeleteRepeat.step(19) == (.milliseconds(100), false)) // with the touch's own, twenty characters
        #expect(DeleteRepeat.step(20) == (.milliseconds(180), true))  // then words
        #expect(DeleteRepeat.step(1, picker: true) == (.milliseconds(400), false)) // the emoji picker's own pace
        #expect(DeleteRepeat.step(2, picker: true) == (.milliseconds(80), false))
        #expect(DeleteRepeat.step(40, picker: true) == (.milliseconds(80), false)) // one emoji at a time, never a word
        #expect(DeleteRepeat.wordLength(before: "one two three") == 6)     // " three": "one two" is left
        #expect(DeleteRepeat.wordLength(before: "one two   ") == 3)       // spaces at the end go first
        #expect(DeleteRepeat.wordLength(before: "hi \u{1F44D}") == 2)     // an emoji is one character
        #expect(DeleteRepeat.wordLength(before: "") == 1)
    }

    @Test func doubleSpaceMakesAFullStop() {
        #expect(DoubleSpace.onSpace(before: "hello ", sinceLastSpaceMs: 200) == .periodSpace)
        #expect(DoubleSpace.onSpace(before: "hello ", sinceLastSpaceMs: 900) == .space) // too slow
        #expect(DoubleSpace.onSpace(before: "hello", sinceLastSpaceMs: 200) == .space)  // no space to replace
        #expect(DoubleSpace.onSpace(before: "hello. ", sinceLastSpaceMs: 200) == .space) // already ends a sentence
        #expect(DoubleSpace.onSpace(before: "hi, ", sinceLastSpaceMs: 200) == .space)   // punctuation, not a word
        #expect(DoubleSpace.onSpace(before: "5 ", sinceLastSpaceMs: 200) == .periodSpace) // a digit is a word
        #expect(DoubleSpace.onSpace(before: "  ", sinceLastSpaceMs: 200) == .space)     // two spaces, no word
        #expect(DoubleSpace.onSpace(before: nil, sinceLastSpaceMs: 200) == .space)
        #expect(DoubleSpace.onSpace(before: "hello ", sinceLastSpaceMs: -100) == .space) // the clock went back
    }

    @Test func everyKeyHasAStableIdentifierAndLabel() {
        #expect(Key.letter("a").identifier == "keyboard.key.a")
        #expect(Key.symbol("/").identifier == "keyboard.key.slash")
        #expect(Key.symbol("1").identifier == "keyboard.key.1")
        #expect(Key.shift.identifier == "keyboard.shift")
        #expect(Key.emoji.identifier == "keyboard.emoji")
        #expect(Key.emoji.label(shift: .off, returnLabel: "return") == "Emoji")
        #expect(Key.emoji.traits(shift: .capsLock) == .keyboardKey)
        #expect(Key.letter("a").label(shift: .oneShot, returnLabel: "return") == "A")
        #expect(Key.letter("a").label(shift: .capsLock, returnLabel: "return") == "A")
        #expect(Key.letter("a").label(shift: .off, returnLabel: "return") == "a")
        #expect(Key.ret.label(shift: .off, returnLabel: "Send") == "Send")
        #expect(Key.symbol("/").label(shift: .off, returnLabel: "return") == "Slash")
        // VoiceOver tells one capital from caps lock, and shift is selected while it is on.
        #expect(Key.shift.label(shift: .off, returnLabel: "return") == "Shift")
        #expect(Key.shift.label(shift: .oneShot, returnLabel: "return") == "Shift, on")
        #expect(Key.shift.label(shift: .capsLock, returnLabel: "return") == "Caps Lock, on")
        #expect(Key.shift.traits(shift: .off) == .keyboardKey)
        #expect(Key.shift.traits(shift: .oneShot) == [.keyboardKey, .selected])
        #expect(Key.shift.traits(shift: .capsLock) == [.keyboardKey, .selected])
        #expect(Key.letter("a").traits(shift: .capsLock) == .keyboardKey)
        // Every symbol key on every layer speaks a name, never the bare glyph (letters and digits may stay as they are).
        for layer in [KeyLayer.letters, .numbers, .symbols] {
            for row in Keyplane.rows(layer, showsGlobe: true) {
                for key in row {
                    guard case .symbol(let c) = key, !c.isLetter, !c.isNumber else { continue }
                    let label = key.label(shift: .off, returnLabel: "return")
                    #expect(label != String(c), "\(c) has no spoken VoiceOver label")
                }
            }
        }
    }
}
