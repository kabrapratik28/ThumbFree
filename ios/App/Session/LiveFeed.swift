import Foundation
import TFCore
import TFEngine

/// One take's live transcriber, fed from the main actor in order. The take's blocks, the stop
/// and the stop tail's checks all go through one stream, so the transcriber hears exactly the WAV's samples and the stop
/// lands between the same two blocks as in the WAV. `onTailEnd` runs once, on the main actor, when the tail has ended.
/// ponytail: the WAV can get a block past the tail's end (one that arrived while the answer came back from the actor);
/// the transcriber ignores it. Stop the WAV at the transcriber's own sample count if Transcribe again needs the exact end.
@MainActor final class LiveFeed {
    private enum Input { case audio([Float]), stop(Int), check(Int) }

    let transcriber: ChunkedTranscriber
    private let inputs: AsyncStream<Input>.Continuation
    private let fed: Task<Void, Never>
    private var cancelled = false

    init(_ transcriber: ChunkedTranscriber, onTailEnd: @escaping @MainActor () -> Void) {
        self.transcriber = transcriber
        let (stream, inputs) = AsyncStream.makeStream(of: Input.self)
        self.inputs = inputs
        fed = Task {
            var ended = false
            for await input in stream {
                guard !Task.isCancelled else { break }
                let end: StopTailPolicy.End?
                switch input {
                case .audio(let block):
                    await transcriber.append(block)
                    end = nil
                case .stop(let nowMs):
                    end = await transcriber.stop(nowMs: nowMs)
                case .check(let nowMs):
                    end = await transcriber.check(nowMs: nowMs)
                }
                if end != nil, !ended {
                    ended = true
                    onTailEnd()
                }
            }
        }
    }

    /// A host dropped in the middle of a take (tests) leaves no feed task waiting.
    deinit { inputs.finish() }

    func append(_ block: [Float]) { inputs.yield(.audio(block)) }

    /// The stop tap. The tail's end comes through `onTailEnd`, at once for a quiet end.
    func stop(nowMs: Int) { inputs.yield(.stop(nowMs)) }

    /// After each tail block and on the host's 50 ms timer.
    func check(nowMs: Int) { inputs.yield(.check(nowMs)) }

    /// The take's texts, once every input fed so far has reached the transcriber (`ChunkedTranscriber.finish`: a tail
    /// that has not ended ends where the audio stopped). Throws CancellationError after `cancel()`.
    func finish() async throws -> ChunkedTranscriber.Result {
        inputs.finish()
        await fed.value
        if cancelled { throw CancellationError() }
        return try await transcriber.finish()
    }

    /// Drops the take (the host's saveOutcome and discard): nothing more reaches the engine, and a pending `finish()`
    /// throws.
    /// ponytail: the actor hears the cancel one hop later, so a job that ends in that hop can start one more engine run,
    /// whose text is dropped. Check a flag in the engine closure if runs after a cancel ever show in the take log.
    func cancel() {
        cancelled = true
        inputs.finish()
        fed.cancel()
        let transcriber = transcriber
        Task { await transcriber.cancel() }
    }
}
