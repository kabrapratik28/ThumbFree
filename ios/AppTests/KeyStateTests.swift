import Foundation
import Testing
import TFCore
import UIKit
@testable import ThumbFree

@Suite struct KeyStateTests {
    @Test func theStatusLineSpeaksPlainEnglish() {
        let states: [KeyState] = [.needsFullAccess, .needsModel, .startDictation, .opening, .openFailed, .starting, .ready,
                                  .listening, .transcribing, .gettingReady, .message("No speech heard.")]
        #expect(states.map(\.text) == [
            "Full Access is off. Turn it on to dictate.",
            "Speech isn't ready. Tap the mic to open ThumbFree.",
            "Tap the mic. ThumbFree opens and listens.",
            "Opening ThumbFree",
            "Open ThumbFree to start.",
            "Starting the microphone",
            "Ready, mic on",
            "Recording",
            "Transcribing",
            "Getting ready, first time only",
            "No speech heard.",
        ])
        for state in states { #expect(!state.text.contains("\u{2014}") && !state.text.contains("\u{2013}")) }
    }

    // The keyboard's own words fit the bar: at most two lines in the width it leaves them on a 375-point iPhone (about
    // 287 points), at the largest text size the bar allows (.xxLarge).
    @Test func theKeyboardsOwnWordsFitTheBar() {
        let font = UIFont.preferredFont(forTextStyle: .footnote, compatibleWith: UITraitCollection(preferredContentSizeCategory: .extraExtraLarge))
        let states: [KeyState] = [.needsFullAccess, .needsModel, .startDictation, .opening, .openFailed, .starting, .ready,
                                  .listening, .transcribing, .gettingReady]
        func height(_ text: String) -> CGFloat {
            (text as NSString).boundingRect(with: CGSize(width: 287, height: CGFloat.greatestFiniteMagnitude),
                                            options: .usesLineFragmentOrigin, attributes: [.font: font], context: nil).height
        }
        let line = height("A")
        for state in states { #expect(height(state.text) < line * 2.5, "\(state.text)") }
        #expect(height(String(repeating: "Speech isn't ready. ", count: 6)) > line * 2.5) // the measure sees a third line
    }

    // The app says it has no model: the keyboard says so instead of offering a take that could only fail. A live take
    // still shows as usual.
    @Test func noModelIsShownUntilATakeIsLive() {
        let now = Date()
        #expect(KeyState.from(HostStatus(engine: .noModel), take: nil, opening: .no, fullAccess: true, now: now) == .needsModel)
        #expect(KeyState.from(HostStatus(engine: .noModel), take: nil, opening: .no, fullAccess: false, now: now) == .needsFullAccess)
        let take = UUID()
        #expect(KeyState.from(HostStatus(session: .ready, engine: .noModel, micOn: true, takeID: take, take: .recording, updatedAt: now),
                              take: take, opening: .no, fullAccess: true, now: now) == .listening)
        #expect(KeyState.from(HostStatus(engine: .unloaded), take: nil, opening: .no, fullAccess: true, now: now) == .startDictation)
    }

    // "Ready, mic on" only from a fresh status: the app rewrites it about once a second while its session is on, so an
    // older one is from an app that went away, and the keyboard shows its idle words instead.
    @Test func readyIsShownOnlyFromAFreshStatus() {
        let now = Date()
        let fresh = HostStatus(session: .ready, micOn: true, expiresAt: now + 200, updatedAt: now - 1)
        #expect(KeyState.from(fresh, take: nil, opening: .no, fullAccess: true, now: now) == .ready)
        let stale = HostStatus(session: .ready, micOn: true, expiresAt: now + 200, updatedAt: now - 6)
        #expect(KeyState.from(stale, take: nil, opening: .no, fullAccess: true, now: now) == .startDictation)
    }

    // The own-take branch needs freshness too: if ThumbFree dies mid-take, its last status must not keep showing
    // recording or transcribing forever, or a stop would start a new take instead of ending the dead one.
    @Test func anOwnTakeStatusOverFiveSecondsOldGivesStartDictation() {
        let now = Date(), take = UUID()
        let recording = HostStatus(session: .ready, micOn: true, takeID: take, take: .recording, updatedAt: now - 6)
        #expect(KeyState.from(recording, take: take, opening: .no, fullAccess: true, now: now) == .startDictation)
        let transcribing = HostStatus(takeID: take, take: .transcribing, updatedAt: now - 6)
        #expect(KeyState.from(transcribing, take: take, opening: .no, fullAccess: true, now: now) == .startDictation)
    }

    // The first load after a download can take a while (about 20 s on an iPhone 16): a take that lands before it
    // finishes must not sit on "Transcribing" as if something is stuck.
    @Test func gettingReadyReplacesTranscribingWhileTheEngineLoads() {
        let now = Date()
        #expect(KeyState.from(HostStatus(engine: .loading, take: .transcribing, updatedAt: now),
                              take: nil, opening: .no, fullAccess: true, now: now) == .gettingReady)
        #expect(KeyState.from(HostStatus(engine: .warming, take: .stopping, updatedAt: now),
                              take: nil, opening: .no, fullAccess: true, now: now) == .gettingReady)
        #expect(KeyState.from(HostStatus(engine: .warming, take: .delivering, updatedAt: now),
                              take: nil, opening: .no, fullAccess: true, now: now) == .gettingReady)
        #expect(KeyState.from(HostStatus(engine: .readyNeuralEngine, take: .transcribing, updatedAt: now),
                              take: nil, opening: .no, fullAccess: true, now: now) == .transcribing)
    }

    @Test func theMicSaysStopOnlyWhileListening() {
        #expect(KeyState.listening.micLabel == "Stop dictation")
        #expect(KeyState.ready.micLabel == "Start dictation")
        #expect(KeyState.startDictation.micLabel == "Start dictation")
    }

    // The take's time on the status line: minutes and seconds.
    @Test func theTakesTimeIsMinutesAndSeconds() {
        #expect([0, 7, 65, 723].map(KeyState.clock) == ["0:00", "0:07", "1:05", "12:03"])
    }

    // While a take records, the line says so, with the take's time and what to do; when the bar is short, Speak now goes
    // first, then the time. Recording and the time are in the red for words, Speak now in the status line's own color.
    // VoiceOver hears it whole.
    @Test func theRecordingLineSaysRecordingTheTimeAndSpeakNow() throws {
        let lines = KeyState.recordingLines(seconds: 7)
        #expect(lines.map { String($0.characters) } == ["Recording 0:07  Speak now", "Recording 0:07", "Recording"])
        let full = try #require(lines.first)
        #expect(full.runs.map { String(full[$0.range].characters) } == ["Recording 0:07", "  Speak now"])
        #expect(full.runs.map(\.swiftUI.foregroundColor) == [KeyState.red, nil])
        #expect(KeyState.recordingLabel(seconds: 7) == "Recording, 7 seconds. Speak now.")
        #expect(KeyState.recordingLabel(seconds: 1) == "Recording, 1 second. Speak now.")
        #expect(KeyState.recordingLabel(seconds: 65) == "Recording, 1 minute, 5 seconds. Speak now.")
    }
}
