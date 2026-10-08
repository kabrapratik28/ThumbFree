import SwiftUI
import TFCore

/// Settings' Clean up section: whether Apple Intelligence can run it on this iPhone (with the one fix when it is off),
/// "Show ✨ after you speak" and "Tap ✨ uses". The model's state is read again each time Settings comes back to the
/// front, so turning Apple Intelligence on in iOS's Settings shows here at once, and every 5 s while it shows.
struct CleanupSection: View {
    let settings: AppSettings
    @State private var model = CleanUp.modelAvailability()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Section {
            status
            if model == .ready || model == .notReady {
                Toggle(isOn: Binding(get: { settings.cleanupShown }, set: { settings.cleanupShown = $0 })) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Show \(Image(systemName: "sparkles")) after you speak")
                        Text("Tap \(Image(systemName: "sparkles")) to tidy what you just said. Hold it to pick a style.")
                            .font(.subheadline).foregroundStyle(Theme.inkSoft)
                    }
                }
                .accessibilityIdentifier("settings.cleanupShown")
                Picker(selection: Binding(get: { settings.cleanupStyle }, set: { settings.cleanupStyle = $0 })) {
                    ForEach(CleanupStyle.allCases, id: \.self) { style in
                        HStack(spacing: 8) {
                            Text(style.title)
                            if style == .clean { Text("Recommended").font(.subheadline).foregroundStyle(Theme.inkSoft) }
                        }
                        .tag(style)
                    }
                } label: {
                    Text("Tap \(Image(systemName: "sparkles")) uses")
                }
                .pickerStyle(.inline)
                .disabled(!settings.cleanupShown)
                .accessibilityIdentifier("settings.cleanupStyle")
            }
        } header: {
            Text("Clean up").foregroundStyle(Theme.heading)
        } footer: {
            Text("Tidies your words with Apple Intelligence on this iPhone. Your words never leave it.")
        }
        .listRowBackground(Theme.card)
        .onChange(of: scenePhase) { _, phase in if phase == .active { model = CleanUp.modelAvailability() } }
        // While the section shows too: Apple Intelligence may finish getting ready with Settings open.
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(5))
                model = CleanUp.modelAvailability()
            }
        }
    }

    /// The model's state, in a sentence, with Open Settings when Apple Intelligence is only switched off.
    @ViewBuilder private var status: some View {
        let words = Self.words(model)
        Label {
            VStack(alignment: .leading, spacing: 2) {
                Text(words.title)
                Text(words.detail).font(.subheadline).foregroundStyle(Theme.inkSoft)
            }
        } icon: {
            Image(systemName: model == .ready ? "checkmark.circle.fill" : "sparkles")
                .foregroundStyle(model == .ready ? Theme.success : Theme.inkSoft)
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("settings.cleanupStatus")
        if model == .appleIntelligenceOff {
            Button("Open Settings") { SetupRows.openSettings() }
                .fontWeight(.semibold)
                .foregroundStyle(Theme.primary)
                .accessibilityIdentifier("settings.cleanupOpenSettings")
        }
    }

    static func words(_ state: CleanupAvailability) -> (title: String, detail: String) {
        switch state {
        case .ready, .off:
            (String(localized: "Ready"), String(localized: "Uses Apple Intelligence on this iPhone."))
        case .appleIntelligenceOff:
            (String(localized: "Apple Intelligence is off"),
             String(localized: "Clean up uses Apple Intelligence. Turn it on in Settings, then come back."))
        case .notEligible:
            (String(localized: "Not on this iPhone"), String(localized: "Clean up needs an iPhone with Apple Intelligence."))
        case .notReady:
            (String(localized: "Getting ready"), String(localized: "Apple Intelligence is still getting ready on this iPhone."))
        case .unsupportedLanguage:
            (String(localized: "Not in your iPhone's language yet"),
             String(localized: "Apple Intelligence doesn't support your iPhone's language yet."))
        }
    }
}
