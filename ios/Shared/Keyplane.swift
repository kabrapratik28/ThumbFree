import CoreGraphics
import UIKit

/// The Apple-style keyboard's pure model: the keys of each layer, where they sit for a given size, which key a touch
/// hits, the shift state machine, the return key's label, and the double-space rule. It has no view and no side
/// effects, so it is unit-tested on the Simulator through the app target (`Shared/` compiles into both the app and the
/// keyboard). The `KeyplaneView` (UIKit) draws these keys and forwards raw touches to `hitKey(at:)`.
enum Key: Equatable, Hashable {
    case letter(Character)   // always stored lowercase; the shift state decides the case shown and inserted
    case symbol(Character)   // a digit or punctuation on the 123 and #+= layers, inserted as is
    case text(String)        // a key that types several characters: the web address keyboard's ".com"
    case shift
    case delete
    case toNumbers           // "123"
    case toSymbols           // "#+="
    case toLetters           // "ABC"
    case space
    case ret                 // return, labelled for the field
    case globe               // next keyboard, only when needsInputModeSwitchKey is true
    case emoji               // opens ThumbFree's emoji picker, as Apple's emoji key opens its emoji keyboard

    /// A key that types its own character (a letter, a digit or punctuation, or ".com"); every other key is a function key.
    var isCharacter: Bool {
        switch self {
        case .letter, .symbol, .text: true
        default: false
        }
    }

    /// A stable id for the accessibility element and the UI-test identifier, for example `keyboard.key.a`,
    /// `keyboard.key.1`, `keyboard.shift`. Punctuation uses a name, so the id is always a plain token.
    var identifier: String {
        switch self {
        case .letter(let c): "keyboard.key.\(c)"
        case .symbol(let c): "keyboard.key.\(Self.symbolName(c))"
        case .text(let text): "keyboard.key.\(text.map(Self.symbolName).joined())"
        case .shift: "keyboard.shift"
        case .delete: "keyboard.delete"
        case .toNumbers: "keyboard.toNumbers"
        case .toSymbols: "keyboard.toSymbols"
        case .toLetters: "keyboard.toLetters"
        case .space: "keyboard.space"
        case .ret: "keyboard.return"
        case .globe: "keyboard.globe"
        case .emoji: "keyboard.emoji"
        }
    }

    /// A short token for a symbol, so an identifier never carries a slash or a quote. Digits and letters map to themselves.
    static func symbolName(_ c: Character) -> String {
        if c.isLetter || c.isNumber { return String(c) }
        let names: [Character: String] = [
            " ": "space", ".": "period", ",": "comma", "?": "question", "!": "exclaim", "'": "apostrophe",
            "\"": "quote", "-": "hyphen", "/": "slash", ":": "colon", ";": "semicolon", "(": "leftParen",
            ")": "rightParen", "$": "dollar", "&": "amp", "@": "at", "[": "leftBracket", "]": "rightBracket",
            "{": "leftBrace", "}": "rightBrace", "#": "hash", "%": "percent", "^": "caret", "*": "star",
            "+": "plus", "=": "equals", "_": "underscore", "\\": "backslash", "|": "pipe", "~": "tilde",
            "<": "less", ">": "greater", "\u{20AC}": "euro", "\u{00A3}": "pound", "\u{00A5}": "yen", "\u{2022}": "bullet",
        ]
        return names[c] ?? String(c.unicodeScalars.map { String(format: "u%04X", $0.value) }.joined())
    }

    /// What VoiceOver reads for the key. Letters read the case they would insert; shift tells one capital from caps lock.
    func label(shift: ShiftState, returnLabel: String) -> String {
        switch self {
        case .letter(let c): String(shift.isUpper ? Character(c.uppercased()) : c)
        case .symbol(let c): Self.symbolLabel(c)
        case .text(let text): text
        case .shift:
            switch shift {
            case .off: "Shift"
            case .oneShot: "Shift, on"
            case .capsLock: "Caps Lock, on"
            }
        case .delete: "Delete"
        case .toNumbers: "Numbers"
        case .toSymbols: "Symbols"
        case .toLetters: "Letters"
        case .space: "Space"
        case .ret: returnLabel
        case .globe: "Next keyboard"
        case .emoji: "Emoji"
        }
    }

    /// VoiceOver's traits for the key: every key is a keyboard key, and shift is also selected while it is on.
    func traits(shift: ShiftState) -> UIAccessibilityTraits {
        self == .shift && shift.isUpper ? [.keyboardKey, .selected] : .keyboardKey
    }

    private static func symbolLabel(_ c: Character) -> String {
        let names: [Character: String] = [
            ".": "Period", ",": "Comma", "?": "Question mark", "!": "Exclamation mark", "'": "Apostrophe",
            "\"": "Quotation mark", "-": "Hyphen", "/": "Slash", ":": "Colon", ";": "Semicolon", "(": "Left parenthesis",
            ")": "Right parenthesis", "$": "Dollar sign", "&": "Ampersand", "@": "At sign",
            "[": "Left bracket", "]": "Right bracket", "{": "Left brace", "}": "Right brace", "#": "Hash",
            "%": "Percent", "^": "Caret", "*": "Asterisk", "+": "Plus", "=": "Equals", "_": "Underscore",
            "\\": "Backslash", "|": "Vertical bar", "~": "Tilde", "<": "Less than", ">": "Greater than",
            "\u{20AC}": "Euro", "\u{00A3}": "Pound", "\u{00A5}": "Yen", "\u{2022}": "Bullet",
        ]
        return names[c] ?? String(c)
    }
}

/// Which layer is on screen. Typing a space or a return on the numbers or symbols layer goes back to letters, once
/// something was typed there, as Apple's keyboard does (read on the iOS 26.5 Simulator).
enum KeyLayer: Equatable {
    case letters, numbers, symbols

    /// The layer once `key` is typed on this one: a switch key goes where it says; on 123 or #+= an apostrophe goes back to
    /// letters (for contractions like "don't"), and so do a space and a return once a key was typed on 123 or #+=
    /// (`typedHere`: a space right after 123 stays on 123, as on Apple's). Every other key leaves the layer as it is.
    func after(_ key: Key, typedHere: Bool = true) -> KeyLayer {
        switch key {
        case .toNumbers: .numbers
        case .toSymbols: .symbols
        case .toLetters, .symbol("'"): .letters
        case .space, .ret: typedHere ? .letters : self
        default: self
        }
    }

    /// Apple's quick slide: keys a finger can touch and slide from, to let go on another key. 123 on the letters, ABC, and
    /// #+= on 123 switch the layer the moment they are touched; shift shows capitals while it is held. 123 on #+= acts
    /// on release, as on Apple's, so a slide from it types the #+= key it ends on.
    func slidesFrom(_ key: Key) -> Bool {
        switch (self, key) {
        case (.letters, .toNumbers), (.letters, .shift), (.numbers, .toLetters), (.numbers, .toSymbols), (.symbols, .toLetters): true
        default: false
        }
    }
}

/// The field's keyboard type, as Apple's keyboard lays it out (read from Apple's keyboard on the iOS 26.5 Simulator into
/// `tools/keyboard/apple-layouts.txt`). Phone pads never reach a custom keyboard: iOS shows its own there.
enum KeyboardKind: String, CaseIterable {
    case text, ascii, numbersFirst, url, email, twitter, webSearch, numberPad, decimalPad

    init(_ type: UIKeyboardType?) {
        switch type {
        case .asciiCapable?: self = .ascii
        case .numbersAndPunctuation?: self = .numbersFirst
        case .URL?: self = .url
        case .emailAddress?: self = .email
        case .twitter?: self = .twitter
        case .webSearch?: self = .webSearch
        case .numberPad?, .asciiCapableNumberPad?: self = .numberPad
        case .decimalPad?: self = .decimalPad
        default: self = .text
        }
    }

    /// Apple's ASCII-capable and numbers-and-punctuation keyboards have no emoji key.
    var hasEmojiKey: Bool { self != .ascii && self != .numbersFirst }
    /// Where a new field opens: a numbers-and-punctuation field on 123, every other on the letters.
    var firstLayer: KeyLayer { self == .numbersFirst ? .numbers : .letters }
    /// Number and decimal fields get Apple's digit pad instead of the letters.
    var isDigitPad: Bool { self == .numberPad || self == .decimalPad }

    /// The letters' bottom row after the layer, globe and emoji keys: Apple's keys for each field.
    fileprivate var lettersTail: [Key] {
        switch self {
        case .url: [.symbol("."), .symbol("/"), .text(".com"), .ret]
        case .email: [.space, .symbol("@"), .symbol("."), .ret]
        case .twitter: [.space, .symbol("@"), .symbol("#")]
        case .webSearch: [.space, .symbol("."), .ret]
        default: [.space, .ret]
        }
    }
}

/// The rows of each layer, in Apple's arrangement. Function keys (shift, delete, the layer switches, the emoji key,
/// space, return, globe) frame the character rows.
enum Keyplane {
    /// `showsEmoji` is false while the keys type into the emoji search, whose bottom row, like Apple's, has no emoji key.
    static func rows(_ layer: KeyLayer, kind: KeyboardKind = .text, showsGlobe: Bool, showsEmoji: Bool = true) -> [[Key]] {
        if kind.isDigitPad {
            // Apple's digit pad: the decimal point (or nothing), 0 and delete under the digits; the globe, when the
            // keyboard draws one, shares the empty corner.
            let last: [Key] = (showsGlobe ? [.globe] : []) + (kind == .decimalPad ? [.symbol(decimalPoint)] : []) + [.symbol("0"), .delete]
            return ["123", "456", "789"].map { $0.map(Key.symbol) } + [last]
        }
        // Apple's bottom row: the layer key, the globe where the keyboard draws its own, the emoji key, then the field's
        // keys on the letters (space and return on the others).
        let bottom: [Key] = [layer == .letters ? .toNumbers : .toLetters] + (showsGlobe ? [.globe] : [])
            + (showsEmoji && kind.hasEmojiKey ? [.emoji] : []) + (layer == .letters ? kind.lettersTail : [.space, .ret])
        switch layer {
        case .letters:
            return [
                "qwertyuiop".map(Key.letter),
                "asdfghjkl".map(Key.letter),
                [.shift] + "zxcvbnm".map(Key.letter) + [.delete],
                bottom,
            ]
        case .numbers:
            return [
                "1234567890".map(Key.symbol),
                "-/:;()$&@\"".map(Key.symbol),
                [.toSymbols] + ".,?!'".map(Key.symbol) + [.delete],
                bottom,
            ]
        case .symbols:
            return [
                "[]{}#%^*+=".map(Key.symbol),
                "_\\|~<>\u{20AC}\u{00A3}\u{00A5}\u{2022}".map(Key.symbol),
                [.toNumbers] + ".,?!'".map(Key.symbol) + [.delete],
                bottom,
            ]
        }
    }

    /// The decimal pad's point: this iPhone's decimal separator, as Apple's pad shows it.
    static var decimalPoint: Character { Locale.current.decimalSeparator?.first ?? "." }
}

/// One key's place on screen.
struct KeyFrame: Equatable {
    let key: Key
    let frame: CGRect
}

/// Lays the rows out in `bounds` the way Apple's keyboard does (`tools/keyboard/apple-layouts.txt`): four rows; letters one
/// unit wide (a tenth of the width, with its gap); the third row's shift (or #+=, 123) and delete 1.3 units at the edges,
/// its middle keys filling seven units centered between them; the bottom row at Apple's widths, space taking what is left
/// (in landscape Apple's narrower 123, emoji and return keys); and Apple's digit pad for number fields. Pure geometry, so a
/// test can check the shape without a screen.
struct KeyLayout: Equatable {
    let frames: [KeyFrame]
    /// Where a touch types nothing: the number pad's empty corner, as on Apple's pad.
    let blank: [CGRect]
    /// Apple's keys start 6.7 pt from the keyboard's edges (measured on a 390 pt iPhone); its caps are 40.4 pt tall.
    static let sideInset: CGFloat = 6.7

    /// `bounds` is the keyplane's area (below the bar). Landscape passes a shorter height and a wider width. `showsEmoji` is
    /// false for Apple's search keys, whose bottom row has no emoji key and gives 123 and the done key a quarter of the row
    /// each.
    init(layer: KeyLayer, kind: KeyboardKind = .text, showsGlobe: Bool, showsEmoji: Bool = true, bounds: CGRect) {
        let rows = Keyplane.rows(layer, kind: kind, showsGlobe: showsGlobe, showsEmoji: showsEmoji)
        if kind.isDigitPad {
            let pad = Self.digitPad(rows, bounds: bounds)
            frames = pad.frames
            blank = pad.blank
            return
        }
        let compact = bounds.height < 200 // landscape
        let sideInset = Self.sideInset
        let rowGap: CGFloat = compact ? 8 : 12.6 // 212 pt rows of Apple's 40.4 pt caps
        let keyGap: CGFloat = 6
        let top: CGFloat = bounds.minY + rowGap / 2
        let rowHeight = (bounds.height - rowGap * CGFloat(rows.count)) / CGFloat(rows.count)
        // A unit: one letter key and its gap, ten across, so every layer's keys line up.
        let unit = (bounds.width - sideInset * 2 - keyGap * 9) / 10 + keyGap
        var out: [KeyFrame] = []
        for (r, row) in rows.enumerated() {
            let y = top + CGFloat(r) * (rowHeight + rowGap)
            let widths: [CGFloat?]
            switch r {
            case 2: widths = Self.thirdRow(row, unit: unit, keyGap: keyGap)
            case rows.count - 1:
                let quarter = (bounds.width - sideInset * 2 - keyGap * CGFloat(row.count - 1)) / 4 // Apple's search keys
                widths = row.map { key in
                    !showsEmoji && [.toNumbers, .toLetters, .ret].contains(key) ? quarter
                        : Self.bottomUnits(key, in: row, kind: kind, layer: layer, compact: compact).map { $0 * unit - keyGap }
                }
            default: widths = row.map { _ in unit - keyGap }
            }
            out += Self.place(row, widths: widths, pinEnds: r == 2, y: y, height: rowHeight, bounds: bounds, sideInset: sideInset, keyGap: keyGap)
        }
        frames = out
        blank = []
    }

    /// The third row: the keys at its ends 1.3 units, the keys between sharing seven units (seven letters, or Apple's five
    /// wide punctuation keys on 123 and #+=).
    private static func thirdRow(_ row: [Key], unit: CGFloat, keyGap: CGFloat) -> [CGFloat?] {
        let middle = CGFloat(row.count - 2)
        let each = (unit * 7 - keyGap - keyGap * (middle - 1)) / middle
        return row.indices.map { $0 == 0 || $0 == row.count - 1 ? unit * 1.3 - keyGap : each }
    }

    /// A bottom-row key's width in units (nil: it shares what the others leave). Apple's widths, read from its keyboard:
    /// 123 (or ABC), emoji and globe 1.25 units (1 in landscape), return 2.5 (2); the web search keyboard's . and return
    /// 1 and 1.75 (0.8 and 1.4); the email and Twitter keyboards' @, . and # like the emoji key; space, and the web address
    /// keyboard's . / .com, share the rest. Without an emoji or globe key, 123 is as wide as return.
    private static func bottomUnits(_ key: Key, in row: [Key], kind: KeyboardKind, layer: KeyLayer, compact: Bool) -> CGFloat? {
        let small: CGFloat = compact ? 1 : 1.25
        switch key {
        case .space: return nil
        case .toNumbers, .toLetters: return row.contains(.emoji) || row.contains(.globe) ? small : small * 2
        case .ret: return kind == .webSearch && layer == .letters ? (compact ? 1.4 : 1.75) : small * 2
        case .symbol, .text:
            switch kind {
            case .url: return nil
            case .webSearch: return compact ? 0.8 : 1
            default: return small
            }
        default: return small
        }
    }

    /// Places one row without overlaps. Keys whose width is nil share what the others leave, so the row spans the full
    /// width from the left inset. A third row pins its end keys to the insets and centers the keys between; a row of
    /// letters or symbols is centered (asdfghjkl sits between the edges).
    private static func place(_ row: [Key], widths: [CGFloat?], pinEnds: Bool, y: CGFloat, height: CGFloat, bounds: CGRect,
                              sideInset: CGFloat, keyGap: CGFloat) -> [KeyFrame] {
        let usable = bounds.width - sideInset * 2
        var w = widths.map { $0 ?? 0 }
        let shared = widths.indices.filter { widths[$0] == nil }
        if !shared.isEmpty {
            let rest = usable - w.reduce(0, +) - keyGap * CGFloat(row.count - 1)
            for i in shared { w[i] = max(0, rest / CGFloat(shared.count)) }
        }
        var xs: [CGFloat] = []
        if pinEnds, row.count > 2 {
            let middle = w[1..<(row.count - 1)].reduce(0, +) + keyGap * CGFloat(row.count - 3)
            var x = bounds.midX - middle / 2
            xs.append(bounds.minX + sideInset)
            for i in 1..<(row.count - 1) { xs.append(x); x += w[i] + keyGap }
            xs.append(bounds.maxX - sideInset - w[row.count - 1])
        } else {
            let total = w.reduce(0, +) + keyGap * CGFloat(row.count - 1)
            var x = shared.isEmpty ? bounds.midX - total / 2 : bounds.minX + sideInset
            for width in w { xs.append(x); x += width + keyGap }
        }
        return zip(row, zip(xs, w)).map { KeyFrame(key: $0.0, frame: CGRect(x: $0.1.0, y: y, width: $0.1.1, height: height)) }
    }

    /// Apple's digit pad: three equal columns of taller keys. The bottom row's 0 and delete take the second and third
    /// cells; the decimal point (and the globe, when shown) share the first, which is otherwise empty.
    private static func digitPad(_ rows: [[Key]], bounds: CGRect) -> (frames: [KeyFrame], blank: [CGRect]) {
        let inset: CGFloat = 6, gap: CGFloat = 8, rowGap: CGFloat = 6
        let width = (bounds.width - inset * 2 - gap * 2) / 3
        let height = (bounds.height - rowGap * CGFloat(rows.count + 1)) / CGFloat(rows.count)
        func cell(_ column: Int, _ row: Int) -> CGRect {
            CGRect(x: bounds.minX + inset + CGFloat(column) * (width + gap), y: bounds.minY + rowGap + CGFloat(row) * (height + rowGap),
                   width: width, height: height)
        }
        var frames: [KeyFrame] = []
        var blank: [CGRect] = []
        for (r, row) in rows.enumerated() where r < rows.count - 1 {
            frames += row.enumerated().map { KeyFrame(key: $0.element, frame: cell($0.offset, r)) }
        }
        let r = rows.count - 1, last = rows[r], lead = Array(last.dropLast(2)), corner = cell(0, r)
        if lead.isEmpty { blank.append(corner) }
        let leadWidth = (corner.width - gap * CGFloat(max(lead.count - 1, 0))) / CGFloat(max(lead.count, 1))
        for (i, key) in lead.enumerated() {
            frames.append(KeyFrame(key: key, frame: CGRect(x: corner.minX + CGFloat(i) * (leadWidth + gap), y: corner.minY, width: leadWidth, height: corner.height)))
        }
        for (i, key) in last.suffix(2).enumerated() { frames.append(KeyFrame(key: key, frame: cell(i + 1, r))) }
        return (frames, blank)
    }

    /// The key a touch at `point` hits: the one it is inside, else the nearest by distance to its frame, so a touch in a
    /// gap never drops. Nil when there are no keys, or in the digit pad's empty corner.
    func hitKey(at point: CGPoint) -> Key? {
        if blank.contains(where: { $0.contains(point) }) { return nil }
        var best: (key: Key, distance: CGFloat)?
        for f in frames {
            let d = Self.distance(from: point, to: f.frame)
            if d == 0 { return f.key }
            if let current = best {
                if d < current.distance { best = (f.key, d) }
            } else {
                best = (f.key, d)
            }
        }
        return best?.key
    }

    private static func distance(from p: CGPoint, to r: CGRect) -> CGFloat {
        let dx = max(r.minX - p.x, 0, p.x - r.maxX)
        let dy = max(r.minY - p.y, 0, p.y - r.maxY)
        return dx * dx + dy * dy
    }
}

/// Shift: one tap gives one capital, a double tap within the window gives caps lock. A letter inserted
/// while one-shot returns it to off; caps lock stays, across the 123 and #+= layers too (as on Apple's keyboard), until
/// the user taps shift again.
enum ShiftState: Equatable { case off, oneShot, capsLock }

extension ShiftState {
    var isUpper: Bool { self != .off }
}

/// The shift key's behavior: its state, whether the field or the user set it, and the double-tap timer. The keyboard
/// reports every shift tap, every other key, and every change of the field's text or caret; the tests drive it the same
/// way (no test target sees the keyboard's own files).
struct ShiftKey {
    private(set) var state = ShiftState.off
    /// A one capital the field asked for (a sentence start), not the user: it follows the field both ways.
    private(set) var automatic = false
    private var lastTap: Date?
    private var lastTapFrom = ShiftState.off
    /// The field and the text before the caret the last `follow` saw (`context(document:capitalization:before:)`).
    private var followed: String?

    /// A shift tap. A second tap within `windowMs` of the first locks caps, whether the first turned a one capital off
    /// (an automatic one at a sentence start, or the user's) or on; only a first tap out of caps lock never re-locks.
    mutating func tap(at now: Date, windowMs: Int = 300) {
        if let last = lastTap, (0...Double(windowMs) / 1_000).contains(now.timeIntervalSince(last)), lastTapFrom != .capsLock {
            state = .capsLock
            lastTap = nil
        } else {
            lastTapFrom = state
            state = state == .off ? .oneShot : .off
            lastTap = now
        }
        automatic = false
    }

    /// Any other key: two shift taps are a double tap only with nothing between them.
    mutating func otherKey() { lastTap = nil }

    /// After a letter typed by sliding from shift: shift is as it was when the press began, as the user's own (Apple's: a
    /// capital that was on at a sentence start stays on for the next letter too; caps lock stays; off stays off).
    mutating func restore(_ before: ShiftState) {
        state = before
        automatic = false
        lastTap = nil
    }

    /// A letter went in: one capital is spent; caps lock stays.
    mutating func typedLetter() {
        guard state == .oneShot else { return }
        state = .off
        automatic = false
    }

    /// The field's text or caret may have changed. `context` names the field and the text before the caret: when it moved,
    /// an automatic or off shift follows the field, on at a sentence start and off anywhere else (after a take went in,
    /// after deleting back past a full stop's space). The user's own one capital and caps lock stay. The same context again
    /// (a status refresh, a late notice of the same edit) changes nothing, so a capital the user turned off stays off
    /// until the text or the caret moves.
    mutating func follow(capsExpected: Bool?, context: String) {
        guard context != followed else { return }
        followed = context
        guard state == .off || automatic else { return }
        state = capsExpected == true ? .oneShot : .off
        automatic = state == .oneShot
    }

    /// The `context` for `follow`: the field (its document and its capitalization setting) and the text before the caret.
    /// Two fields that share a document and their text but capitalize differently are two contexts, so an automatic
    /// capital never carries over from one field to the other.
    static func context(document: UUID?, capitalization: UITextAutocapitalizationType?, before: String) -> String {
        "\(document?.uuidString ?? "none")|\(capitalization.map { String($0.rawValue) } ?? "unknown")|\(before)"
    }
}

/// The return key's label from the field's return key type. Unknown types read "return".
enum ReturnLabel {
    static func label(for type: UIReturnKeyType) -> String {
        switch type {
        case .go: "Go"
        case .google: "Search"
        case .join: "Join"
        case .next: "Next"
        case .route: "Route"
        case .search: "Search"
        case .send: "Send"
        case .yahoo: "Search"
        case .done: "Done"
        case .emergencyCall: "Emergency Call"
        case .continue: "Continue"
        default: "return"
        }
    }

    /// Apple tints the return key blue when it acts (go, search, send, join, route, done); a plain return, next or
    /// continue keeps the key color.
    static func isBlue(for type: UIReturnKeyType) -> Bool {
        switch type {
        case .go, .google, .join, .route, .search, .send, .yahoo, .done: true
        default: false
        }
    }

    /// Apple's iOS 26 return key shows a symbol, not a word (read on the Simulator for each type SwiftUI can set): an arrow
    /// for go, join and route, an up arrow for send, a magnifying glass for search, a check mark for done, a chevron for
    /// next and continue, the return arrow otherwise. The emergency call key keeps its words.
    static func symbol(for type: UIReturnKeyType) -> String? {
        switch type {
        case .go, .join, .route: "arrow.right"
        case .send: "arrow.up"
        case .search, .google, .yahoo: "magnifyingglass"
        case .done: "checkmark"
        case .next, .continue: "chevron.right"
        case .emergencyCall: nil
        default: "return.left"
        }
    }
}

/// Apple's delete key when held (measured on the iOS 26.5 Simulator): one character at the touch, then after half a second
/// a character every tenth of a second, and once twenty characters are gone (the touch's one too) whole words, about five
/// a second.
enum DeleteRepeat {
    /// The wait before repeat `n` (the first repeat is 1), and whether it takes a word. The emoji picker's delete keeps its
    /// own pace (`picker`): 400 ms to the first repeat, then one emoji every 80 ms, never a word.
    static func step(_ n: Int, picker: Bool = false) -> (wait: Duration, word: Bool) {
        if picker { return (.milliseconds(n == 1 ? 400 : 80), false) }
        return n == 1 ? (.milliseconds(500), false) : n < 20 ? (.milliseconds(100), false) : (.milliseconds(180), true)
    }

    /// How many characters one word takes from the end of `before` (the text before the cursor): the word, and the spaces
    /// before it, so the text ends at the previous word, as Apple's does ("one two three" leaves "one two"). At least one.
    static func wordLength(before: String) -> Int {
        let word = before.reversed().prefix { !$0.isWhitespace }.count
        let spaces = before.dropLast(word).reversed().prefix(while: \.isWhitespace).count
        return max(word + spaces, 1)
    }
}

/// Double space inserts a full stop, as on Apple's keyboard: a second space soon after the first, when a word sits
/// before it, replaces the space with ". ".
enum DoubleSpace {
    enum Action: Equatable { case space, periodSpace }

    /// `before` is the text before the cursor; `sinceLastSpaceMs` is the time since this keyboard last inserted a space
    /// (nil if it did not, or the layer or the text changed since). The rule fires only when the last character is a lone
    /// space with a letter or a digit before it, and only within `0...windowMs`: a negative time (a clock set back) never
    /// counts.
    static func onSpace(before: String?, sinceLastSpaceMs: Int?, windowMs: Int = 500) -> Action {
        guard let ms = sinceLastSpaceMs, (0...windowMs).contains(ms), let before, before.hasSuffix(" ") else { return .space }
        // A word must sit before the lone space: a letter or a digit. Punctuation (a comma, a colon, a closing bracket)
        // never becomes "x,. ".
        guard let prev = before.dropLast().last, prev.isLetter || prev.isNumber else { return .space }
        return .periodSpace
    }
}
