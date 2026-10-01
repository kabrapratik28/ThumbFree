import Testing
@testable import TFCore

@Suite struct DictionaryRulesTests {
    @Test func parseSplitsTrimsAndDropsRepeats() {
        #expect(CustomWords.parse("GitHub, Zendesk\nMacBook   Pro\r\n  chat gpt ,, ") == ["GitHub", "Zendesk", "MacBook Pro", "chat gpt"])
        #expect(CustomWords.parse("GitHub, github\nGITHUB, Git Hub") == ["GitHub", "Git Hub"])
        // 60 characters is 60 code points: an emoji is one.
        let x61 = String(repeating: "x", count: 61), e60 = String(repeating: "😀", count: 60)
        #expect(CustomWords.parse("\(x61), \(e60), \(e60)😀") == [e60])
        let many = CustomWords.parse((1...600).map { "w\($0)" }.joined(separator: ","))
        #expect(many.count == CustomWords.maxEntries)
        #expect(many.last == "w500")
        #expect(CustomWords.parse(" , \n ").isEmpty)
        // NFC: the same entry, kept precomposed.
        let resume = CustomWords.parse("Re\u{301}sume\u{301}, R\u{E9}sum\u{E9}")
        #expect(resume.count == 1)
        TextTest.expectSame(resume[0], "R\u{E9}sum\u{E9}")
        // Case is compared as Java lowercases, final sigma included.
        let athens = CustomWords.parse("ΑΘΉΝΑΣ, Αθήνας")
        #expect(athens.count == 1)
        TextTest.expectSame(athens[0], "ΑΘΉΝΑΣ")
    }

    @Test func riskyEntriesAreOneWordCommonWords() {
        let words = ["Will", "IT", "U.S.", "GitHub", "Second Brain", "iOS", "R&D", "go", "iPhone", "Kubernetes"]
        #expect(CustomWords.riskyEntries(words) == ["Will", "IT", "U.S.", "go", "iPhone"])
        #expect(CustomWords.riskyNote("Will") == "“Will” is also a common word, so every “will” will be written “Will”.")
        #expect(CustomWords.riskyNote("Anika") == nil)
    }

    @Test func oneEntryIsCheckedBeforeItIsSaved() {
        let words = ["GitHub", "Anika"]
        #expect(CustomWords.checkEntry("  Dr.   Nakamura ", existing: words, editing: nil) == .success("Dr. Nakamura"))
        #expect(CustomWords.checkEntry("   ", existing: words, editing: nil) == .failure(.blank))
        #expect(CustomWords.checkEntry("github", existing: words, editing: nil) == .failure(.duplicate))
        #expect(CustomWords.checkEntry(String(repeating: "x", count: 61), existing: words, editing: nil) == .failure(.tooLong))
        #expect(CustomWords.checkEntry("Priya, Will", existing: words, editing: nil) == .failure(.several))
    }

    @Test func anEditMayRepeatOnlyItself() {
        let words = ["github", "Anika"]
        #expect(CustomWords.checkEntry("GitHub", existing: words, editing: "github") == .success("GitHub"))
        #expect(CustomWords.checkEntry("anika", existing: words, editing: "github") == .failure(.duplicate))
    }

    @Test func problemsSayWhyInTheAndroidWords() {
        #expect(CustomWords.EntryProblem.blank.message == nil)
        #expect(CustomWords.EntryProblem.duplicate.message == "Already in your Dictionary.")
        #expect(CustomWords.EntryProblem.tooLong.message == "Too long: an entry has at most 60 characters.")
        #expect(CustomWords.EntryProblem.several.message == "One at a time here. Use Paste a list for several.")
    }

    @Test func aPastedListAddsItsNewEntriesAndCountsTheRest() {
        let added = CustomWords.addList("Kubernetes, github\nDr. Nakamura,, \(String(repeating: "x", count: 61))\n  Anika  , kubernetes",
                                        to: ["GitHub"])
        #expect(added.entries == ["GitHub", "Kubernetes", "Dr. Nakamura", "Anika"])
        #expect(added.added == ["Kubernetes", "Dr. Nakamura", "Anika"])
        #expect(added.duplicates == 2) // github (in the list) and kubernetes (twice in the paste)
        #expect(added.tooLong == 1)
        #expect(added.notFitting == 0)
        #expect(added.summary == "3 will be added. 2 duplicates and 1 long entry will be skipped.")
    }

    @Test func aFullListLeavesTheRestOut() {
        let full = (1..<CustomWords.maxEntries).map { "word\($0)" }
        let added = CustomWords.addList("Anika, Priya, Will", to: full)
        #expect(added.added == ["Anika"])
        #expect(added.notFitting == 2)
        #expect(added.entries.count == CustomWords.maxEntries)
        #expect(added.summary == "1 will be added. 2 over the limit of 500 will be skipped.")
    }

    @Test func aPasteAloneOverTheCapIsOverLimitNotDuplicates() {
        // 600 new names, all unique: the cap is 500, so 100 don't fit. None are duplicates.
        let raw = (1...600).map { "word\($0)" }.joined(separator: ", ")
        let added = CustomWords.addList(raw, to: [])
        #expect(added.added.count == CustomWords.maxEntries)
        #expect(added.duplicates == 0)
        #expect(added.notFitting == 100)
        #expect(added.summary == "500 will be added. 100 over the limit of 500 will be skipped.")
    }

    @Test func aPasteWithRepeatsAndOverflowCountsEachSeparately() {
        // 520 unique names plus 180 repeats of the first 180 of them: 180 duplicates, 20 over the limit.
        let unique = (1...520).map { "word\($0)" }
        let raw = (unique + Array(unique.prefix(180))).joined(separator: ", ")
        let added = CustomWords.addList(raw, to: [])
        #expect(added.added.count == CustomWords.maxEntries)
        #expect(added.duplicates == 180)
        #expect(added.notFitting == 20)
        #expect(added.summary == "500 will be added. 180 duplicates and 20 over the limit of 500 will be skipped.")
    }

    @Test func pasteSummaries() {
        typealias Addition = CustomWords.ListAddition
        #expect(Addition(entries: [], added: Array(repeating: "x", count: 12), duplicates: 2, tooLong: 1, notFitting: 0).summary
            == "12 will be added. 2 duplicates and 1 long entry will be skipped.")
        #expect(Addition(entries: [], added: [], duplicates: 0, tooLong: 0, notFitting: 0).summary == "Nothing new to add.")
        #expect(Addition(entries: [], added: [], duplicates: 1, tooLong: 2, notFitting: 1).summary
            == "Nothing new to add. 1 duplicate, 2 long entries and 1 over the limit of 500 will be skipped.")
    }
}
