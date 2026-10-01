/// The last 300 ms of session audio (4,800 samples), kept while no take records, so a take starts with the audio just
/// before the tap.
struct PreRoll {
    static let samples = 4_800

    private var ring = [Float](repeating: 0, count: PreRoll.samples)
    private var next = 0
    private var filled = 0

    mutating func push(_ block: [Float]) {
        for sample in block.suffix(Self.samples) {
            ring[next] = sample
            next = (next + 1) % Self.samples
        }
        filled = min(Self.samples, filled + block.count)
    }

    /// The kept audio, oldest first. Empties the ring.
    mutating func drain() -> [Float] {
        defer { filled = 0 }
        return Array((ring[next...] + ring[..<next]).suffix(filled))
    }
}
