import Foundation
import SwiftUI
import TFCore
import UIKit

/// What the ThumbFree keyboard shows in its status line. The app runs the take; the keyboard shows what the app reports.
/// The words stay short: the bar leaves them the width less the mic, about 287 points on a 375-point iPhone.
enum KeyState: Equatable {
    case needsFullAccess
    case needsModel
    case startDictation
    case opening
    case openFailed
    case starting
    case ready
    case listening
    case transcribing
    case gettingReady
    case message(String)

    var text: String {
        switch self {
        case .needsFullAccess: "Full Access is off. Turn it on to dictate."
        case .needsModel: "No speech model yet. Tap the mic to get it."
        case .startDictation: "Tap the mic. ThumbFree opens and listens."
        case .opening: "Opening ThumbFree"
        case .openFailed: "Open ThumbFree to start."
        case .starting: "Starting the microphone"
        case .ready: "Ready, mic on"
        case .listening: "Recording"
        case .transcribing: "Transcribing"
        case .gettingReady: "Getting ready, first time only"
        case .message(let text): text
        }
    }

    /// The mic key's VoiceOver label.
    var micLabel: String { self == .listening ? "Stop dictation" : "Start dictation" }
}

extension KeyState {
    /// The status line from the app's last status. `take`: the take this keyboard pressed for.
    static func from(_ status: HostStatus?, take: UUID?, opening: KeyboardClient.Opening, fullAccess: Bool, now: Date) -> KeyState {
        guard fullAccess else { return .needsFullAccess }
        // This keyboard's take, or any take the app reports as live: a tap acts on the live take (pressDown names it), so
        // the key shows it even in a keyboard made after the press (a cold take's app switch can recreate the keyboard).
        // Freshness is required even for this keyboard's own take: if ThumbFree died mid-take, its last status must not
        // keep showing recording or transcribing forever, or a stop would start a new take instead of ending the dead one.
        if let status, status.isFresh(now: now), status.takeID == take || status.liveTake(now: now) != nil {
            switch status.take {
            case .recording: return status.micOn ? .listening : (opening == .opening ? .opening : .starting)
            // The first load after a download (about 20 s on an iPhone 16): a take that lands before it finishes
            // must not sit on "Transcribing" as if something is stuck.
            case .stopping, .transcribing, .delivering:
                return status.engine == .loading || status.engine == .warming ? .gettingReady : .transcribing
            case .idle: break
            }
        }
        if opening == .failed { return .openFailed }
        if opening == .opening { return .opening }
        if let status, status.isFresh(now: now), let message = status.message { return .message(message) }
        if status?.engine == .noModel { return .needsModel }
        if let status, status.session == .ready, status.micOn, status.isFresh(now: now), (status.expiresAt ?? .distantFuture) > now {
            return .ready
        }
        return .startDictation
    }
}

extension HostStatus {
    /// The app rewrites its status about once a second while a take or its session is live: an older one than 5 s is
    /// from an app that went away.
    func isFresh(now: Date) -> Bool { now.timeIntervalSince(updatedAt) < 5 }

    /// The take the app reports as live, from a fresh status.
    func liveTake(now: Date) -> UUID? {
        take != .idle && isFresh(now: now) ? takeID : nil
    }
}

extension KeyState {
    /// The status line's red while a take records: the app's red for words (`Theme.error`), readable at 4.5:1 on the
    /// keyboard in light and in dark.
    static let red = Color(uiColor: UIColor {
        $0.userInterfaceStyle == .dark ? UIColor(red: 1, green: 180 / 255, blue: 171 / 255, alpha: 1)
            : UIColor(red: 186 / 255, green: 26 / 255, blue: 26 / 255, alpha: 1)
    })

    /// The take's time in minutes and seconds: "0:07", "12:03".
    static func clock(_ seconds: Int) -> String { String(format: "%d:%02d", seconds / 60, seconds % 60) }

    /// The status line while a take records, longest first, for the bar to show the longest that fits: "Recording 0:07
    /// Speak now", then "Recording 0:07", then "Recording". Recording and the time in `red`, Speak now in the line's color.
    static func recordingLines(seconds: Int) -> [AttributedString] {
        var recording = AttributedString(listening.text), time = AttributedString(" " + clock(seconds))
        recording.swiftUI.foregroundColor = red
        time.swiftUI.foregroundColor = red
        return [recording + time + AttributedString("  Speak now"), recording + time, recording]
    }

    /// What VoiceOver reads for that line, whole: "Recording, 7 seconds. Speak now."
    static func recordingLabel(seconds: Int) -> String {
        let english = Duration.UnitsFormatStyle(allowedUnits: [.minutes, .seconds], width: .wide).locale(Locale(identifier: "en_US"))
        return "Recording, \(Duration.seconds(seconds).formatted(english)). Speak now."
    }
}
