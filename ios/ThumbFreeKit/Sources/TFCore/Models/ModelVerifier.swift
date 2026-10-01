import CryptoKit
import Foundation

/// Checks a model folder against its catalog entry: every file's size first (cheap), then the SHA-256 of the files
/// whose size is right.
public enum ModelVerifier {
    public enum Result: String, Sendable, Equatable { case ok, missing, wrongSize, wrongHash }

    /// One result per catalog file, keyed by path. Files the entry does not list are ignored. A file that cannot be
    /// read counts as wrongHash: it is no more usable than a damaged one. A path that would escape `folder` (see
    /// `ModelFile.url(in:)`) counts as missing: it is never opened.
    public static func check(folder: URL, entry: ModelEntry) -> [String: Result] {
        var results: [String: Result] = [:]
        for file in entry.files {
            guard let url = file.url(in: folder),
                  let size = (try? FileManager.default.attributesOfItem(atPath: url.path))?[.size] as? Int64 else {
                results[file.path] = .missing
                continue
            }
            if size != file.bytes { results[file.path] = .wrongSize }
        }
        for file in entry.files where results[file.path] == nil {
            guard let url = file.url(in: folder) else {
                results[file.path] = .missing
                continue
            }
            let hash = try? sha256(of: url)
            results[file.path] = hash == file.sha256 ? .ok : .wrongHash
        }
        return results
    }

    /// Lowercase hex SHA-256 of a file, read in 1 MiB blocks so a 445 MB model file never sits in memory. Each block
    /// is read in its own autorelease pool: without it the blocks pile up until the call returns.
    public static func sha256(of url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hasher = SHA256()
        while try autoreleasepool(invoking: {
            guard let block = try handle.read(upToCount: 1 << 20), !block.isEmpty else { return false }
            hasher.update(data: block)
            return true
        }) {}
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }
}
