import CoreGraphics
import Foundation
import Testing
import UIKit
@testable import ThumbFree

/// The keyboard follows the field as Apple's does: each keyboard type's layout and key widths, as read from Apple's
/// keyboard on the iOS 26.5 Simulator into `tools/keyboard/apple-layouts.txt`.
@MainActor @Suite struct KeyboardKindTests {
    @Test func eachFieldTypeGetsItsLayout() {
        #expect(KeyboardKind(.default) == .text)
        #expect(KeyboardKind(nil) == .text)
        #expect(KeyboardKind(.asciiCapable) == .ascii)
        #expect(KeyboardKind(.numbersAndPunctuation) == .numbersFirst)
        #expect(KeyboardKind(.URL) == .url)
        #expect(KeyboardKind(.emailAddress) == .email)
        #expect(KeyboardKind(.twitter) == .twitter)
        #expect(KeyboardKind(.webSearch) == .webSearch)
        #expect(KeyboardKind(.numberPad) == .numberPad)
        #expect(KeyboardKind(.asciiCapableNumberPad) == .numberPad)
        #expect(KeyboardKind(.decimalPad) == .decimalPad)
        #expect(KeyboardKind(.phonePad) == .text) // never reaches a custom keyboard: iOS shows its own
        #expect(KeyboardKind.numbersFirst.firstLayer == .numbers)
        #expect(KeyboardKind.email.firstLayer == .letters)
    }

    // Apple's bottom rows on the letters: email @ and ., the web address keys . / .com with no space, Twitter's @ and #
    // with no return, web search's . before Go; the ASCII-capable and numbers-and-punctuation keyboards have no emoji key.
    // The 123 and #+= layers keep the usual bottom row.
    @Test func theLettersBottomRowFollowsTheField() {
        func bottom(_ kind: KeyboardKind, _ layer: KeyLayer = .letters) -> [Key] { Keyplane.rows(layer, kind: kind, showsGlobe: false).last ?? [] }
        #expect(bottom(.email) == [.toNumbers, .emoji, .space, .symbol("@"), .symbol("."), .ret])
        #expect(bottom(.url) == [.toNumbers, .emoji, .symbol("."), .symbol("/"), .text(".com"), .ret])
        #expect(bottom(.twitter) == [.toNumbers, .emoji, .space, .symbol("@"), .symbol("#")])
        #expect(bottom(.webSearch) == [.toNumbers, .emoji, .space, .symbol("."), .ret])
        #expect(bottom(.ascii) == [.toNumbers, .space, .ret])
        #expect(bottom(.numbersFirst, .numbers) == [.toLetters, .space, .ret])
        #expect(bottom(.url, .numbers) == [.toLetters, .emoji, .space, .ret])
        #expect(bottom(.twitter, .symbols) == [.toLetters, .emoji, .space, .ret])
        #expect(Key.text(".com").identifier == "keyboard.key.periodcom")
        #expect(Key.text(".com").label(shift: .off, returnLabel: "return") == ".com")
        #expect(Key.text(".com").isCharacter)
    }

    // Apple's digit pad for number fields: 1 to 9 in three columns, then an empty corner (the decimal point on the decimal
    // pad), 0 and delete, and no return key. A touch in the empty corner types nothing, as on Apple's.
    @Test func numberFieldsGetApplesDigitPad() throws {
        #expect(Keyplane.rows(.letters, kind: .numberPad, showsGlobe: false) == ["123", "456", "789"].map { $0.map(Key.symbol) } + [[.symbol("0"), .delete]])
        #expect(Keyplane.rows(.letters, kind: .decimalPad, showsGlobe: false).last == [.symbol("."), .symbol("0"), .delete])
        #expect(Keyplane.rows(.numbers, kind: .numberPad, showsGlobe: true).last == [.globe, .symbol("0"), .delete])
        let bounds = CGRect(x: 0, y: 0, width: 390, height: 212)
        let layout = KeyLayout(layer: .letters, kind: .numberPad, showsGlobe: false, bounds: bounds)
        let frame = { (key: Key) in try #require(layout.frames.first { $0.key == key }).frame }
        #expect(try abs(frame(.symbol("1")).width - frame(.symbol("2")).width) < 0.01)
        #expect(try frame(.symbol("0")).midX == frame(.symbol("8")).midX) // 0 under 8
        #expect(try frame(.delete).midX == frame(.symbol("9")).midX)      // delete under 9
        let corner = try CGPoint(x: frame(.symbol("7")).midX, y: frame(.symbol("0")).midY)
        #expect(layout.hitKey(at: corner) == nil)
        #expect(try layout.hitKey(at: CGPoint(x: frame(.symbol("5")).midX, y: frame(.symbol("5")).midY)) == .symbol("5"))
        #expect(KeyLayout(layer: .letters, kind: .decimalPad, showsGlobe: false, bounds: bounds).hitKey(at: corner) == .symbol("."))
    }

    // Every layout against Apple's as read: the same keys in the same rows, each key within a tenth of a letter's width of
    // Apple's (both measured in letters, so the different side margins do not matter). The digit pads compare keys only.
    @Test func everyLayoutMatchesApplesAsRead() throws {
        let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("tools/keyboard/apple-layouts.txt")
        let lines = try String(contentsOf: file, encoding: .utf8).split(separator: "\n").filter { !$0.hasPrefix("#") }
        #expect(lines.count == 24)
        for line in lines {
            let fields = line.split(separator: "\t").map(String.init)
            let name = "\(fields[0]) \(fields[1])"
            let kind = try #require(fields[0] == "asciiNumberPad" ? .numberPad : KeyboardKind(rawValue: fields[0]), "unknown kind \(name)")
            let layer: KeyLayer = fields[1] == "numbers" ? .numbers : fields[1] == "symbols" ? .symbols : .letters
            let apple = fields.dropFirst(2).map { row in
                row.split(separator: " ").map { key in // "::38" is the colon key, 38 points wide
                    let width = key.split(separator: ":").last.map(String.init) ?? ""
                    return (token: String(key.dropLast(width.count + 1)), width: Double(width) ?? 0)
                }
            }
            let ours = Keyplane.rows(layer, kind: kind, showsGlobe: false)
            #expect(ours.map { $0.map(\.appleName) } == apple.map { $0.map(\.token).filter { $0 != "blank" } }, "\(name)")
            guard !kind.isDigitPad else { continue }
            let layout = KeyLayout(layer: layer, kind: kind, showsGlobe: false, bounds: CGRect(x: 0, y: 0, width: 390, height: 212))
            let appleUnit = apple[0].map(\.width).reduce(0, +) / 10
            let top = layout.frames.prefix(10).map(\.frame)
            let ourUnit = ((top.last?.maxX ?? 0) - (top.first?.minX ?? 0) + 6) / 10
            for (row, keys) in zip(ours, apple) {
                for (key, a) in zip(row, keys) {
                    let frame = try #require(layout.frames.first { $0.key == key }).frame
                    #expect(abs((frame.width + 6) / ourUnit - a.width / appleUnit) < 0.1, "\(name) \(a.token)")
                }
            }
        }
    }
}

private extension Key {
    /// The name the reader gives the key in `tools/keyboard/apple-layouts.txt`.
    var appleName: String {
        switch self {
        case .letter(let c), .symbol(let c): String(c)
        case .text(let text): text
        case .shift: "shift"
        case .delete: "delete"
        case .toNumbers: "123"
        case .toSymbols: "#+="
        case .toLetters: "ABC"
        case .space: "space"
        case .ret: "return"
        case .globe: "globe"
        case .emoji: "emoji"
        }
    }
}
