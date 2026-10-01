import Foundation
import Testing
@testable import TFCore

// The take invariants and the Delivery table over 10,000 seeded random event sequences: one take at a
// time, every take ends exactly once, no delivery after a cancel, audio deleted only for silent short takes, a
// replayed or late keyboard command or a tap naming an ended take changes nothing, and the machine always gets back
// to idle.
@Suite struct TakePropertyTests {
    static let old = UUID(uuidString: "00000000-0000-0000-0000-0000000000FF") ?? UUID()

    @Test func randomSequencesKeepTheInvariants() {
        var visited = Set<String>()
        var failed = false
        var deliveringSeeds = 0 // seeds whose machine reached delivering
        for seed in 0..<10_000 where !failed {
            var rng = SplitMix64(seed: UInt64(seed))
            var machine = TakeReducer()
            var events: [TakeEvent] = []
            var fresh = 0
            var clock = 0
            var lastTouch: Int?
            var capturing = Set<UUID>() // takes whose capture is open
            var created = Set<UUID>()
            var ended: [UUID: Int] = [:] // saveOutcome and discard per take
            var cancelled = Set<UUID>()
            var recorded = Set<UUID>() // takes that reached recording
            var inOutbox = Set<UUID>()
            var delivered = false

            func check(_ ok: Bool, _ what: @autoclosure () -> String) { // the message is only built for a failure
                guard !ok, !failed else { return }
                failed = true // the first broken seed is enough to debug
                Issue.record("seed \(seed): \(what())\nevents: \(events)")
            }

            func run(_ event: TakeEvent, noOp: Bool) {
                events.append(event)
                let before = machine.state
                let (next, effects) = machine.reduce(event)
                if noOp { check(next == machine && effects.isEmpty, "\(event) should change nothing in \(before)") }
                // The tail's capture closes when tailDone takes the machine out of stopping.
                if case .stopping(let id, _) = before, case .tailDone = event, next.state != before { capturing.remove(id) }
                for effect in effects {
                    switch effect {
                    case .createTake(let id):
                        check(before == .idle, "createTake in \(before)")
                        created.insert(id)
                    case .startTakeCapture(let id):
                        check(capturing.isEmpty, "a second capture while \(capturing) records")
                        capturing.insert(id)
                    case .transcribeChunk(let id, _), .finish(let id):
                        check(!cancelled.contains(id), "\(effect) after a cancel")
                    case .writeOutbox(let id, _, _):
                        check(!cancelled.contains(id), "\(effect) after a cancel")
                        inOutbox.insert(id)
                    case .markInserting(let id), .setOutboxState(let id, _):
                        check(inOutbox.contains(id), "\(effect) before the take reached the outbox")
                    case .saveOutcome(let id, let status, _):
                        check(!status.isLive, "saveOutcome with live status \(status)")
                        if case .cancel = event, status == .cancelled { cancelled.insert(id) }
                        ended[id, default: 0] += 1
                        capturing.remove(id)
                    case .discard(let id):
                        let shortSilent: Bool
                        switch (event, before) {
                        case (.tailDone(_, let samples, false), _):
                            shortSilent = samples < TakeReducer.shortTakeSamples
                        case (.transcriptReady(_, _, _, false), .transcribing(_, _, let samples)):
                            shortSilent = samples < TakeReducer.shortTakeSamples
                        default:
                            shortSilent = false
                        }
                        check(!recorded.contains(id) || shortSilent, "audio of \(id) deleted after it recorded")
                        ended[id, default: 0] += 1
                        capturing.remove(id)
                    default:
                        break
                    }
                }
                machine = next
                if case .delivering = next.state { delivered = true }
                if case .recording(let id, _, _) = next.state { recorded.insert(id) }
                visited.insert(String(describing: next.state).prefix { $0 != "(" }.description)
                if next.state == .idle {
                    check(ended == Dictionary(uniqueKeysWithValues: created.map { ($0, 1) }), "takes \(created) ended \(ended)")
                    check(capturing.isEmpty, "capture of \(capturing) left open")
                }
            }

            for _ in 0..<60 {
                clock += Int.random(in: 0...1_000, using: &rng)
                let live = machine.state.takeID
                let id = Int.random(in: 0..<4, using: &rng) > 0 ? (live ?? Self.old) : Self.old
                // Now and then a touch file arrives late, sent before the last touch handled.
                let touchAt = Int.random(in: 0..<8, using: &rng) == 0 ? clock - Int.random(in: 0...3_000, using: &rng) : clock
                // A third of the steps move the live take on toward delivering (a press starts or stops it), so every
                // phase meets the random events.
                let forward = switch machine.state {
                case .idle, .recording: 0
                case .arming: 4
                case .stopping: 8
                case .transcribing: 10
                case .delivering: 12
                }
                let guided = Int.random(in: 0..<3, using: &rng) == 0
                let kind = guided ? forward : Int.random(in: 0..<15, using: &rng)
                let event: TakeEvent
                switch kind {
                case 0:
                    // Now and then a tap still names a take that ended (its keyboard has not read the new status yet).
                    let endedIDs = ended.keys.sorted { $0.uuidString < $1.uuidString } // a fixed order, so seeds repeat
                    if let reused = endedIDs.randomElement(using: &rng), Int.random(in: 0..<4, using: &rng) == 0 {
                        event = .press(reused, atMs: touchAt)
                    } else {
                        fresh += 1
                        event = .press(UUID(uuidString: String(format: "00000000-0000-0000-0000-%012d", fresh)) ?? UUID(), atMs: touchAt)
                    }
                case 1: event = .sessionEnded(Bool.random(using: &rng) ? .call : nil)
                case 2: event = .release(id, atMs: touchAt)
                case 3: event = .cancel(id)
                case 4: event = .firstAudio(id)
                case 5: event = .captureFailed(id, .micUnavailable)
                case 6: event = .tick(id, recordedMs: Int.random(in: 0...1_000_000, using: &rng),
                                      msSinceSpeech: Int.random(in: 0...200_000, using: &rng))
                case 7: event = .chunkClosed(id, Chunk(start: 0, end: 480, mayHoldSpeech: true))
                case 8: event = .tailDone(id, samples: Int.random(in: 0...100_000, using: &rng), mayHoldSpeech: guided || Bool.random(using: &rng))
                case 9: event = .chunkTextReady(id, partial: Transcript("so"), chunksDone: 1)
                case 10 where guided: event = .transcriptReady(id, Transcript("hi"), saved: true, speech: true)
                case 10:
                    let speech = Bool.random(using: &rng) // a take Silero heard nothing in has no text
                    event = .transcriptReady(id, Transcript(speech ? ["", "hi"].randomElement(using: &rng) ?? "" : ""),
                                             saved: Bool.random(using: &rng), speech: speech)
                case 11: event = .transcriptFailed(id, .engineFailed)
                case 12, 13: event = .insertion(id, [Insertion.began, .confirmed, .unverified, .heldBack].randomElement(using: &rng) ?? .began)
                default: event = .deliveryTimedOut(id)
                }
                var stale = kind >= 2 && id != live // kinds from 2 on name a take
                if case .press(let named, _) = event, ended[named] != nil { stale = true } // a take that already ended
                var late = false
                if case .press(_, let at) = event, let last = lastTouch, at <= last { late = true }
                if case .release(_, let at) = event, !stale, let last = lastTouch, at <= last { late = true }
                run(event, noOp: stale || late)
                switch event {
                case .press(_, let at) where !stale && !late, .release(_, let at) where !stale && !late: lastTouch = at
                default: break
                }
                // A crash before the app removes a command file replays it: the second time changes nothing.
                let fromKeyboard: Bool
                switch event {
                case .press, .release, .cancel, .insertion: fromKeyboard = true
                default: fromKeyboard = false
                }
                if fromKeyboard, Int.random(in: 0..<4, using: &rng) == 0 { run(event, noOp: true) }
            }
            // Always back to idle: cancel ends any take before delivery; a keyboard's report ends delivery.
            if let id = machine.state.takeID { run(.cancel(id), noOp: false) }
            if let id = machine.state.takeID { run(.insertion(id, .confirmed), noOp: false) }
            check(machine.state == .idle, "stuck in \(machine.state)")
            if delivered { deliveringSeeds += 1 }
        }
        // Every state is reached, so the invariants are not checked on a machine that never leaves idle.
        #expect(visited == ["idle", "arming", "recording", "stopping", "transcribing", "delivering"])
        // Enough seeds reach delivering that its rules meet the random events many times, not by chance.
        #expect(deliveringSeeds >= 9_000, "only \(deliveringSeeds) of 10,000 seeds reached delivering") // 9,272 now
    }
}
