import AVFoundation
import Foundation
import Testing
import TFCore
@testable import ThumbFree

/// The first try's rules: the line follows the person from the switch to the ThumbFree keyboard, to its mic, through the
/// live take, to words in the box; the keyboard's mark counts only when it is not the one there when the screen came up;
/// only a refused microphone, a keyboard not in iOS's list or speech not working yet stop the try; leaving cancels a take
/// still on its way; and Home shows one blocker at a time, in order.
@MainActor @Suite struct TryItTests {
    /// The stage with the mic on and the box empty before the take began recording, unless said otherwise.
    func stage(seen: Bool = true, take: TakePhase = .idle, micOn: Bool = true, atStart: Int = 0, now: Int = 0,
               box: String = "", before: String? = "") -> TryItView.Stage {
        TryItView.stage(keyboardSeen: seen, take: take, micOn: micOn, takesAtStart: atStart, takesNow: now, box: box,
                        boxAtRecording: before)
    }

    @Test func theStageFollowsTheKeyboardThenTheTake() {
        #expect(stage(seen: false) == .switchKeyboard)
        #expect(stage() == .tapMic)
        #expect(stage(take: .recording) == .recording)
        for take in [TakePhase.stopping, .transcribing, .delivering] { #expect(stage(take: take) == .transcribing, "\(take)") }
        #expect(stage(take: .delivering, now: 1) == .transcribing) // the text is saved, not yet in the box
        #expect(stage(now: 1, box: "Hello.") == .worked)
        #expect(stage(atStart: 3, now: 3, box: "Hello.") == .tapMic) // earlier takes do not count
        #expect(stage(atStart: 3, now: 4, box: "Hello.") == .worked)
    }

    // At the first take the take records before the mic is on, while iOS asks for the microphone: the line stays on the
    // mic, with its note that iOS will ask, until the mic is on.
    @Test func theLineSaysSpeakOnceTheMicIsOn() {
        #expect(stage(take: .recording, micOn: false) == .tapMic)
        #expect(TryItView.notes(stage(take: .recording, micOn: false), speechLoads: false, mic: .undetermined)
            == ["iOS will ask for microphone access."])
        #expect(stage(take: .recording) == .recording)
        #expect(stage(take: .transcribing, micOn: false) == .transcribing)
    }

    // Only words that landed in the box count: the box must change, to words, from what it held when the take began
    // recording here. A take that gave text without the keyboard up in the box, a take held back (the box keeps what it
    // had, empty or typed by hand), and a take whose recording began elsewhere (none seen here) are no try that worked.
    @Test func onlyWordsThatLandInTheBoxCount() {
        #expect(stage(seen: false, now: 1, box: "Hello.") == .switchKeyboard)
        #expect(stage(now: 1) == .tapMic)
        #expect(stage(now: 1, box: "typed by hand", before: "typed by hand") == .tapMic)
        #expect(stage(now: 1, box: "typed by hand", before: nil) == .tapMic)
        #expect(stage(now: 1, box: "typed by hand. Hello.", before: "typed by hand") == .worked)
        // A second take after one worked: the line follows it until its words land.
        #expect(stage(take: .recording, now: 1, box: "Hello.", before: "Hello.") == .recording)
        #expect(stage(now: 2, box: "Hello. Again.", before: "Hello.") == .worked)
    }

    // Under the line, its small notes: at the first take with the ThumbFree keyboard up, that iOS will ask for the
    // microphone; and while speech loads again (the box is up and a take waits for it), that speech is getting ready.
    @Test func theNotesUnderTheLineSayWhatComes() {
        #expect(TryItView.notes(.tapMic, speechLoads: false, mic: .undetermined) == ["iOS will ask for microphone access."])
        #expect(TryItView.notes(.tapMic, speechLoads: false, mic: .granted).isEmpty)
        #expect(TryItView.notes(.switchKeyboard, speechLoads: false, mic: .undetermined).isEmpty)
        #expect(TryItView.notes(.recording, speechLoads: false, mic: .undetermined).isEmpty)
        #expect(TryItView.notes(.switchKeyboard, speechLoads: true, mic: .granted) == ["Getting speech ready…"])
        #expect(TryItView.notes(.tapMic, speechLoads: true, mic: .undetermined) == ["iOS will ask for microphone access.",
                                                                                   "Getting speech ready…"])
    }

    // The line says one thing at a time, following the take; VoiceOver hears its own gesture for the globe.
    @Test func theLineSaysOneThingAtATime() {
        #expect(TryItView.Stage.allCases.map(TryItView.line) == [
            "Hold 🌐 at the bottom left, then choose ThumbFree.",
            "Tap the yellow mic.",
            "Speak, then tap the red stop button.",
            "Turning speech into text…",
            "Your words appeared. You’re ready.",
        ])
        #expect(TryItView.spoken(.switchKeyboard) == "Double-tap and hold the globe key at the bottom left, then choose ThumbFree.")
        #expect(TryItView.spoken(.tapMic) == "Tap the yellow mic.")
        #expect(TryItView.Stage.allCases.map(\.rawValue) == ["switchKeyboard", "tapMic", "recording", "transcribing", "worked"]) // -TFTryStage's words
    }

    // Only a mark the keyboard left after the screen came up says it is up in the box: one of the file's dates against
    // another, never against the clock.
    @Test func onlyANewMarkMeansTheKeyboardCameUp() {
        let before = Date(timeIntervalSince1970: 1_000), after = Date(timeIntervalSince1970: 1_001)
        #expect(!TryItView.keyboardAppeared(mark: nil, markAtStart: nil)) // never seen, or no App Group
        #expect(TryItView.keyboardAppeared(mark: after, markAtStart: nil)) // its first appearance, here
        #expect(!TryItView.keyboardAppeared(mark: before, markAtStart: before)) // seen elsewhere, before this screen
        #expect(TryItView.keyboardAppeared(mark: after, markAtStart: before))
    }

    // Leaving the screen cancels a take that has not given its text yet, never one the keyboard is already typing, and
    // never the dictate link's take, which the keyboard in another app started while the try was up.
    @Test func leavingCancelsOnlyATakeStillOnItsWay() {
        let take = UUID()
        for phase in [TakePhase.recording, .stopping, .transcribing] {
            #expect(TryItView.takeToCancel(HostStatus(takeID: take, take: phase), coldTake: nil) == take, "\(phase)")
            #expect(TryItView.takeToCancel(HostStatus(takeID: take, take: phase), coldTake: take) == nil, "\(phase)")
            #expect(TryItView.takeToCancel(HostStatus(takeID: take, take: phase), coldTake: UUID()) == take, "\(phase)")
        }
        #expect(TryItView.takeToCancel(HostStatus(takeID: take, take: .delivering), coldTake: nil) == nil)
        #expect(TryItView.takeToCancel(HostStatus(), coldTake: nil) == nil)
    }

    // Home's one blocker, a refused microphone first, then the keyboard, then speech; what only the try finishes (a keyboard
    // waiting for its first appearance, a microphone not asked yet, which has no card of its own) comes last, once speech
    // works. Nil is ready, which Try it follows until a take has given text.
    @Test func homeShowsOneBlockerInOrder() {
        func blocker(mic: AVAudioApplication.recordPermission = .granted, keyboard: KeyboardStatus = .ready,
                     phase: SpeechModel.Phase = .ready, waiting: SpeechModel.Waiting? = nil,
                     engine: EnginePhase = .readyCPU) -> SetupBlocker? {
            SetupBlocker.forHome(mic: mic, keyboard: keyboard, phase: phase, waiting: waiting, engine: engine)
        }
        #expect(blocker() == nil)
        #expect(blocker(engine: .readyNeuralEngine) == nil)
        #expect(blocker(mic: .undetermined, keyboard: .notAdded, phase: .missing) == .keyboardOff)
        #expect(blocker(mic: .denied, keyboard: .notAdded, phase: .missing) == .micOff)
        #expect(blocker(keyboard: .notAdded, phase: .missing) == .keyboardOff)
        #expect(blocker(keyboard: .added, phase: .missing) == .speechMissing)
        #expect(blocker(mic: .undetermined, phase: .missing) == .speechMissing)
        #expect(blocker(keyboard: .added, engine: .loading) == .loading)
        #expect(blocker(keyboard: .added) == .untried)
        #expect(blocker(mic: .undetermined) == .untried)
        #expect(SetupBlocker.forHome(mic: .granted, keyboard: .added, phase: .ready, waiting: nil, engine: .readyCPU)?
            .words(.home, model: "English", size: 465_000_000).action == .tryKeyboard)
    }

    // The try (the welcome's step 3 and Home's sheet) stops only for what makes a take impossible: a refused
    // microphone (iOS asks at the first take otherwise), a keyboard not in iOS's list (one in the list but not yet seen
    // comes up in the box), and speech not there yet: the download, or a load that failed. While the engine loads a model
    // that is here, the box is up and a take waits for the engine.
    @Test func onlyWhatMakesATakeImpossibleStopsTheTry() {
        func blocker(mic: AVAudioApplication.recordPermission = .granted, keyboard: KeyboardStatus = .ready,
                     phase: SpeechModel.Phase = .ready, waiting: SpeechModel.Waiting? = nil,
                     engine: EnginePhase = .readyCPU) -> SetupBlocker? {
            SetupBlocker.forTry(mic: mic, keyboard: keyboard, phase: phase, waiting: waiting, engine: engine)
        }
        #expect(blocker() == nil)
        #expect(blocker(mic: .undetermined) == nil)
        #expect(blocker(keyboard: .added) == nil)
        #expect(blocker(mic: .denied, keyboard: .notAdded, phase: .missing) == .micOff)
        #expect(blocker(keyboard: .notAdded, phase: .missing) == .keyboardOff)
        #expect(blocker(phase: .missing, engine: .noModel) == .speechMissing)
        #expect(blocker(phase: .downloading(done: 42, total: 100)) == .downloading(percent: 42))
        for engine in [EnginePhase.unloaded, .loading, .warming] { #expect(blocker(engine: engine) == nil, "\(engine)") }
        #expect(blocker(engine: .failed) == .loadFailed)
    }

    // Speech in its states, from the download's phase and why it waits, then the engine's load.
    @Test func speechSaysWhereTheDownloadIs() {
        func speech(_ phase: SpeechModel.Phase, waiting: SpeechModel.Waiting? = nil, engine: EnginePhase = .noModel) -> SetupBlocker? {
            SetupBlocker.speech(phase: phase, waiting: waiting, engine: engine)
        }
        #expect(speech(.missing) == .speechMissing)
        #expect(speech(.downloading(done: 42, total: 100)) == .downloading(percent: 42))
        #expect(speech(.downloading(done: 42, total: 100), waiting: .wifi) == .waitingForWifi)
        #expect(speech(.downloading(done: 42, total: 100), waiting: .connection) == .waitingForConnection)
        #expect(speech(.downloading(done: 100, total: 100)) == .checking)
        #expect(speech(.failed(.interrupted)) == .downloadPaused)
        #expect(speech(.failed(.noInternet)) == .downloadPaused)
        #expect(speech(.failed(.noSpace(needed: 2_000_000_000))) == .noSpace(needed: 2_000_000_000))
        #expect(speech(.failed(.checkFailed)) == .checkFailed)
        for engine in [EnginePhase.unloaded, .loading, .warming, .noModel] { #expect(speech(.ready, engine: engine) == .loading, "\(engine)") }
        #expect(speech(.ready, engine: .failed) == .loadFailed)
        #expect(speech(.ready, engine: .readyCPU) == nil)
    }

    // Each blocker in few words with its one fix; work under way has no button, and the download offers Change language
    // while it runs, waits or broke off.
    @Test func eachBlockerSaysItInFewWords() {
        let size: Int64 = 465_000_000
        func words(_ blocker: SetupBlocker, _ place: SetupBlocker.Place = .tryIt) -> SetupBlocker.Words {
            blocker.words(place, model: "English", size: size)
        }
        #expect(words(.downloading(percent: 42)) == SetupBlocker.Words(title: "Getting speech ready",
                                                                      detail: "It downloads once. You can leave ThumbFree.", action: nil))
        #expect(words(.downloading(percent: 42), .home).detail == "English · 42%")
        #expect(words(.waitingForWifi) == SetupBlocker.Words(title: "Waiting for Wi-Fi", detail: "English · about 465\u{00A0}MB",
                                                            action: .useMobileData))
        #expect(words(.waitingForConnection).detail == "Connect to Wi-Fi or mobile data. The download continues by itself.")
        #expect(words(.checking).title == "Checking the download")
        #expect(words(.loading) == SetupBlocker.Words(title: "Getting ThumbFree ready",
                                                     detail: "The first time takes about half a minute.", action: nil))
        #expect(words(.downloadPaused) == SetupBlocker.Words(title: "Speech download paused", detail: "Your progress is saved.",
                                                            action: .tryAgain))
        #expect(words(.noSpace(needed: 1_500_000_000)).detail == "Make about 1.5\u{00A0}GB of room, then try again.")
        #expect(words(.checkFailed) == SetupBlocker.Words(title: "Couldn’t check the download", detail: "Download it again.",
                                                         action: .downloadAgain))
        #expect(words(.loadFailed) == SetupBlocker.Words(title: "Couldn’t get speech ready", detail: "Your download is saved.",
                                                        action: .loadAgain))
        #expect(words(.micOff).detail == "Turn it on in Settings to try ThumbFree.")
        #expect(words(.micOff, .home).detail == "Turn it on in Settings.")
        #expect(words(.keyboardOff) == SetupBlocker.Words(title: "The keyboard is still off", detail: "Add ThumbFree in Settings.",
                                                         action: .openSettings))
        #expect(words(.speechMissing).detail == "Download speech before trying it.")
        #expect(words(.speechMissing, .home).detail == "English · about 465\u{00A0}MB")
        // The welcome's wait says the engine's load as almost ready; the download, the files' check and what stops them in
        // the try's words.
        #expect(words(.loading, .welcome) == SetupBlocker.Words(title: "Almost ready",
                                                               detail: "The first time takes about half a minute.", action: nil))
        #expect(words(.loading, .home) == SetupBlocker.Words(title: "Getting ThumbFree ready", detail: nil, action: nil))
        #expect(words(.checking, .welcome) == words(.checking))
        #expect(words(.downloading(percent: 42), .welcome) == words(.downloading(percent: 42)))
        #expect(words(.waitingForWifi, .welcome) == words(.waitingForWifi))
        #expect(words(.downloadPaused, .welcome) == words(.downloadPaused))
        #expect(words(.loadFailed, .welcome) == words(.loadFailed))
        #expect(words(.speechMissing, .welcome).detail == "English · about 465\u{00A0}MB")
        let changes = [SetupBlocker.downloading(percent: 1), .waitingForWifi, .waitingForConnection, .downloadPaused, .checking,
                       .loading, .noSpace(needed: 1), .checkFailed, .loadFailed, .micOff, .keyboardOff, .speechMissing]
            .filter(\.offersLanguageChange)
        #expect(changes == [.downloading(percent: 1), .waitingForWifi, .waitingForConnection, .downloadPaused])
        #expect(SetupBlocker.Action.allCases.map(\.title) == ["Open Settings", "Download", "Download again", "Try again",
                                                              "Use mobile data", "Try again", "Try it"])
    }
}
