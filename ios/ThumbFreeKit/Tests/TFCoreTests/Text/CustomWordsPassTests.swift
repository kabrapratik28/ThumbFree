import Testing
@testable import TFCore

@Suite struct CustomWordsPassTests {
    private func correct(_ text: String, _ entries: [String]) -> String {
        CustomWords.correct(text, entries: entries, exactOnly: false)
    }

    @Test func emptyListChangesNothing() {
        for t in CustomWordsTrapTests.all { TextTest.expectSame(correct(t.text, []), t.text) }
    }

    @Test func correctingAgainChangesNothing() {
        for t in CustomWordsTrapTests.all { TextTest.expectSame(correct(t.expected, t.entries), t.expected) }
    }

    @Test func aPassCanSetUpTheNext() {
        // The first pass fixes "kubernetis"; the next finds the exact "Kubernetes Engine" that made.
        #expect(correct("kubernetis engine", ["Kubernetes", "Kubernetes Engine"]) == "Kubernetes Engine")
        // Each merge sets up the next, 5 passes deep; then one of 12 stages.
        let steps = ["Axbycz", "Axbyczduev", "Axbyczduevfwgx", "Axbyczduevfwgxhyiz", "Axbyczduevfwgxhyizjukv"]
        #expect(correct("ax by cz du ev fw gx hy iz ju kv", steps) == "Axbyczduevfwgxhyizjukv")
        let parts = Array(Self.qWords.prefix(25))
        let chain = (1...12).map { Self.capitalized(parts.prefix(2 * $0 + 1).joined()) }
        #expect(correct(parts.joined(separator: " "), chain) == chain[11])
        // A 4-word entry no n-gram can match whole, next to a merge into part of it: still a fixed point.
        let odd = ["W X Y Z", "WX"]
        #expect(correct(correct("wxyz", odd), odd) == correct("wxyz", odd))
    }

    @Test func longChainsAndLoopsEndInAFixedPoint() {
        // One word whose "&" entries step down one per pass for 13 passes.
        let aliases = (0...12).map { "a&" + String(repeating: "and", count: $0) + "b" }
        let aliased = "a" + String(repeating: "and", count: 13) + "b"
        #expect(correct(aliased, aliases) == "a&b")
        // One word that becomes 20, which 9 merges then join back up, one pass each.
        let part = Array(Self.qWords.prefix(20))
        let grown = [part.joined(separator: " ")] + (1...9).map { part.prefix(2 * $0 + 1).joined() }
        #expect(correct(part.joined(), grown) == part.prefix(19).joined() + " " + part[19])
        // With the whole word as an entry too, the merges and the split loop: the text comes back as it was.
        let loop = grown + [part.joined()]
        #expect(correct(part.joined(), loop) == part.joined())
        for (text, list) in [(aliased, aliases), (part.joined(), grown), (part.joined(), loop)] {
            #expect(correct(correct(text, list), list) == correct(text, list))
        }
    }

    @Test func the64PassCapGivesBackTheInput() {
        // 63 links converge to "a&b" one pass before the cap; 64 links run out of passes and give the text back.
        let converging = (0..<63).map { "a&" + String(repeating: "and", count: $0) + "b" }
        #expect(correct("a" + String(repeating: "and", count: 63) + "b", converging) == "a&b")
        let capped = (0..<64).map { "a&" + String(repeating: "and", count: $0) + "b" }
        let text = "a" + String(repeating: "and", count: 64) + "b"
        #expect(correct(text, capped) == text)
    }

    @Test func randomTextsAreFixedPoints() {
        let words = ["GitHub", "ChatGPT", "Zendesk", "MacBook Pro", "Kubernetes", "SEV", "Ned", "R&D", "kubectl", "GPU",
                     "#hashtag", "U.S.", "C++", "Kubernetes Engine", "W X Y Z", "WX", "Axbycz", "Axbyczduev"]
        let pool = ["github", "chat", "gpt", "zen", "desk", "zendsk", "mac", "book", "pro", "kubernetis", "kubernetes's", "sev",
                    "save", "ned", "red", "r", "and", "d", "kubectl", "Kubectl", "gpus", "the", "a", "is", "engine", ",", ".", "?",
                    "hashtag", "#hashtag", "us", "c", "c++", "w", "x", "y", "z", "wxyz", "ax", "by", "cz", "du", "ev", "😀", "”"]
        var random = TextTest.Random(seed: 11)
        for _ in 0..<2_000 {
            let text = (0..<random.int(12)).map { _ in random.pick(pool) }.joined(separator: " ")
            let once = correct(text, words)
            TextTest.expectSame(correct(once, words), once)
        }
    }

    @Test(.timeLimit(.minutes(1)))
    func neverCrashesOrHangs() {
        // Each random text has an exact " zendesk " in its middle, which comes out as " Zendesk " only when the passes ran
        // to the end. Odd entries ("", " ") come in too, as a list that skipped parse might.
        let chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 -,.!?'’&".map(String.init)
        let pool = chars + ["é", "ß", "İ", "你", "😀", "\u{301}", "\u{200D}", "\u{A0}", "\n"]
        let words = ["Zendesk", "R&D", "CTA", "MacBook Pro", "İstanbul", "", " ", "kubectl", "a’s"]
        var random = TextTest.Random(seed: 7)
        for _ in 0..<10_000 {
            let text = (0..<2).map { _ in (0..<random.int(21)).map { _ in random.pick(pool) }.joined() }
                .joined(separator: " zendesk ")
            let start = ContinuousClock.now
            let out = correct(text, words)
            #expect(ContinuousClock.now - start < .seconds(1))
            #expect(out.unicodeScalars.firstRange(of: " Zendesk ".unicodeScalars) != nil)
            TextTest.expectSame(correct(out, words), out)
        }
    }

    /// "qb" to "qz", less any common word: made-up words for the chains.
    static let qWords = "bcdefghijklmnopqrstuvwxyz".map { "q\($0)" }.filter { !CommonWords.all.contains($0) }

    static func capitalized(_ s: String) -> String { s.prefix(1).uppercased() + s.dropFirst() }
}
