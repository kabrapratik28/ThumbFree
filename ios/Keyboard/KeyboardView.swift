import SwiftUI
import TFCore
import UIKit

/// The bar above the Apple-style keys: a short status on the left and ThumbFree's mic (the small `BubbleArt` mark) at
/// the right, tap to talk and tap again to stop, hold to talk. After a take, Clean up's round sparkle sits just left of
/// the mic, its size. While a take records, both ends say so: the mic is a red
/// stop key and the status reads "● Recording 0:07  Speak now". While typing, Apple's three suggestions take the
/// status's place. The delivery chip (Insert here, Copy, Dismiss) takes the bar's place when a take could not be typed.
/// The keys themselves are the UIKit `KeyplaneView` below, laid out by `KeyboardViewController`; this view is only the
/// bar.
struct KeyboardBar: View {
    let model: KeyboardClient
    let emoji: EmojiBarState
    let suggestions: SuggestionBarState
    let micDown: () -> Void
    let micUp: () -> Void
    let insertHere: () -> Void
    let copy: () -> Void
    let dismiss: () -> Void
    let searchEmoji: () -> Void
    let clearSearch: () -> Void
    let pickSuggestion: (Int) -> Void
    /// Clean up: tidy (nil: the default style), Undo, and the style menu's open and Cancel.
    let tidy: (CleanupStyle?) -> Void
    let undoTidy: () -> Void
    let openStyles: () -> Void
    let closeStyles: () -> Void

    @GestureState private var micPressed = false
    @Environment(\.displayScale) private var displayScale

    var body: some View {
        // Once a second, with or without a new status: the mic and status follow how old the app's last status is, so an
        // app that went away stops showing as ready or listening after 5 s, so a status never outlives its app.
        // It also moves the take's time on.
        TimelineView(.periodic(from: .now, by: 1)) { context in
            let state = model.state
            // Clean up's style menu sits above the bar while it is open; the keyboard grows by its height.
            VStack(spacing: 0) {
                if model.choosingStyle { styleMenu.frame(height: Self.menuHeight) }
                HStack(spacing: 10) {
                    // A search being typed keeps the bar (the chip waits until it ends); otherwise the chip comes first.
                    if emoji.query == nil, let chip = model.chip {
                        chipView(chip)
                    } else if emoji.showsSearch, emoji.query != nil || state != .listening {
                        // A take recording has the field's place until a search begins (the take matters more than a
                        // search not yet begun); a search being typed keeps its field, and the red stop key shows the take.
                        searchField
                    } else if state == .listening {
                        recordingLine(seconds: model.recordingSeconds(now: context.date))
                        Spacer(minLength: 0)
                    } else if model.choosingStyle {
                        Text(CleanupWords.chooseStyle).font(.footnote).foregroundStyle(.secondary)
                        Spacer(minLength: 0)
                        Button(CleanupWords.cancel, action: closeStyles)
                            .font(.body.weight(.semibold))
                            .frame(minWidth: 44, maxHeight: .infinity)
                            .accessibilityIdentifier("keyboard.cleanup.cancel")
                    } else if let line = model.cleanLine(now: context.date) {
                        Text(line)
                            .font(.footnote)
                            .lineLimit(2)
                            .minimumScaleFactor(0.8)
                            .accessibilityIdentifier("keyboard.cleanup.line")
                        Spacer(minLength: 0)
                    } else if let slots = suggestions.slots {
                        suggestionRow(slots)
                    } else {
                        Text(state.text)
                            .font(.footnote)
                            .lineLimit(2)
                            .minimumScaleFactor(0.8) // a backstop for the app's longer messages; the keyboard's own words fit
                            .accessibilityIdentifier("keyboard.status")
                        Spacer(minLength: 0)
                    }
                    if !emoji.showsSearch, let sparkle = model.sparkle(now: context.date) { sparkleKey(sparkle) }
                    mic
                }
                .padding(.horizontal, 10)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        // The bar is 48 pt tall (36 pt in landscape), so its words stop growing at .xxLarge, one step below the largest
        // standard text size: the status and the chip are never cut.
        .dynamicTypeSize(...DynamicTypeSize.xxLarge)
        // The chip comes with a status notification, wherever VoiceOver's focus is: say that the text was not typed, and
        // what to tap. Watched as nil during a search, like the chip view itself, so it announces only once the chip
        // shows again (never a chip that changed off-screen while the search had the bar).
        .onChange(of: emoji.query == nil ? model.chip : nil) { _, chip in
            if let chip { UIAccessibility.post(notification: .announcement, argument: chip.announcement) }
        }
    }

    /// Apple's Search Emoji field, in the status's place while the emoji picker is up (Apple's sits at the top of its
    /// Emoji keyboard too). A tap starts the search; the keys then type into it, and the clear button empties it. Apple's
    /// sizes, measured on the iOS 26.5 Simulator: 40 pt tall (28 in landscape's shorter bar), the magnifier 20 pt in, the
    /// words 8 pt after it, the clear button 6.7 pt from the end (the paddings leave out the images' own margins).
    private var searchField: some View {
        HStack(spacing: 6) {
            HStack(spacing: 5) {
                Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                if let query = emoji.query, !query.isEmpty {
                    // The caret right after the last letter (or a typed space), centered on the place the next one goes.
                    HStack(spacing: 0) {
                        Text(query).lineLimit(1).truncationMode(.head)
                        caret(tall: true).offset(x: -1)
                    }
                } else {
                    // An empty search: the caret at the start, before the words, as Apple's.
                    ZStack(alignment: .leading) {
                        Text("Search Emoji").foregroundStyle(.secondary).lineLimit(1)
                        if emoji.query != nil { caret(tall: false) }
                    }
                }
                Spacer(minLength: 0)
            }
            .frame(maxHeight: .infinity)
            .contentShape(.rect)
            .onTapGesture { if emoji.query == nil { searchEmoji() } }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Search Emoji")
            .accessibilityValue(emoji.query ?? "")
            .accessibilityAddTraits(emoji.query == nil ? .isButton : .isSearchField)
            .accessibilityAction { if emoji.query == nil { searchEmoji() } }
            .accessibilityIdentifier("keyboard.emoji.search")
            if emoji.query?.isEmpty == false {
                Button(action: clearSearch) { Image(systemName: "xmark.circle.fill").foregroundStyle(.tertiary) }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Clear text")
                    .accessibilityIdentifier("keyboard.emoji.search.clear")
            }
        }
        .padding(.leading, 18)
        .padding(.trailing, 5.3)
        .background(Self.fieldColor, in: .rect(cornerRadius: 12))
        .padding(.vertical, 4)
    }

    /// Apple's suggestion bar, in the status's place while typing (read on the iOS 26.5 Simulator): three equal places,
    /// the typed word in quotes first, words in 17 pt regular in Apple's gray, a hairline 24 pt tall between two places.
    /// The autocorrection sits in a lighter capsule 38 pt tall across its place, with no hairline beside it. A place with
    /// nothing to offer stays empty, as Apple's does.
    private func suggestionRow(_ slots: [Suggestion]) -> some View {
        let lit = { (index: Int) in slots.indices.contains(index) && slots[index].kind == .correction }
        return HStack(spacing: 0) {
            ForEach(0..<3, id: \.self) { index in
                if index > 0 {
                    Rectangle().fill(Self.dividerColor).frame(width: 1 / displayScale, height: 24).opacity(lit(index - 1) || lit(index) ? 0 : 1)
                }
                suggestionSlot(index < slots.count ? slots[index] : nil, index: index)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func suggestionSlot(_ suggestion: Suggestion?, index: Int) -> some View {
        let lit = suggestion?.kind == .correction
        return Button { pickSuggestion(index) } label: {
            // The undo place: the word as typed and Apple's undo arrow, as in the bubble Apple's keyboard shows under a
            // corrected word (a keyboard cannot draw in the app's text, so it shows here).
            (suggestion?.kind == .undo ? Text("\(suggestion?.text ?? "") \(Image(systemName: "arrow.uturn.backward"))") : Text(suggestion?.label ?? ""))
                .font(.system(size: 17))
                .foregroundStyle(lit ? Self.litWordColor : Self.wordColor)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .padding(.horizontal, 4)
                .frame(maxWidth: .infinity, maxHeight: 38)
                .background(lit ? Self.litColor : .clear, in: .capsule)
                .padding(.vertical, 4) // in landscape's shorter bar the capsule shrinks to fit
                .frame(maxHeight: .infinity)
                .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .disabled(suggestion == nil)
        .accessibilityHidden(suggestion == nil)
        .accessibilityLabel(suggestion?.spoken ?? "")
        .accessibilityAddTraits(lit ? .isSelected : []) // Apple's lit place reads as selected
        .accessibilityIdentifier("keyboard.suggestion.\(index)")
    }

    /// Apple's colors for the bar's words and hairlines, sampled from its keyboard on the Simulator in light and dark.
    private static let wordColor = Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 172 / 255, alpha: 1)
        : UIColor(red: 54 / 255, green: 56 / 255, blue: 60 / 255, alpha: 1) })
    private static let dividerColor = Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? UIColor(red: 50 / 255, green: 50 / 255, blue: 52 / 255, alpha: 1)
        : UIColor(red: 208 / 255, green: 210 / 255, blue: 215 / 255, alpha: 1) })
    private static let litColor = Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 115 / 255, alpha: 1)
        : UIColor(red: 235 / 255, green: 237 / 255, blue: 240 / 255, alpha: 1) })
    private static let litWordColor = Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? .white
        : UIColor(red: 44 / 255, green: 46 / 255, blue: 49 / 255, alpha: 1) })

    /// The field's caret while searching, as Apple's: 2 pt wide in the tint's blue, 22 pt tall beside letters and 16 pt
    /// (its top part) in an empty field, shown half a second, then out for half a second with a short fade each way. Each
    /// key starts it again, shown (`id`), as a text field's caret stays up while you type.
    private func caret(tall: Bool) -> some View {
        Capsule().fill(.tint).frame(width: 2, height: tall ? 22 : 16)
            .frame(height: 22, alignment: .top)
            .keyframeAnimator(initialValue: 1.0) { view, opacity in view.opacity(opacity) } keyframes: { _ in
                LinearKeyframe(1.0, duration: 0.5)
                LinearKeyframe(0.0, duration: 0.15)
                LinearKeyframe(0.0, duration: 0.2)
                LinearKeyframe(1.0, duration: 0.15)
            }
            .id(emoji.query)
            .accessibilityHidden(true)
    }

    /// Apple's search field color over the keyboard: rgb(239, 241, 245) in light, white at 11% in dark.
    private static let fieldColor = Color(uiColor: UIColor {
        $0.userInterfaceStyle == .dark ? UIColor(white: 1, alpha: 0.11) : UIColor(red: 239 / 255, green: 241 / 255, blue: 245 / 255, alpha: 1)
    })

    /// ThumbFree's mic, small, at the right of the bar: yellow when idle, a red stop key while listening, a turning arc while
    /// the take is transcribed. Tap to talk and tap again to stop, or hold to talk. The mark sits 4 pt inside a square as tall
    /// as the bar, and the whole rectangle around it, at least 48 pt wide, takes the touch: 48 by 48 pt in portrait, 48 by
    /// 36 pt in landscape's shorter bar.
    private var mic: some View {
        BubbleArt(mode: BubbleArt.Mode(model.state))
            .opacity(model.fullAccess ? 1 : 0.4) // dimmed without Full Access; the keys still type, the status line explains
            .padding(4)
            .aspectRatio(1, contentMode: .fit)
            .frame(minWidth: 48)
            .contentShape(.rect)
            .scaleEffect(model.pressing ? 0.92 : 1)
            .gesture(DragGesture(minimumDistance: 0).updating($micPressed) { _, pressed, _ in pressed = true })
            // `@GestureState` resets to `false` on a normal release AND on a cancelled gesture (Control Center, an incoming
            // call, the keyboard dismissed), so this one callback covers both; `onEnded` alone would miss the cancelled case
            // and leave the mic drawn pressed with the next tap silently swallowed (one release per press).
            .onChange(of: micPressed) { _, pressed in pressed ? micDown() : micUp() }
            .accessibilityElement()
            .accessibilityLabel(model.state.micLabel)
            .accessibilityAddTraits([.isButton, .isKeyboardKey])
            .accessibilityIdentifier("keyboard.mic")
            .accessibilityAction { micDown(); micUp() }
    }

    /// The status while a take records: a red dot that pulses with the stop key's glow, then "Recording 0:07  Speak now". When
    /// the bar is short (a small iPhone, the largest text), Speak now goes first, then the time; the mic keeps its size.
    /// VoiceOver reads it whole: "Recording, 7 seconds. Speak now."
    private func recordingLine(seconds: Int) -> some View {
        HStack(spacing: 5) {
            Image(systemName: "circle.fill").imageScale(.small).foregroundStyle(BubbleArt.red).modifier(BubbleArt.Pulse())
            ViewThatFits(in: .horizontal) {
                ForEach(KeyState.recordingLines(seconds: seconds), id: \.self) { Text($0).lineLimit(1) }
            }
        }
        .font(.footnote.monospacedDigit()) // the time's digits keep their width, so the line never jitters
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(KeyState.recordingLabel(seconds: seconds))
        .accessibilityAddTraits(.isStaticText)
        .accessibilityIdentifier("keyboard.status")
    }

    // MARK: Clean up

    /// How much taller the style menu makes the keyboard.
    static let menuHeight: CGFloat = 46

    /// Clean up's round button, just left of the mic and exactly its size: the same 4 pt inset square and touch area
    /// (48 by 48 pt in portrait, 48 by 36 in landscape). Tap tidies in the default style, a hold opens the style menu;
    /// after a tidy it is Undo, while the app works it shows the mic's busy arc and takes no tap.
    @ViewBuilder private func sparkleKey(_ sparkle: KeyboardClient.Sparkle) -> some View {
        let key = SparkleArt(mode: sparkle)
            .padding(4)
            .aspectRatio(1, contentMode: .fit)
            .frame(minWidth: 48)
            .contentShape(.rect)
        switch sparkle {
        case .offer:
            key.gesture(LongPressGesture(minimumDuration: 0.5).exclusively(before: TapGesture()).onEnded { value in
                switch value {
                case .first: openStyles()
                case .second: tidy(nil)
                }
            })
            .accessibilityElement()
            .accessibilityLabel(Text("Tidy"))
            .accessibilityHint(Text("Hold to choose a style."))
            .accessibilityAddTraits(.isButton)
            .accessibilityAction { tidy(nil) }
            .accessibilityAction(named: Text("Choose a style")) { openStyles() }
            .accessibilityIdentifier("keyboard.cleanup")
        case .working:
            key.accessibilityElement()
                .accessibilityLabel(Text(CleanupWords.working))
                .accessibilityIdentifier("keyboard.cleanup")
        case .undo:
            key.onTapGesture(perform: undoTidy)
                .accessibilityElement()
                .accessibilityLabel(Text("Undo"))
                .accessibilityAddTraits(.isButton)
                .accessibilityAction { undoTidy() }
                .accessibilityIdentifier("keyboard.cleanup.undo")
        }
    }

    /// The five styles in one row above the bar, the largest type that fits (an iPhone 16 is 393 pt wide).
    private var styleMenu: some View {
        ViewThatFits(in: .horizontal) {
            styleRow(size: 15)
            styleRow(size: 14)
            styleRow(size: 13)
            ScrollView(.horizontal, showsIndicators: false) { styleRow(size: 13) }
        }
        .padding(.horizontal, 8)
        .padding(.top, 8)
    }

    private func styleRow(size: CGFloat) -> some View {
        HStack(spacing: 5) {
            ForEach(CleanupStyle.allCases, id: \.self) { style in
                Button { tidy(style) } label: {
                    Text(style.title)
                        .font(.system(size: size, weight: style == .clean ? .semibold : .regular))
                        .foregroundStyle(Self.keyText)
                        .lineLimit(1)
                        .fixedSize()
                        .padding(.horizontal, 10)
                        .frame(height: 34)
                        .background(Self.keyFace, in: .capsule)
                        .shadow(color: .black.opacity(0.18), radius: 0, y: 1)
                        .contentShape(.capsule)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("keyboard.cleanup.style.\(style.rawValue)")
            }
        }
    }

    /// The keys' own face and words (Apple's, sampled on the Simulator), for the style capsules.
    private static let keyFace = Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 0.42, alpha: 1) : .white })
    private static let keyText = Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? .white : .black })

    /// Plain text buttons, like the words in Apple's suggestion bar, and an xmark to dismiss, so the line keeps its words
    /// on a 375 pt iPhone. Each button is at least 44 pt wide and as tall as the bar.
    private func chipView(_ chip: KeyboardClient.Chip) -> some View {
        HStack(spacing: 8) {
            Text(chip.text).font(.caption).lineLimit(2).minimumScaleFactor(0.8)
            Spacer(minLength: 0)
            if chip.canInsert { chipButton(Text("Insert here"), action: insertHere).accessibilityIdentifier("keyboard.insertHere") }
            chipButton(Text("Copy"), action: copy)
            chipButton(Image(systemName: "xmark"), action: dismiss).accessibilityLabel("Dismiss")
        }
        .buttonStyle(.borderless)
        .controlSize(.small)
    }

    private func chipButton(_ label: some View, action: @escaping () -> Void) -> some View {
        Button(action: action) { label.frame(minWidth: 44, maxHeight: .infinity).contentShape(.rect) }
    }
}

/// What the bar shows in the status's place while typing: Apple's suggestions for the word at the caret. Nil shows the
/// status: before the first key, and once the mic is used.
@MainActor @Observable final class SuggestionBarState {
    var slots: [Suggestion]?
}

/// What the bar shows in the status's place while the emoji picker is up: the search field, with what the keys typed into
/// it once a search starts.
@MainActor @Observable final class EmojiBarState {
    var showsSearch = false   // the picker or its search is up
    var query: String?        // nil until the field is tapped; then what the keys typed
}
