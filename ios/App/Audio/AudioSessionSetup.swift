import AVFoundation

/// Record and play; other audio keeps playing; Bluetooth headphones keep their music quality (A2DP) and
/// the phone's own mic records (never the call-quality Bluetooth mic).
enum AudioSessionSetup {
    static let options: AVAudioSession.CategoryOptions = [.mixWithOthers, .allowBluetoothA2DP, .defaultToSpeaker]

    static func configure(_ session: AVAudioSession = .sharedInstance()) throws {
        try session.setCategory(.playAndRecord, mode: .default, options: options)
        // 20 ms blocks, since the stop tail is judged per block. Only a preference: the mic runs anyway.
        try? session.setPreferredIOBufferDuration(0.02)
    }
}
