import Foundation

/// How long history keeps takes (nil: no limit). Both rules apply, so the stricter one wins.
public enum Retention {
    public static let dayChoices: [Int?] = [7, 30, 90, nil]
    public static let countChoices: [Int?] = [50, 200, 1_000, nil]
    public static let defaultDays: Int? = nil
    public static let defaultCount: Int? = 200

    /// The takes to delete, newest first: those past the newest `keepCount`, and those that started more than `keepDays`
    /// days before `now`. Only ended, unstarred takes count or go. Newest is by `order`, so a clock set back never makes
    /// a new take look old.
    public static func idsToDelete(_ takes: [TakeRecord], keepDays: Int?, keepCount: Int?, now: Date) -> [UUID] {
        let oldest = keepDays.map { now.addingTimeInterval(-Double($0) * 86_400) }
        return takes.filter { !$0.starred && !$0.status.isLive }
            .sorted { $0.order > $1.order }
            .enumerated()
            .filter { index, take in index >= (keepCount ?? .max) || oldest.map { take.startedAt < $0 } == true }
            .map(\.element.id)
    }
}
