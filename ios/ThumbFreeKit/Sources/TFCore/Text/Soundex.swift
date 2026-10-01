/// Soundex as the Rust `natural` 0.5.0 crate computes it, not American Soundex: the first letter is kept as is and never
/// merges with the digit after it ("pfister" is p123). Made for lowercase ASCII keys: any other byte counts as a vowel.
enum Soundex {
    /// The code: keep the first letter, map the rest, drop 9s, collapse runs, drop 0s, pad with 0 or cut to 4.
    static func code(_ key: String) -> String {
        let p = packed(Array(key.utf8))
        return String(decoding: [UInt8(p >> 24), UInt8(p >> 16 & 0xFF), UInt8(p >> 8 & 0xFF), UInt8(p & 0xFF)], as: UTF8.self)
    }

    /// The same code, its 4 characters packed one byte each, in one loop: custom words ask for it per n-gram.
    static func packed(_ key: [UInt8]) -> UInt32 {
        var out: UInt32 = 0
        var count = 0
        var previous: UInt8 = 0 // the code before, once 9s are dropped: runs collapse to it
        for (i, c) in key.enumerated() {
            let d = i == 0 ? c : digit(c)
            if d == UInt8(ascii: "9") || d == previous { continue }
            previous = d
            if d != UInt8(ascii: "0") && count < 4 {
                out = out << 8 | UInt32(d)
                count += 1
            }
        }
        for _ in count..<4 { out = out << 8 | UInt32(UInt8(ascii: "0")) }
        return out
    }

    private static func digit(_ c: UInt8) -> UInt8 {
        switch c {
        case UInt8(ascii: "b"), UInt8(ascii: "f"), UInt8(ascii: "p"), UInt8(ascii: "v"): UInt8(ascii: "1")
        case UInt8(ascii: "c"), UInt8(ascii: "g"), UInt8(ascii: "j"), UInt8(ascii: "k"),
             UInt8(ascii: "q"), UInt8(ascii: "s"), UInt8(ascii: "x"), UInt8(ascii: "z"): UInt8(ascii: "2")
        case UInt8(ascii: "d"), UInt8(ascii: "t"): UInt8(ascii: "3")
        case UInt8(ascii: "l"): UInt8(ascii: "4")
        case UInt8(ascii: "m"), UInt8(ascii: "n"): UInt8(ascii: "5")
        case UInt8(ascii: "r"): UInt8(ascii: "6")
        case UInt8(ascii: "h"), UInt8(ascii: "w"): UInt8(ascii: "9") // dropped before runs collapse: letters on both sides of h or w merge
        default: UInt8(ascii: "0") // vowels and y: dropped after runs collapse, so they keep equal letters apart
        }
    }
}
