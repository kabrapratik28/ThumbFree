import Foundation

/// Accuracy helpers shared by the tests and `tfbench`. Not used by the app.
public enum Bench {
    /// The Android accuracy harness's normalization. English: lowercase, keep a-z, 0-9 and spaces, collapse spaces, trim.
    /// Any language: lowercase letters and digits of any script, everything else one space.
    public static func normalize(_ text: String, english: Bool) -> String {
        var out = String.UnicodeScalarView()
        for ch in text.precomposedStringWithCanonicalMapping.lowercased().unicodeScalars {
            if english {
                if ("a"..."z").contains(ch) || ("0"..."9").contains(ch) || ch == " " { out.append(ch) }
            } else {
                out.append(CharacterSet.letters.contains(ch) || CharacterSet.decimalDigits.contains(ch) ? ch : " ")
            }
        }
        return String(out).split(separator: " ").joined(separator: " ")
    }

    /// Word-level edit distance after `normalize`, and the reference's word count.
    public static func wordErrors(reference: String, hypothesis: String, english: Bool) -> (errors: Int, words: Int) {
        let ref = normalize(reference, english: english).split(separator: " ")
        let hyp = normalize(hypothesis, english: english).split(separator: " ")
        var previous = Array(0...hyp.count)
        for (i, r) in ref.enumerated() {
            var current = [i + 1]
            for (j, h) in hyp.enumerated() {
                current.append(min(previous[j + 1] + 1, current[j] + 1, previous[j] + (r == h ? 0 : 1)))
            }
            previous = current
        }
        return (previous[hyp.count], ref.count)
    }

    /// `refs.tsv` in `directory`: one "file<TAB>reference" per line.
    public static func references(in directory: URL) throws -> [String: String] {
        let text = try String(contentsOf: directory.appendingPathComponent("refs.tsv"), encoding: .utf8)
        var refs: [String: String] = [:]
        for line in text.split(separator: "\n") {
            let parts = line.split(separator: "\t", maxSplits: 1)
            if parts.count == 2 { refs[String(parts[0])] = String(parts[1]) }
        }
        return refs
    }

    /// Splits audio longer than one Encoder window: each cut is in the middle of the quietest 100 ms that
    /// starts 8 s or more into the window. Every piece fits the window.
    /// ponytail: bench-only splitter; the app cuts takes with the chunk planner.
    public static func windows(_ samples: [Float], maxSamples: Int = ParakeetEngine.maxSamples) -> [[Float]] {
        var pieces: [[Float]] = []
        var start = 0
        while samples.count - start > maxSamples {
            var best = start + 128_000, bestEnergy = Float.infinity
            for i in stride(from: start + 128_000, through: start + maxSamples - 1_600, by: 160) {
                var energy: Float = 0
                for s in samples[i..<i + 1_600] { energy += s * s }
                if energy < bestEnergy { bestEnergy = energy; best = i }
            }
            pieces.append(Array(samples[start..<best + 800]))
            start = best + 800
        }
        pieces.append(Array(samples[start...]))
        return pieces
    }
}
