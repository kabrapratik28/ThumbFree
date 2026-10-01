import Testing
@testable import TFCore

@Suite struct CustomWordsTrapTests {
    /// One row of the Android golden table (CustomWordsTest.traps): the text, the Dictionary, and what `correct` gives.
    /// Where a matcher without guards 1 to 5 (the same scores and threshold) gives something else, its output follows
    /// "unguarded" in the comment.
    struct Trap: Sendable, CustomTestStringConvertible {
        let text: String
        let entries: [String]
        let expected: String
        init(_ text: String, _ entries: [String], _ expected: String) {
            (self.text, self.entries, self.expected) = (text, entries, expected)
        }
        var testDescription: String { "\(text.debugDescription) \(entries)" }
    }

    /// The rows exact matching alone gives (98 of 112). Rows that stay unchanged here guard the near-miss matching too.
    static let exact: [Trap] = [
        .init("i pushed it to github", ["GitHub"], "i pushed it to GitHub"),
        .init("GITHUB is down", ["GitHub"], "GitHub is down"),  // unguarded "GITHUB is down"
        .init("ask chat gpt", ["ChatGPT"], "ask ChatGPT"),  // "gpt" is not common, so guard 5 lets it merge
        .init("open zen desk", ["Zendesk"], "open Zendesk"),
        .init("install fb lite", ["FBLite"], "install FBLite"),
        .init("my second brain notes", ["Second Brain"], "my Second Brain notes"),  // common words, multi-word entry
        .init("using mac book pro", ["MacBook Pro"], "using MacBook Pro"),
        .init("run an a b test", ["A/B test"], "run an A/B test"),
        .init("send it to rd", ["R&D"], "send it to R&D"),
        .init("use GPT4 for this", ["GPT-4"], "use GPT-4 for this"),
        .init("zoë and zoe", ["Zoë"], "Zoë and zoe"),  // exact only outside ASCII
        .init("你好。", ["你号"], "你好。"),
        .init("ned is two", ["Ned"], "Ned is two"),
        .init("the red bed was wed and fed by a net", ["Ned"], "the red bed was wed and fed by a net"),  // 1 edit each
        .init("open a sev for it", ["SEV"], "open a SEV for it"),
        .init("save seven sieve serve", ["SEV"], "save seven sieve serve"),  // unguarded "SEV seven SEV serve"
        .init("let's chat later", ["CTA"], "let's chat later"),  // unguarded "let's CTA later"
        .init("it rained all day", ["R&D"], "it rained all day"),  // unguarded "it R&D all day"
        .init("add a div here", ["diff"], "add a div here"),  // unguarded "add a diff here"; guard 3
        .init("it got dark early", ["Derek"], "it got dark early"),  // "dark": 2 edits, and Soundex agrees
        .init("charge b is great", ["ChargeBee"], "charge b is great"),  // unguarded "ChargeBee is great"
        .init("open a Zendesk ticket", ["Zendesk"], "open a Zendesk ticket"),  // unguarded "open Zendesk ticket"
        .init("an installation guide", ["Instagram"], "an installation guide"),  // unguarded "an Instagram guide"
        .init("pick a timeframe", ["ThumbFree"], "pick a timeframe"),  // unguarded "pick a ThumbFree"
        .init("my superstar", ["Supercharger"], "my superstar"),  // same Soundex (s162), 5 edits in 12
        .init("two hawks", ["Haiku"], "two hawks"),  // unguarded "two Haiku"
        .init("7 zendesk", ["Zendesk"], "7 Zendesk"),  // unguarded "Zendesk"
        .init("zq kubernetes", ["Kubernetes"], "zq Kubernetes"),  // unguarded "Kubernetes"
        .init("ask a mina", ["Amina"], "ask Amina"),  // "mina" is not common, so the "a" may merge
        .init("can I phone you later", ["iPhone"], "can I phone you later"),  // unguarded "can IPHONE you later"
        .init("my iphone died", ["iPhone"], "my iPhone died"),
        .init("one plus two", ["OnePlus"], "one plus two"),  // unguarded "OnePlus two"
        .init("I went to get her", ["Together"], "I went to get her"),  // unguarded "I went Together"
        .init("send it to R and D", ["R&D"], "send it to R and D"),  // unguarded "send it to R&D"
        .init("hand off the keys", ["Handoff"], "hand off the keys"),  // unguarded "Handoff the keys"
        .init("hold out your hand", ["holdout"], "hold out your hand"),  // unguarded "holdout your hand"
        .init("Open AI GPT model", ["OpenAI", "GPT"], "OpenAI GPT model"),
        .init("message me on whats app", ["WhatsApp"], "message me on WhatsApp"),
        .init("log it in air table", ["Airtable"], "log it in Airtable"),
        .init("post it on work place", ["Workplace"], "post it on Workplace"),
        .init("run the back test", ["backtest"], "run the backtest"),
        .init("the G P T model", ["GPT"], "the GPT model"),  // spelled letters: none is a function word
        .init("rebuild the source tree", ["Sourcetree"], "rebuild the Sourcetree"),
        .init("a long work day", ["Workday"], "a long Workday"),
        .init("we need service now", ["ServiceNow"], "we need ServiceNow"),
        .init("a super human effort", ["Superhuman"], "a Superhuman effort"),
        .init("I pad the numbers", ["iPad"], "iPad the numbers"),  // "pad" is not common, so "I" may merge
        .init("Kubectl is great", ["kubectl"], "Kubectl is great"),
        .init("I use Kubectl daily.", ["kubectl"], "I use kubectl daily."),
        .init("did he say Kubectl? Kubectl!", ["kubectl"], "did he say kubectl? Kubectl!"),
        .init("Second brain rocks", ["second brain"], "Second brain rocks"),
        .init("Iphone is here", ["iPhone"], "iPhone is here"),
        .init("Done. 😀 Kubectl works", ["kubectl"], "Done. 😀 Kubectl works"),  // past a lone emoji or quote
        .init("Done. ” Kubectl works", ["kubectl"], "Done. ” Kubectl works"),
        .init("Done. “ Kubectl works", ["kubectl"], "Done. “ Kubectl works"),
        .init("Done. -- Kubectl works", ["kubectl"], "Done. -- Kubectl works"),
        .init("😀 Kubectl works", ["kubectl"], "😀 Kubectl works"),
        .init("I said \u{2014} Kubectl works", ["kubectl"], "I said \u{2014} kubectl works"),
        .init("(github) rocks", ["GitHub"], "(GitHub) rocks"),
        .init("«github»!", ["GitHub"], "«GitHub»!"),
        .init("chat, gpt", ["ChatGPT"], "chat, gpt"),
        .init("chat (gpt)", ["ChatGPT"], "chat (gpt)"),
        .init("is it github.com?", ["GitHub"], "is it github.com?"),
        .init("kubernetes's api", ["Kubernetes"], "Kubernetes's api"),  // unguarded "Kubernetes api"
        .init("Kubernetes’s api", ["Kubernetes"], "Kubernetes’s api"),
        .init("github's actions", ["GitHub"], "GitHub's actions"),  // unguarded "GitHub actions"
        .init("the kubernetes' pods", ["Kubernetes"], "the Kubernetes' pods"),
        .init("Ned's toy", ["Ned"], "Ned's toy"),
        .init("this", ["Thi"], "this"),  // a common word's s is no plural
        .init("tag it hashtag", ["#hashtag"], "tag it #hashtag"),
        .init("tag it #hashtag", ["#hashtag"], "tag it #hashtag"),
        .init("say \"hashtag\".", ["#hashtag"], "say \"#hashtag\"."),
        .init("Hashtag it.", ["#hashtag"], "#Hashtag it."),  // the capital goes on the first letter
        .init("moved to the us today", ["U.S."], "moved to the U.S. today"),
        .init("we moved to the us.", ["U.S."], "we moved to the U.S."),  // one period for both
        .init("(the U.S.)", ["U.S."], "(the U.S.)"),
        .init("built on net", [".NET"], "built on .NET"),
        .init("built on (.net)", [".NET"], "built on (.NET)"),
        .init("write it in c", ["C#"], "write it in C#"),
        .init("write it in C#.", ["C#"], "write it in C#."),
        .init("learn c++ or c+ first", ["C++"], "learn C++ or C++ first"),
        .init("\"C++\" rocks", ["C++"], "\"C++\" rocks"),
        .init("git😀hub and Open🇺🇸AI", ["GitHub", "OpenAI"], "git😀hub and Open🇺🇸AI"),
        .init("😀github github😀 github❤\u{FE0F}", ["GitHub"], "😀GitHub GitHub😀 GitHub❤\u{FE0F}"),
        .init("git\u{200D}hub and githu\u{0332}b", ["GitHub"], "git\u{200D}hub and githu\u{0332}b"),
        .init("gitx\u{0301} now", ["Gitx"], "gitx\u{0301} now"),  // x has no precomposed acute: the mark stays loose
        .init("my r\u{E9}sum\u{E9}", ["R\u{E9}sum\u{E9}"], "my R\u{E9}sum\u{E9}"),  // precomposed
        .init("my re\u{0301}sume\u{0301}", ["R\u{E9}sum\u{E9}"], "my R\u{E9}sum\u{E9}"),  // compared in NFC, and written so
        .init("my re\u{0301}sume\u{0301}", ["resume"], "my re\u{0301}sume\u{0301}"),  // untouched, still decomposed
        .init("zendesk", ["Zende\u{0301}sk"], "zendesk"),  // in NFC the entry has é: exact only
        .init("github’s actions", ["GitHub"], "GitHub’s actions"),
        .init("mac bookpro", ["MacBook Pro"], "mac bookpro"),
        .init("macbook pro", ["Mac Book Pro", "MacBook Pro"], "MacBook Pro"),  // the first would make 3 words of 2
        .init("one plus two", ["Oneplus", "One Plus"], "One Plus two"),  // guard 5 refuses the first
        .init("bibibobaba", ["bababababa"], "bibibobaba"),
        .init("line one\ngithub  two ", ["GitHub"], "line one\nGitHub  two "),
        .init("", ["GitHub"], ""),
        .init(" \n ", ["GitHub"], " \n "),
        // Android throws building this entry and drops the whole take's corrections; Swift's "& so" is intentional.
        .init("And so", ["&"], "& so"),
    ]

    /// The rows only a near miss gives: a plural s or a fuzzy match (14 of 112).
    static let nearMiss: [Trap] = [
        .init("deploy kubernetis now", ["Kubernetes"], "deploy Kubernetes now"),
        .init("code in kotlen", ["Kotlin"], "code in Kotlin"),
        .init("zendsk ticket", ["Zendesk"], "Zendesk ticket"),
        .init("ask ameena", ["Amina"], "ask Amina"),  // 2 edits in 6, with the Soundex discount
        .init("ping dereck", ["Derek"], "ping Derek"),
        .init("run gradel", ["Gradle"], "run Gradle"),
        .init("use kafca", ["Kafka"], "use Kafka"),
        .init("「zendsk。」", ["Zendesk"], "「Zendesk。」"),
        .init("kubernetis's api", ["Kubernetes"], "Kubernetes's api"),
        .init("two gpus", ["GPU"], "two GPUs"),  // unguarded "two gpus"
        .init("open two sevs", ["SEV"], "open two SEVs"),
        .init("chat gpts", ["ChatGPT"], "ChatGPTs"),
        .init("zendsk", ["Zen-desk", "Zendesk"], "Zen-desk"),  // one fuzzy key: the first spelling
        .init("bibibababa", ["bababababa"], "bababababa"),
    ]

    static var all: [Trap] { exact + nearMiss }

    @Test(arguments: all)
    func trap(_ t: Trap) {
        TextTest.expectSame(CustomWords.correct(t.text, entries: t.entries, exactOnly: false), t.expected)
    }
}
