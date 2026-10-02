import AVFoundation
import SwiftUI
import TFCore

/// The first tab after the welcome. While something stops dictation, one card with the one fix (`SetupBlocker`, a refused
/// microphone first, then the keyboard, then speech, then the try for what only it finishes), and nothing else. Once dictation works now (`SetupRows.ready`):
/// "Ready · works offline", Try it until a take has given text (the try, `TryItView`, in a sheet), and how to use
/// ThumbFree in other apps, the walkthrough in its EXAMPLE frame; once a take worked, a link to putting ThumbFree first in
/// the keyboard list. A quick load of a model loaded before shows only a quiet "Getting ready" in the ready chip's place
/// rather than the card, which would only flash. While a session is on, a line says so, with End session. Nothing
/// scrolls at the standard text sizes: the walkthrough's drawing shrinks to the room left, and only the accessibility
/// sizes scroll.
struct HomeView: View {
    let host: SessionHost
    let models: SpeechModels
    @State private var keyboard = KeyboardStatus.current()
    /// Try it was tapped: the try screen's sheet is up.
    @State private var trying = TryItView.opensAtLaunch
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        NavigationStack {
            let content = VStack(alignment: .leading, spacing: 16) { page }
                .padding(.horizontal, 20)
                .padding(.vertical, 8)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            Group {
                if dynamicTypeSize.isAccessibilitySize {
                    ScrollView { content }
                } else {
                    content
                }
            }
            .background(Theme.paper)
            .foregroundStyle(Theme.ink)
            .navigationTitle("ThumbFree")
            .navigationBarTitleDisplayMode(.inline)
            .followsKeyboard($keyboard)
        }
        .sheet(isPresented: $trying) {
            TryItView(host: host, models: models) { trying = false }
        }
        // Ready means loaded: whenever the model is here, the engine gets ready (about half a minute the first time; it
        // then stays loaded).
        .onChange(of: model.phase == .ready, initial: true) { _, here in
            if here { host.prepareEngine() }
        }
    }

    private var model: SpeechModel { models.active }

    @ViewBuilder private var page: some View {
        let _ = scenePhase // back from Settings: read the microphone again
        let top = Self.top(mic: AVAudioApplication.shared.recordPermission, keyboard: keyboard, phase: model.phase,
                           waiting: model.waiting, engine: host.status.engine, loadedBefore: model.loadedBefore)
        if host.status.session != .off { session }
        switch top {
        case .blocker(let blocker):
            SetupBlockerCard(blocker: blocker, model: model, engine: host.status.engine, run: run)
        case .gettingReady:
            // A matter of seconds: the walkthrough waits until speech works.
            StatusChip(text: "Getting ready", symbol: "hourglass", tone: .busy, id: "home.gettingReady")
        case .ready:
            StatusChip(text: "Ready · works offline", symbol: "checkmark.circle.fill", id: "home.ready")
            let offersTry = host.textTakes == 0
            if offersTry {
                PrimaryActionButton(title: "Try it", id: "home.tryIt") { trying = true }
                    .accessibilityHint("Try the ThumbFree keyboard here.")
            }
            let heading = String(localized: "How to use it in other apps")
            Text(heading.keepingLastWordsTogether).font(.headline).accessibilityLabel(heading).accessibilityAddTraits(.isHeader)
            GuideView()
            if !offersTry { putFirst }
        }
    }

    /// The top of Home: one blocker's card (`SetupBlocker.forHome`), with a quiet "Getting ready" in its place when it
    /// would only be the quick load of a model loaded before (a matter of seconds at a launch: the card would only
    /// flash); else ready. The first load after a download keeps the card, which says it takes a while.
    enum Top: Equatable {
        case blocker(SetupBlocker)
        case gettingReady
        case ready
    }

    static func top(mic: AVAudioApplication.recordPermission, keyboard: KeyboardStatus, phase: SpeechModel.Phase,
                    waiting: SpeechModel.Waiting?, engine: EnginePhase, loadedBefore: Bool) -> Top {
        guard let blocker = SetupBlocker.forHome(mic: mic, keyboard: keyboard, phase: phase, waiting: waiting, engine: engine) else {
            return .ready
        }
        return blocker == .loading && loadedBefore ? .gettingReady : .blocker(blocker)
    }

    private func run(_ action: SetupBlocker.Action) {
        switch action {
        case .openSettings: SetupRows.openSettings()
        case .download, .downloadAgain, .tryAgain: model.download()
        case .useMobileData: model.useMobileData()
        case .loadAgain: host.prepareEngine()
        case .tryKeyboard: trying = true
        }
    }

    /// "Put ThumbFree first", on a page of its own.
    private var putFirst: some View {
        NavigationLink {
            ScrollView { PutFirstView().padding(20) }
                .background(Theme.paper)
                .navigationTitle("Put ThumbFree first")
                .navigationBarTitleDisplayMode(.inline)
        } label: {
            Label("Put ThumbFree first", systemImage: "list.number")
                .font(.subheadline.weight(.semibold))
                .frame(minHeight: 44)
                .contentShape(.rect)
        }
        .foregroundStyle(Theme.primary)
        .accessibilityIdentifier("home.putFirst")
    }

    /// The live session: the mic is on until End session or the session's length runs out.
    private var session: some View {
        HStack {
            Label("Ready, mic on", systemImage: "mic.fill").font(.subheadline).foregroundStyle(Theme.error)
            Spacer()
            Button("End session", role: .destructive) { host.endSession() }
                .font(.subheadline.weight(.semibold))
                .buttonStyle(.bordered)
                .buttonBorderShape(.capsule)
                .tint(Theme.recording)
                .foregroundStyle(Theme.error) // the page's ink would win over the tint; this red reads on the capsule
                .accessibilityIdentifier("home.endSession")
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(Theme.card, in: .rect(cornerRadius: 20, style: .continuous))
    }
}
