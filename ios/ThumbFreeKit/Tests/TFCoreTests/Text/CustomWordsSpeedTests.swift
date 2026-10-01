import Testing
@testable import TFCore

/// 500 entries against a 1,000-word transcript: at most 10 ms median in a release build, run with
/// `tools/test-kit.sh -c release --filter CustomWordsSpeedTests`. A debug build is unoptimized (about 10 times slower),
/// so it checks the realistic case against a loose ceiling and skips the worst cases.
@Suite struct CustomWordsSpeedTests {
    #if DEBUG
    static let (isRelease, slack, warmUps, rounds) = (false, 20.0, 1, 5)
    #else
    static let (isRelease, slack, warmUps, rounds) = (true, 1.0, 10, 21)
    #endif

    @Test func realisticListIsFast() {
        var random = TextTest.Random(seed: 3)
        let entries = Self.entries(&random)
        let common = CommonWords.all.sorted()
        let transcript = (0..<1_000).map { _ -> String in
            switch random.int(20) {
            case 0: random.pick(entries) // said exactly
            case 1: String(random.pick(entries).lowercased().dropFirst()) // said nearly
            case 2, 3: Self.name(&random) // uncommon words
            default: random.pick(common)
            }
        }.joined(separator: " ")
        Self.expectFast(transcript, entries, msPer1000Words: 10 * Self.slack, "realistic")
    }

    @Test(.enabled(if: isRelease))
    func worstCasesStayUnderTheirCeilings() {
        var random = TextTest.Random(seed: 5)
        let entries = Self.entries(&random)
        let uncommon = (0..<1_000).map { _ in Self.name(&random) + Self.name(&random) }.joined(separator: " ") // all fuzzy
        // 28-letter entries with one prefix (one Soundex code) and the same letters (one letter mask), against words of
        // that shape that match none of them. 2,250 words is a 15-minute take.
        func shape() -> String { "kbdrmptlsnvc" + String("aaeeiioouuyzgfhw".shuffled(using: &random)) }
        let alike = (0..<CustomWords.maxEntries).map { _ in shape() }
        let attack = (0..<2_250).map { _ in shape() }
        // The accepted ceiling: 17-letter Eulerian circuits over b, f, p and v share their letters, all 16 letter pairs
        // and their Soundex code, so both prefilters pass every pair. Queries are circuits over 2 edits from each entry.
        let circuits = Self.eulerian(&random, count: CustomWords.maxEntries + 200)
        let circuitEntries = Array(circuits.prefix(CustomWords.maxEntries))
        let queries = circuits.dropFirst(CustomWords.maxEntries).filter { query in
            circuitEntries.allSatisfy { CustomWords.levenshtein(Array(query.utf8), Array($0.utf8), most: 2) == nil }
        }
        #expect(queries.count >= 20)
        func circuitText(_ count: Int) -> String { (0..<count).map { queries[$0 % queries.count] }.joined(separator: " ") }
        for (text, list) in [(attack.joined(separator: " "), alike), (circuitText(2_250), circuitEntries)] {
            #expect(CustomWords.correct(text, entries: list, exactOnly: false) == text) // no match
        }
        Self.expectFast(uncommon, entries, msPer1000Words: 10, "uncommon words only")
        Self.expectFast(attack.prefix(1_000).joined(separator: " "), alike, msPer1000Words: 10, "alike entries")
        Self.expectFast(attack.joined(separator: " "), alike, msPer1000Words: 10, "alike entries, 15-minute take")
        Self.expectFast(circuitText(1_000), circuitEntries, msPer1000Words: 150, "Eulerian entries")
        Self.expectFast(circuitText(2_250), circuitEntries, msPer1000Words: 150, "Eulerian entries, 15-minute take")
    }

    /// The median of `rounds` runs, after `warmUps`, under the budget per 1,000 words.
    static func expectFast(_ text: String, _ entries: [String], msPer1000Words: Double, _ what: String) {
        for _ in 0..<warmUps { _ = CustomWords.correct(text, entries: entries, exactOnly: false) }
        let ms = (0..<rounds).map { _ -> Double in
            let start = ContinuousClock.now
            _ = CustomWords.correct(text, entries: entries, exactOnly: false)
            let d = ContinuousClock.now - start
            return Double(d.components.seconds) * 1_000 + Double(d.components.attoseconds) / 1e15
        }.sorted()
        let words = text.split(separator: " ").count
        let median = ms[ms.count / 2]
        print("CustomWords.correct, \(entries.count) entries, \(words) words, \(what): median \(median) ms")
        #expect(median < msPer1000Words * Double(words) / 1_000, "\(what): median \(median) ms")
    }

    /// 500 made-up entries, every fifth of two words.
    static func entries(_ random: inout TextTest.Random) -> [String] {
        (0..<CustomWords.maxEntries).map { $0 % 5 == 0 ? name(&random) + " " + name(&random) : name(&random) }
    }

    /// A made-up name of 2 to 9 letters, rarely a common word.
    static func name(_ random: inout TextTest.Random) -> String {
        let consonants = Array("bcdfghjklmnprstvwz"), vowels = Array("aeiou")
        let s = (0...random.int(4)).map { _ in String(random.pick(consonants)) + String(random.pick(vowels)) }.joined()
        return s.prefix(1).uppercased() + s.dropFirst() + (random.int(2) == 0 ? "n" : "")
    }

    /// Distinct 17-letter walks from b that use each of the 16 pairs of b, f, p and v once.
    static func eulerian(_ random: inout TextTest.Random, count: Int) -> [String] {
        var found: [String] = []
        var seen = Set<String>()
        while found.count < count {
            var unused: [Character: [Character]] = ["b": Array("bfpv"), "f": Array("bfpv"), "p": Array("bfpv"), "v": Array("bfpv")]
            var walk: [Character] = ["b"]
            while let last = walk.last, var next = unused[last], !next.isEmpty {
                walk.append(next.remove(at: random.int(next.count)))
                unused[last] = next
            }
            if walk.count == 17, seen.insert(String(walk)).inserted { found.append(String(walk)) }
        }
        return found
    }
}
