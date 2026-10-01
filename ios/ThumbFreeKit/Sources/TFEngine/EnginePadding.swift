/// Engine-only padding (the WAV keeps the real audio): input under 16_000 samples gets 8_000 zeros on each side,
/// then trailing zeros up to 20_000 samples in total. Longer input, and empty input, is returned unchanged.
/// Parakeet returned nothing for a bare 0.39 s "yes" (Android `Padding.kt`).
public enum EnginePadding {
    public static func pad(_ samples: [Float]) -> [Float] {
        guard !samples.isEmpty, samples.count < 16_000 else { return samples }
        let head = [Float](repeating: 0, count: 8_000)
        let tail = [Float](repeating: 0, count: max(8_000, 20_000 - 8_000 - samples.count))
        return head + samples + tail
    }
}
