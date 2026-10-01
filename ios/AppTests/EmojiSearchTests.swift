import Testing
@testable import ThumbFree

/// Emoji search: names and CLDR keywords, best matches first.
@MainActor @Suite struct EmojiSearchTests {
    private func names(_ query: String) -> [String] { EmojiSearch.results(for: query).map(\.name) }

    // Whole words first (the cars before the carrot), then Apple's starting Frequently Used (the red heart for "love"),
    // then the emoji named by the query or by its face (the cat face and the cat), then the picker's order.
    @Test func theBestMatchesComeFirst() throws {
        let car = names("car")
        #expect(car.first == "automobile")
        #expect(try #require(car.firstIndex(of: "racing car")) < #require(car.firstIndex(of: "carrot")))
        #expect(names("love").first == "red heart")
        #expect(Array(names("cat").prefix(2)) == ["cat face", "cat"])
        #expect(names("cat").contains("black cat"))
    }

    // Apple ranks with its own keywords and its own usage data, neither public. These are ThumbFree's first results for
    // the queries checked against Apple's Search Emoji on the iPhone 16 Simulator, pinned
    // so that a change to the rules shows here.
    @Test(arguments: [
        ("smile", "😍 ☺️ 😊 😁 😎"),
        ("happy", "😂 ☺️ 😁 😄 😀"),
        ("sad", "😭 😩 😔 🥹 😞"),
        ("laugh", "😂 😄 😀 😆 🤣"),
        ("cry", "😭 🥹 😢 😿 😂"),
        ("love", "❤️ 😍 😘 💕 🥰"),
        ("heart", "❤️ 😍 😘 💕 🥰"),
        ("fire", "🔥 👩‍🚒 🧑‍🚒 👨‍🚒 🚒"),
        ("ok", "👌 🫡 🙆‍♀️ 🙆 🙆‍♂️"),
        ("thumbs", "👍 👎"),
        ("clap", "👏 🪭 🎬"),
        ("hand", "👌 👍 ✌️ 💁 👏"),
        ("party", "😜 😛 🥳 👯‍♀️ 👯"),
        ("cat", "🐱 🐈 😺 😸 😹"),
        ("dog", "🐶 🐕 🐩 🦮 🐕‍🦺"),
        ("star", "⭐️ 🤩 👩‍🎤 🧑‍🎤 👨‍🎤"),
        ("sun", "☀️ 🌻 🌞 🌤️ ⛅️"),
        ("food", "😋 🦑 🦐 🍄‍🟫 🍎"),
        ("car", "🚗 🚕 🚙 🏎️ 🚓"),
        ("angry", "😠 😤 😡 👿 👺"),
    ])
    func theFirstResultsAreAsPinned(_ query: String, _ first: String) {
        let shown = EmojiSearch.results(for: query).prefix(first.split(separator: " ").count).map(\.text)
        #expect(shown.joined(separator: " ") == first)
    }

    // A keyword finds an emoji whose name lacks it (CLDR's "lol" for the laughing faces); every word must match, as the
    // beginning of a word, in any case.
    @Test func keywordsAndEveryWordCount() {
        #expect(names("lol").contains("face with tears of joy"))
        #expect(names("Thumbs UP").first == "thumbs up")
        #expect(names("red heart").first == "red heart")
        #expect(!names("red heart").contains("blue heart"))
        #expect(names("ughing").isEmpty) // the middle of "laughing" begins no word
        #expect(names("zzzzqq").isEmpty)
        #expect(names("5-0").contains("police car")) // CLDR's en dash, typed as a hyphen
        // A hyphenated name or keyword ("heart-eyes", "upside-down", "star-struck"): every word must be found typed
        // either way, with the hyphen or with a space in its place, as a user typing does not know which the name uses.
        #expect(names("heart eyes").contains { $0.contains("heart-eyes") })
        #expect(names("upside down").contains("upside-down face"))
        #expect(names("star struck").contains("star-struck"))
    }

    // Unicode's own names use a curly apostrophe ("woman’s hat"); a query typed with the straight one this keyboard's
    // own apostrophe key types must still find it, and find the same results a curly one would.
    @Test func aStraightApostropheFindsWhatACurlyOneFinds() {
        let hat = "woman\u{2019}s hat"
        #expect(names("woman's hat").contains(hat))
        #expect(names("woman's hat") == names(hat))
    }

    // An empty query lists the whole picker in order, after the emoji you picked (as Apple's search shows them before
    // anything is typed); flags are found by country.
    @Test func anEmptyQueryListsEverything() {
        #expect(EmojiSearch.results(for: "  ") == EmojiCatalog.categories.flatMap(\.emoji))
        let used = EmojiSearch.results(for: "", used: ["\u{1F602}", "not an emoji", "\u{1F436}"])
        #expect(used.prefix(2).map(\.text) == ["\u{1F602}", "\u{1F436}"])
        #expect(used.dropFirst(2).elementsEqual(EmojiCatalog.categories.flatMap(\.emoji)))
        #expect(EmojiSearch.results(for: "cat", used: ["\u{1F602}"]) == EmojiSearch.results(for: "cat"))
        #expect(names("united states").contains("flag: United States"))
    }
}
