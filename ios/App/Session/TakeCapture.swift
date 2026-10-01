import Foundation
import TFCore

/// Session audio into takes. While no take records it keeps the 300 ms pre-roll. A take's WAV
/// starts with the pre-roll, and every sample the WAV gets goes to `onAudio` too, in the same order: the take's live
/// transcriber, which also owns the stop tail. Its own speech gate keeps the silence clock
/// and `mayHoldSpeech`; no samples stay in memory.
@MainActor final class TakeCapture {
    struct Finished {
        let samples: Int
        /// A frame was speech or above -55 dBFS.
        let mayHoldSpeech: Bool
    }

    private(set) var takeID: UUID?
    private var preRoll = PreRoll()
    private var wav: WavWriter?
    private var onAudio: @MainActor ([Float]) -> Void = { _ in }
    private var received = 0 // samples in the take so far
    private var judged = 0 // samples in the whole frames judged so far
    private var unjudged: [Float] = [] // the samples after `judged`, under one frame
    private var gate = SpeechGate()
    private var mayHoldSpeech = false
    private var lastSpeechEnd = 0

    var recordedMs: Int { received / 16 }
    var msSinceSpeech: Int { (received - lastSpeechEnd) / 16 }

    /// Starts a take whose WAV, and `onAudio`, begin with the pre-roll.
    func begin(_ id: UUID, wavURL: URL, onAudio: @escaping @MainActor ([Float]) -> Void) throws {
        wav = try WavWriter(url: wavURL)
        takeID = id
        self.onAudio = onAudio
        received = 0
        judged = 0
        unjudged = []
        gate = SpeechGate()
        mayHoldSpeech = false
        lastSpeechEnd = 0
        try append(preRoll.drain())
    }

    /// One block of session audio: into the pre-roll while idle, into the take while one records.
    func consume(_ block: [Float]) throws {
        guard takeID != nil else { return preRoll.push(block) }
        try append(block)
    }

    /// Ends the take: patches and closes the WAV. A failed close still ends the take.
    func finish() -> Finished {
        _ = try? wav?.finish()
        let done = Finished(samples: received, mayHoldSpeech: mayHoldSpeech)
        discard()
        return done
    }

    /// Ends the take without its audio (its folder is deleted by the caller).
    func discard() {
        takeID = nil
        wav = nil
        onAudio = { _ in }
        unjudged = []
    }

    private func append(_ block: [Float]) throws {
        guard !block.isEmpty else { return }
        try wav?.append(block)
        onAudio(block)
        received += block.count
        unjudged += block
        var start = 0
        while unjudged.count - start >= SpeechGate.frameSamples {
            let verdict = gate.push(unjudged[start..<start + SpeechGate.frameSamples])
            start += SpeechGate.frameSamples
            judged += SpeechGate.frameSamples
            if verdict.isSpeech || verdict.loud { mayHoldSpeech = true }
            if verdict.isSpeech { lastSpeechEnd = judged }
        }
        unjudged.removeFirst(start)
    }
}
