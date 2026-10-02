import SwiftUI
import TFCore
import UIKit

@main
struct ThumbFreeApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @State private var host: SessionHost
    @State private var models: SpeechModels
    @State private var dictionary: DictionaryStore
    @State private var settings: AppSettings
    @State private var tab = RootTab.home
    @State private var welcome: Bool
    @State private var showSession = false
    @State private var commands: DarwinObserver?
    @Environment(\.scenePhase) private var phase

    init() {
        let (host, models, dictionary, settings) = AppEnvironment.make()
        _host = State(initialValue: host)
        _models = State(initialValue: models)
        _dictionary = State(initialValue: dictionary)
        _settings = State(initialValue: settings)
        _welcome = State(initialValue: !UserDefaults.standard.bool(forKey: WelcomeView.doneKey)) // after -TFResetState
        if !UserDefaults.standard.bool(forKey: WelcomeView.doneKey) { WelcomeView.restoreStep(model: models.active) }
        // Keyboard commands are heard from launch on, not from the first scene: Start ThumbFree can launch the app in the
        // background with no scene, and the keyboard's next tap must reach that session. With no session and the app
        // not on screen, handleCommands() leaves a press on disk.
        _commands = State(initialValue: DarwinObserver(DarwinName.command) { host.handleCommands() })
    }

    var body: some Scene {
        WindowGroup {
            Group {
                if welcome {
                    WelcomeView(host: host, models: models) {
                        tab = .home
                        welcome = false
                    }
                } else {
                    RootView(host: host, models: models, dictionary: dictionary, settings: settings, tab: $tab) {
                        UserDefaults.standard.removeObject(forKey: WelcomeView.stepKey)
                        UserDefaults.standard.removeObject(forKey: WelcomeView.tripKey)
                        UserDefaults.standard.set(false, forKey: WelcomeView.doneKey)
                        welcome = true
                    }
                }
            }
                .fullScreenCover(isPresented: $showSession) {
                    SessionScreen(status: host.status, returnTrip: host.returnTrip) {
                        host.endSession()
                        showSession = false
                    }
                }
                .onOpenURL { url in
                    // The keyboard found no model, or a dictate link came with none: no take; show where to get it, Home's
                    // card, or during the welcome its own step, whose first one gets speech ready.
                    if DictateLink.isModel(url) || (DictateLink.take(from: url) != nil && !host.hasModel) {
                        tab = .home
                        return
                    }
                    guard let take = DictateLink.take(from: url) else { return }
                    // The screen is for the take the host started: the link's own, or a fresh one in place of a take
                    // from before this launch. A link for a take that already ended starts nothing. The link may also
                    // carry the trusted host for automatic return; the app opens it once audio is flowing.
                    showSession = host.openLink(take, host: DictateLink.host(from: url), autoReturn: AutoReturn.enabled) != nil
                }
                .onChange(of: phase, initial: true) { _, phase in
                    host.appActive = phase == .active
                    host.inBackground = phase == .background
                    if phase == .active {
                        host.handleCommands()
                        host.applyDayRetention() // a day limit counts days, not takes
                    }
                }
                .onChange(of: host.status.session) { _, session in
                    if session == .off { showSession = false }
                }
                .task {
                    #if DEBUG
                    // Testing on a phone that locks itself: `-TFKeepAwake YES` keeps the screen on while ThumbFree is open.
                    if UserDefaults.standard.bool(forKey: "TFKeepAwake") { UIApplication.shared.isIdleTimerDisabled = true }
                    #endif
                }
        }
    }
}
