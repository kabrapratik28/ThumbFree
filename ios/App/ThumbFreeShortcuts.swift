import AppIntents

/// Start ThumbFree in Shortcuts, Spotlight and Siri, and as an Action Button choice.
struct ThumbFreeShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: StartSessionIntent(), phrases: ["Start \(.applicationName)", "Dictate with \(.applicationName)"],
                    shortTitle: "Start ThumbFree", systemImageName: "mic.fill")
    }
}
