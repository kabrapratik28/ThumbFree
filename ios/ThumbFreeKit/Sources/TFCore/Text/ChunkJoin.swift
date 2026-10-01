/// The text of a take cut into chunks: each chunk's text trimmed, empty ones dropped, one space between.
public enum ChunkJoin {
    // ponytail: always one space; a script written without spaces (CJK, Thai) needs a script-aware join if such a model ships.
    public static func join(_ texts: [String]) -> String {
        texts.map(UnicodeText.trim).filter { !$0.isEmpty }.joined(separator: " ")
    }
}
