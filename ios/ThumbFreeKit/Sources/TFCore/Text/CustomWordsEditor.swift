// The Dictionary editor's rules and messages (the Android wording). Add and Save check one entry; Paste a list says what
// it will add and skip before it does.
extension CustomWords {
    /// Why a draft can't be saved as one entry.
    public enum EntryProblem: Error, Equatable {
        case blank, duplicate, tooLong, several

        /// The line under the field; nil for blank (Add is only disabled).
        public var message: String? {
            switch self {
            case .blank: nil
            case .duplicate: "Already in your Dictionary."
            case .tooLong: "Too long: an entry has at most \(CustomWords.maxChars) characters."
            case .several: "One at a time here. Use Paste a list for several."
            }
        }
    }

    /// `raw` as one entry of `existing`, spelled as `parse` spells it, or why it can't be one: blank, several (a comma or a
    /// line break), too long, or already in the list in any case. `editing` is the entry being edited, which its new
    /// spelling may repeat (a case-only change).
    public static func checkEntry(_ raw: String, existing: [String], editing: String?) -> Result<String, EntryProblem> {
        if raw.unicodeScalars.allSatisfy(UnicodeText.isWhitespace) { return .failure(.blank) }
        if raw.unicodeScalars.contains(where: isSeparator) { return .failure(.several) }
        let parsed = parse(raw)
        guard parsed.count == 1, let entry = parsed.first else { return .failure(.tooLong) }
        let taken = Set(existing.filter { $0 != editing }.map(UnicodeText.lowercase))
        return taken.contains(UnicodeText.lowercase(entry)) ? .failure(.duplicate) : .success(entry)
    }

    /// The caution for an entry that is also a common word, or nil: "“Will” is also a common word, so every “will” will
    /// be written “Will”."
    public static func riskyNote(_ entry: String) -> String? {
        if riskyEntries([entry]).isEmpty { return nil }
        return "“\(entry)” is also a common word, so every “\(UnicodeText.lowercase(entry))” will be written “\(entry)”."
    }

    /// What adding a pasted list to the Dictionary gives: `entries` the new list, `added` its new entries, and what is
    /// left out: `duplicates` (of the list, or within the paste), `tooLong` (over `maxChars`), `notFitting` (past `maxEntries`).
    public struct ListAddition: Sendable, Equatable {
        public let entries: [String]
        public let added: [String]
        public let duplicates: Int
        public let tooLong: Int
        public let notFitting: Int

        /// "12 will be added. 2 duplicates and 1 long entry will be skipped."
        public var summary: String {
            let adding = added.isEmpty ? "Nothing new to add." : "\(added.count) will be added."
            var skipped: [String] = []
            if duplicates > 0 { skipped.append(duplicates == 1 ? "1 duplicate" : "\(duplicates) duplicates") }
            if tooLong > 0 { skipped.append(tooLong == 1 ? "1 long entry" : "\(tooLong) long entries") }
            if notFitting > 0 { skipped.append("\(notFitting) over the limit of \(CustomWords.maxEntries)") }
            guard let lastSkipped = skipped.last else { return adding }
            let joined = skipped.count == 1 ? lastSkipped : skipped.dropLast().joined(separator: ", ") + " and " + lastSkipped
            return adding + " " + joined + " will be skipped."
        }
    }

    /// `raw` (names split at commas or line breaks) added after `existing` through `parse`, which keeps the list's own
    /// spelling of a repeat.
    public static func addList(_ raw: String, to existing: [String]) -> ListAddition {
        let pieces = raw.unicodeScalars.split(omittingEmptySubsequences: false, whereSeparator: isSeparator)
            .map { String(Substring($0)) }.filter { !$0.unicodeScalars.allSatisfy(UnicodeText.isWhitespace) }
        let tooLong = pieces.filter { parse($0).isEmpty }.count // a non-blank piece parse keeps nothing of
        let known = Set(existing.map(UnicodeText.lowercase))
        // The paste's own new entries, uncapped: `parse(raw)` alone would stop at maxEntries and undercount
        // duplicates when the paste itself has more new entries than that.
        let fresh = parse(raw, limit: .max).filter { !known.contains(UnicodeText.lowercase($0)) }.count
        let merged = parse((existing + [raw]).joined(separator: "\n"))
        let added = Array(merged.dropFirst(existing.count))
        return ListAddition(entries: merged, added: added, duplicates: pieces.count - tooLong - fresh, tooLong: tooLong,
                            notFitting: fresh - added.count)
    }
}
