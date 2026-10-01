import CoreML

/// Greedy TDT decoding of one Encoder window with the Decoder and JointDecision models, standard (NeMo) greedy
/// semantics: a fresh zero state primed with blank; a blank advances by its duration (at least 1) and leaves the
/// decoder alone; a token is kept, updates the decoder and advances by its duration; after 10 tokens at one frame
/// the next frame is forced; decoding stops at the last valid frame.
///
/// Our own code. FluidAudio's TdtDecoderV3 (Apache 2.0, github.com/FluidInference/FluidAudio,
/// Sources/FluidAudio/ASR/Parakeet/SlidingWindow/TDT/Decoder/TdtDecoderV3.swift) was the reference for the model
/// calls, with three deliberate differences. FluidAudio (a) drops a token whose duration step reaches the end of
/// the audio and then flushes the last frames, where we keep that token because losing a final period is bad for
/// dictation; (b) forces a one-frame advance on a second token landing at the same frame (TdtDecoderV3.swift:
/// 310-314), where we follow standard greedy TDT and allow up to 10 symbols per frame; and (c) caps the number of
/// tokens decoded per chunk (TdtDecoderV3.swift, around line 413) against runaway decoding, which we do not need
/// because our loop is already bounded by the valid frame count and the per-frame cap.
/// Arrays are allocated once. The Decoder writes straight into the Joint's input (output backings).
final class TdtDecoder {
    static let maxTokensPerFrame = 10

    private let decoder: MLModel
    private let joint: MLModel
    private let blankID: Int
    private let target: MLMultiArray                       // [1, 1] Int32
    private let hIn: MLMultiArray, cIn: MLMultiArray        // [2, 1, 640] Float32
    private let hOut: MLMultiArray, cOut: MLMultiArray
    private let decoderInput: MLFeatureProvider
    private let decoderOptions = MLPredictionOptions()
    private let encoderStep: MLMultiArray                  // [1, 1024, 1]
    private let decoderStep: MLMultiArray                  // [1, 640, 1], written by the Decoder
    private let tokenID: MLMultiArray, tokenProb: MLMultiArray, duration: MLMultiArray  // [1, 1, 1]
    private let jointInput: MLFeatureProvider
    private let jointOptions = MLPredictionOptions()

    init(decoder: MLModel, joint: MLModel, blankID: Int) throws {
        self.decoder = decoder
        self.joint = joint
        self.blankID = blankID
        target = try MLMultiArray(shape: [1, 1], dataType: .int32)
        let targetLength = try MLMultiArray(shape: [1], dataType: .int32)
        targetLength[0] = 1
        hIn = try MLMultiArray(shape: [2, 1, 640], dataType: .float32)
        cIn = try MLMultiArray(shape: [2, 1, 640], dataType: .float32)
        hOut = try MLMultiArray(shape: [2, 1, 640], dataType: .float32)
        cOut = try MLMultiArray(shape: [2, 1, 640], dataType: .float32)
        decoderInput = try MLDictionaryFeatureProvider(dictionary: [
            "targets": target, "target_length": targetLength, "h_in": hIn, "c_in": cIn,
        ])
        encoderStep = try MLMultiArray(shape: [1, 1024, 1], dataType: .float32)
        decoderStep = try MLMultiArray(shape: [1, 640, 1], dataType: .float32)
        tokenID = try MLMultiArray(shape: [1, 1, 1], dataType: .int32)
        tokenProb = try MLMultiArray(shape: [1, 1, 1], dataType: .float32)
        duration = try MLMultiArray(shape: [1, 1, 1], dataType: .int32)
        jointInput = try MLDictionaryFeatureProvider(dictionary: ["encoder_step": encoderStep, "decoder_step": decoderStep])
        decoderOptions.outputBackings = ["decoder": decoderStep, "h_out": hOut, "c_out": cOut]
        jointOptions.outputBackings = ["token_id": tokenID, "token_prob": tokenProb, "duration": duration]
    }

    /// Token ids for `frames` (Encoder output, [1, 1024, T] Float32, any strides); the first `valid` frames hold audio.
    func decode(frames: MLMultiArray, valid: Int) throws -> [Int] {
        guard valid > 0 else { return [] }
        let source = frames.dataPointer.assumingMemoryBound(to: Float.self)
        let hiddenStride = frames.strides[1].intValue, timeStride = frames.strides[2].intValue
        let step = encoderStep.dataPointer.assumingMemoryBound(to: Float.self)
        let tokenOut = tokenID.dataPointer.assumingMemoryBound(to: Int32.self)
        let durationOut = duration.dataPointer.assumingMemoryBound(to: Int32.self)
        hIn.dataPointer.assumingMemoryBound(to: Float.self).update(repeating: 0, count: hIn.count)
        cIn.dataPointer.assumingMemoryBound(to: Float.self).update(repeating: 0, count: cIn.count)
        try runDecoder(blankID)

        var tokens: [Int] = []
        var t = 0, tokensAtFrame = 0
        while t < valid {
            for h in 0..<1024 { step[h] = source[h * hiddenStride + t * timeStride] }
            _ = try joint.prediction(from: jointInput, options: jointOptions)
            let label = Int(tokenOut[0]), jump = Int(durationOut[0])
            if label == blankID {
                t += max(1, jump)
                tokensAtFrame = 0
                continue
            }
            tokens.append(label)
            try runDecoder(label)
            tokensAtFrame += 1
            if jump > 0 || tokensAtFrame == Self.maxTokensPerFrame {
                t += max(1, jump)
                tokensAtFrame = 0
            }
        }
        return tokens
    }

    /// Feeds one token to the Decoder: its output lands in the Joint's `decoder_step`, its state in hIn/cIn.
    private func runDecoder(_ token: Int) throws {
        target[0] = NSNumber(value: token)
        _ = try decoder.prediction(from: decoderInput, options: decoderOptions)
        hIn.dataPointer.assumingMemoryBound(to: Float.self)
            .update(from: hOut.dataPointer.assumingMemoryBound(to: Float.self), count: hIn.count)
        cIn.dataPointer.assumingMemoryBound(to: Float.self)
            .update(from: cOut.dataPointer.assumingMemoryBound(to: Float.self), count: cIn.count)
    }
}
