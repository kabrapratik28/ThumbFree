/// Skin tones, as Apple's keyboard offers them: a long press on an emoji that has them opens the tone picker, and the
/// tone chosen is remembered for that emoji, so the grid and Frequently Used show it from then on. For one person the
/// picker is a row of five tones; for two people (a handshake, a couple) it is Apple's two rows, a tone for each person.
/// The keyboard keeps the choices in its own defaults, as [base emoji text: chosen variant text], like Frequently Used.
enum EmojiTones {
    static let key = "TFEmojiTones"
    private static let toneNames = ["light skin tone", "medium-light skin tone", "medium skin tone", "medium-dark skin tone", "dark skin tone"]

    /// What the one-person tone picker offers for an emoji (its base or one of its variants): the base, then Apple's five
    /// tones from light to dark. nil when the emoji has no tones or has two people (see `pairs`). Only what this iOS draws.
    static func choices(for text: String) -> [Emoji]? { table[base(of: text)] }

    /// Apple's two-person picker for an emoji with two people (a handshake, a couple, people holding hands): the variant
    /// for [the left person's tone][the right person's tone], each 0 (light) to 4 (dark); the same tone twice is the
    /// one-tone variant. nil for anything else.
    static func pairs(for text: String) -> [[Emoji]]? { twos[base(of: text)] }

    /// The emoji a variant belongs to (an emoji without tones is its own), so Frequently Used counts every tone of an
    /// emoji as that one emoji and shows it in the tone chosen now.
    static func base(of text: String) -> String { baseOf[text] ?? text }

    /// The emoji as the grid shows it: in the tone chosen for it, if any. Only a tone of this emoji counts, so a stale or
    /// damaged store never shows another emoji in its place.
    static func shown(_ emoji: Emoji, chosen: [String: String]) -> Emoji {
        chosen[emoji.text].flatMap { baseOf[$0] == emoji.text ? byText[$0] : nil } ?? emoji
    }

    /// A pick in a tone picker, kept in `chosen`: the tone is remembered for its emoji, and the plain emoji forgets it.
    static func choose(_ choice: Emoji, for base: Emoji, in chosen: inout [String: String]) {
        chosen[base.text] = choice == base ? nil : choice.text
    }

    /// Base text to [base, then the five one-tone variants], for emoji with one person.
    static let table: [String: [Emoji]] = split.singles
    /// Base text to its 5 by 5 grid of variants, for emoji with two people.
    static let twos: [String: [[Emoji]]] = split.twos

    private static let split: (singles: [String: [Emoji]], twos: [String: [[Emoji]]]) = {
        let lines = toneLines()
        let twoPeople = Set(lines.filter { $0.tones.count == 2 }.map(\.base.text))
        var singles: [String: [Emoji]] = [:]
        var grids: [String: [[Emoji?]]] = [:]
        for (base, variant, tones) in lines {
            if twoPeople.contains(base.text) {
                var grid = grids[base.text] ?? Array(repeating: Array(repeating: nil, count: 5), count: 5)
                grid[tones[0]][tones.count == 2 ? tones[1] : tones[0]] = variant
                grids[base.text] = grid
            } else if tones.count == 1 {
                singles[base.text, default: [base]].append(variant)
            }
        }
        // A grid with a hole (an iOS that draws only some of an emoji's variants) is left out, never offered in part.
        let twos = grids.compactMapValues { grid in grid.joined().contains { $0 == nil } ? nil : grid.map { $0.compactMap { $0 } } }
        return (singles, twos)
    }()

    private static let byText = Dictionary((Array(table.values.joined()) + Array(twos.values.joined().joined())).map { ($0.text, $0) },
                                           uniquingKeysWith: { first, _ in first })
    private static let baseOf = Dictionary(table.flatMap { base, choices in choices.map { ($0.text, base) } }
                                               + twos.flatMap { base, grid in grid.joined().map { ($0.text, base) } },
                                           uniquingKeysWith: { first, _ in first })

    /// Each skin-tone variant this iOS draws, with the emoji in the picker it belongs to and its tones (0 light to 4 dark):
    /// one for one person or two people alike, two for two people each in their own. A line that is not four fields long
    /// (a damaged `EmojiData`) is skipped, never read past its end.
    private static func toneLines() -> [(base: Emoji, variant: Emoji, tones: [Int])] {
        EmojiData.skinTones.split(separator: "\n").compactMap { line in
            let parts = line.split(separator: " ", maxSplits: 3).map(String.init)
            guard parts.count == 4, let added = Double(parts[2]), added <= EmojiCatalog.drawnVersion,
                  let base = EmojiCatalog.emoji(parts[0]) else { return nil }
            // "thumbs up: medium skin tone" has one tone; "kiss: woman, man, light skin tone, dark skin tone" has two.
            let tones = (parts[3].split(separator: ": ").last?.split(separator: ", ") ?? []).compactMap { toneNames.firstIndex(of: String($0)) }
            return tones.isEmpty ? nil : (base, Emoji(text: parts[1], name: parts[3]), tones)
        }
    }
}
