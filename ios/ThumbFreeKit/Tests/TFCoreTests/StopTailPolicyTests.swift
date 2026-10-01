import Testing
@testable import TFCore

// Frames are 480 samples (30 ms). Loud frames are speech at -20 dBFS; quiet frames are non-speech at -60 dBFS.
@Suite struct StopTailPolicyTests {
    let loud = FrameVerdict(isSpeech: true, levelDBFS: -20)
    let quiet = FrameVerdict(isSpeech: false, levelDBFS: -60)
    let noisy = FrameVerdict(isSpeech: false, levelDBFS: -50) // no gate speech, but above -55 dBFS: sound

    func push(_ policy: inout StopTailPolicy, _ verdict: FrameVerdict, _ count: Int) {
        for _ in 0..<count { policy.push(verdict) }
    }

    // The user had already paused: the last 500 ms had no sound, so no tail and no fill.
    @Test func aQuietEndHasNoTail() {
        var policy = StopTailPolicy()
        push(&policy, loud, 50)
        push(&policy, quiet, 17) // 510 ms
        #expect(policy.stop(nowMs: 0, received: 67 * 480) == .quiet)
        #expect(policy.zeroFill(endSample: 67 * 480) == 0)
    }

    // 480 ms of quiet is not enough.
    @Test func sound480msBeforeTheStopNeedsATail() {
        var policy = StopTailPolicy()
        push(&policy, loud, 50)
        push(&policy, quiet, 16)
        #expect(policy.stop(nowMs: 0, received: 66 * 480) == nil)
        #expect(policy.end == nil)
    }

    // More than 800 samples not yet judged: what is still unjudged may hold sound, so the tail runs.
    @Test func unjudgedAudioKeepsTheTail() {
        var policy = StopTailPolicy()
        push(&policy, quiet, 20)
        #expect(policy.stop(nowMs: 0, received: 20 * 480 + 801) == nil)
    }

    // The tail ends once 100 ms after the stop and 100 ms after the last sound are judged. Zeros then fill the engine's
    // copy to 5,760 samples past the stop, the length the 350 ms cap would have recorded.
    @Test func theHangoverEndsTheTailAndZerosFillIt() {
        var policy = StopTailPolicy()
        push(&policy, loud, 50)
        let stopSample = 50 * 480
        #expect(policy.stop(nowMs: 1_000, received: stopSample) == nil)
        push(&policy, loud, 1) // sound right after the stop: judged up to 50 * 480 + 480
        push(&policy, quiet, 3)
        #expect(policy.check(nowMs: 1_120, received: 54 * 480) == nil) // 1,440 samples since the last sound
        push(&policy, quiet, 1)
        #expect(policy.check(nowMs: 1_150, received: 55 * 480) == .hangover)
        #expect(policy.soundAfterStop)
        #expect(policy.zeroFill(endSample: 55 * 480) == stopSample + 5_760 - 55 * 480)
    }

    // Sound that never stops: the tail ends at 350 ms on the clock, and nothing is filled.
    @Test func theCapEndsATailThatKeepsSounding() {
        var policy = StopTailPolicy()
        push(&policy, loud, 50)
        #expect(policy.stop(nowMs: 1_000, received: 50 * 480) == nil)
        push(&policy, noisy, 11)
        #expect(policy.check(nowMs: 1_349, received: 61 * 480) == nil)
        #expect(policy.check(nowMs: 1_350, received: 61 * 480) == .cap)
        #expect(policy.zeroFill(endSample: 61 * 480) == 0)
    }

    // A quiet tail after a loud end: the engine's text from before the stop can stand (optimistic stop).
    @Test func aQuietTailHeldNoSound() {
        var policy = StopTailPolicy()
        push(&policy, loud, 50)
        _ = policy.stop(nowMs: 0, received: 50 * 480)
        push(&policy, quiet, 4)
        #expect(policy.check(nowMs: 120, received: 54 * 480) == .hangover)
        #expect(!policy.soundAfterStop)
    }

    @Test func theEndIsKeptAndAStopCountsOnce() {
        var policy = StopTailPolicy()
        #expect(policy.check(nowMs: 0, received: 0) == nil) // no stop yet
        push(&policy, quiet, 20)
        #expect(policy.stop(nowMs: 0, received: 9_600) == .quiet)
        #expect(policy.stop(nowMs: 500, received: 20_000) == .quiet)
        #expect(policy.stopSample == 9_600)
        #expect(policy.check(nowMs: 900, received: 20_000) == .quiet)
    }

    // The planner's longest chunk plus the fill still fits the Encoder's 240,000 samples.
    @Test func theFillFitsTheWindow() {
        #expect(ChunkConfig.window15s.maxSamples + StopTailPolicy.fillSamples <= 240_000)
    }
}
