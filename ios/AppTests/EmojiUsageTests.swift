import Foundation
import Testing
@testable import ThumbFree

/// Frequently Used: ranked by how often and how recently, starting from Apple's set.
@MainActor @Suite struct EmojiUsageTests {
    private let t0 = Date(timeIntervalSinceReferenceDate: 800_000_000)
    private let day: TimeInterval = 24 * 60 * 60

    // A new keyboard shows Apple's starting set in Apple's order; the first pick of anything comes first.
    @Test func itStartsFromApplesSetAndAPickLeads() {
        var usage = EmojiUsage(stored: nil, starting: EmojiCatalog.recentsDefault, now: t0)
        #expect(usage.ranked(at: t0) == EmojiCatalog.recentsDefault)
        #expect(EmojiCatalog.recentsDefault.prefix(3) == ["\u{1F602}", "\u{2764}\u{FE0F}", "\u{1F60D}"])
        usage.pick("\u{1F996}", at: t0 + 1)
        #expect(usage.ranked(at: t0 + 2).first == "\u{1F996}")
        #expect(usage.ranked(at: t0 + 2).count == EmojiUsage.shown) // the oldest of Apple's set drops out of sight
    }

    // What an empty search lists first, as Apple's does: only the emoji picked, in Frequently Used's order, never Apple's
    // starting set until one of it is picked (after these picks, Apple's showed 😂 🎉 🐶 on the iOS 26.5 Simulator).
    @Test func onlyPickedEmojiLeadAnEmptySearch() {
        var usage = EmojiUsage(stored: nil, starting: EmojiCatalog.recentsDefault, now: t0)
        #expect(usage.picked(at: t0).isEmpty)
        usage.pick("\u{1F602}", at: t0 + 1) // in Apple's starting set, and now picked too
        usage.pick("\u{1F602}", at: t0 + 2)
        usage.pick("\u{1F436}", at: t0 + 3)
        usage.pick("\u{1F389}", at: t0 + 4)
        #expect(usage.picked(at: t0 + 5) == ["\u{1F602}", "\u{1F389}", "\u{1F436}"])
    }

    // How often: three picks beat one made later the same day. How recently: after a few weeks, one fresh pick beats the
    // three old ones.
    @Test func itRanksByHowOftenAndHowRecently() {
        var usage = EmojiUsage(stored: [:], starting: [], now: t0)
        for n in 0..<3 { usage.pick("a", at: t0 + Double(n)) }
        usage.pick("b", at: t0 + 60)
        #expect(usage.ranked(at: t0 + 120) == ["a", "b"])
        usage.pick("b", at: t0 + 21 * day)
        #expect(usage.ranked(at: t0 + 21 * day) == ["b", "a"])
        #expect(abs(usage.score("a", at: t0 + 7 * day + 2) - 1.5) < 0.001) // three picks, one week later: half
    }

    // One pick each: the later one first. The store keeps at most 60, dropping the lowest score now.
    @Test func theLaterPickLeadsAndTheStoreStaysSmall() {
        var usage = EmojiUsage(stored: [:], starting: [], now: t0)
        usage.pick("a", at: t0)
        usage.pick("b", at: t0 + 1)
        #expect(usage.ranked(at: t0 + 1) == ["b", "a"])
        for n in 0..<100 { usage.pick("e\(n)", at: t0 + 10 + Double(n)) }
        #expect(usage.scores.count == EmojiUsage.kept)
        #expect(usage.scores["e99"] != nil && usage.scores["a"] == nil)
    }

    // What the store round-trips through the keyboard's defaults (a property list: text to two numbers).
    @Test func itSurvivesTheDefaults() throws {
        let defaults = try #require(UserDefaults(suiteName: "EmojiUsageTests"))
        defaults.removePersistentDomain(forName: "EmojiUsageTests")
        var usage = EmojiUsage(stored: defaults.dictionary(forKey: EmojiUsage.key) as? [String: [Double]], starting: ["x"], now: t0)
        usage.pick("\u{1F44D}", at: t0 + 5)
        defaults.set(usage.scores, forKey: EmojiUsage.key)
        let again = EmojiUsage(stored: defaults.dictionary(forKey: EmojiUsage.key) as? [String: [Double]], starting: ["x"], now: t0)
        #expect(again == usage)
        defaults.removePersistentDomain(forName: "EmojiUsageTests")
    }

    // The picker leads with Frequently Used, named for VoiceOver; a stored emoji the catalog does not show is dropped.
    @Test func frequentlyUsedLeadsThePicker() {
        #expect(EmojiCatalog.sections(recents: []) == EmojiCatalog.categories)
        let sections = EmojiCatalog.sections(recents: ["\u{1F44D}", "not an emoji", "\u{1FAEB}", "\u{1F600}"])
        #expect(sections.first == EmojiSection(category: .recents, emoji: [Emoji(text: "\u{1F44D}", name: "thumbs up"),
                                                                          Emoji(text: "\u{1F600}", name: "grinning face")]))
        #expect(Array(sections.dropFirst()) == EmojiCatalog.categories)
        // Apple's starting set is all in the catalog.
        #expect(EmojiCatalog.recentsDefault.allSatisfy { EmojiCatalog.emoji($0) != nil })
    }
}
