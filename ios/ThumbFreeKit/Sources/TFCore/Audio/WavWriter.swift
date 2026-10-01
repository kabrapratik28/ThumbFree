import Foundation

/// Writes a take as a 16 kHz mono 16-bit PCM WAV while it records. The 44-byte header goes first; its sizes are patched
/// on every sync() (at each chunk boundary) and on finish(), so a killed process leaves a playable prefix, and repair()
/// recovers the rest from the file length. Owned by one actor: not Sendable.
public final class WavWriter {
    public static let headerBytes = 44
    private let handle: FileHandle
    private var failed = false
    /// Test seam only: makes the next append() write fail as if the underlying write failed, without touching the file.
    var debugFailNextWrite = false

    /// Thrown by append() once a previous write has failed. The writer refuses to write again so a partial
    /// sample can never land; call sync() or finish() instead.
    public enum Error: Swift.Error, Equatable { case appendAfterFailure }
    private struct DebugWriteFailure: Swift.Error {}

    /// Creates the file (replacing any old one) with an empty header.
    public init(url: URL) throws {
        try Self.header(dataBytes: 0).write(to: url)
        handle = try FileHandle(forWritingTo: url)
        try handle.seekToEnd()
    }

    /// Samples in -1...1 become 16-bit PCM: rounded, clipped, NaN as 0.
    /// Do not call again after this throws: a failed write (for example the disk is full) can otherwise leave a
    /// partial sample on disk that later samples would land on top of, one byte off, forever. The writer remembers
    /// the failure, and any later call throws `Error.appendAfterFailure` at once without writing. Call sync() or
    /// finish() instead: both patch the header from the file's actual length, which already drops a trailing odd
    /// byte, so every complete sample written before the failure is kept.
    public func append(_ samples: [Float]) throws {
        guard !failed else { throw Error.appendAfterFailure }
        var bytes = Data(count: samples.count * 2)
        bytes.withUnsafeMutableBytes { raw in
            for (i, sample) in samples.enumerated() {
                raw.storeBytes(of: Self.pcm(sample).littleEndian, toByteOffset: i * 2, as: Int16.self)
            }
        }
        do {
            if debugFailNextWrite {
                debugFailNextWrite = false
                throw DebugWriteFailure()
            }
            try handle.write(contentsOf: bytes)
        } catch {
            failed = true
            throw error
        }
    }

    /// Patches the header to the samples written so far and flushes to disk.
    public func sync() throws {
        _ = try Self.patch(handle)
        try handle.synchronize()
    }

    /// Patches the header, flushes and closes. Returns the sample count.
    public func finish() throws -> Int {
        let samples = try Self.patch(handle)
        try handle.synchronize()
        try handle.close()
        return samples
    }

    /// Rewrites the header from the file length, dropping a trailing odd byte. A file shorter than the header holds no
    /// audio and becomes a valid empty WAV. Returns the sample count.
    public static func repair(url: URL) throws -> Int {
        let handle = try FileHandle(forWritingTo: url)
        defer { try? handle.close() }
        if try handle.seekToEnd() < UInt64(headerBytes) {
            try handle.truncate(atOffset: 0)
            try handle.write(contentsOf: header(dataBytes: 0))
        }
        let samples = try patch(handle)
        try handle.synchronize()
        return samples
    }

    /// Sets the RIFF and data sizes from the file length and truncates a partial sample. Returns the sample count.
    private static func patch(_ handle: FileHandle) throws -> Int {
        let dataBytes = (Int(try handle.seekToEnd()) - headerBytes) / 2 * 2
        try handle.truncate(atOffset: UInt64(headerBytes + dataBytes))
        try handle.seek(toOffset: 4)
        try handle.write(contentsOf: le32(36 + dataBytes))
        try handle.seek(toOffset: 40)
        try handle.write(contentsOf: le32(dataBytes))
        try handle.seekToEnd()
        return dataBytes / 2
    }

    private static func header(dataBytes: Int) -> Data {
        var data = Data("RIFF".utf8) + le32(36 + dataBytes) + Data("WAVEfmt ".utf8)
        data += le32(16) + le16(1) + le16(1) + le32(16_000) + le32(32_000) + le16(2) + le16(16) // PCM, mono, 16 kHz, 16-bit
        return data + Data("data".utf8) + le32(dataBytes)
    }

    private static func le32(_ value: Int) -> Data { withUnsafeBytes(of: UInt32(truncatingIfNeeded: value).littleEndian) { Data($0) } }
    private static func le16(_ value: Int) -> Data { withUnsafeBytes(of: UInt16(truncatingIfNeeded: value).littleEndian) { Data($0) } }

    static func pcm(_ sample: Float) -> Int16 {
        let scaled = (sample * 32_768).rounded()
        return scaled.isNaN ? 0 : Int16(max(-32_768, min(32_767, scaled)))
    }
}
