import Testing
@testable import TFCore

@Suite struct CustomWordsDistanceTests {
    @Test func bandedDistanceMatchesTheFullOne() {
        // Every pair of strings over {a, b, c} of up to 6 letters (5 in a debug build, where 6 takes 15 s), at each
        // edit limit the matcher uses.
        #if DEBUG
        let longest = 5
        #else
        let longest = 6
        #endif
        var strings: [[UInt8]] = [[]]
        var level: [[UInt8]] = [[]]
        for _ in 1...longest {
            level = level.flatMap { s in [UInt8]("abc".utf8).map { s + [$0] } }
            strings += level
        }
        var wrong: [String] = []
        var previous = [Int](repeating: 0, count: 7), current = previous // full-matrix rows, reused
        for a in strings {
            for b in strings {
                for j in 0...b.count { previous[j] = j }
                for i in 0..<a.count {
                    current[0] = i + 1
                    for j in 0..<b.count {
                        current[j + 1] = min(previous[j + 1] + 1, current[j] + 1, previous[j] + (a[i] == b[j] ? 0 : 1))
                    }
                    swap(&previous, &current)
                }
                let distance = previous[b.count]
                for most in 0...2 where CustomWords.levenshtein(a, b, most: most) != (distance <= most ? distance : nil) {
                    wrong.append("\(String(decoding: a, as: UTF8.self)),\(String(decoding: b, as: UTF8.self)),\(most)")
                }
            }
        }
        #expect(wrong.isEmpty)
    }

    @Test func allowedEditsFollowTheTable() {
        // longest key: plain match / Soundex match. Any score within these is under 0.18.
        for longest in 4...5 { #expect(CustomWords.mostEdits(longest, phonetic: false) == 0) }
        #expect(CustomWords.mostEdits(5, phonetic: true) == 1)
        for longest in 6...11 {
            #expect(CustomWords.mostEdits(longest, phonetic: false) == 1)
            #expect(CustomWords.mostEdits(longest, phonetic: true) == 2)
        }
        for longest in 12...52 {
            #expect(CustomWords.mostEdits(longest, phonetic: false) == 2)
            #expect(CustomWords.mostEdits(longest, phonetic: true) == 2)
        }
    }
}
