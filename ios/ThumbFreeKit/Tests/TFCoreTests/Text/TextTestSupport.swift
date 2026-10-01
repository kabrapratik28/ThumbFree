import Testing

/// Helpers for the text tests, in one namespace so other test files cannot clash with them.
enum TextTest {
    /// Swift's == calls "é" and "e\u{301}" equal; the text rules must match code point for code point.
    static func expectSame(_ actual: String, _ expected: String, sourceLocation: SourceLocation = #_sourceLocation) {
        #expect(actual.unicodeScalars.elementsEqual(expected.unicodeScalars),
                "got \(actual.debugDescription), want \(expected.debugDescription)", sourceLocation: sourceLocation)
    }

    /// SplitMix64: a seeded generator, so the random property checks repeat exactly.
    struct Random: RandomNumberGenerator {
        private var state: UInt64
        init(seed: UInt64) { state = seed }
        mutating func next() -> UInt64 {
            state &+= 0x9E37_79B9_7F4A_7C15
            var z = state
            z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
            z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
            return z ^ (z >> 31)
        }
        mutating func pick<T>(_ items: [T]) -> T { items[Int.random(in: 0..<items.count, using: &self)] }
        mutating func int(_ below: Int) -> Int { Int.random(in: 0..<below, using: &self) }
    }
}
