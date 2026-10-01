import Testing
@testable import TFCore

@Suite struct CustomWordsExactOnlyTests {
    // Names of tech and of people, some close to everyday words of other languages.
    static let fleursEntries = "GitHub, kubectl, Zendesk, Kubernetes, ChatGPT, iPhone, WhatsApp, Workplace, Workday, "
        + "ThumbFree, MacBook Pro, Second Brain, Slack, Figma, Jira, Notion, Spotify, YouTube, Instagram, LinkedIn, Grafana, "
        + "Postgres, Python, Kotlin, Android, Gradle, TensorFlow, OpenAI, Anthropic, Claude, Gemini, Priya, Anika, Rahul, "
        + "Nakamura, Siobhan, Oluwaseun, Grazia, Marta, Lukas, Sofia, Mateo, Chiara, Pieter"

    @Test(arguments: [false, true])
    func exactMatchesWorkInAnyLanguage(_ exactOnly: Bool) {
        let words = ["Müller", "Łódź", "Москва", "Αθήνα", "São Paulo", "GitHub", "Grazia"]
        func correct(_ text: String) -> String { CustomWords.correct(text, entries: words, exactOnly: exactOnly) }
        TextTest.expectSame(correct("herr müller pusht es auf github"), "herr Müller pusht es auf GitHub")
        TextTest.expectSame(correct("jutro jadę do łódź"), "jutro jadę do Łódź")
        TextTest.expectSame(correct("я еду в москва"), "я еду в Москва")
        TextTest.expectSame(correct("πάμε στην αθήνα"), "πάμε στην Αθήνα")
        TextTest.expectSame(correct("vou para são paulo amanhã"), "vou para São Paulo amanhã")
        #expect(correct("herr muller") == "herr muller")
    }

    @Test func exactOnlyTakesNoNearMisses() {
        let words = CustomWords.parse(Self.fleursEntries)
        #expect(words.count == 44)
        let missed = [
            ("grazie mille", "Grazia mille"), ("Grazie a tutti", "Grazia a tutti"), ("lui chiama sempre", "lui Chiara sempre"),
            ("mort au combat", "Marta combat"), ("de noten, zei hij", "de Notion, zei hij"),
            ("de kubernetis cluster", "de Kubernetes cluster"), ("zwei pythons", "zwei Pythons"),
        ]
        for (text, fuzzy) in missed {
            #expect(CustomWords.correct(text, entries: words, exactOnly: false) == fuzzy)
            #expect(CustomWords.correct(text, entries: words, exactOnly: true) == text)
        }
        #expect(CustomWords.correct("Ich habe es gestern auf github und zu chat gpt gepusht", entries: words, exactOnly: true)
            == "Ich habe es gestern auf GitHub und zu ChatGPT gepusht")
        TextTest.expectSame(CustomWords.correct("zadzwoń do łukasz", entries: words + ["Łukasz"], exactOnly: true), "zadzwoń do Łukasz")
    }

    @Test(arguments: [("en", false), ("en-US", false), ("en_GB", false), ("de", true), ("pt-BR", true)])
    func onlyEnglishGetsNearMisses(_ language: String, _ exactOnly: Bool) {
        #expect(CustomWords.exactOnly(language: language) == exactOnly)
    }

    @Test func theMultilingualModelGetsExactMatchesOnly() {
        #expect(CustomWords.exactOnly(language: nil))
    }
}
