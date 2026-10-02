import SwiftUI

/// The app's tabs; a link can pick one (the keyboard's "get the model" opens the Home tab).
enum RootTab: Hashable { case home, history, dictionary, settings }

struct RootView: View {
    let host: SessionHost
    let models: SpeechModels
    let dictionary: DictionaryStore
    let settings: AppSettings
    @Binding var tab: RootTab
    /// Settings' Show the welcome screens.
    let onWelcome: () -> Void

    var body: some View {
        TabView(selection: $tab) {
            Tab("Home", systemImage: "house", value: RootTab.home) {
                HomeView(host: host, models: models)
            }
            Tab("History", systemImage: "clock", value: RootTab.history) { HistoryView(host: host) { tab = .home } }
            Tab("Dictionary", systemImage: "book.closed", value: RootTab.dictionary) {
                NavigationStack { DictionaryView(dictionary: dictionary, language: host.language) }
            }
            Tab("Settings", systemImage: "gearshape", value: RootTab.settings) {
                SettingsView(host: host, models: models, settings: settings, dictionary: dictionary, onWelcome: onWelcome)
            }
        }
        .tint(Theme.primary)
    }
}
