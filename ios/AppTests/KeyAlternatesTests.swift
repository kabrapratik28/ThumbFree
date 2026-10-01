import CoreGraphics
import Foundation
import Testing
import UIKit
@testable import ThumbFree

/// Apple's long-press alternatives: the table, as read from Apple's keyboard, and the row they show in.
@MainActor @Suite struct KeyAlternatesTests {
    private let bounds = CGRect(x: 0, y: 0, width: 390, height: 212)

    // The table is `tools/keyboard/apple-alternates.txt` as the reader wrote it, line for line.
    @Test func theTableIsApplesAsRead() throws {
        let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("tools/keyboard/apple-alternates.txt")
        let read = try String(contentsOf: file, encoding: .utf8).split(separator: "\n").filter { !$0.hasPrefix("#") }
        #expect(KeyAlternates.lines.split(separator: "\n") == read)
    }

    // Apple's order, with the key's own character where the finger starts; capitals while shift is on; a field's own keys
    // have their own (the email keyboard's . offers the domains, .com under the finger), its 123 layer a text field's.
    @Test func aLongPressOffersApplesAlternativesInApplesOrder() throws {
        let e = try #require(KeyAlternates.of(.letter("e"), kind: .text, layer: .letters, upper: false))
        #expect(e.items == ["ë", "é", "e", "è", "ê", "ě", "ẽ", "ē", "ė", "ę"])
        #expect(e.start == 2)
        #expect(KeyAlternates.of(.letter("e"), kind: .text, layer: .letters, upper: true)?.items.first == "Ë")
        #expect(KeyAlternates.of(.letter("e"), kind: .email, layer: .letters, upper: false) == e) // the letters are a text field's
        #expect(KeyAlternates.of(.symbol("$"), kind: .text, layer: .numbers, upper: false)?.items == ["₽", "¥", "€", "$", "¢", "£", "₩"])
        let domains = try #require(KeyAlternates.of(.symbol("."), kind: .email, layer: .letters, upper: false))
        #expect(domains.items[domains.start] == ".com")
        #expect(KeyAlternates.of(.symbol("."), kind: .email, layer: .numbers, upper: false)?.items == [".", "…"])
        let search = try #require(KeyAlternates.of(.symbol("."), kind: .webSearch, layer: .letters, upper: false))
        #expect(search.items == [".us", ".edu", ".net", ".com", ".org"] && search.items[search.start] == ".com")
        #expect(KeyAlternates.of(.text(".com"), kind: .url, layer: .letters, upper: false)?.items.contains(".org") == true)
        #expect(KeyAlternates.of(.letter("q"), kind: .text, layer: .letters, upper: false) == nil)
        #expect(KeyAlternates.of(.symbol("5"), kind: .numberPad, layer: .letters, upper: false) == nil)
        #expect(KeyAlternates.of(.space, kind: .text, layer: .letters, upper: false) == nil)
    }

    // e's row: ten cells across the keyboard between Apple's margins, e's own over the key, the band above it. A short row
    // is 4 pt wider than the key per cell, a release outside it types the key itself, and a top-row band stays inside the
    // keyboard.
    @Test func theAlternativesRowSitsOverTheKeyInsideTheKeyboard() {
        let e = CGRect(x: 81, y: 60, width: 33, height: 42)
        let row = KeyPopup.Callout(count: 10, start: 2, key: e, bounds: bounds, top: -48)
        #expect(abs(row.cells[0].minX - 13.5) < 0.01 && abs(row.cells[9].maxX - 376.5) < 0.01)
        #expect(row.index(at: CGPoint(x: e.midX, y: e.midY)) == 2)
        #expect(row.band.maxY <= e.minY - 6.5)
        let w = CGRect(x: 42, y: 60, width: 33, height: 42)
        let short = KeyPopup.Callout(count: 2, start: 0, key: w, bounds: bounds, top: -48)
        #expect(short.cells[0].width == 37)
        #expect(short.index(at: CGPoint(x: w.midX, y: w.midY)) == 0)
        #expect(short.index(at: CGPoint(x: 300, y: w.midY)) == nil)
        let top = KeyPopup.Callout(count: 2, start: 0, key: CGRect(x: 42, y: 5.5, width: 33, height: 42), bounds: bounds, top: -48)
        #expect(top.band.minY == -48)
    }

    // Letting go away from the row, as on Apple's (measured on the iOS 26.5 Simulator): from the row's top down to the key's
    // bottom edge the cell under the finger goes in; above the row or just below the key, the key itself (no cell); more
    // than a key's height below the key, nothing at all.
    @Test func aReleaseAwayFromTheRowTypesWhatApplesDoes() {
        let e = CGRect(x: 81, y: 60, width: 33, height: 42)
        let row = KeyPopup.Callout(count: 10, start: 2, key: e, bounds: bounds, top: -48)
        let x = row.cells[1].midX // é
        #expect(row.index(at: CGPoint(x: x, y: row.band.minY)) == 1)
        #expect(row.index(at: CGPoint(x: x, y: e.maxY)) == 1)
        #expect(row.index(at: CGPoint(x: x, y: row.band.minY - 1)) == nil)
        #expect(row.index(at: CGPoint(x: x, y: e.maxY + 1)) == nil)
        #expect(!row.cancels(at: CGPoint(x: x, y: e.maxY + e.height)))
        #expect(row.cancels(at: CGPoint(x: x, y: e.maxY + e.height + 1)))
        #expect(!row.cancels(at: CGPoint(x: x, y: row.band.minY - 200))) // far above: the key itself
    }
}
