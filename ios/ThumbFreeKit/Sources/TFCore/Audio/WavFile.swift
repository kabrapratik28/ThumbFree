import Foundation

public enum WavFile {
    public enum Error: Swift.Error, Equatable { case unsupported(String) }

    /// Reads a 16 kHz mono 16-bit PCM WAV into Float samples in -1...1. Skips chunks it does not need (LIST, FLLR and others).
    public static func readMono16k(url: URL) throws -> [Float] {
        let data = try Data(contentsOf: url)
        func u32(_ o: Int) -> UInt32 { data.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: o, as: UInt32.self) } }
        func u16(_ o: Int) -> UInt16 { data.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: o, as: UInt16.self) } }
        func tag(_ o: Int) -> String { String(decoding: data[o..<o + 4], as: UTF8.self) }
        guard data.count >= 12, tag(0) == "RIFF", tag(8) == "WAVE" else { throw Error.unsupported("not a RIFF WAVE file") }
        var offset = 12
        var format: (tag: UInt16, channels: UInt16, rate: UInt32, bits: UInt16)?
        while offset + 8 <= data.count {
            let size = Int(u32(offset + 4))
            let body = offset + 8
            switch tag(offset) {
            case "fmt ":
                guard body + 16 <= data.count else { throw Error.unsupported("short fmt chunk") }
                var formatTag = u16(body)
                // WAVE_FORMAT_EXTENSIBLE: the real format is the first two bytes of the SubFormat GUID.
                if formatTag == 0xFFFE, size >= 40 { formatTag = u16(body + 24) }
                format = (formatTag, u16(body + 2), u32(body + 4), u16(body + 14))
            case "data":
                guard let f = format, f.tag == 1, f.channels == 1, f.rate == 16_000, f.bits == 16 else {
                    throw Error.unsupported("need 16 kHz mono 16-bit PCM")
                }
                let count = (min(body + size, data.count) - body) / 2
                return data.withUnsafeBytes { raw in
                    (0..<count).map { Float(raw.loadUnaligned(fromByteOffset: body + $0 * 2, as: Int16.self)) / 32768 }
                }
            default:
                break
            }
            offset = body + size + (size & 1)
        }
        throw Error.unsupported("no data chunk")
    }
}
