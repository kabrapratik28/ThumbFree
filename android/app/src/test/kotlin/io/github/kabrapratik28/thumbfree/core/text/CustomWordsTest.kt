package io.github.kabrapratik28.thumbfree.core.text

import com.google.common.truth.Truth.assertThat
import kotlin.random.Random
import org.junit.Test

// 44 entries for the multilingual model: names of tech and of people, some close to everyday words of other languages.
private const val FLEURS_ENTRIES = "GitHub, kubectl, Zendesk, Kubernetes, ChatGPT, iPhone, WhatsApp, Workplace, Workday, " +
    "ThumbFree, MacBook Pro, Second Brain, Slack, Figma, Jira, Notion, Spotify, YouTube, Instagram, LinkedIn, Grafana, " +
    "Postgres, Python, Kotlin, Android, Gradle, TensorFlow, OpenAI, Anthropic, Claude, Gemini, Priya, Anika, Rahul, " +
    "Nakamura, Siobhan, Oluwaseun, Grazia, Marta, Lukas, Sofia, Mateo, Chiara, Pieter"

class CustomWordsTest {
    private data class CW(val text: String, val words: List<String>)

    // Each row: the text, the words, and what correct() gives. Where a matcher without guards 1 to 5 (the same scores and
    // threshold) gives something else, its output follows "unguarded" in the comment.
    private val table = mapOf(
        // Exact: the keys are equal, so case, punctuation and spacing are all that differ. The user's casing wins.
        CW("i pushed it to github", listOf("GitHub")) to "i pushed it to GitHub",
        CW("GITHUB is down", listOf("GitHub")) to "GitHub is down", // unguarded "GITHUB is down"
        CW("ask chat gpt", listOf("ChatGPT")) to "ask ChatGPT", // "gpt" is not common, so guard 5 lets it merge
        CW("open zen desk", listOf("Zendesk")) to "open Zendesk",
        CW("install fb lite", listOf("FBLite")) to "install FBLite",
        CW("my second brain notes", listOf("Second Brain")) to "my Second Brain notes", // common words, multi-word entry
        CW("using mac book pro", listOf("MacBook Pro")) to "using MacBook Pro",
        CW("run an a b test", listOf("A/B test")) to "run an A/B test",
        CW("send it to rd", listOf("R&D")) to "send it to R&D",
        CW("use GPT4 for this", listOf("GPT-4")) to "use GPT-4 for this",
        CW("zoë and zoe", listOf("Zoë")) to "Zoë and zoe", // exact only outside ASCII
        CW("你好。", listOf("你号")) to "你好。",
        // Short entries (guard 1): a key under 4 characters matches exactly only.
        CW("ned is two", listOf("Ned")) to "Ned is two",
        CW("the red bed was wed and fed by a net", listOf("Ned")) to "the red bed was wed and fed by a net", // 1 edit each
        CW("open a sev for it", listOf("SEV")) to "open a SEV for it",
        CW("save seven sieve serve", listOf("SEV")) to "save seven sieve serve", // unguarded "SEV seven SEV serve"
        CW("let's chat later", listOf("CTA")) to "let's chat later", // unguarded "let's CTA later"
        CW("it rained all day", listOf("R&D")) to "it rained all day", // unguarded "it R&D all day"
        CW("add a div here", listOf("diff")) to "add a div here", // unguarded "add a diff here"; guard 3
        // Common words (guard 2): replaced only by an exact match.
        CW("it got dark early", listOf("Derek")) to "it got dark early", // "dark": 2 edits, and Soundex agrees
        CW("charge b is great", listOf("ChargeBee")) to "charge b is great", // unguarded "ChargeBee is great"
        CW("open a Zendesk ticket", listOf("Zendesk")) to "open a Zendesk ticket", // unguarded "open Zendesk ticket"
        // Fuzzy: uncommon words close to an entry of 5 or more characters.
        CW("deploy kubernetis now", listOf("Kubernetes")) to "deploy Kubernetes now",
        CW("code in kotlen", listOf("Kotlin")) to "code in Kotlin",
        CW("zendsk ticket", listOf("Zendesk")) to "Zendesk ticket",
        CW("ask ameena", listOf("Amina")) to "ask Amina", // 2 edits in 6, with the Soundex discount
        CW("ping dereck", listOf("Derek")) to "ping Derek",
        CW("run gradel", listOf("Gradle")) to "run Gradle",
        CW("use kafca", listOf("Kafka")) to "use Kafka",
        // Guard 3's cap: Soundex agrees on these, but they are more than 2 edits (1 for 5 letters) away.
        CW("an installation guide", listOf("Instagram")) to "an installation guide", // unguarded "an Instagram guide"
        CW("pick a timeframe", listOf("ThumbFree")) to "pick a timeframe", // unguarded "pick a ThumbFree"
        CW("my superstar", listOf("Supercharger")) to "my superstar", // 5 edits in 12
        CW("two hawks", listOf("Haiku")) to "two hawks", // unguarded "two Haiku"
        // Guard 4: the first word stays when the rest alone matches at least as well.
        CW("7 zendesk", listOf("Zendesk")) to "7 Zendesk", // unguarded "Zendesk"
        CW("zq kubernetes", listOf("Kubernetes")) to "zq Kubernetes", // unguarded "Kubernetes"
        CW("ask a mina", listOf("Amina")) to "ask Amina", // "mina" is not common, so the "a" may merge
        // Guard 5: common words merge into a one-word entry unless one is a function word (a multi-word entry may take
        // any words, above).
        CW("can I phone you later", listOf("iPhone")) to "can I phone you later", // unguarded "can IPHONE you later"
        CW("my iphone died", listOf("iPhone")) to "my iPhone died",
        CW("one plus two", listOf("OnePlus")) to "one plus two", // unguarded "OnePlus two"
        CW("I went to get her", listOf("Together")) to "I went to get her", // unguarded "I went Together"
        CW("send it to R and D", listOf("R&D")) to "send it to R and D", // unguarded "send it to R&D"
        CW("hand off the keys", listOf("Handoff")) to "hand off the keys", // unguarded "Handoff the keys"
        CW("hold out your hand", listOf("holdout")) to "hold out your hand", // unguarded "holdout your hand"
        CW("Open AI GPT model", listOf("OpenAI", "GPT")) to "OpenAI GPT model",
        CW("message me on whats app", listOf("WhatsApp")) to "message me on WhatsApp",
        CW("log it in air table", listOf("Airtable")) to "log it in Airtable",
        CW("post it on work place", listOf("Workplace")) to "post it on Workplace",
        CW("run the back test", listOf("backtest")) to "run the backtest",
        CW("the G P T model", listOf("GPT")) to "the GPT model", // spelled letters: none is a function word
        // Guard 5's known cost: common content words still merge into a one-word entry, meant or not. The first was in
        // running text (docs/); the others are ordinary phrases made only of such words.
        CW("rebuild the source tree", listOf("Sourcetree")) to "rebuild the Sourcetree",
        CW("a long work day", listOf("Workday")) to "a long Workday",
        CW("we need service now", listOf("ServiceNow")) to "we need ServiceNow",
        CW("a super human effort", listOf("Superhuman")) to "a Superhuman effort",
        CW("I pad the numbers", listOf("iPad")) to "iPad the numbers", // "pad" is not common, so "I" may merge
        // Casing: an all-lowercase entry keeps the capital of a sentence's first word, and only there.
        CW("Kubectl is great", listOf("kubectl")) to "Kubectl is great",
        CW("I use Kubectl daily.", listOf("kubectl")) to "I use kubectl daily.",
        CW("did he say Kubectl? Kubectl!", listOf("kubectl")) to "did he say kubectl? Kubectl!",
        CW("Second brain rocks", listOf("second brain")) to "Second brain rocks",
        CW("Iphone is here", listOf("iPhone")) to "iPhone is here",
        CW("Done. 😀 Kubectl works", listOf("kubectl")) to "Done. 😀 Kubectl works", // past a lone emoji or quote
        CW("Done. ” Kubectl works", listOf("kubectl")) to "Done. ” Kubectl works",
        CW("Done. “ Kubectl works", listOf("kubectl")) to "Done. “ Kubectl works",
        CW("Done. -- Kubectl works", listOf("kubectl")) to "Done. -- Kubectl works",
        CW("😀 Kubectl works", listOf("kubectl")) to "😀 Kubectl works",
        CW("I said — Kubectl works", listOf("kubectl")) to "I said — kubectl works",
        // Punctuation around a match stays, and an n-gram never reaches across it.
        CW("(github) rocks", listOf("GitHub")) to "(GitHub) rocks",
        CW("«github»!", listOf("GitHub")) to "«GitHub»!",
        CW("「zendsk。」", listOf("Zendesk")) to "「Zendesk。」",
        CW("chat, gpt", listOf("ChatGPT")) to "chat, gpt",
        CW("chat (gpt)", listOf("ChatGPT")) to "chat (gpt)",
        CW("is it github.com?", listOf("GitHub")) to "is it github.com?",
        // Possessives and plurals keep their ending.
        CW("kubernetes's api", listOf("Kubernetes")) to "Kubernetes's api", // unguarded "Kubernetes api"
        CW("Kubernetes’s api", listOf("Kubernetes")) to "Kubernetes’s api",
        CW("kubernetis's api", listOf("Kubernetes")) to "Kubernetes's api",
        CW("github's actions", listOf("GitHub")) to "GitHub's actions", // unguarded "GitHub actions"
        CW("the kubernetes' pods", listOf("Kubernetes")) to "the Kubernetes' pods",
        CW("Ned's toy", listOf("Ned")) to "Ned's toy",
        CW("two gpus", listOf("GPU")) to "two GPUs", // unguarded "two gpus"
        CW("open two sevs", listOf("SEV")) to "open two SEVs",
        CW("chat gpts", listOf("ChatGPT")) to "ChatGPTs",
        CW("this", listOf("Thi")) to "this", // a common word's s is no plural
        // An entry's own leading and trailing marks appear once, reusing the text's.
        CW("tag it hashtag", listOf("#hashtag")) to "tag it #hashtag",
        CW("tag it #hashtag", listOf("#hashtag")) to "tag it #hashtag",
        CW("say \"hashtag\".", listOf("#hashtag")) to "say \"#hashtag\".",
        CW("Hashtag it.", listOf("#hashtag")) to "#Hashtag it.", // the capital goes on the first letter
        CW("moved to the us today", listOf("U.S.")) to "moved to the U.S. today",
        CW("we moved to the us.", listOf("U.S.")) to "we moved to the U.S.", // one period for both
        CW("(the U.S.)", listOf("U.S.")) to "(the U.S.)",
        CW("built on net", listOf(".NET")) to "built on .NET",
        CW("built on (.net)", listOf(".NET")) to "built on (.NET)",
        CW("write it in c", listOf("C#")) to "write it in C#",
        CW("write it in C#.", listOf("C#")) to "write it in C#.",
        CW("learn c++ or c+ first", listOf("C++")) to "learn C++ or C++ first",
        CW("\"C++\" rocks", listOf("C++")) to "\"C++\" rocks",
        // A symbol, emoji or loose mark among a word's letters leaves it alone; around them it stays.
        CW("git😀hub and Open🇺🇸AI", listOf("GitHub", "OpenAI")) to "git😀hub and Open🇺🇸AI",
        CW("😀github github😀 github❤️", listOf("GitHub")) to "😀GitHub GitHub😀 GitHub❤️",
        CW("git\u200Dhub and githu\u0332b", listOf("GitHub")) to "git\u200Dhub and githu\u0332b",
        CW("gitx\u0301 now", listOf("Gitx")) to "gitx\u0301 now", // x has no precomposed acute: the mark stays loose
        CW("my résumé", listOf("Résumé")) to "my Résumé",
        CW("my re\u0301sume\u0301", listOf("Résumé")) to "my Résumé", // compared in NFC, and written so
        CW("my re\u0301sume\u0301", listOf("resume")) to "my re\u0301sume\u0301", // untouched, still decomposed
        CW("zendesk", listOf("Zende\u0301sk")) to "zendesk", // in NFC the entry has é: exact only
        CW("github’s actions", listOf("GitHub")) to "GitHub’s actions",
        // A same-count merge only fixes case: word keys must line up.
        CW("mac bookpro", listOf("MacBook Pro")) to "mac bookpro",
        // Entries that share a key: the first one allowed wins.
        CW("macbook pro", listOf("Mac Book Pro", "MacBook Pro")) to "MacBook Pro", // the first would make 3 words of 2
        CW("one plus two", listOf("Oneplus", "One Plus")) to "One Plus two", // guard 5 refuses the first
        CW("zendsk", listOf("Zen-desk", "Zendesk")) to "Zen-desk", // one fuzzy key: the first spelling
        // Fuzzy stops at 2 edits: both are Soundex b100.
        CW("bibibababa", listOf("bababababa")) to "bababababa",
        CW("bibibobaba", listOf("bababababa")) to "bibibobaba",
        // The text's own spacing stays.
        CW("line one\ngithub  two ", listOf("GitHub")) to "line one\nGitHub  two ",
        CW("", listOf("GitHub")) to "",
        CW(" \n ", listOf("GitHub")) to " \n ",
    )

    @Test
    fun traps() {
        assertThat(table.mapValues { (cw, _) -> CustomWords.correct(cw.text, cw.words) }).containsExactlyEntriesIn(table)
    }

    // Exact matches in any language the multilingual model writes, accents and other scripts included. A word or an
    // entry with a letter outside ASCII changes only through an exact match (guard 1).
    @Test
    fun exactMatchesInAnyLanguage() {
        val words = listOf("Müller", "Łódź", "Москва", "Αθήνα", "São Paulo", "GitHub", "Grazia")

        for (exactOnly in listOf(false, true)) {
            assertThat(CustomWords.correct("herr müller pusht es auf github", words, exactOnly))
                .isEqualTo("herr Müller pusht es auf GitHub")
            assertThat(CustomWords.correct("jutro jadę do łódź", words, exactOnly)).isEqualTo("jutro jadę do Łódź")
            assertThat(CustomWords.correct("я еду в москва", words, exactOnly)).isEqualTo("я еду в Москва")
            assertThat(CustomWords.correct("πάμε στην αθήνα", words, exactOnly)).isEqualTo("πάμε στην Αθήνα")
            assertThat(CustomWords.correct("vou para são paulo amanhã", words, exactOnly)).isEqualTo("vou para São Paulo amanhã")
            assertThat(CustomWords.correct("herr muller", words, exactOnly)).isEqualTo("herr muller")
        }
    }

    // The guards against near misses (the common words, the function words, Soundex, the plural s) know English only,
    // so the multilingual model's takes match exactly only. With the 44 entries above, the words of other languages
    // that fuzzy matching took on FLEURS stay (0 of 800 FLEURS sentences change); an exact entry still applies inside a
    // German sentence; the English models' takes keep every kind of match.
    @Test
    fun exactOnlyTakesNoNearMisses() {
        val words = CustomWords.parse(FLEURS_ENTRIES)
        val missed = mapOf(
            "grazie mille" to "Grazia mille", "Grazie a tutti" to "Grazia a tutti", "lui chiama sempre" to "lui Chiara sempre",
            "mort au combat" to "Marta combat", "de noten, zei hij" to "de Notion, zei hij",
            "de kubernetis cluster" to "de Kubernetes cluster", "zwei pythons" to "zwei Pythons",
        )

        for ((text, fuzzy) in missed) {
            assertThat(CustomWords.correct(text, words)).isEqualTo(fuzzy)
            assertThat(CustomWords.correct(text, words, exactOnly = true)).isEqualTo(text)
        }
        assertThat(CustomWords.correct("Ich habe es gestern auf github und zu chat gpt gepusht", words, exactOnly = true))
            .isEqualTo("Ich habe es gestern auf GitHub und zu ChatGPT gepusht")
        assertThat(CustomWords.correct("zadzwoń do łukasz", words + "Łukasz", exactOnly = true)).isEqualTo("zadzwoń do Łukasz")
        // Who gets exact only: every language but English, the unknown one (the multilingual model's) included.
        assertThat(CustomWords.exactOnlyFor("en")).isFalse()
        assertThat(CustomWords.exactOnlyFor("de")).isTrue()
        assertThat(CustomWords.exactOnlyFor(null)).isTrue()
    }

    @Test
    fun riskyEntries() {
        // Each would change a common word everywhere: every "will" written "Will". Brand names people type a lot, such
        // as iphone, are on the common list too.
        val words = listOf("Will", "IT", "U.S.", "GitHub", "Second Brain", "iOS", "R&D", "go", "iPhone", "Kubernetes")
        assertThat(CustomWords.riskyEntries(words)).containsExactly("Will", "IT", "U.S.", "go", "iPhone").inOrder()
    }

    @Test
    fun emptyListChangesNothing() {
        for (cw in table.keys) assertThat(CustomWords.correct(cw.text, emptyList())).isEqualTo(cw.text)
    }

    @Test
    fun correctingAgainChangesNothing() {
        for ((cw, out) in table) assertThat(CustomWords.correct(out, cw.words)).isEqualTo(out)
        // The first pass fixes "kubernetis"; the next finds the exact "Kubernetes Engine" that made.
        assertThat(CustomWords.correct("kubernetis engine", listOf("Kubernetes", "Kubernetes Engine")))
            .isEqualTo("Kubernetes Engine")
        // A chain: each merge sets up the next, 5 passes deep; then one of 12 stages.
        val codex = listOf("Axbycz", "Axbyczduev", "Axbyczduevfwgx", "Axbyczduevfwgxhyiz", "Axbyczduevfwgxhyizjukv")
        assertThat(CustomWords.correct("ax by cz du ev fw gx hy iz ju kv", codex)).isEqualTo("Axbyczduevfwgxhyizjukv")
        val parts = ('b'..'z').map { "q$it" }.filter { it !in COMMON_WORDS }.take(25)
        val chain = (1..12).map { stage -> parts.take(2 * stage + 1).joinToString("").replaceFirstChar(Char::uppercaseChar) }
        assertThat(CustomWords.correct(parts.joinToString(" "), chain)).isEqualTo(chain.last())
        // A 4-word entry no n-gram can match whole, next to a merge into part of it: still a fixed point.
        val odd = listOf("W X Y Z", "WX")
        assertThat(CustomWords.correct(CustomWords.correct("wxyz", odd), odd)).isEqualTo(CustomWords.correct("wxyz", odd))
        // An alias chain: one word, whose "&" entries step down one per pass for 13 passes.
        val aliases = (0..12).map { "a&" + "and".repeat(it) + "b" }
        val aliased = "a" + "and".repeat(13) + "b"
        assertThat(CustomWords.correct(aliased, aliases)).isEqualTo("a&b")
        // One word that becomes 20, which 9 merges then join back up, one pass each.
        val part = ('b'..'z').map { "q$it" }.filter { it !in COMMON_WORDS }.take(20)
        val grown = listOf(part.joinToString(" ")) + (1..9).map { part.take(2 * it + 1).joinToString("") }
        assertThat(CustomWords.correct(part.joinToString(""), grown)).isEqualTo(part.take(19).joinToString("") + " " + part[19])
        // With the whole word as an entry too, the merges and the split loop: the text comes back as it was.
        val loop = grown + part.joinToString("")
        assertThat(CustomWords.correct(part.joinToString(""), loop)).isEqualTo(part.joinToString(""))
        for ((text, list) in listOf(aliased to aliases, part.joinToString("") to grown, part.joinToString("") to loop)) {
            assertThat(CustomWords.correct(CustomWords.correct(text, list), list)).isEqualTo(CustomWords.correct(text, list))
        }
        // Random texts from the words above and their near misses.
        val words = listOf(
            "GitHub", "ChatGPT", "Zendesk", "MacBook Pro", "Kubernetes", "SEV", "Ned", "R&D", "kubectl", "GPU", "#hashtag",
            "U.S.", "C++", "Kubernetes Engine", "W X Y Z", "WX", "Axbycz", "Axbyczduev",
        )
        val pool = listOf(
            "github", "chat", "gpt", "zen", "desk", "zendsk", "mac", "book", "pro", "kubernetis", "kubernetes's", "sev",
            "save", "ned", "red", "r", "and", "d", "kubectl", "Kubectl", "gpus", "the", "a", "is", "engine", ",", ".", "?",
            "hashtag", "#hashtag", "us", "c", "c++", "w", "x", "y", "z", "wxyz", "ax", "by", "cz", "du", "ev", "😀", "”",
        )
        val random = Random(11)
        repeat(2_000) {
            val text = List(random.nextInt(12)) { pool.random(random) }.joinToString(" ")
            val once = CustomWords.correct(text, words)
            assertThat(CustomWords.correct(once, words)).isEqualTo(once)
        }
    }

    @Test
    fun bandedDistanceMatchesTheFullOne() {
        // Every pair of strings over {a, b, c} of up to 6 letters, at each edit limit the matcher uses.
        val strings = (0..6).flatMap { n ->
            var count = 1
            repeat(n) { count *= 3 }
            (0 until count).map { i -> buildString { var x = i; repeat(n) { append("abc"[x % 3]); x /= 3 } } }
        }
        val matcher = CustomWords.Matcher(emptyList())
        val wrong = mutableListOf<String>()
        for (a in strings) for (b in strings) {
            var previous = IntArray(b.length + 1) { it }
            for (i in a.indices) {
                val current = IntArray(b.length + 1).also { it[0] = i + 1 }
                for (j in b.indices) current[j + 1] = minOf(previous[j + 1] + 1, current[j] + 1, previous[j] + if (a[i] == b[j]) 0 else 1)
                previous = current
            }
            val distance = previous[b.length]
            for (most in 0..2) {
                if (matcher.levenshtein(a, b, most) != distance.takeIf { it <= most }) wrong += "$a,$b,$most"
            }
        }
        assertThat(wrong).isEmpty()
    }

    @Test
    fun pipelineOrder() {
        // Custom words, then fillers, then stutters (D16; unguarded "so I opened Zendesk ticket").
        val raw = "um so I opened a Zendesk uh ticket ticket ticket"
        assertThat(cleanup(raw, "en") { CustomWords.correct(it, listOf("Zendesk")) }).isEqualTo("so I opened a Zendesk ticket")
    }

    @Test
    fun neverThrows() {
        // correct fails open: a crash would quietly give back the text as it was. So each random text has an exact
        // "zendesk" in its middle, which comes out as Zendesk only when the passes ran to the end. The time limit is a
        // hang check: a call here takes microseconds, but a busy machine stalled one for 60 ms (`fast` checks speed).
        val pool = (('a'..'z') + ('A'..'Z') + ('0'..'9') + " -,.!?'’&".toList()).map(Char::toString) +
            listOf("é", "ß", "İ", "你", "😀", "\u0301", "\u200D", "\u00A0", "\n")
        val words = listOf("Zendesk", "R&D", "CTA", "MacBook Pro", "İstanbul", "", " ", "kubectl", "a’s")
        val random = Random(7)
        CustomWords.correct("warm up", words)
        repeat(10_000) {
            val text = List(2) { List(random.nextInt(21)) { pool.random(random) }.joinToString("") }.joinToString(" zendesk ")
            val start = System.nanoTime()
            val out = CustomWords.correct(text, words)
            assertThat((System.nanoTime() - start) / 1_000_000.0).isLessThan(1_000.0)
            assertThat(out).contains(" Zendesk ")
            assertThat(CustomWords.correct(out, words)).isEqualTo(out)
        }
    }

    @Test
    fun parse() {
        assertThat(CustomWords.parse("GitHub, Zendesk\nMacBook   Pro\r\n  chat gpt ,, ")).containsExactly(
            "GitHub", "Zendesk", "MacBook Pro", "chat gpt",
        ).inOrder()
        assertThat(CustomWords.parse("GitHub, github\nGITHUB, Git Hub")).containsExactly("GitHub", "Git Hub").inOrder()
        // 60 characters is 60 code points: an emoji is two chars.
        assertThat(CustomWords.parse("${"x".repeat(61)}, ${"😀".repeat(60)}, ${"😀".repeat(61)}"))
            .containsExactly("😀".repeat(60))
        val many = CustomWords.parse((1..600).joinToString(",") { "w$it" })
        assertThat(many).hasSize(CustomWords.MAX_ENTRIES)
        assertThat(many.last()).isEqualTo("w500")
        assertThat(CustomWords.parse(" , \n ")).isEmpty()
        assertThat(CustomWords.parse("Re\u0301sume\u0301, Résumé")).containsExactly("Résumé") // NFC: the same entry
    }

    @Test
    fun fast() {
        // 500 entries against a 1,000-word transcript: common words, uncommon ones, and entries said near and exactly.
        val random = Random(3)
        val words = List(CustomWords.MAX_ENTRIES) { if (it % 5 == 0) "${name(random)} ${name(random)}" else name(random) }
        val common = COMMON_WORDS.toList().sorted()
        val transcript = List(1_000) {
            when (random.nextInt(20)) {
                0 -> words.random(random)
                1 -> words.random(random).lowercase().drop(1)
                2, 3 -> name(random)
                else -> common.random(random)
            }
        }.joinToString(" ")
        val worst = List(1_000) { name(random) + name(random) }.joinToString(" ") // no common word: all of it fuzzy
        // A worst case: 28-letter entries with one prefix, so one Soundex code, and the same letters, so one letter
        // mask, against words of that shape that match none of them. 2,250 words is a 15-minute take.
        val shape = { "kbdrmptlsnvc" + "aaeeiioouuyzgfhw".toList().shuffled(random).joinToString("") }
        val alike = List(CustomWords.MAX_ENTRIES) { shape() }
        val attack = List(2_250) { shape() }
        // The accepted ceiling: 17-letter Eulerian circuits over b, f, p and v share their letters, all 16 letter pairs
        // and their Soundex code, so the masks pass every one. Queries are circuits more than 2 edits from each entry.
        // A loose 150 ms per 1,000 words still catches a real regression.
        val circuits = eulerian(random, CustomWords.MAX_ENTRIES + 200)
        val eulerianEntries = circuits.take(CustomWords.MAX_ENTRIES)
        val matcher = CustomWords.Matcher(emptyList())
        val queries = circuits.drop(CustomWords.MAX_ENTRIES)
            .filter { query -> eulerianEntries.all { matcher.levenshtein(query, it, 2) == null } }
        assertThat(queries.size).isAtLeast(20)
        val eulerianText = { count: Int -> List(count) { queries[it % queries.size] }.joinToString(" ") }
        val cases = listOf(
            Case("realistic", transcript, words, 10.0),
            Case("uncommon words only", worst, words, 10.0),
            Case("alike entries", attack.take(1_000).joinToString(" "), alike, 10.0),
            Case("alike entries, 15-minute take", attack.joinToString(" "), alike, 10.0),
            Case("Eulerian entries", eulerianText(1_000), eulerianEntries, 150.0),
            Case("Eulerian entries, 15-minute take", eulerianText(2_250), eulerianEntries, 150.0),
        )
        for (case in listOf(cases[3], cases[5])) assertThat(CustomWords.correct(case.text, case.list)).isEqualTo(case.text) // no match
        for ((what, text, list, budget) in cases) {
            repeat(10) { CustomWords.correct(text, list) }
            val ms = List(21) {
                val start = System.nanoTime()
                CustomWords.correct(text, list)
                (System.nanoTime() - start) / 1_000_000.0
            }.sorted()
            val count = text.count { it == ' ' } + 1
            println("CustomWords.correct, 500 entries, $count words, $what: median ${"%.2f".format(ms[10])} ms, " +
                "90th percentile ${"%.2f".format(ms[18])} ms")
            assertThat(ms[10]).isLessThan(budget * count / 1_000) // at most 10 ms per 1,000 words, or the ceiling's
        }
    }

    // One timing case: a transcript, a word list, and the median it must stay under per 1,000 words.
    private data class Case(val what: String, val text: String, val list: List<String>, val budget: Double)

    // Distinct 17-letter walks from b that use each of the 16 pairs of b, f, p and v once.
    private fun eulerian(random: Random, count: Int): List<String> {
        val found = LinkedHashSet<String>()
        while (found.size < count) {
            val unused = "bfpv".associateWith { "bfpv".toMutableList() }
            val walk = StringBuilder("b")
            while (true) {
                val next = unused.getValue(walk.last())
                if (next.isEmpty()) break
                walk.append(next.removeAt(random.nextInt(next.size)))
            }
            if (walk.length == 17) found += walk.toString()
        }
        return found.toList()
    }

    // A made-up name of 2 to 9 letters, rarely a common word.
    private fun name(random: Random): String {
        val syllables = List(1 + random.nextInt(4)) { "bcdfghjklmnprstvwz".random(random).toString() + "aeiou".random(random) }
        return syllables.joinToString("").let { it.replaceFirstChar(Char::uppercaseChar) + if (random.nextBoolean()) "n" else "" }
    }
}
