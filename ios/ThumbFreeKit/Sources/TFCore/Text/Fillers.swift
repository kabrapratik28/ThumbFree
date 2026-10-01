/// Filler words ("uh", "um") removed from a take's text. Adapted from MIT-licensed code; see THIRD_PARTY_NOTICES.md.
public enum Fillers {
    /// Not a word in any language the models write, so removed with no language known.
    static let universal = ["uh", "uhm", "umm", "uhh", "uhhh", "ehh", "ehm", "ahm", "hmm", "hm", "mmm", "хм", "ммм"]

    /// Real words elsewhere (Portuguese "um", Spanish "ha"), so removed only for their own language.
    static func gated(_ language: String) -> [String] {
        switch code(language) {
        case "en": ["um", "ah", "eh", "ha"]
        case "de": ["äh", "ähm"]
        case "fr": ["euh"]
        default: []
        }
    }

    /// The language code before "-" or "_": "pt-BR" is "pt".
    static func code(_ language: String) -> String {
        String(Substring(language.unicodeScalars.prefix { $0 != "-" && $0 != "_" }))
    }

    /// `language`: an ISO 639-1 code, or nil when unknown (the multilingual model), which removes only the universal list.
    public static func remove(_ text: String, language: String?) -> String {
        remove(text, fillers: universal + (language.map(gated) ?? []))
    }

    /// Each filler goes as a whole word (Rust regex `\b`), in any case, with one comma or period right after it,
    /// in list order. (Android's custom filler list, not in its UI, goes through here in the tests.)
    static func remove(_ text: String, fillers: [String]) -> String {
        fillers.reduce(text, removeWord)
    }

    private static func removeWord(_ text: String, _ word: String) -> String {
        let w = Array(word.unicodeScalars)
        // "" matches at every word boundary, and a naive loop never ends: remove nothing.
        if w.isEmpty { return text }
        let t = Array(text.unicodeScalars)
        var out = String.UnicodeScalarView()
        var kept = 0 // start of the text not yet copied to out
        var i = 0
        while i + w.count <= t.count {
            if isBoundary(t, i) && matches(t, i, w) && isBoundary(t, i + w.count) {
                out.append(contentsOf: t[kept..<i])
                i += w.count
                if i < t.count && (t[i] == "," || t[i] == ".") { i += 1 }
                kept = i
            } else {
                i += 1
            }
        }
        out.append(contentsOf: t[kept...])
        return String(out)
    }

    // Case compared scalar by scalar through the lowercase mapping: equal to the regex's case folding for every built-in filler.
    private static func matches(_ t: [Unicode.Scalar], _ at: Int, _ w: [Unicode.Scalar]) -> Bool {
        for k in 0..<w.count {
            let a = t[at + k], b = w[k]
            if a != b && a.properties.lowercaseMapping != b.properties.lowercaseMapping { return false }
        }
        return true
    }

    // Rust regex \b: a word character on exactly one side of i.
    private static func isBoundary(_ t: [Unicode.Scalar], _ i: Int) -> Bool {
        (i > 0 && UnicodeText.isWordChar(t[i - 1])) != (i < t.count && UnicodeText.isWordChar(t[i]))
    }
}
