import Foundation
import Testing
@testable import TFCore

/// Seeded random numbers, so every run of a random test is the same run.
struct SplitMix64: RandomNumberGenerator {
    var state: UInt64
    init(seed: UInt64) { state = seed }
    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}

/// `ms` of 16 kHz Gaussian noise whose RMS level is `dbfs`.
fileprivate func noise(_ ms: Int, _ dbfs: Double, _ rng: inout SplitMix64) -> [Float] {
    let sigma = pow(10, dbfs / 20)
    return (0..<ms * 16).map { _ in
        let u1 = Double.random(in: Double.leastNonzeroMagnitude..<1, using: &rng)
        let u2 = Double.random(in: 0..<1, using: &rng)
        return Float(sigma * (-2 * log(u1)).squareRoot() * cos(2 * .pi * u2))
    }
}

/// Pushes every whole 480-sample frame; returns the verdicts.
@discardableResult
fileprivate func feed(_ gate: inout SpeechGate, _ samples: [Float]) -> [FrameVerdict] {
    stride(from: 0, through: samples.count - 480, by: 480).map { gate.push(samples[$0..<$0 + 480]) }
}

@Suite struct SpeechGateTests {
    var rng = SplitMix64(seed: 7)

    // Some phones feed zeros when the microphone is muted by a privacy switch.
    @Test func zerosAreSilentInput() {
        var gate = SpeechGate()
        feed(&gate, [Float](repeating: 0, count: 3 * 16_000))
        #expect(gate.silentMic)
        #expect(!gate.hasSpeech)
    }

    // Every model answered "Yeah." to a padded 0.25 s silent tap, so the gate must stop it before the engine.
    @Test func shortSilentTapIsNoSpeech() {
        var gate = SpeechGate()
        feed(&gate, [Float](repeating: 0, count: 4_000))
        #expect(!gate.hasSpeech)
    }

    // Tap and speak at once: 0.4 s of loud signal from sample 0, then room noise.
    @Test mutating func coldStartYesIsSpeech() {
        var gate = SpeechGate()
        feed(&gate, noise(400, -25, &rng) + noise(350, -60, &rng))
        #expect(gate.hasSpeech)
    }

    // A floor guessed at the start counted steady -35 dBFS noise as 29 speech frames.
    @Test mutating func steadyNoiseAtTheStartIsNotSpeech() {
        for dbfs in [-35.0, -25.0] {
            var gate = SpeechGate()
            feed(&gate, noise(3_500, dbfs, &rng))
            #expect(gate.speechFrames == 0)
        }
    }

    // The first two frames near -74 dBFS, then the room comes in.
    @Test mutating func noiseAfterAQuietStartIsNotSpeech() {
        var gate = SpeechGate()
        feed(&gate, noise(30, -74, &rng) + noise(30, -72, &rng) + noise(3_000, -40, &rng))
        #expect(gate.speechFrames == 0)
    }

    // Speech from the first sample in a -45 dBFS room: the -22 and -20 dBFS frames count, the dip between them does not.
    @Test mutating func speechAtTheStartIsSpeech() {
        var gate = SpeechGate()
        feed(&gate, noise(150, -22, &rng) + noise(60, -42, &rng) + noise(240, -20, &rng) + noise(350, -45, &rng))
        #expect(gate.speechFrames == 13)
        #expect(gate.hasSpeech)
    }

    // -48 dBFS from the first sample in a -70 dBFS room: 22 dB over the floor and above -55 dBFS.
    @Test mutating func quietSpeechAtTheStartIsSpeech() {
        var gate = SpeechGate()
        feed(&gate, noise(390, -48, &rng) + noise(360, -70, &rng))
        #expect(gate.speechFrames == 13)
    }

    // In the first 3 s a frame needs 8 dB over the floor; after them 12.
    @Test mutating func theFirst3sNeed8dBOverTheFloorAndLaterFrames12() {
        var atStart = SpeechGate()
        feed(&atStart, noise(390, -41, &rng) + noise(360, -50, &rng))
        #expect(atStart.speechFrames == 13)

        var tooQuiet = SpeechGate()
        feed(&tooQuiet, noise(390, -45, &rng) + noise(360, -50, &rng))
        #expect(tooQuiet.speechFrames == 0)

        var later = SpeechGate()
        feed(&later, noise(3_000, -50, &rng) + noise(390, -41, &rng) + noise(360, -50, &rng))
        #expect(later.speechFrames == 0)
    }

    // push() cannot know the floor in the first 3 s, so it reports those frames as non-speech; speechFrames counts them
    // once the floor of the first 3 s is known. From then on each frame is judged as it comes.
    @Test mutating func theFirst3sCountOnceTheirFloorIsKnown() {
        var gate = SpeechGate()
        let first = feed(&gate, noise(990, -60, &rng) + noise(300, -20, &rng) + noise(1_710, -60, &rng))
        #expect(first.count == 100)
        #expect(!first.contains { $0.isSpeech })
        #expect(gate.speechFrames == 10)

        let next = feed(&gate, noise(300, -20, &rng))
        #expect(next.map(\.isSpeech) == [Bool](repeating: true, count: 10))
        #expect(gate.speechFrames == 20)
    }

    // 3 s of Gaussian noise at -40 dBFS.
    @Test mutating func steadyNoiseIsNotSpeech() {
        var gate = SpeechGate()
        let verdicts = feed(&gate, noise(3_000, -40, &rng))
        #expect(verdicts.count == 100)
        #expect(verdicts.allSatisfy { abs($0.levelDBFS + 40) < 1 && $0.loud })
        #expect(verdicts.allSatisfy { !$0.isSpeech })
        #expect(!gate.hasSpeech)
    }

    // After the first 3 s, -40 dBFS noise sets the floor: bursts 8 dB over it are not speech, bursts 20 dB over it are.
    @Test mutating func burstsAboveTheFloorAreSpeech() {
        var gate = SpeechGate()
        feed(&gate, noise(3_000, -40, &rng))
        for _ in 0..<3 { feed(&gate, noise(300, -32, &rng) + noise(600, -40, &rng)) }
        #expect(!gate.hasSpeech)
        for _ in 0..<3 { feed(&gate, noise(300, -20, &rng) + noise(600, -40, &rng)) }
        #expect(gate.hasSpeech)
    }

    // Over digital silence the floor is -80 dBFS: -58 dBFS bursts are 22 dB over it but too quiet to be speech.
    @Test mutating func burstsUnder55dBFSAreNotSpeech() {
        var gate = SpeechGate()
        feed(&gate, [Float](repeating: 0, count: 900 * 16))
        for _ in 0..<3 { feed(&gate, noise(300, -58, &rng) + [Float](repeating: 0, count: 600 * 16)) }
        #expect(!gate.hasSpeech)
        feed(&gate, noise(300, -50, &rng))
        #expect(gate.hasSpeech)
    }

    // One 1 ms click at 1 s is loud enough for one speech frame, but a take needs 150 ms of them.
    @Test mutating func aSingleClickIsNotSpeech() {
        var take = noise(2_000, -60, &rng)
        for i in 16_000..<16_016 { take[i] = 30_000 / 32_768 }
        var gate = SpeechGate()
        feed(&gate, take)
        #expect(gate.speechFrames == 1)
        #expect(!gate.hasSpeech)
    }

    @Test mutating func needs150msOfSpeech() {
        var gate = SpeechGate()
        feed(&gate, noise(900, -60, &rng) + noise(120, -20, &rng) + noise(900, -60, &rng))
        #expect(!gate.hasSpeech)
        feed(&gate, noise(30, -20, &rng))
        #expect(gate.hasSpeech)
    }

    // A slice from the middle of a buffer (start index not 0) is judged like a copy of it.
    @Test mutating func aSliceIsJudgedLikeACopy() {
        let block = noise(30, -40, &rng) + noise(30, -10, &rng)
        var sliced = SpeechGate()
        var copied = SpeechGate()
        #expect(sliced.push(block[480..<960]) == copied.push(ArraySlice(Array(block[480..<960]))))
    }

    @Test func silentMicAfter1500ms() {
        var blocked = SpeechGate()
        let quiet = [Float](repeating: 32 / 32_768, count: 480) // peak -60.2 dBFS
        for _ in 0..<49 { _ = blocked.push(quiet[...]) }
        #expect(!blocked.silentMic) // not judged before 1.5 s
        _ = blocked.push(quiet[...])
        #expect(blocked.silentMic)

        var live = SpeechGate()
        for i in 0..<50 {
            var frame = [Float](repeating: 0, count: 480)
            if i == 25 { frame[0] = 33 / 32_768 } // one sample at -59.9 dBFS
            _ = live.push(frame[...])
        }
        #expect(!live.silentMic)
    }

    @Test func verdictsSayLoudAboveMinus55() {
        #expect(FrameVerdict(isSpeech: false, levelDBFS: -54.9).loud)
        #expect(!FrameVerdict(isSpeech: false, levelDBFS: -55).loud)
        #expect(!FrameVerdict(isSpeech: false, levelDBFS: -.infinity).loud)
    }
}

@Suite struct LevelsTests {
    @Test func dbfsMapsToZeroToOne() {
        #expect(Levels.unit(-60) == 0)
        #expect(Levels.unit(-10) == 1)
        #expect(Levels.unit(-35) == 0.5)
        #expect(Levels.unit(-90) == 0)
        #expect(Levels.unit(0) == 1)
        #expect(Levels.unit(-.infinity) == 0) // digital silence
    }

    // 0.7 of the old value plus 0.3 of the new one.
    @Test func smoothsFrameLevels() {
        var levels = Levels()
        let shown = [levels.push(-10, nowMs: 0), levels.push(-10, nowMs: 100), levels.push(-60, nowMs: 200)]
        for (value, expected) in zip(shown, [Float(0.3), 0.51, 0.357]) {
            #expect(abs((value ?? -1) - expected) < 1e-6)
        }
    }

    // 48 frames a second for 10 s: at most 30 shown in any second.
    @Test func showsAtMost30ASecond() {
        var levels = Levels()
        let shownAt = (0..<480).map { $0 * 1000 / 48 }.filter { levels.push(-30, nowMs: $0) != nil }
        for i in 0..<(shownAt.count - 30) { #expect(shownAt[i + 30] - shownAt[i] >= 1000) }
        #expect(shownAt.count >= 200)
    }
}
