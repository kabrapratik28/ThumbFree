/// Every take's text, and Retry's and Transcribe again's, in Android's order: join the chunk texts, then custom words,
/// then fillers, then normalize. Every step is a total function (it never throws and never traps), so there is no
/// fallback path. `raw` is the joined text, kept in History as the model's own.
public enum TextPipeline {
    public static func run(chunkTexts: [String], dictionary: [String], language: String?) -> (raw: String, text: String) {
        let raw = ChunkJoin.join(chunkTexts)
        let exactOnly = CustomWords.exactOnly(language: language)
        return (raw, clean(raw, language: language) { CustomWords.correct($0, entries: dictionary, exactOnly: exactOnly) })
    }

    /// Custom words, then fillers, then normalize. `customWords` is a parameter so a test can show the order.
    static func clean(_ raw: String, language: String?, customWords: (String) -> String) -> String {
        Normalize.apply(Fillers.remove(customWords(raw), language: language))
    }
}
