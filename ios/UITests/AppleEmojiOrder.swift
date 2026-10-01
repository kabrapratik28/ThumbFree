import XCTest

/// Not a test: `tools/dump-apple-emoji.sh` runs it to read the layout of Apple's Emoji keyboard (its sections and the
/// order of the emoji in them, Frequently Used included) from the keyboard's accessibility tree, where each emoji key's
/// identifier is the emoji itself. `tools/gen-emoji.py` builds the picker from that file. Skipped in normal runs.
@MainActor final class AppleEmojiOrder: XCTestCase {
    private struct Item { let text: String; let x: Double; let y: Double }

    func testDumpAppleEmojiOrder() throws {
        guard let out = ProcessInfo.processInfo.environment["TF_APPLE_EMOJI_OUT"], !out.isEmpty else {
            throw XCTSkip("not a test: tools/dump-apple-emoji.sh runs it")
        }
        let app = ThumbFreeUI.launch()
        ThumbFreeUI.element("try.field", in: app).tap()
        // To Apple's letters with the system's globe (the Emoji keyboard's ABC has the same label, so the first match), then
        // Apple's emoji key; unless iOS brought back the Emoji keyboard itself.
        let emojiKeyboard = app.keyboards.collectionViews.firstMatch
        let emojiKey = app.keyboards.buttons.matching(identifier: "emoji").firstMatch
        for _ in 0..<5 where !emojiKeyboard.exists && !emojiKey.waitForExistence(timeout: 2) { app.buttons["Next keyboard"].firstMatch.tap() }
        if !emojiKeyboard.exists { emojiKey.tap() }
        XCTAssertTrue(emojiKeyboard.waitForExistence(timeout: 5), "Apple's emoji keyboard did not open")
        dragY = emojiKeyboard.frame.midY // the drags run along the middle of Apple's grid, whatever the iPhone
        // Back to the very start (Frequently Used), however far an earlier use left the grid (at most 300 drags).
        var keys = page(app).keys
        var still = 0
        for _ in 0..<300 where still < 3 {
            drag(app, from: 100, to: 400)
            let now = page(app).keys
            if let shift = shift(from: keys, to: now), abs(shift) >= 1 { still = 0 } else { still += 1 }
            keys = now
        }
        XCTAssertEqual(still, 3, "the grid did not come to its start")
        // Then forward in short slow drags (no fling), placing every key at its distance from the start.
        var grid: [String: Item] = [:]
        var starts: [(title: String, x: Double)] = [("FREQUENTLY USED", -10)]
        var offset = 0.0
        var current = page(app)
        func record(_ page: (keys: [Item], titles: [Item])) {
            for k in page.keys { grid[String(format: "%.0f %.1f", k.x + offset, k.y)] = Item(text: k.text, x: k.x + offset, y: k.y) }
            // A title first seen away from the left edge is not pinned: its section starts 2 pt to its left.
            for t in page.titles where t.x > 20 && !starts.contains(where: { $0.title == t.text }) { starts.append((t.text, t.x + offset - 2)) }
        }
        record(current)
        still = 0
        for _ in 0..<900 where still < 4 {
            drag(app, from: 300, to: 140)
            let now = page(app)
            let moved = try XCTUnwrap(shift(from: current.keys, to: now.keys), "lost track of the grid")
            still = moved < 1 ? still + 1 : 0
            offset += moved
            record(now)
            current = now
        }
        // One line per section, in Apple's order: its title, a tab, then its emoji in order, split by spaces.
        var sections: [(title: String, emoji: [String])] = []
        for item in grid.values.sorted(by: { ($0.x, $0.y) < ($1.x, $1.y) }) {
            let title = starts.filter { $0.x <= item.x + 1 }.max { $0.x < $1.x }?.title ?? "?"
            if sections.last?.title == title { sections[sections.count - 1].emoji.append(item.text) } else { sections.append((title, [item.text])) }
        }
        // A whole read: Apple's nine sections in order, none empty, no emoji listed twice outside Frequently Used.
        let titles = ["FREQUENTLY USED", "SMILEYS & PEOPLE", "ANIMALS & NATURE", "FOOD & DRINK", "ACTIVITY", "TRAVEL & PLACES",
                      "OBJECTS", "SYMBOLS", "FLAGS"]
        XCTAssertEqual(sections.map(\.title), titles, "the sections read are not Apple's nine")
        let fixed = sections.dropFirst().flatMap(\.emoji)
        XCTAssertEqual(Set(fixed).count, fixed.count, "an emoji was read twice")
        XCTAssertGreaterThan(fixed.count, 1_800, "the read stopped early")
        var text = "# Apple's Emoji keyboard, read by tools/dump-apple-emoji.sh on the iOS \(UIDevice.current.systemVersion) Simulator. Do not edit by hand.\n"
        for section in sections { text += "\(section.title)\t\(section.emoji.joined(separator: " "))\n" }
        try text.write(toFile: out, atomically: true, encoding: .utf8)
    }

    /// The visible emoji keys and section titles, parsed from one tree snapshot (one round trip, not one per key).
    private func page(_ app: XCUIApplication) -> (keys: [Item], titles: [Item]) {
        var keys: [Item] = [], titles: [Item] = []
        let key = /Key, 0x[0-9a-f]+, \{\{(-?[0-9.]+), (-?[0-9.]+)\}, \{32\.0, 32\.0\}\}, identifier: '([^']+)'/
        let title = /Other, 0x[0-9a-f]+, \{\{(-?[0-9.]+), (-?[0-9.]+)\}, \{[0-9.]+, 26\.0\}\}, label: '([^']+)'/
        for line in app.keyboards.firstMatch.debugDescription.split(separator: "\n") {
            if let m = line.firstMatch(of: key), let x = Double(m.1), let y = Double(m.2) { keys.append(Item(text: String(m.3), x: x, y: y)) }
            else if let m = line.firstMatch(of: title), let x = Double(m.1), let y = Double(m.2) { titles.append(Item(text: String(m.3), x: x, y: y)) }
        }
        return (keys, titles)
    }

    private var dragY = 0.0

    private func drag(_ app: XCUIApplication, from x0: Double, to x1: Double) {
        let origin = app.coordinate(withNormalizedOffset: .zero)
        origin.withOffset(CGVector(dx: x0, dy: dragY))
            .press(forDuration: 0.05, thenDragTo: origin.withOffset(CGVector(dx: x1, dy: dragY)), withVelocity: .slow, thenHoldForDuration: 0.6)
    }

    /// How far the grid moved between two snapshots: the distance most keys seen in both agree on (an emoji shown twice,
    /// in Frequently Used and its own section, cannot skew it). nil when fewer than three keys agree.
    private func shift(from previous: [Item], to now: [Item]) -> Double? {
        let old = Dictionary(previous.map { ($0.text, $0.x) }, uniquingKeysWith: { first, _ in first })
        var votes: [Double: Int] = [:]
        for k in now { if let x = old[k.text] { votes[((x - k.x) * 2).rounded() / 2, default: 0] += 1 } }
        guard let best = votes.max(by: { $0.value < $1.value }), best.value >= 3 else { return nil }
        return best.key
    }
}
