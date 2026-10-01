import SwiftUI
import TFCore
import UIKit

/// The Try tab: one big box to dictate into, with an example as its placeholder, and the mic under it. Above the box, a
/// compact card with only what setup is still missing (else "Ready · works offline"); under it, the way to "How to use
/// it in other apps". The in-app mic key works without the keyboard and follows the keyboard's
/// rule: tap to start and tap again to stop, or hold while you talk.
struct TryView: View {
    let host: SessionHost
    let model: SpeechModel
    @State private var text = ""
    /// The take the mic key's press named, so its release names it too.
    @State private var take: UUID?
    @GestureState private var micPressed = false
    @FocusState private var typing: Bool
    @Environment(\.scenePhase) private var scenePhase
    /// Bumped when the microphone prompt is answered, so the setup card reads the permission again.
    @State private var setupTick = 0
    @State private var keyboard = KeyboardStatus.current()
    #if DEBUG
    /// UI tests (Debug builds): `-TFOpenGuide YES` opens "How to use it in other apps" at launch, as its link does.
    @State private var guideOpen = UserDefaults.standard.bool(forKey: "TFOpenGuide")
    #endif

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                // At least the screen's height, so the box takes what the rest leaves; longer (large text, a long take,
                // the keyboard up), it scrolls.
                GeometryReader { screen in
                    ScrollView {
                        AtLeast(height: screen.size.height) {
                            VStack(alignment: .leading, spacing: 12) { content }
                                .padding(.horizontal, 20)
                                .padding(.vertical, 8)
                        }
                    }
                    .scrollBounceBehavior(.basedOnSize)
                }
                mic.padding(.top, 8)
                VStack(spacing: 4) {
                    Text(Self.hint(for: host.status, modelReady: model.phase == .ready))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .accessibilityIdentifier("try.status")
                    if let ms = host.lastStopToTextMs {
                        Text(Self.speedLine(ms))
                            .font(.caption2)
                            .foregroundStyle(.tertiary)
                            .accessibilityIdentifier("try.speed")
                    }
                }
                .multilineTextAlignment(.center)
                .padding(.horizontal) // large text: the status line stays clear of the screen edges
                .padding(.bottom, 12)
            }
            .background(Theme.paper)
            .foregroundStyle(Theme.ink)
            .navigationTitle("Try it")
            .onAppear { host.deliverInApp = { append($0) } }
            .followsKeyboard($keyboard)
            #if DEBUG
            .navigationDestination(isPresented: $guideOpen) { guidePage }
            #endif
        }
    }

    @ViewBuilder private var content: some View {
        let _ = scenePhase // back from Settings: read the microphone again
        let _ = setupTick // the microphone prompt answered: read it again
        let facts = SetupRows.facts(model: model.phase, engine: host.status.engine, keyboard: keyboard)
        if SetupRows.doneCount(facts) < 3 {
            SetupRows(host: host, model: model, facts: facts, compact: true, onMicAnswered: { setupTick += 1 })
                .padding(16)
                .background(Theme.hero, in: .rect(cornerRadius: 20))
                .accessibilityElement(children: .contain)
        } else {
            Label("Ready · works offline", systemImage: "checkmark.circle.fill")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Theme.success)
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(Theme.success.opacity(0.12), in: .capsule)
                .accessibilityIdentifier("try.ready")
        }
        if host.status.session != .off {
            HStack {
                Label("Ready, mic on", systemImage: "mic.fill").font(.subheadline).foregroundStyle(Theme.error)
                Spacer()
                Button("End session", role: .destructive) { host.endSession() }
                    .font(.subheadline.weight(.semibold))
                    .buttonStyle(.bordered)
                    .buttonBorderShape(.capsule)
                    .tint(Theme.recording)
                    .foregroundStyle(Theme.error) // the page's ink would win over the tint; this red reads on the capsule
                    .accessibilityIdentifier("try.endSession")
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(Theme.card, in: .rect(cornerRadius: 20))
        }
        practiceBox
        NavigationLink {
            guidePage
        } label: {
            Label("How to use it in other apps", systemImage: "play.circle.fill").font(.subheadline.weight(.semibold))
        }
        .foregroundStyle(Theme.primary)
        .accessibilityIdentifier("try.guide")
    }

    /// "How to use it in other apps": the walkthrough, then putting ThumbFree first.
    private var guidePage: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 32) {
                GuideView()
                PutFirstView()
            }
            .padding(20)
        }
        .background(Theme.paper)
        .navigationTitle("Use ThumbFree in any app")
        .navigationBarTitleDisplayMode(.inline)
    }

    /// The box, as tall as the page leaves it: a tap anywhere in it starts typing. The example is its placeholder.
    private var practiceBox: some View {
        VStack(alignment: .leading, spacing: 8) {
            TextField("Tap the mic and say: Running ten minutes late, save me a seat", text: $text, axis: .vertical)
                .lineLimit(3...)
                .keyboardType(Self.fieldType)
                .autocorrectionDisabled(Self.autocorrectionOff)
                .focused($typing)
                .accessibilityIdentifier("try.field")
                .padding(16)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                .contentShape(.rect)
                .onTapGesture { typing = true }
                .overlay(RoundedRectangle(cornerRadius: 24).stroke(Theme.inkSoft, lineWidth: 1))
            HStack {
                Text("Only for practice. Nothing here is sent anywhere.").font(.footnote).foregroundStyle(Theme.inkSoft)
                Spacer()
                Button("Clear", systemImage: "xmark") { text = "" }
                    .font(.subheadline.weight(.semibold))
                    .disabled(text.isEmpty)
                    .opacity(text.isEmpty ? 0.35 : 1) // the page's foreground style keeps SwiftUI from dimming it
            }
        }
        .frame(maxHeight: .infinity)
    }

    private var mic: some View {
        let mode = SessionScreen.mode(for: host.status)
        let listening = mode == .stop
        let busy = mode == .busy
        let ready = model.phase == .ready
        return BubbleArt(mode: mode)
            .frame(width: 104, height: 104)
            .saturation(ready ? 1 : 0)
            .opacity(ready ? 1 : 0.5)
            .contentShape(.circle)
            .gesture(DragGesture(minimumDistance: 0).updating($micPressed) { _, pressed, _ in pressed = true })
            // `@GestureState` resets on a release and on a cancelled touch (the first run's mic permission prompt,
            // Control Center, a call), so every press gets its release; with `onEnded` alone a cancel left the take
            // running as a hold and swallowed the next tap (the same pattern as Keyboard/KeyboardView.swift's mic).
            .onChange(of: micPressed) { _, pressed in pressed ? micDown() : micUp() }
            .accessibilityElement()
            .accessibilityLabel(listening ? "Stop dictation" : busy ? "Transcribing" : "Start dictation")
            .accessibilityAddTraits(.isButton)
            .accessibilityIdentifier("try.mic")
            .disabled(!ready)
            // VoiceOver's double-tap fires the default accessibility action, not the drag gesture above: without
            // this, a VoiceOver user cannot start or stop a take here (mirrors Keyboard/KeyboardView.swift's mic).
            .accessibilityAction {
                guard ready else { return }
                let id = host.status.takeID ?? UUID() // a stop tap names the live take, so its release counts
                host.pressInApp(id)
                host.releaseInApp(id)
            }
    }

    private func micDown() {
        guard model.phase == .ready else { return } // no model: the card above offers it
        let id = host.status.takeID ?? UUID() // a stop tap names the live take, so its release counts
        take = id
        host.pressInApp(id)
    }

    private func micUp() {
        if let take { host.releaseInApp(take) }
        take = nil
    }

    /// Types an in-app take's text at the end of the box, spaced and capitalized like the keyboard does it.
    private func append(_ transcript: String) {
        text += CursorFormatter.payload(text: transcript, before: text, after: "",
                                        capsExpected: FieldTraits.capsExpected(.sentences, before: text), field: .text, trailingSpace: false)
    }

    /// Small print under the status line, to compare a phone with the Mac's stop-to-text numbers.
    static func speedLine(_ ms: Int) -> String { "Last take: text ready \(ms) ms after the stop." }

    /// UI tests (Debug builds): `-TFFieldType <UIKeyboardType raw value>` gives the practice box that keyboard type, so the
    /// keyboard's layouts for email, web address and number fields can be checked in the app's own field. Otherwise text.
    static var fieldType: UIKeyboardType {
        #if DEBUG
        UIKeyboardType(rawValue: UserDefaults.standard.integer(forKey: "TFFieldType")) ?? .default
        #else
        .default
        #endif
    }

    /// UI tests (Debug builds): `-TFAutocorrect NO` turns the practice box's autocorrection off (`autocorrectionType` .no),
    /// as some apps' fields are, so the keyboard's suggestions and corrections can be checked there. Otherwise on.
    static var autocorrectionOff: Bool {
        #if DEBUG
        UserDefaults.standard.object(forKey: "TFAutocorrect") != nil && !UserDefaults.standard.bool(forKey: "TFAutocorrect")
        #else
        false
        #endif
    }

    static func hint(for status: HostStatus, modelReady: Bool = true) -> String {
        if let message = status.message { return message }
        switch status.take {
        case .recording: return status.micOn ? "Listening. Tap to stop." : "Starting the microphone"
        case .stopping, .transcribing, .delivering: return "Transcribing"
        case .idle:
            if !modelReady { return "Get the speech model above to start." }
            // The first load after a download (about 20 s on an iPhone 16): a take now would sit on "Transcribing".
            if status.engine == .loading || status.engine == .warming {
                return "Getting ready for this iPhone. The first time takes about half a minute. After that, ThumbFree starts in a moment."
            }
            return "Tap to talk, tap again to stop. Or hold while you talk."
        }
    }
}

/// Lays out its one view at least `height` tall and never shorter than it needs, so its flexible parts (the Try tab's
/// box) fill the screen. A scroll view offers no height, so `frame(minHeight:)` only pads around the view's own size.
private struct AtLeast: Layout {
    let height: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let needed = subviews.first?.sizeThatFits(ProposedViewSize(width: proposal.width, height: nil)) ?? .zero
        return CGSize(width: proposal.width ?? needed.width, height: max(needed.height, height))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        subviews.first?.place(at: bounds.origin, proposal: ProposedViewSize(bounds.size))
    }
}
