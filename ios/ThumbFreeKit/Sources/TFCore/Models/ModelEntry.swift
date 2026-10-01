import Foundation

/// One file of a model, relative to the model's folder, with its pinned size and SHA-256 (lowercase hex).
public struct ModelFile: Sendable, Equatable {
    public let path: String
    public let bytes: Int64
    public let sha256: String

    public init(path: String, bytes: Int64, sha256: String) {
        self.path = path
        self.bytes = bytes
        self.sha256 = sha256
    }

    /// `folder` joined with `path`, or nil if `path` would escape it. `appendingPathComponent` does not resolve
    /// "..", but `stat`/`open` do, so an unchecked join lets a hostile entry (a corrupt manifest, or later a bad
    /// download response) read or write outside `folder`. Callers must treat nil the same as a missing file and
    /// never fall back to joining the raw path themselves.
    public func url(in folder: URL) -> URL? {
        let components = path.split(separator: "/", omittingEmptySubsequences: false)
        guard !path.isEmpty, !path.hasPrefix("/"),
              components.allSatisfy({ !$0.isEmpty && $0 != "." && $0 != ".." }) else { return nil }
        return folder.appendingPathComponent(path)
    }
}

/// A model ThumbFree downloads: a speech model the user can pick, or a support model such as the speech check.
/// `languageHint`: "en" for an English-only speech model; nil for a multilingual one and for support models.
public struct ModelEntry: Sendable, Equatable, Identifiable {
    public let id: String
    public let displayName: String
    public let summary: String
    public let repo: String
    public let revision: String
    public let files: [ModelFile]
    public let languageHint: String?

    public init(id: String, displayName: String, summary: String, repo: String, revision: String, files: [ModelFile],
                languageHint: String?) {
        self.id = id
        self.displayName = displayName
        self.summary = summary
        self.repo = repo
        self.revision = revision
        self.files = files
        self.languageHint = languageHint
    }

    public var totalBytes: Int64 { files.reduce(0) { $0 + $1.bytes } }
}

extension ModelCatalog {
    /// The entry for a saved id. An id the catalog no longer has, or none, reads as the default.
    public static func entry(for id: String?) -> ModelEntry { all.first { $0.id == id } ?? v2 }
}
