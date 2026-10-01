import XCTest

/// Not a test: `tools/dump-apple-keyboard.sh` runs it to read Apple's English (US) keyboard on the Simulator: the keys of
/// each layout (for every field type a keyboard can serve) with their widths, and the letters and symbols a long press
/// offers, in Apple's order. `tools/keyboard/apple-layouts.txt` and `tools/keyboard/apple-alternates.txt` hold what it
/// read; unit tests hold ThumbFree's keyboard to them. Skipped in normal runs.
@MainActor final class AppleKeyboardReader: XCTestCase {
    /// The field types a custom keyboard can serve (phone pads always get Apple's keyboard), by `UIKeyboardType` raw value.
    private let kinds: [(name: String, type: Int)] = [("text", 0), ("ascii", 1), ("numbersFirst", 2), ("url", 3), ("numberPad", 4),
                                                      ("email", 7), ("decimalPad", 8), ("twitter", 9), ("webSearch", 10), ("asciiNumberPad", 11)]

    func testReadAppleLayouts() throws {
        guard let out = ProcessInfo.processInfo.environment["TF_APPLE_LAYOUTS_OUT"], !out.isEmpty else {
            throw XCTSkip("not a test: tools/dump-apple-keyboard.sh runs it")
        }
        KeyboardSetup.ensureReady()
        var lines: [String] = []
        for kind in kinds {
            let (app, _) = appleField(type: kind.type)
            var seen: [String: [[Key]]] = [:]
            for _ in 0..<3 {
                let (layer, rows) = snapshot(app)
                seen[layer] = rows
                if layer == "pad" { break }
                // letters -> 123 -> #+= -> letters, whichever the field opens on.
                if layer == "letters" { tapKey(app, id: "more") } else if layer == "numbers" { tapKey(app, id: "shift") } else { tapKey(app, id: "more") }
                Thread.sleep(forTimeInterval: 0.8)
            }
            for layer in ["letters", "numbers", "symbols", "pad"] {
                guard let rows = seen[layer] else { continue }
                lines.append(([kind.name, layer] + rows.map { $0.map { "\($0.token):\(Int($0.width.rounded()))" }.joined(separator: " ") }).joined(separator: "\t"))
            }
            app.terminate()
        }
        XCTAssertEqual(lines.count, 7 * 3 + 3, "not every layout was read") // 7 kinds with three layers, three digit pads
        XCTAssertTrue(lines.first?.hasPrefix("text\tletters\tq:") == true, "not Apple's English (US) keyboard")
        let header = "# Apple's keyboard, read by tools/dump-apple-keyboard.sh on the iOS \(UIDevice.current.systemVersion) Simulator"
            + " (\(Int(app0Width)) pt wide, portrait). Do not edit by hand.\n# kind<TAB>layer<TAB>each row: key:touch width in points\n"
        try write(header + lines.joined(separator: "\n") + "\n", to: out)
    }

    func testReadAppleAlternates() throws {
        guard let out = ProcessInfo.processInfo.environment["TF_APPLE_ALTERNATES_OUT"], !out.isEmpty else {
            throw XCTSkip("not a test: tools/dump-apple-keyboard.sh runs it")
        }
        KeyboardSetup.ensureReady()
        // A word prediction, a word slid across the letters or a curly quote would change what a release typed: off
        // while reading (through Settings, which Apple's keyboard follows at once), back as they were after.
        let saved = setKeyboardSettings(["KeyboardPrediction": false, "KeyboardAutocorrection": false,
                                         "KeyboardContinuousPathEnabled": false, "SmartTyping": false])
        addTeardownBlock { @MainActor in _ = self.setKeyboardSettings(saved) }
        var lines: [String] = []
        // `TF_APPLE_ALTERNATES_ONLY=e,$` reads just those keys of a text field (a quick check of one key).
        let only = ProcessInfo.processInfo.environment["TF_APPLE_ALTERNATES_ONLY"].map { Set($0.split(separator: ",").map(String.init)) }
        // Every key of the letters, 123 and #+= layers of a text field, the letters in both cases; then the keys only
        // other fields have (their bottom rows).
        do {
            let (app, field) = appleField(type: 0)
            typeWithApple(app, "qzx") // no automatic capital from here on, and a start no word prediction completes
            for layer in ["letters", "LETTERS", "numbers", "symbols"] {
                let labels = snapshot(reach(app, layer)).rows.flatMap { $0 }.filter(\.types).map(\.token)
                    .filter { label in layer != "LETTERS" || lines.contains { $0.hasPrefix("text\tletters\t\(label)\t") } } // capitals: only letters with alternatives
                for label in labels where only?.contains(label) ?? true && !(layer == "symbols" && lines.contains { $0.hasPrefix("text\tnumbers\t\(label)\t") }) {
                    if let line = read(app, field: field, key: label, layer: layer, kind: "text", layerName: layer == "symbols" ? "numbers" : layer.lowercased()) {
                        lines.append(line)
                    }
                }
            }
            app.terminate()
        }
        for kind in kinds where only == nil && ["url", "email", "twitter", "webSearch"].contains(kind.name) {
            let (app, field) = appleField(type: kind.type)
            typeWithApple(app, "qzx")
            let bottom = snapshot(app).rows.last ?? []
            for key in bottom where key.types {
                if let line = read(app, field: field, key: key.token, layer: "letters", kind: kind.name, layerName: "letters") { lines.append(line) }
            }
            app.terminate()
        }
        // A whole read: every letter with alternatives read in capitals too, with as many; the fields' own keys read.
        if only == nil {
            let rows = Dictionary(lines.map { line in (line.split(separator: "\t").prefix(3).joined(separator: "\t"), line.split(separator: "\t")[3].split(separator: " ").count) },
                                  uniquingKeysWith: { first, _ in first })
            for (key, count) in rows where key.hasPrefix("text\tletters\t") {
                let letter = key.dropFirst("text\tletters\t".count)
                guard letter == letter.lowercased() else { continue }
                XCTAssertEqual(rows["text\tletters\t\(letter.uppercased())"], count, "capital \(letter.uppercased()) not read like \(letter)")
            }
            XCTAssertGreaterThanOrEqual(lines.count, 50, "the read stopped early")
        }
        let header = "# Apple's long-press alternatives, read by tools/dump-apple-keyboard.sh on the iOS \(UIDevice.current.systemVersion) Simulator."
            + " Do not edit by hand.\n# kind<TAB>layer<TAB>key<TAB>the alternatives left to right<TAB>the one under the finger when they open (from 0)\n"
        try write(header + lines.joined(separator: "\n") + "\n", to: only == nil ? out : out + ".only") // a partial read never replaces the file
    }

    /// Writes a read only when nothing failed, so a broken read never replaces the committed file.
    private func write(_ text: String, to path: String) throws {
        guard (testRun?.totalFailureCount ?? 0) == 0 else { return XCTFail("not written: the read had failures") }
        try text.write(toFile: path, atomically: true, encoding: .utf8)
    }

    // MARK: reading one key's alternatives

    /// Long-presses `key` and reads its alternatives left to right: from the one under the finger when they open, it
    /// slides a little further each time to the left, then to the right, and lets go, recording what each release typed,
    /// until a release types a key of the layout (the finger left the alternatives), nothing, or one already read. nil when
    /// the key has none.
    private func read(_ app: XCUIApplication, field: XCUIElement, key label: String, layer: String, kind: String, layerName: String) -> String? {
        let width = app.windows.firstMatch.frame.width
        let keys = snapshot(reach(app, layer)).rows.flatMap { $0 }
        guard let frame = keys.first(where: { $0.token == label })?.frame else { return nil }
        let plain = Set(keys.map(\.token) + keys.map { $0.token.uppercased() } + [" ", "\n"])
        var start = typed(app, field: field, frame: frame, layer: layer, at: frame.midX)
        if start.isEmpty { start = typed(app, field: field, frame: frame, layer: layer, at: frame.midX) } // a press that misfired
        guard !start.isEmpty else { return nil }
        var row = [start]
        for direction in [-1.0, 1.0] {
            var previous = start
            var x = frame.midX
            while true {
                x += direction * 24 // less than the narrowest cell (twelve alternatives across the screen)
                guard x > 4, x < width - 4 else { break }
                // Until alternatives have shown (one read, or a start that is not the key itself, as web search's .com),
                // never let go over a function key: delete would edit the field, 123 would change the layout.
                if row.count == 1, start == label, keys.contains(where: { !$0.types && $0.frame.contains(CGPoint(x: x, y: frame.midY)) }) { break }
                var text = typed(app, field: field, frame: frame, layer: layer, at: x)
                let stops = { (t: String) in t.isEmpty || row.contains(t) || (plain.contains(t) && t != start) }
                if text != previous, stops(text) { text = typed(app, field: field, frame: frame, layer: layer, at: x) } // confirm the end
                if text == previous { continue }                                  // still in the same cell
                if stops(text) { break }                                           // outside
                if direction < 0 { row.insert(text, at: 0) } else { row.append(text) }
                previous = text
            }
        }
        guard row.count > 1, let index = row.firstIndex(of: start) else { return nil }
        let shown = layer == "LETTERS" ? label.uppercased() : label
        return [kind, layerName, shown, row.joined(separator: " "), String(index)].joined(separator: "\t")
    }

    /// One long press on the key at `frame`, slid to `x` and let go: what it typed, deleted again.
    private func typed(_ app: XCUIApplication, field: XCUIElement, frame: CGRect, layer: String, at x: Double) -> String {
        // A typed apostrophe goes back to the letters, and a capital turns shift off: bring the layer back first.
        let probe = layer == "numbers" ? "1" : layer == "symbols" ? "[" : "q"
        if !app.keyboards.keys.matching(NSPredicate(format: "label ==[c] %@", probe)).firstMatch.exists { reach(app, layer) }
        if layer == "LETTERS", !app.keyboards.buttons["shift"].firstMatch.isSelected { app.keyboards.buttons["shift"].firstMatch.tap() }
        let before = value(field)
        let origin = app.coordinate(withNormalizedOffset: .zero)
        origin.withOffset(CGVector(dx: frame.midX, dy: frame.midY))
            .press(forDuration: 0.8, thenDragTo: origin.withOffset(CGVector(dx: x, dy: frame.midY)), withVelocity: XCUIGestureVelocity(600), thenHoldForDuration: 0.1)
        let text = String(value(field).dropFirst(before.count))
        for _ in 0..<text.count { app.keyboards.keys["delete"].firstMatch.tap() }
        return text
    }

    // MARK: Apple's keyboard, from one tree snapshot

    private struct Key { let token: String; let frame: CGRect; let types: Bool; var width: Double { frame.width } }

    private var app0Width = 0.0

    /// The keys on screen, in rows, and which layer they make: one debug-description snapshot (one round trip).
    private func snapshot(_ app: XCUIApplication) -> (layer: String, rows: [[Key]]) {
        let pattern = /(Key|Button), 0x[0-9a-f]+, \{\{(-?[0-9.]+), (-?[0-9.]+)\}, \{([0-9.]+), ([0-9.]+)\}\}(?:, identifier: '([^']*)')?(?:, label: '(.+?)')?(?:, |$)/
        var keys: [Key] = []
        for line in app.keyboards.firstMatch.debugDescription.split(separator: "\n") {
            guard let m = line.firstMatch(of: pattern), let x = Double(m.2), let y = Double(m.3), let w = Double(m.4), let h = Double(m.5), w > 0 else { continue }
            let id = m.6.map(String.init) ?? "", label = m.7.map(String.init) ?? ""
            guard !label.hasPrefix("Padding") else { continue }
            let token: String
            switch (id, label) {
            case ("more", "numbers"), ("shift", "numbers"): token = "123"
            case ("more", _): token = "ABC"
            case ("shift", "symbols"): token = "#+="
            case ("shift", _): token = "shift"
            case ("emoji", _): token = "emoji"
            case ("space", _): token = "space"
            case ("delete", _), (_, "Delete"): token = "delete"
            case (_, "ampersand"): token = "&"
            case (_, ""): token = "blank"
            default: token = m.1 == "Button" ? "return" : (label.count == 1 ? label.lowercased() : label)
            }
            let function = ["123", "ABC", "#+=", "shift", "emoji", "space", "delete", "return", "blank"].contains(token)
            keys.append(Key(token: token, frame: CGRect(x: x, y: y, width: w, height: h), types: !function))
        }
        app0Width = app.windows.firstMatch.frame.width
        var rows: [[Key]] = []
        for key in keys.sorted(by: { ($0.frame.minY, $0.frame.minX) < ($1.frame.minY, $1.frame.minX) }) {
            if let last = rows.last?.first, abs(last.frame.minY - key.frame.minY) < 2 { rows[rows.count - 1].append(key) } else { rows.append([key]) }
        }
        let tokens = Set(keys.map(\.token))
        let layer = tokens.contains("q") ? "letters" : tokens.contains("[") ? "symbols" : tokens.contains("-") ? "numbers" : "pad"
        return (layer, rows)
    }

    /// Brings `layer` up: "letters" in small letters, "LETTERS" with shift on for one capital, "numbers" or "symbols".
    @discardableResult private func reach(_ app: XCUIApplication, _ layer: String) -> XCUIApplication {
        for _ in 0..<3 {
            let now = snapshot(app).layer
            let want = layer == "LETTERS" ? "letters" : layer
            if now == want { break }
            if now == "letters" { tapKey(app, id: "more") }
            else if now == "numbers" { want == "symbols" ? tapKey(app, id: "shift") : tapKey(app, id: "more") }
            else { want == "numbers" ? tapKey(app, id: "shift") : tapKey(app, id: "more") }
            Thread.sleep(forTimeInterval: 0.5)
        }
        let shift = app.keyboards.buttons["shift"].firstMatch
        if layer == "LETTERS", !shift.isSelected { shift.tap() }
        if layer == "letters", shift.isSelected { shift.tap() }
        return app
    }

    private func tapKey(_ app: XCUIApplication, id: String) {
        app.keyboards.descendants(matching: .any).matching(identifier: id).firstMatch.tap()
    }

    /// Sets switches in Settings, General, Keyboard (by their identifiers) and returns what they were.
    private func setKeyboardSettings(_ values: [String: Bool]) -> [String: Bool] {
        let settings = XCUIApplication(bundleIdentifier: "com.apple.Preferences")
        settings.terminate()
        settings.launch()
        settings.staticTexts["General"].tap()
        settings.staticTexts["Keyboard"].tap()
        var old: [String: Bool] = [:]
        let height = settings.windows.firstMatch.frame.height
        for (id, on) in values.sorted(by: { $0.key < $1.key }) {
            let toggle = settings.switches[id]
            let shown = { toggle.exists && toggle.frame.minY > 100 && toggle.frame.maxY < height - 40 } // clear of the title and the home bar
            for _ in 0..<8 where !shown() { settings.swipeUp() }
            for _ in 0..<8 where !shown() { settings.swipeDown() }
            guard shown() else { XCTFail("no \(id) switch in Settings"); continue }
            old[id] = toggle.value as? String == "1"
            // A plain tap lands on the row's label; the switch's knob is at the row's right end.
            if old[id] != on { toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap() }
            XCTAssertEqual(toggle.value as? String == "1", on, "\(id) did not change")
        }
        settings.terminate()
        return old
    }

    // MARK: the field

    /// The Try tab's field as `type` (`-TFFieldType`), with Apple's keyboard up.
    private func appleField(type: Int) -> (XCUIApplication, XCUIElement) {
        let app = ThumbFreeUI.launch(arguments: ["-TFFieldType", String(type)])
        let field = ThumbFreeUI.element("try.field", in: app)
        field.tap()
        // Away from ThumbFree's keyboard (its mic) and Apple's Emoji keyboard (its grid), to Apple's letters.
        for _ in 0..<6 {
            if !app.buttons["keyboard.mic"].waitForExistence(timeout: 1.5), !app.keyboards.collectionViews.firstMatch.exists,
               app.keyboards.firstMatch.exists { break }
            app.buttons["Next keyboard"].firstMatch.tap()
        }
        XCTAssertFalse(app.buttons["keyboard.mic"].exists, "Apple's keyboard did not come up")
        return (app, field)
    }

    private func typeWithApple(_ app: XCUIApplication, _ text: String) {
        for c in text { app.keyboards.keys.matching(NSPredicate(format: "label ==[c] %@", String(c))).firstMatch.tap() }
    }

    private func value(_ field: XCUIElement) -> String {
        let text = field.value as? String ?? ""
        return text == "Try it here" ? "" : text // an empty field's value is its placeholder
    }
}
