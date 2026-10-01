import Foundation
import Testing
import TFCore
import TFEngine

/// What the app uses from ThumbFreeKit (docs/contract.md): it stops compiling when a signature the app relies on
/// drifts.
@Suite struct ContractTests {
    @Test func thePackageProvidesWhatTheAppUses() throws {
        let take = UUID()
        let (machine, effects) = TakeReducer().reduce(.press(take, atMs: 1))
        #expect(machine.state == .arming(take, lockOnReady: false))
        #expect(effects == [.createTake(take), .startTakeCapture(take)])
        let target = InsertTarget(documentID: UUID(), contextHash: InsertTarget.contextHash(before: "Hi", after: nil))
        #expect(KeyboardCommand(takeID: take, kind: .insertionBegan, target: target).event == .insertion(take, .began))
        #expect(HostStatus(session: .ready, engine: .readyCPU, micOn: true, takeID: take, take: .recording).takeID == take)
        #expect(OutboxItem(takeID: take, text: "Hi.", target: target).state == .pending)
        #expect(DeliveryTable.noAnswer(began: true).status == .needsReview)
        #expect(DeliveryTable.row(for: .heldBack).outbox == .heldBack)
        #expect(DeliveryTable.timeoutMs == 3_000)
        #expect(SpeechGate.frameSamples == 480)
        #expect(ChunkConfig.window15s.maxSamples + StopTailPolicy.fillSamples <= ParakeetEngine.maxSamples)
        #expect(ChunkPlanner.chunks(of: [Float](repeating: 0, count: 16_000), config: .window15s).count == 1)
        #expect(TextPipeline.run(chunkTexts: ["hello"], dictionary: [], language: "en").raw == "hello")
        #expect(CursorFormatter.payload(text: "hello world", before: "", after: "", capsExpected: true, field: .text,
                                        trailingSpace: false) == "Hello world")
        #expect(ModelCatalog.defaultID == ModelVariant.v2.rawValue)
        #expect(TakeMessage.noSpeech.text == "No speech heard.")
        #expect(Retention.defaultCount == 200)
        #expect(try HistoryStore(root: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)).record(take) == nil)
        let recover: (HistoryStore, SharedStore) throws -> [TakeRecord] = Recovery.run
        _ = recover
        _ = DevModels.directory(for: .v2)
        _ = DevModels.sileroDirectory()
    }

    // The app picks the Encoder's compute units.
    @Test func theEngineTakesItsEncoderUnits() async {
        let missing = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        await #expect(throws: (any Error).self) {
            _ = try await ParakeetEngine(modelDirectory: missing, variant: .v2, encoderUnits: .cpuOnly)
        }
    }
}
