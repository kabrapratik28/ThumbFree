/// Where session audio comes from: the microphone, or a WAV file in tests and with `-TFAudioFile`.
/// Blocks are 16 kHz mono Float samples, delivered in order on the source's own thread.
@MainActor protocol AudioSource: AnyObject {
    func start(_ onSamples: @escaping @Sendable ([Float]) -> Void) async throws
    func stop()
}
