import SwiftUI
import TFCore

/// The Settings tab, as on Android and adapted to iOS: Setup, Speech model, Session length (in place of the bubble's
/// settings), History, Dictionary and About. Each model's Download, Cancel and Delete sit in its own row here rather
/// than on a separate page: two models fit one list, and a pushed page would only hide them one tap deeper.
struct SettingsView: View {
    let host: SessionHost
    let models: SpeechModels
    let settings: AppSettings
    let dictionary: DictionaryStore
    /// Show the welcome screens.
    let onWelcome: () -> Void
    @State private var recordingBytes: Int64 = 0
    /// A stricter retention rule waiting for its warning: the rule, and the takes it deletes now.
    @State private var stricter: (days: Int?, count: Int?, takes: [UUID])?
    // ponytail: a plain Binding(get:set:) over AutoReturn's static UserDefaults read never told SwiftUI to
    // re-render, so the switch's own accessibility value could go stale after a tap. Mirror it in @State instead.
    @State private var autoReturn = AutoReturn.enabled
    @State private var keyboard = KeyboardStatus.current()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        NavigationStack {
            Form {
                let _ = scenePhase // back from system Settings (the mic granted there): read Setup again
                Section {
                    SetupRows(host: host, model: models.active,
                              facts: SetupRows.facts(model: models.active.phase, engine: host.status.engine, keyboard: keyboard))
                    Button(action: onWelcome) { Label("Show the welcome screens", systemImage: "arrow.counterclockwise") }
                        .accessibilityIdentifier("settings.welcome")
                } header: {
                    heading("Setup")
                } footer: {
                    Text("What ThumbFree needs to work.")
                }
                .listRowBackground(Theme.card)
                Section {
                    ForEach(models.all, id: \.id) { ModelRow(model: $0, models: models, host: host) }
                    Toggle(isOn: Binding(get: { models.wifiOnly }, set: { models.wifiOnly = $0 })) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Download on Wi-Fi only")
                            Text("Models are big files, so Wi-Fi is best. Turn this off to use mobile data. A download already under way keeps its network: to change it, tap Cancel and Download again, or Use mobile data.")
                                .font(.subheadline).foregroundStyle(Theme.inkSoft)
                        }
                    }
                    .accessibilityIdentifier("settings.wifiOnly")
                } header: {
                    heading("Speech model")
                } footer: {
                    Text("Turns your voice into text on this iPhone. A new choice applies from your next take."
                         + "\n\nAfter you install or update ThumbFree, or update iOS, the first start takes about half a minute while ThumbFree prepares the model for this iPhone.")
                }
                .listRowBackground(Theme.card)
                Section {
                    Picker("Keep the mic on for", selection: Binding(get: { settings.sessionMinutes }, set: { settings.sessionMinutes = $0 })) {
                        ForEach(AppSettings.sessionChoices, id: \.self) { Text(Self.minutes($0)).tag($0) }
                    }
                    .accessibilityIdentifier("settings.session")
                    #if TF_AUTO_RETURN // a build without automatic return has no switch for it
                    Toggle("Go back to the app automatically", isOn: $autoReturn)
                        .accessibilityIdentifier("settings.autoReturn")
                        .onChange(of: autoReturn) { _, on in AutoReturn.setEnabled(on) }
                    #endif
                } header: {
                    heading("Session length")
                } footer: {
                    Text("After your last take, the microphone stays on this long, so your next tap starts at once. The orange dot shows while it's on."
                         + Self.autoReturnFooter)
                }
                .listRowBackground(Theme.card)
                Section {
                    Picker("Keep takes for", selection: Binding(get: { settings.keepDays }, set: { propose(days: $0, count: settings.keepCount) })) {
                        ForEach(Retention.dayChoices, id: \.self) { Text(Self.days($0)).tag($0) }
                    }
                    .accessibilityIdentifier("settings.keepDays")
                    Picker("Keep at most", selection: Binding(get: { settings.keepCount }, set: { propose(days: settings.keepDays, count: $0) })) {
                        ForEach(Retention.countChoices, id: \.self) { Text(Self.takes($0)).tag($0) }
                    }
                    .accessibilityIdentifier("settings.keepCount")
                } header: {
                    heading("History")
                } footer: {
                    Text("Both rules apply, so the stricter one wins. A take that is still recording or being typed is never deleted.\n\n"
                         + Self.recordings(recordingBytes))
                }
                .listRowBackground(Theme.card)
                Section {
                    NavigationLink {
                        DictionaryView(dictionary: dictionary, language: host.language)
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Open the dictionary")
                            Text(dictionary.entries.isEmpty ? "No words yet" : DictionaryView.countLabel(dictionary.entries.count))
                                .font(.subheadline).foregroundStyle(Theme.inkSoft)
                        }
                    }
                    .accessibilityIdentifier("settings.dictionary")
                } header: {
                    heading("Dictionary")
                } footer: {
                    Text("Names and terms ThumbFree spells your way.")
                }
                .listRowBackground(Theme.card)
                AboutSection()
            }
            .scrollContentBackground(.hidden)
            .background(Theme.paper)
            .navigationTitle("Settings")
            .followsKeyboard($keyboard)
            .task(id: host.historyChanges) { recordingBytes = Self.recordingBytes(host.history) }
            .alert("Delete older takes?", isPresented: Binding(get: { stricter != nil }, set: { if !$0 { stricter = nil } })) {
                Button("Delete", role: .destructive) {
                    // Exactly the takes the warning counted (one live or transcribed again by now stays), then the rule.
                    if let stricter {
                        _ = try? host.deleteTakes(stricter.takes)
                        save(days: stricter.days, count: stricter.count)
                    }
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text(AppSettings.deleteWarning(stricter?.takes.count ?? 0))
            }
        }
    }

    private func heading(_ text: String) -> some View { Text(text).foregroundStyle(Theme.heading) }

    /// A rule that would delete takes now asks first; one that deletes nothing is saved at once.
    private func propose(days: Int?, count: Int?) {
        let takes = host.takesToDelete(keepDays: days, keepCount: count)
        guard !takes.isEmpty else { return save(days: days, count: count) }
        stricter = (days, count, takes)
    }

    private func save(days: Int?, count: Int?) {
        settings.keepDays = days
        settings.keepCount = count
    }

    /// The automatic-return switch's footer, in a build that has the switch.
    #if TF_AUTO_RETURN
    static let autoReturnFooter = "\n\nAfter you tap the mic in another app, ThumbFree takes you back to it. Works in the apps ThumbFree knows; elsewhere, swipe back."
    #else
    static let autoReturnFooter = ""
    #endif

    static func minutes(_ value: Int) -> String { "\(value) minutes" }
    static func days(_ value: Int?) -> String { value.map { "\($0) days" } ?? "Forever" }
    static func takes(_ value: Int?) -> String { value.map { $0.formatted() + " takes" } ?? "No limit" }

    /// "Recordings use 2 MB on this iPhone.", or "under 1 MB".
    static func recordings(_ bytes: Int64) -> String {
        "Recordings use \(bytes < 1_000_000 ? "under 1 MB" : ModelStatusView.size(bytes)) on this iPhone."
    }

    static func recordingBytes(_ history: HistoryStore) -> Int64 {
        ((try? history.all()) ?? []).reduce(0) { $0 + (ModelDownloader.size(of: history.audioURL(for: $1.id)) ?? 0) }
    }

    /// "English (recommended)" or "Multilingual", as the Android app names them.
    static func title(_ model: SpeechModel, in models: SpeechModels) -> String {
        model === models.english ? "English (recommended)" : "Multilingual"
    }

    /// The multilingual model's 25 languages, by name.
    static let languages = SpeechModels.languages.map(\.value).joined(separator: ", ")
}

/// One speech model: tap to use it once it is here; its size and Download, Cancel or Delete.
private struct ModelRow: View {
    let model: SpeechModel
    let models: SpeechModels
    let host: SessionHost
    @State private var confirmDelete = false
    @State private var inUse = false
    @State private var showLanguages = false

    var body: some View {
        let chosen = models.active === model
        let title = SettingsView.title(model, in: models)
        VStack(alignment: .leading, spacing: 12) {
            Button { models.choose(model) } label: {
                HStack(alignment: .top, spacing: 14) {
                    Image(systemName: chosen ? "checkmark.circle.fill" : "circle")
                        .font(.title3)
                        .foregroundStyle(chosen ? Theme.primary : Theme.inkSoft)
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title).font(.headline).foregroundStyle(Theme.ink)
                        Text(model.entries[0].summary).foregroundStyle(Theme.inkSoft)
                        if model.phase == .ready {
                            Text("Downloaded · \(ModelStatusView.size(model.totalBytes))").font(.subheadline).foregroundStyle(Theme.inkSoft)
                        }
                    }
                }
            }
            .buttonStyle(.plain)
            .disabled(model.phase != .ready)
            .accessibilityAddTraits(chosen ? .isSelected : [])
            .accessibilityHint(model.phase == .ready ? "Uses this model from your next take." : "Download it first.")
            .accessibilityIdentifier("settings.model.\(model.id)")
            if model.phase != .ready {
                ModelStatusView(phase: model.phase, engine: host.status.engine, total: model.totalBytes, waiting: model.waiting)
            }
            HStack {
                switch model.phase {
                case .missing: Button("Download", action: model.download).accessibilityLabel("Download \(model.name)")
                case .failed: Button("Try again", action: model.download).accessibilityLabel("Try downloading \(model.name) again")
                case .downloading:
                    Button("Cancel", action: model.cancel).accessibilityLabel("Cancel \(model.name) download")
                    if model.waiting == .wifi {
                        Button("Use mobile data", action: model.useMobileData).accessibilityLabel("Use mobile data for \(model.name)")
                    }
                case .ready:
                    // No model goes while a take or a Transcribe again runs: the model in use may be the one not chosen.
                    Button("Delete", role: .destructive) { if host.modelInUse { inUse = true } else { confirmDelete = true } }
                        .accessibilityLabel("Delete \(model.name)")
                        .tint(Theme.error)
                }
            }
            .buttonStyle(.bordered)
            .buttonBorderShape(.capsule)
            if model === models.multilingual {
                DisclosureGroup("See supported languages", isExpanded: $showLanguages) {
                    Text(SettingsView.languages).font(.subheadline).foregroundStyle(Theme.inkSoft)
                }
                .accessibilityIdentifier("settings.languages")
            }
        }
        .padding(.vertical, 4)
        .confirmationDialog("Delete this speech model?", isPresented: $confirmDelete, titleVisibility: .visible) {
            // A take can start while this dialog waits for an answer: check again now, not only at the tap that opened it.
            Button("Delete model", role: .destructive) {
                if host.modelInUse { inUse = true } else { models.delete(model) }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("\(title) will be removed from this iPhone. Using it again means downloading \(ModelStatusView.size(model.totalBytes)) again.")
        }
        .alert("A take is using this model. Try again when it ends.", isPresented: $inUse) { Button("OK", role: .cancel) {} }
    }
}

/// Version, the privacy promise, and the licenses and credits of what ThumbFree is built on.
private struct AboutSection: View {
    @State private var showCredits = false

    var body: some View {
        Section {
            HStack(spacing: 14) {
                Image(systemName: "waveform")
                    .font(.title2.weight(.semibold))
                    .foregroundStyle(Theme.onSunflower)
                    .frame(width: 52, height: 52)
                    .background(Theme.sunflower, in: .circle)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text("ThumbFree").font(.headline)
                    Text("Version \(Self.version)").foregroundStyle(Theme.inkSoft)
                }
            }
            .accessibilityElement(children: .combine)
            HStack(alignment: .top, spacing: 14) {
                Image(systemName: "lock.fill").frame(width: 28).accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Everything stays on your iPhone").font(.headline)
                    Text("Your voice becomes text on this iPhone. Audio and text never leave it. The internet is used only to download speech models.")
                        .foregroundStyle(Theme.inkSoft)
                }
            }
            .accessibilityElement(children: .combine)
            // App Review asks for the privacy policy inside the app too, not only in the listing (guideline 5.1.1(i)).
            Link("Privacy policy", destination: Self.url("https://kabrapratik28.github.io/ThumbFree/privacy.html"))
                .foregroundStyle(Theme.ink)
                .accessibilityIdentifier("settings.privacy")
            DisclosureGroup("Open-source licenses and credits", isExpanded: $showCredits) {
                VStack(alignment: .leading, spacing: 10) {
                    Text("Parakeet TDT 0.6B v2, the English speech model, by NVIDIA: CC BY 4.0.")
                    Link("NVIDIA's English model", destination: Self.url("https://huggingface.co/nvidia/parakeet-tdt-0.6b-v2"))
                    Text("Parakeet TDT 0.6B v3, the multilingual speech model, by NVIDIA: CC BY 4.0.")
                    Link("NVIDIA's multilingual model", destination: Self.url("https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3"))
                    Link("CC BY 4.0 license", destination: Self.url("https://creativecommons.org/licenses/by/4.0/"))
                    Text("Both converted to Core ML by FluidInference.")
                    Link("English Core ML files", destination: Self.url("https://huggingface.co/FluidInference/parakeet-tdt-0.6b-v2-coreml"))
                    Link("Multilingual Core ML files", destination: Self.url("https://huggingface.co/FluidInference/parakeet-tdt-0.6b-v3-coreml"))
                    Text("Silero VAD, the speech check: MIT License. Converted to Core ML by FluidInference.")
                    Link("Silero VAD", destination: Self.url("https://github.com/snakers4/silero-vad"))
                    Text("wordfreq word frequency data (the common-word list): CC BY-SA 4.0.")
                    Text("Text cleanup rules adapted from MIT-licensed code, Copyright (c) 2025 CJ Pais.")
                    Text("Emoji names and search keywords: Unicode License v3.")
                }
                .font(.subheadline)
                .padding(.vertical, 4)
            }
            .accessibilityIdentifier("settings.credits")
            Link(destination: Self.url("https://kabrapratik28.github.io/ThumbFree")) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Website").foregroundStyle(Theme.ink)
                    Text("kabrapratik28.github.io/ThumbFree").font(.subheadline).foregroundStyle(Theme.inkSoft)
                }
            }
        } header: {
            Text("About").foregroundStyle(Theme.heading)
        } footer: {
            Text("Version, privacy and licenses.")
        }
        .listRowBackground(Theme.card)
    }

    static var version: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0" }

    /// A fixed address from this file; the fallback never shows, it only keeps force unwraps out.
    private static func url(_ string: String) -> URL { URL(string: string) ?? URL(fileURLWithPath: "/") }
}
