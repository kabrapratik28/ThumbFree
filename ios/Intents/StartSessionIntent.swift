import AppIntents

/// Start ThumbFree: turns the microphone on for a dictation session (the same session length as a keyboard take's), so
/// the ThumbFree keyboard's next tap starts at once, with no trip to the app. From Control Center, the Action Button or
/// Shortcuts. It first tries without opening ThumbFree; when the microphone does not come on (iOS did not let it start
/// from the background), ThumbFree opens and starts it there. With no speech model yet it opens the model's offer on
/// Home instead.
///
/// Apple's documentation says iOS stops an audio recording intent's recording unless the app starts a Live Activity.
/// This intent starts none, so iOS may stop the recording: Start ThumbFree then starts, stops within seconds, and
/// ThumbFree never opens.
struct StartSessionIntent: AudioRecordingIntent {
    static let title: LocalizedStringResource = "Start ThumbFree"
    static let description = IntentDescription("Turns on the microphone for dictation, so the ThumbFree keyboard starts listening at once.")
    static let supportedModes: IntentModes = [.background, .foreground(.dynamic)]
    /// Only the app can start its session: from iOS 27 on, iOS runs this intent in the app, never in the widget extension.
    @available(iOS 27.0, *)
    static var allowedExecutionTargets: IntentExecutionTargets { .main }

    /// Set by the app at launch: starts the session and says whether the microphone came on. Nil in the widget
    /// extension, which shows the control; iOS 26 may still run the intent there, and it then opens ThumbFree.
    @MainActor static var start: (() async -> Bool)?
    /// Set by the app at launch: while there is no speech model, the link to its offer on Home (the keyboard's no-model
    /// link); nil once there is one.
    @MainActor static var modelOffer: (() -> URL?)?

    @MainActor func perform() async throws -> some IntentResult {
        guard let start = Self.start else { // not in the app: open it rather than report a success that started nothing
            try await continueInForeground(nil, alwaysConfirm: false)
            return .result()
        }
        if let offer = Self.modelOffer?() { return .result(opensIntent: OpenURLIntent(offer)) }
        if await start() { return .result() }
        try await continueInForeground(nil, alwaysConfirm: false)
        _ = await start()
        return .result()
    }
}
