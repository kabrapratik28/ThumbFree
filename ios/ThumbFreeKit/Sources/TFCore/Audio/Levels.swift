/// The level ring, ported from Android: each frame's level as 0...1, smoothed, at most 30 updates a second.
public struct Levels: Sendable {
    public static let maxPerSecond = 30
    private var smoothed: Float = 0
    private var shownAtMs: Int?

    public init() {}

    /// -60 dBFS and below is 0, -10 dBFS and above is 1, linear in between.
    public static func unit(_ dbfs: Float) -> Float { min(1, max(0, (dbfs + 60) / 50)) }

    /// Smooths in one frame level (0.7 old + 0.3 new). Returns the value to show, or nil when showing it would pass
    /// 30 updates a second.
    public mutating func push(_ dbfs: Float, nowMs: Int) -> Float? {
        smoothed = 0.7 * smoothed + 0.3 * Self.unit(dbfs)
        if let last = shownAtMs, (nowMs - last) * Self.maxPerSecond < 1000 { return nil }
        shownAtMs = nowMs
        return smoothed
    }
}
