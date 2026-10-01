import UIKit

/// The Apple-style keys, all in one UIKit view with its own touch handling (per-key SwiftUI buttons, each with its own
/// gesture recognizer, drop taps at the boundaries between keys). It draws the current layer's keys as plain labels (no
/// per-key gesture recognizers), hit-tests every touch against `KeyLayout` so a touch in a gap goes to the nearest key,
/// shows a popup over the pressed key and Apple's alternatives after a long press, turns the space bar into a trackpad
/// when held, repeats delete when held, plays the system click and a haptic, and exposes one accessibility element per
/// key with the keyboard-key trait. The controller owns the text: `onKey` reports each committed key, `onAlternate`
/// each alternative picked, `onCursor` each trackpad step.
@MainActor final class KeyplaneView: UIView {
    /// A committed key (a normal key on touch-up; delete on touch-down and each repeat; the pressed key when another key is
    /// touched down over it, so fast typing commits in touch-down order).
    var onKey: (Key) -> Void = { _ in }
    /// The globe's raw touches, wired to the controller's `handleInputModeList(from:with:)` so a tap advances to the next
    /// keyboard and a long press lists them (the behavior the current keyboard has). Only used when the globe is shown.
    var onGlobe: (UIView, UIEvent) -> Void = { _, _ in }
    /// What a long press on a key offers (the controller knows the field, the layer and shift): Apple's alternatives.
    var alternates: (Key) -> KeyAlternates? = { _ in nil }
    /// An alternative picked after a long press: the key it belongs to and the text it types.
    var onAlternate: (Key, String) -> Void = { _, _ in }
    /// The space bar's trackpad moved the cursor: characters (negative: left) and lines (negative: up).
    var onCursor: (_ characters: Int, _ lines: Int) -> Void = { _, _ in }
    /// Delete held long enough to take whole words (Apple's `DeleteRepeat`).
    var onDeleteWord: () -> Void = {}
    /// A key typed by sliding from 123, ABC or #+= went in: the layer the slide started on comes back.
    var onSlideBack: (KeyLayer) -> Void = { _ in }
    /// A letter reached by sliding from shift: it goes in as a capital.
    var onShiftSlide: (Key) -> Void = { _ in }
    /// The trackpad started (true) or ended (false): the controller keeps the bar from taking touches meanwhile.
    var onTrackpad: (Bool) -> Void = { _ in }

    private var layer0: KeyLayer = .letters
    private var kind = KeyboardKind.text
    private var shift: ShiftState = .off
    private var showsGlobe = false
    private var searching = false
    private var returnKey = UIReturnKeyType.default

    private var layout = KeyLayout(layer: .letters, showsGlobe: false, bounds: .zero)
    private var caps: [Key: UILabel] = [:]
    private var elementsByKey: [Key: KeyAccessibilityElement] = [:]
    private var globeButton: UIButton?
    private let popup = KeyPopupView()
    private let calloutView = CalloutView()
    private var active: [UITouch: Press] = [:]
    /// True while a finger holds the space bar as a trackpad: the keys show blank and faded, as Apple's do.
    private var trackpadOn = false
    /// The finger resting on a character key (Apple's long press), and the wait before its alternatives open.
    private var held: UITouch?
    private var holdTimer: Task<Void, Never>?
    /// The finger resting on space, and the wait before the trackpad starts: its own slot, so a second finger elsewhere
    /// does not cancel a pending trackpad start, and vice versa (each slot is reassigned only by its own kind of key).
    private var trackpadHeld: UITouch?
    private var trackpadHoldTimer: Task<Void, Never>?
    private var deleteRepeat: Task<Void, Never>?
    /// Made once and kept prepared (again after each tap), so every press is felt at once.
    private let lightImpact = UIImpactFeedbackGenerator(style: .light)
    private let mediumImpact = UIImpactFeedbackGenerator(style: .medium)
    /// Where the last committed key sat, so VoiceOver's focus can land on the key in its place when the layer changes.
    private var lastCommitPoint: CGPoint?

    override init(frame: CGRect) {
        super.init(frame: frame)
        isMultipleTouchEnabled = true
        popup.isHidden = true
        addSubview(popup)
        addSubview(calloutView)
    }

    required init?(coder: NSCoder) { nil }

    /// Sets the visible layer, the field's kind of keyboard, shift state and return key, and redraws when one of them
    /// changed. Called by the controller after every state change (and on every status refresh, so an unchanged call does
    /// nothing). `searching` is true while the keys type into the emoji search: Apple's search keys have no emoji key and
    /// a blue done key with a check mark (VoiceOver reads "Done").
    func configure(layer: KeyLayer, kind: KeyboardKind = .text, shift: ShiftState, showsGlobe: Bool, searching: Bool = false,
                   returnKey: UIReturnKeyType) {
        guard layer != layer0 || kind != self.kind || (shift, showsGlobe, searching, returnKey)
                != (self.shift, self.showsGlobe, self.searching, self.returnKey) else { return }
        let layerChanged = layer != layer0 || kind != self.kind
        layer0 = layer
        self.kind = kind
        self.shift = shift
        self.showsGlobe = showsGlobe
        self.searching = searching
        self.returnKey = returnKey
        rebuild()
        // VoiceOver: the key just used may have gone with its layer (123 became ABC); focus the key now in its place.
        if layerChanged, let point = lastCommitPoint, let key = layout.hitKey(at: point), let element = elementsByKey[key] {
            UIAccessibility.post(notification: .layoutChanged, argument: element)
        }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        rebuild()
    }

    /// Builds the caps and accessibility elements for the current layer and size. Reuses each key's cap and accessibility
    /// element across rebuilds (only updating the label, case and frame), so a keystroke's redraw does not drop VoiceOver's
    /// focus. The globe is a real `UIButton` wired to `handleInputModeList` for the tap-and-long-press keyboard list.
    private func rebuild() {
        guard bounds.width > 0, bounds.height > 0 else { return }
        layout = KeyLayout(layer: layer0, kind: kind, showsGlobe: showsGlobe, showsEmoji: !searching, bounds: bounds)
        let wanted = Set(layout.frames.map(\.key))
        for (key, cap) in caps where !wanted.contains(key) { cap.removeFromSuperview(); caps[key] = nil }
        for key in elementsByKey.keys where !wanted.contains(key) { elementsByKey[key] = nil }
        if !wanted.contains(.globe) { globeButton?.removeFromSuperview(); globeButton = nil }
        var built: [KeyAccessibilityElement] = []
        for f in layout.frames {
            if f.key == .globe {
                positionGlobe(f.frame)
            } else {
                let cap = caps[f.key] ?? makeCap(for: f.key)
                caps[f.key] = cap
                cap.frame = f.frame
                style(cap, for: f.key)
            }
            let element = elementsByKey[f.key] ?? KeyAccessibilityElement(container: self, key: f.key)
            element.accessibilityLabel = f.key.label(shift: shown, returnLabel: returnLabel)
            element.accessibilityIdentifier = f.key.identifier
            element.accessibilityTraits = f.key.traits(shift: shown)
            element.accessibilityFrameInContainerSpace = f.frame
            elementsByKey[f.key] = element
            built.append(element)
        }
        accessibilityElements = built
        bringSubviewToFront(popup)
        bringSubviewToFront(calloutView)
    }

    private func positionGlobe(_ frame: CGRect) {
        let button: UIButton
        if let existing = globeButton {
            button = existing
        } else {
            button = UIButton(type: .system)
            button.setImage(UIImage(systemName: "globe"), for: .normal)
            button.tintColor = .label
            button.layer.cornerRadius = 6
            button.layer.masksToBounds = true
            button.isAccessibilityElement = false // the container exposes the globe's accessibility element
            button.addTarget(self, action: #selector(globeTouched(_:with:)), for: .allTouchEvents)
            addSubview(button)
            globeButton = button
        }
        button.backgroundColor = trackpadOn ? Self.fadedKeyColor : Self.letterKeyColor
        button.setImage(trackpadOn ? nil : UIImage(systemName: "globe"), for: .normal) // blank while the trackpad is on
        button.frame = frame
    }

    @objc private func globeTouched(_ view: UIView, with event: UIEvent) {
        guard !trackpadOn else { return } // other fingers do nothing while the trackpad is on, the globe included
        onGlobe(view, event)
    }

    private func makeCap(for key: Key) -> UILabel {
        let cap = UILabel()
        cap.textAlignment = .center
        cap.adjustsFontSizeToFitWidth = true
        cap.minimumScaleFactor = 0.5
        cap.layer.cornerRadius = 6
        cap.layer.masksToBounds = true
        cap.isAccessibilityElement = false // the view exposes its own accessibility elements
        addSubview(cap)
        return cap
    }

    /// The return key as Apple's keyboard labels it for the field (VoiceOver reads the word), tints it (blue when it
    /// acts) and draws it (a symbol). While searching emoji it is the blue done key with a check mark.
    private var returnLabel: String { searching ? "Done" : ReturnLabel.label(for: returnKey) }
    private var returnIsBlue: Bool { searching || ReturnLabel.isBlue(for: returnKey) }
    private var returnSymbol: String? { ReturnLabel.symbol(for: returnKey) }

    /// Apple's iOS 26 keys: every key one color, a function key gray while it is pressed; return blue with a white symbol
    /// when it acts; shift a filled arrow while on (caps lock with its bar); space blank; symbols for shift, delete, return
    /// and the emoji key, drawn in the text color.
    private func style(_ cap: UILabel, for key: Key) {
        cap.text = title(for: key)
        cap.font = .systemFont(ofSize: fontSize(for: key), weight: .regular)
        cap.numberOfLines = 1
        if trackpadOn { // Apple's trackpad: blank keys, faded
            cap.text = nil
            cap.backgroundColor = Self.fadedKeyColor
            return
        }
        if kind.isDigitPad { return padStyle(cap, for: key) }
        if let symbol = symbol(for: key) { cap.attributedText = NSAttributedString(attachment: symbol) }
        let blue = key == .ret && returnIsBlue
        cap.backgroundColor = blue ? (searching ? Self.searchDoneColor : .systemBlue)
            : !key.isCharacter && isPressed(key) ? Self.pressedKeyColor : Self.letterKeyColor
        cap.textColor = blue ? .white : .label
    }

    /// The SF Symbol a function key shows in Apple's keyboard, if any.
    private func symbol(for key: Key) -> NSTextAttachment? {
        switch key {
        case .shift: Self.glyphs[shown == .capsLock ? "capslock.fill" : shown == .oneShot ? "shift.fill" : "shift"]
        case .delete: Self.glyphs["delete.left"]
        case .ret: searching ? Self.searchCheckmark : returnSymbol.flatMap { Self.glyphs[$0] }
        case .emoji: Self.glyphs["face.smiling"]
        default: nil
        }
    }

    private static let glyphs: [String: NSTextAttachment] = Dictionary(uniqueKeysWithValues: [
        "shift", "shift.fill", "capslock.fill", "delete.left", "face.smiling", "return.left", "arrow.right", "arrow.up",
        "magnifyingglass", "checkmark", "chevron.right",
    ].map { name in
        (name, NSTextAttachment(image: UIImage(systemName: name, withConfiguration: UIImage.SymbolConfiguration(pointSize: 24)) ?? UIImage()))
    })

    /// Apple's search keys' done key: a check mark (drawn at the key's type size, 15.7 pt across as Apple's) on Apple's
    /// blue for it (#007AFF in light and dark, a little deeper than the return key's system blue), as measured on the
    /// iOS 26.5 Simulator.
    private static let searchCheckmark = NSTextAttachment(image: UIImage(systemName: "checkmark") ?? UIImage())
    private static let searchDoneColor = UIColor(red: 0, green: 122 / 255, blue: 1, alpha: 1)

    /// Whether a finger is on `key` now: Apple's function keys and digit keys turn gray while pressed.
    private func isPressed(_ key: Key) -> Bool { active.values.contains { $0.key == key } }

    /// Redraws `keys` (their pressed state changed).
    private func restyle(_ keys: [Key]) {
        for key in keys { if let cap = caps[key] { style(cap, for: key) } }
    }

    /// Apple's digit pad: each digit with the phone letters under it (none under 1 and 0), delete and the decimal point
    /// without a key of their own.
    private func padStyle(_ cap: UILabel, for key: Key) {
        cap.textColor = .label
        cap.backgroundColor = key == .delete || key == .symbol(Keyplane.decimalPoint) ? .clear : isPressed(key) ? Self.padPressedColor : Self.letterKeyColor
        if key == .delete, let symbol = Self.glyphs["delete.left"] { cap.attributedText = NSAttributedString(attachment: symbol) }
        guard case .symbol(let digit) = key, digit.isNumber else { return }
        let text = NSMutableAttributedString(string: String(digit), attributes: [.font: UIFont.systemFont(ofSize: 26)])
        text.append(NSAttributedString(string: "\n" + (Self.padLetters[digit] ?? " "),
                                       attributes: [.font: UIFont.systemFont(ofSize: 10, weight: .semibold), .kern: 2]))
        cap.numberOfLines = 2
        cap.attributedText = text
    }

    private static let padLetters: [Character: String] = ["2": "ABC", "3": "DEF", "4": "GHI", "5": "JKL", "6": "MNO", "7": "PQRS",
                                                          "8": "TUV", "9": "WXYZ"]

    private func title(for key: Key) -> String {
        switch key {
        case .letter(let c): shown.isUpper ? c.uppercased() : String(c)
        case .symbol(let c): c == "'" ? "\u{2019}" : c == "\"" ? "\u{201D}" : String(c) // Apple's caps show curly quotes
        case .text(let text): text
        case .shift, .delete, .emoji, .space: ""                         // symbols (`symbol(for:)`); Apple's space bar is blank
        case .toNumbers: "123"
        case .toSymbols: "#+="
        case .toLetters: "ABC"
        case .ret: returnLabel                                          // drawn as a symbol unless it has none
        case .globe: "\u{1F310}"                                        // 🌐
        }
    }

    /// Apple's sizes, measured on the Simulator: small letters larger than capitals, #+= and its 123 smaller than the
    /// bottom row's words.
    private func fontSize(for key: Key) -> CGFloat {
        switch key {
        case .letter: shown.isUpper ? 22 : 25
        case .symbol: 22
        case .toSymbols: 13
        case .toNumbers where layer0 == .symbols: 13
        case .ret where searching: 19.2 // the search's check mark (`searchCheckmark`)
        default: 18
        }
    }

    // MARK: touches

    /// A finger on the keys: the key it is on, whether that key already went in (by rollover, or delete at the touch), and,
    /// after a long press, the alternatives it is choosing among or the trackpad it drives.
    private struct Press {
        var key: Key
        var committed = false
        var callout: Choosing?
        var trackpad: Trackpad?
        /// A quick slide: the key touched first (123, ABC or #+=, which switched at once, or shift) and its layer.
        var slide: (from: Key, layer: KeyLayer)?
    }

    /// Capitals while a finger holds shift, as on Apple's keyboard, even when shift is off.
    private var shown: ShiftState {
        shift == .off && active.values.contains(where: { $0.slide?.from == .shift && !$0.committed }) ? .oneShot : shift
    }

    /// A key's alternatives while they show: where they sit, which one the finger is on (nil outside them), and whether the
    /// finger is so far below the key that letting go types nothing.
    private struct Choosing {
        let alternates: KeyAlternates
        let layout: KeyPopup.Callout
        var choice: Int?
        var cancelled = false
    }

    /// The nearest-key rule covers the globe too: a touch in the gap nearest it goes to its button, and so to
    /// `handleInputModeList`, instead of nowhere. While the trackpad is on, that touch goes to `self` instead: the globe
    /// does nothing then, like every other key (`touchesBegan` already ignores every touch while `trackpadOn`).
    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        guard let globe = globeButton, self.point(inside: point, with: event), layout.hitKey(at: point) == .globe else {
            return super.hitTest(point, with: event)
        }
        return trackpadOn ? self : globe
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        for touch in touches where !trackpadOn { // while the trackpad is on, other fingers do nothing
            // Fast typing: the key already down commits first (touch-down order). It may switch the layer, so this touch is
            // placed on the layer that key leaves behind.
            commitPending()
            guard let key = layout.hitKey(at: touch.location(in: self)), key != .globe else { continue } // the globe button owns its touches
            if layer0.slidesFrom(key) {
                // Apple's quick slide: a layer key switches under the finger at once; shift shows capitals while held.
                active[touch] = Press(key: key, committed: key != .shift, slide: (key, layer0))
                press(key)
                if key == .shift { rebuild() } else { commit(key) }
                continue
            }
            active[touch] = Press(key: key)
            press(key)
            restyle([key])
            if key.isCharacter || key == .space { waitForHold(touch) }
        }
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        for touch in touches {
            guard let press = active[touch] else { continue }
            let point = touch.location(in: self)
            if let slide = press.slide {
                // Sliding from 123, ABC, #+= or shift: a character or space under the finger is the one it will type.
                let key = layout.hitKey(at: point).flatMap { $0.isCharacter || $0 == .space ? $0 : nil } ?? slide.from
                guard key != press.key else { continue }
                active[touch]?.key = key
                if key.isCharacter { showPopup(for: key) } else { popup.isHidden = true }
                announce(key.label(shift: shown, returnLabel: returnLabel)) // VoiceOver hears the key it will type
                continue
            }
            if var trackpad = press.trackpad {
                let steps = trackpad.move(to: point, at: touch.timestamp)
                active[touch]?.trackpad = trackpad
                if steps != (0, 0) { onCursor(steps.characters, steps.lines) }
                continue
            }
            if let callout = press.callout {
                // The choice follows the finger along the alternatives, from their top down to the key's bottom; anywhere
                // else nothing is chosen (the key itself), and far below the key letting go types nothing (Apple's).
                active[touch]?.callout?.cancelled = callout.layout.cancels(at: point)
                let choice = callout.layout.index(at: point)
                guard choice != callout.choice else { continue }
                active[touch]?.callout?.choice = choice
                calloutView.choose(choice)
                if let choice { announce(callout.alternates.items[choice]) }
                continue
            }
            // A key already typed by rollover stays typed: a finger drifting onto another key does not type that one too.
            guard !press.committed, let key = layout.hitKey(at: point), key != .globe, key != press.key else { continue }
            if press.key == .delete {
                // Delete already fired at the touch: sliding off it only stops the repeat, and the release types nothing.
                stopDeleteRepeat()
                active[touch]?.committed = true
                continue
            }
            active[touch] = Press(key: key)
            showPopup(for: key) // delete has none: the letter's balloon goes
            if key == .delete { startDeleteRepeat() }
            restyle([press.key, key])
            if key.isCharacter || key == .space { waitForHold(touch) } else { cancelHold(for: touch) }
        }
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        for touch in touches {
            guard let press = active[touch] else { continue }
            active[touch] = nil
            cancelHold(for: touch)
            if let slide = press.slide {
                if press.key == slide.from {
                    if !press.committed { commit(press.key) } // shift let go where it was touched: a tap
                } else if slide.from == .shift {
                    onShiftSlide(press.key) // one capital
                } else {
                    commit(press.key)          // the key it slid to goes in, and the layer it started on comes back
                    onSlideBack(slide.layer)
                }
                if slide.from == .shift { rebuild() } // the capitals shown while shift was held go back to shift's own state
            } else if press.trackpad != nil { endTrackpad() } // the cursor stays where the finger left it: no space
            else if let callout = press.callout { pick(callout, for: press.key) }
            else if press.key == .delete { stopDeleteRepeat() }
            else if !press.committed { commit(press.key) } // insert on touch-up
            hidePopupIfIdle()
            restyle([press.key])
        }
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        for touch in touches { active[touch] = nil }
        cancelAllHolds()
        calloutView.hide()
        endTrackpad()
        stopDeleteRepeat()
        hidePopupIfIdle()
        rebuild() // every key drawn as it is again, the capitals shown while shift was held too
    }

    /// Apple's long press: after half a second on a character key its alternatives open, if it has any; after a quarter of a
    /// second on space the trackpad starts (`Trackpad.startDelay`). Space keeps its own slot (`trackpadHeld`): a touch
    /// sliding between a character key and space leaves no stale timer behind in the slot it left (`cancelHold`), but a
    /// second finger's touch on the other kind of key no longer cancels this one's pending hold.
    private func waitForHold(_ touch: UITouch) {
        cancelHold(for: touch)
        if active[touch]?.key == .space {
            trackpadHoldTimer?.cancel()
            trackpadHeld = touch
            trackpadHoldTimer = Task { [weak self] in
                try? await Task.sleep(for: Trackpad.startDelay)
                guard !Task.isCancelled else { return }
                self?.holdFired(touch)
            }
        } else {
            holdTimer?.cancel()
            held = touch
            holdTimer = Task { [weak self] in
                try? await Task.sleep(for: .milliseconds(500))
                guard !Task.isCancelled else { return }
                self?.holdFired(touch)
            }
        }
    }

    /// Clears `touch`'s pending hold, in whichever slot it is waiting in.
    private func cancelHold(for touch: UITouch) {
        if held == touch { holdTimer?.cancel(); held = nil }
        if trackpadHeld == touch { trackpadHoldTimer?.cancel(); trackpadHeld = nil }
    }

    /// Clears both pending-hold slots outright, however occupied: for teardown (a cancelled touch, the keyboard going away).
    private func cancelAllHolds() {
        holdTimer?.cancel()
        held = nil
        trackpadHoldTimer?.cancel()
        trackpadHeld = nil
    }

    private func holdFired(_ touch: UITouch) {
        guard let press = active[touch], !press.committed, press.callout == nil, press.trackpad == nil else { return }
        if press.key == .space { return searching ? () : startTrackpad(touch) } // the emoji search's space only types
        guard let alternates = alternates(press.key), let cap = caps[press.key] else { return }
        let layout = KeyPopup.Callout(count: alternates.items.count, start: alternates.start, key: cap.frame, bounds: bounds, top: -frame.minY)
        active[touch]?.callout = Choosing(alternates: alternates, layout: layout, choice: alternates.start)
        popup.isHidden = true
        calloutView.show(alternates.items, layout: layout, choice: alternates.start)
        bringSubviewToFront(calloutView)
        announce(alternates.items[alternates.start])
    }

    /// Space held: the keys fade and the finger moves the cursor, with a tap to feel it start. Another finger's delete
    /// repeat stops, and its pending hold or open alternatives callout hides without typing anything: other fingers do
    /// nothing once the trackpad is on.
    private func startTrackpad(_ touch: UITouch) {
        active[touch]?.trackpad = Trackpad(at: touch.location(in: self), time: touch.timestamp)
        trackpadOn = true
        onTrackpad(true)
        stopDeleteRepeat()
        for (other, press) in active where other != touch {
            cancelHold(for: other)
            if press.callout != nil {
                active[other]?.callout = nil
                active[other]?.committed = true
            }
        }
        calloutView.hide()
        popup.isHidden = true
        impact(light: true)
        rebuild()
    }

    private func endTrackpad() {
        guard trackpadOn else { return }
        trackpadOn = false
        onTrackpad(false)
        rebuild()
    }

    /// The finger let go over the alternatives: the chosen one goes in, the key itself when none is chosen, or nothing far
    /// below the key. A chosen alternate gets the same commit-point bookkeeping as a tapped key: the apostrophe's alternates
    /// (`‘ ’ '`) flip the layer back to letters exactly as the key itself does (`KeyLayer.after`, `Shared/Keyplane.swift`),
    /// so VoiceOver's refocus-after-layer-change (`configure(...)` above) must land on the apostrophe, not on whatever was
    /// last tapped.
    private func pick(_ callout: Choosing, for key: Key) {
        calloutView.hide()
        guard !callout.cancelled else { return } // let go far below the key: nothing, as on Apple's
        if let choice = callout.choice {
            markCommitPoint(key)
            onAlternate(key, callout.alternates.items[choice])
        } else {
            commit(key)
        }
    }

    /// VoiceOver hears each alternative as the finger reaches it (double-tap and hold passes the finger through).
    private func announce(_ text: String) {
        if UIAccessibility.isVoiceOverRunning { UIAccessibility.post(notification: .announcement, argument: text) }
    }

    /// The keyboard went away mid-press (the old SwiftUI delete key's `.onDisappear` rule): a held delete never keeps going
    /// in a hidden keyboard, and no press is left behind for the next time it shows.
    override func didMoveToWindow() {
        super.didMoveToWindow()
        guard window == nil else {
            lightImpact.prepare() // on screen: the first press is felt at once too
            mediumImpact.prepare()
            return
        }
        cancelTouches()
    }

    /// Lets go of every key still under a finger: none commits when it is let go, no long press, alternatives or trackpad
    /// is left open, delete stops repeating and the popup goes. The keyboard going away does it, and so does the end of an
    /// emoji search, before the keys type into the text again: a delete or a letter held from the search would otherwise
    /// go on into the text.
    func cancelTouches() {
        active.removeAll()
        cancelAllHolds()
        calloutView.hide()
        endTrackpad()
        stopDeleteRepeat()
        popup.isHidden = true
    }

    /// A key was touched down: feedback and popup now; delete starts repeating; other keys insert on release.
    private func press(_ key: Key) {
        UIDevice.current.playInputClick()
        impact(light: key.isCharacter)
        showPopup(for: key) // a key without one (delete) hides the one before
        if key == .delete { startDeleteRepeat() }
    }

    /// A light haptic for a character key (and the mic, from the controller), a medium one for a function key.
    func impact(light: Bool) {
        let generator = light ? lightImpact : mediumImpact
        generator.impactOccurred()
        generator.prepare() // ready for the next press
    }

    /// Commits every still-pressed non-delete key that has not committed yet (rollover on the next touch-down); a key with
    /// its alternatives open gives the one chosen. A slide from shift ends here: the letter it reached goes in once, as a
    /// capital (shift still under the finger is a tap), and its lift types nothing more.
    private func commitPending() {
        for (touch, press) in active where !press.committed && press.key != .delete {
            active[touch]?.committed = true
            active[touch]?.callout = nil
            active[touch]?.slide = nil
            if let callout = press.callout {
                pick(callout, for: press.key)
            } else if press.slide?.from == .shift, press.key != .shift {
                onShiftSlide(press.key)
                rebuild() // the capitals shown while shift was held go back to shift's own state
            } else {
                commit(press.key)
            }
        }
    }

    /// Every committed key, from a touch, the delete repeat or VoiceOver, goes through here: it remembers where the key sat.
    fileprivate func commit(_ key: Key) {
        markCommitPoint(key)
        onKey(key)
    }

    /// Where `key` sat, so VoiceOver's focus can land on the key in its place if the layer changes underneath it.
    private func markCommitPoint(_ key: Key) {
        if let frame = layout.frames.first(where: { $0.key == key })?.frame { lastCommitPoint = CGPoint(x: frame.midX, y: frame.midY) }
    }

    /// Delete at the touch, then Apple's repeat: characters, then whole words.
    private func startDeleteRepeat() {
        guard deleteRepeat == nil else { return }
        commit(.delete)
        deleteRepeat = Task { [weak self] in
            for n in 1... {
                let step = DeleteRepeat.step(n)
                try? await Task.sleep(for: step.wait)
                guard !Task.isCancelled, let self else { return } // a torn-down keyboard ends the loop too
                if step.word { self.onDeleteWord() } else { self.commit(.delete) }
            }
        }
    }

    private func stopDeleteRepeat() {
        deleteRepeat?.cancel()
        deleteRepeat = nil
    }

    // MARK: popup

    /// A letter's or symbol's balloon, rising from its key (the top row's over the bar, never above the keyboard).
    private func showPopup(for key: Key) {
        guard key.isCharacter, !kind.isDigitPad, let cap = caps[key] else { popup.isHidden = true; return } // Apple's digit pad has none
        popup.show(KeyPopup.Balloon(key: cap.frame, bounds: bounds, top: -frame.minY, sideInset: KeyLayout.sideInset), text: title(for: key))
        bringSubviewToFront(popup)
    }

    private func hidePopupIfIdle() {
        if active.isEmpty { popup.isHidden = true }
    }

    /// Apple's iOS 26 key color, every key alike (measured on the Simulator): white, or a dark gray in dark mode.
    static let letterKeyColor = UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 61 / 255, alpha: 1) : .white }
    /// A function key while pressed, and a digit pad key while pressed.
    static let pressedKeyColor = UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 125 / 255, alpha: 1)
        : UIColor(red: 196 / 255, green: 197 / 255, blue: 200 / 255, alpha: 1) }
    static let padPressedColor = UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 92 / 255, alpha: 1)
        : UIColor(red: 233 / 255, green: 235 / 255, blue: 239 / 255, alpha: 1) }
    /// The keys while the trackpad is on: faded about halfway into the keyboard behind them.
    static let fadedKeyColor = UIColor { letterKeyColor.resolvedColor(with: $0).withAlphaComponent(0.55) }
}

/// One key's accessibility element: the keyboard-key trait for VoiceOver and an identifier for UI tests. VoiceOver's
/// double-tap inserts the key through `onKey`; a UI-test tap lands in the key's frame and the view's touch handling inserts.
@MainActor final class KeyAccessibilityElement: UIAccessibilityElement {
    private weak var view: KeyplaneView?
    private let key: Key

    init(container: KeyplaneView, key: Key) {
        self.view = container
        self.key = key
        super.init(accessibilityContainer: container)
    }

    override func accessibilityActivate() -> Bool {
        guard let view else { return false }
        view.commit(key)
        return true
    }
}
