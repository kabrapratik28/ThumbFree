import Testing
@testable import TFCore

@Suite struct CleanupReplaceTests {
    let take = "Yes, yes, I booked a table for seven thirty."

    // The take ThumbFree typed sits right before the cursor.
    @Test func theTakeAtTheCursorMatches() {
        #expect(CleanupReplace.matches(before: "Hi Maya! " + take, typed: take))
        #expect(CleanupReplace.matches(before: take, typed: take))
    }

    // iOS often shows only the end of the text: its last 16 characters or more are enough, as for the insertion's read-back.
    @Test func aCutOffContextMatchesOnTheTakesEnd() {
        #expect(CleanupReplace.matches(before: String(take.suffix(20)), typed: take))
        #expect(!CleanupReplace.matches(before: String(take.suffix(10)), typed: take))
    }

    // A short take must show whole.
    @Test func aShortTakeMustShowWhole() {
        #expect(CleanupReplace.matches(before: "ok see you", typed: "see you"))
        #expect(!CleanupReplace.matches(before: "you", typed: "see you"))
    }

    // The person's own change, or anything typed after the take, and the take is not the take any more.
    @Test func anEditOrMoreTextFails() {
        #expect(!CleanupReplace.matches(before: "Hi " + take.replacingOccurrences(of: "seven", with: "eight"), typed: take))
        #expect(!CleanupReplace.matches(before: take + " Thanks", typed: take))
        #expect(!CleanupReplace.matches(before: take, typed: ""))
    }

    // What came before the take, for the replacement's spacing and capital; nil when iOS shows less than the whole take.
    @Test func theTextBeforeTheTake() {
        #expect(CleanupReplace.beforeTake(before: "Hi Maya! " + take, typed: take) == "Hi Maya! ")
        #expect(CleanupReplace.beforeTake(before: take, typed: take) == "")
        #expect(CleanupReplace.beforeTake(before: String(take.suffix(20)), typed: take) == nil)
    }
}
