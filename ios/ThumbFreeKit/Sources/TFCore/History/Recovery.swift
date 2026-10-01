import Foundation

/// Startup recovery, ported from Android, with the Delivery table's "the app restarts" rows. Run once at app start,
/// before the first take, the first history read and the first command. Nothing is retried or typed after a restart.
public enum Recovery {
    /// Ends every take the last process left live:
    /// - recording, transcribing -> interrupted, WAV header repaired from the file length, duration set, text cleared
    ///   (nobody confirmed it was speech); with the audio missing or shorter than the WAV header -> failed(audioMissing);
    /// - staged -> notInserted, its outbox item left pending (nothing began);
    /// - inserting -> needsReview, its outbox item set to unverified (the text may already be in the field);
    ///   both keep their text (it was saved for exactly this), and note audioMissing in `error` when the audio is gone.
    /// Also deletes take folders that have no take.json. One bad take does not stop the others: the first error is
    /// thrown after the rest are done, and that take is left for the next start. Returns the takes it ended.
    @discardableResult
    public static func run(_ store: HistoryStore, shared: SharedStore) throws -> [TakeRecord] {
        let files = FileManager.default
        for id in ((try? files.contentsOfDirectory(atPath: store.root.path)) ?? []).compactMap(UUID.init(uuidString:))
        where !files.fileExists(atPath: store.folder(for: id).appendingPathComponent("take.json").path) {
            try? files.removeItem(at: store.folder(for: id))
        }
        var recovered: [TakeRecord] = []
        var firstError: (any Error)?
        for var take in try store.all() where take.status.isLive {
            do {
                let audio = store.audioURL(for: take.id)
                let size = (try? files.attributesOfItem(atPath: audio.path)[.size] as? Int) ?? 0
                let missing = size < WavWriter.headerBytes
                take.insertedText = nil
                take.error = missing ? TakeMessage.audioMissing.rawValue : nil
                switch take.status {
                case .staged, .inserting:
                    take.status = DeliveryTable.noAnswer(began: take.status == .inserting).status
                default:
                    take.partialText = nil
                    take.rawText = nil
                    take.text = nil
                    take.retranscribed = false
                    if missing {
                        take.status = .failed
                    } else {
                        take.status = .interrupted
                        take.durationMs = try WavWriter.repair(url: audio) * 1_000 / 16_000
                    }
                }
                try store.update(take)
                recovered.append(take)
            } catch {
                firstError = firstError ?? error
            }
        }
        do {
            let review = Set(recovered.filter { $0.status == .needsReview }.map(\.id))
            var outbox = try shared.outbox()
            for i in outbox.indices where review.contains(outbox[i].takeID) {
                outbox[i].state = DeliveryTable.noAnswer(began: true).outbox
            }
            if !review.isEmpty { try shared.write(outbox) }
        } catch {
            firstError = firstError ?? error
        }
        if let firstError { throw firstError }
        return recovered
    }
}
