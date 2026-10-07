/// Where Clean up may write: only over the take ThumbFree typed, right before the cursor. The keyboard reads the text
/// before the cursor (`documentContextBeforeInput`), which iOS often cuts to its end, so a match is judged as the
/// insertion's read-back is: as much of the take as iOS shows, at least its last 16 characters or all of a shorter one.
public enum CleanupReplace {
    public static let minimumSeen = 16

    /// The text before the cursor ends with `typed`, as far as iOS shows it.
    public static func matches(before: String, typed: String) -> Bool {
        guard !typed.isEmpty else { return false }
        let seen = min(before.count, typed.count)
        return seen >= min(typed.count, minimumSeen) && before.hasSuffix(typed.suffix(seen))
    }

    /// The text before the take, when iOS shows the whole take and what precedes it (possibly nothing); nil when it
    /// shows less, which the cursor formatter reads as unknown.
    public static func beforeTake(before: String, typed: String) -> String? {
        guard before.hasSuffix(typed) else { return nil }
        return String(before.dropLast(typed.count))
    }
}
