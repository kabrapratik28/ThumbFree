/// Why a take ended, or a warning while it records. The raw value is what history stores in `TakeRecord.error`;
/// `text` is what the user sees (HostStatus.message). Plain English, from the Android app's strings.
public enum TakeMessage: String, Codable, Sendable, CaseIterable {
    case micPermission, micUnavailable, micNotReady, micSilent, deviceLost, captureStalled, captureOverflow
    case storageFull, historyWriteFailed, noModel, loadFailed, noMemory, engineFailed
    case noSpeech, takeLimit, takeEndsSoon, lockedSilence, call, audioMissing

    public var text: String {
        switch self {
        case .micPermission: "Microphone permission is off."
        case .micUnavailable: "Another app is using the microphone."
        case .micNotReady: "Microphone was not ready. Try again."
        case .micSilent: "Microphone is silent. Is it blocked or turned off?"
        case .deviceLost: "The microphone disconnected."
        case .captureStalled: "The microphone stopped sending audio."
        case .captureOverflow: "Recording fell behind and stopped. Your audio so far is saved."
        case .storageFull: "Phone storage is full. Your recording so far is saved."
        case .historyWriteFailed: "Could not save to history."
        case .noModel: "No speech model yet."
        case .loadFailed: "Could not load the model."
        case .noMemory: "Not enough free memory. Close some apps and try again."
        case .engineFailed: "Transcription stopped. Your audio is saved."
        case .noSpeech: "No speech heard."
        case .takeLimit: "Stopped at 15 minutes."
        case .takeEndsSoon: "Recording stops in 1 minute."
        case .lockedSilence: "Stopped after 2 minutes without speech."
        case .call: "Stopped for a call."
        case .audioMissing: "The audio file is missing."
        }
    }
}
