import Foundation

/// SentencePiece pieces by token id, read from the model folder's `parakeet_vocab.json` ({"id": "piece"}).
struct Vocabulary: Sendable {
    let pieces: [String]

    init(pieces: [String]) { self.pieces = pieces }

    /// Keeps ids 0..<blankID (the real tokens); every one of them must be present.
    init(url: URL, blankID: Int) throws {
        guard let data = try? Data(contentsOf: url) else { throw EngineError.modelMissing(url.lastPathComponent) }
        guard let map = try? JSONDecoder().decode([String: String].self, from: data) else {
            throw EngineError.loadFailed("\(url.lastPathComponent) is not an id to piece map")
        }
        var pieces = [String?](repeating: nil, count: blankID)
        for (key, piece) in map {
            if let id = Int(key), pieces.indices.contains(id) { pieces[id] = piece }
        }
        let found = pieces.compactMap { $0 }
        guard found.count == blankID else {
            throw EngineError.loadFailed("\(url.lastPathComponent) lacks some of the ids below \(blankID)")
        }
        self.pieces = found
    }

    /// Joins the pieces, turns the SentencePiece word mark (U+2581) into spaces, trims.
    func text(_ ids: [Int]) -> String {
        ids.map { pieces.indices.contains($0) ? pieces[$0] : "" }.joined()
            .replacingOccurrences(of: "\u{2581}", with: " ")
            .trimmingCharacters(in: .whitespaces)
    }
}
