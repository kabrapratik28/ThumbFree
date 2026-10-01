import SwiftUI
import TFCore
import UIKit

/// The Apple-style keyboard: a bar (status, mic, delivery chip) above a UIKit keyplane
/// (letters, the 123 and #+= layers, shift, delete, space, return, and the globe only when needed). It owns the text:
/// it turns each key the keyplane reports into an edit, keeps the layer and shift state, and does double space. It
/// keeps every earlier keyboard behavior (the mic's take handshake, the no-model state, the cold take, the stale
/// status, hasDictationKey). Automatic return lives behind `TF_AUTO_RETURN`: it resolves the trusted host and puts it
/// in the dictate link.
final class KeyboardViewController: UIInputViewController {
    private lazy var client = KeyboardClient(shared: (try? AppGroup.ipcDirectory()).map { SharedStore(directory: $0) }) { [weak self] url, done in
        guard let self else { return done(false) }
        AppOpener.open(url, from: self) { opened in
            if !opened { UIAccessibility.post(notification: .announcement, argument: KeyState.openFailed.text) }
            done(opened)
        }
    }
    private var statusObserver: DarwinObserver?

    private let keyplane = KeyplaneView()
    private var heightConstraint: NSLayoutConstraint?
    private var barHeightConstraint: NSLayoutConstraint?
    private var keyplaneTop: NSLayoutConstraint?
    private var barView: UIView?
    /// The emoji picker, in the keys' place while it shows. Made when the emoji key is tapped and let go on ABC, so no
    /// emoji cells stay in memory while the keys show; how far it was scrolled is kept, to reopen there.
    private var emojiPicker: EmojiPickerView?
    private var emojiOffset: CGFloat?
    /// Emoji search: the bar's field and its query, and the results row above the keys while the keys type into it.
    private let emojiBar = EmojiBarState()
    private var emojiResults: EmojiResultsView?
    /// Apple's suggestions while typing: Apple's spelling dictionary, and what the bar shows in the status's place.
    private let speller = Speller()
    private let suggestions = SuggestionBarState()
    /// From the first key typed until the mic is used or the keyboard shows again, the bar keeps the suggestions' places
    /// (blank between words: Apple's bar shows next-word predictions there, which this keyboard does not make).
    private var isTyping = false
    /// What the bar's suggestions are for: the text at the caret they came from (`key`: the same text again, a status
    /// refresh or iOS's late notice of this keyboard's own edit, does not ask the dictionary again) and the word they
    /// replace (`token` when it is a text replacement's shortcut), which a tap checks is still what ends at the caret.
    private var suggestedFor: (key: String, word: String?, token: Bool)?
    /// iOS's lexicon requests: only the latest answer is taken, and nothing is corrected until it has come.
    private var lexiconRequests = LexiconRequests()
    /// The end of the text before the caret after this keyboard's last edit (`tail`), so a change of the text or the
    /// caret from outside (a tap elsewhere, the app's own edit) can be told from iOS's late notice of the keyboard's own.
    private var editTail: String?
    /// Right after this keyboard typed a character (a letter, digit or mark) at the caret, the end of the text before the
    /// caret (`tail`): only text typed here, with nothing moved since, is corrected at the word's end. Nil after it.
    private var wordTail: String?
    /// The last autocorrection, while a delete can still undo it: the word as typed, what replaced it, and the end of the
    /// text before the caret right after the space, return or mark that followed it.
    private var lastCorrection: (typed: String, fix: String, tail: String?)?
    /// That space, return or mark was deleted: until anything else is typed, the bar's first place offers the typed word
    /// back (Apple's undo). `tail` is the end of the text before the caret right after the delete.
    private var undo: (typed: String, fix: String, tail: String?)?
    /// Right after a tapped suggestion and its space, the end of the text before the caret (`tail`): as on Apple's
    /// keyboard, a space typed next is dropped and a punctuation mark takes the space's place. Nil otherwise.
    private var spaceAfterPick: String?

    /// The layer on screen. Every way back to the letters (a key, a slide, a search's start and end) starts Apple's 123
    /// rule again.
    private var layer = KeyLayer.letters { didSet { if layer == .letters { typedOnLayer = false } } }
    /// A key was typed on 123 or #+= since the letters: a space or a return then goes back to the letters (Apple's rule).
    private var typedOnLayer = false
    /// The field's kind of keyboard (email, web address, number, ...), and the field it was read for: a new field opens
    /// where Apple's keyboard opens for it.
    private var kind = KeyboardKind.text
    private var openedFor: String?
    private var shift = ShiftKey()
    /// Shift as it was the moment a search started (caps lock included), so ending the search restores it: Apple's own
    /// search never leaves caps lock on just because it was set during the search.
    private var shiftBeforeSearch: ShiftKey?
    /// When this keyboard last typed a space, on a monotonic clock (a clock change never makes a double space), and the
    /// end of the text before the caret just after it (`tail`), so a change from outside can be told from a late notice
    /// of that same space.
    private var lastSpaceAt: ContinuousClock.Instant?
    private var afterLastSpace: String?

    #if TF_AUTO_RETURN
    private let arbiter = HostArbiter()
    #endif

    override func viewDidLoad() {
        super.viewDidLoad()

        let root = KeyboardInputView(frame: view.bounds, inputViewStyle: .keyboard)
        root.allowsSelfSizing = true
        inputView = root

        let barHost = UIHostingController(rootView: makeBar())
        barHost.view.backgroundColor = .clear
        barHost.view.translatesAutoresizingMaskIntoConstraints = false
        keyplane.translatesAutoresizingMaskIntoConstraints = false
        addChild(barHost) // the parent retains the child; no stored property needed
        root.addSubview(barHost.view)
        root.addSubview(keyplane)
        keyplane.onKey = { [weak self] key in self?.handleKey(key) }
        keyplane.alternates = { [weak self] key in self?.alternates(for: key) }
        keyplane.onAlternate = { [weak self] key, text in self?.handleKey(key, typing: text) }
        keyplane.onCursor = { [weak self] characters, lines in self?.moveCursor(characters: characters, lines: lines) }
        keyplane.onDeleteWord = { [weak self] in self?.deleteWord() }
        keyplane.onSlideBack = { [weak self] layer in self?.slideBack(to: layer) }
        keyplane.onShiftSlide = { [weak self] key in self?.shiftSlide(to: key) }
        keyplane.onTrackpad = { [weak self] on in self?.barView?.isUserInteractionEnabled = !on } // the mic and bar wait too
        keyplane.onGlobe = { [weak self] view, event in self?.handleInputModeList(from: view, with: event) } // tap advances, long press lists

        let height = root.heightAnchor.constraint(equalToConstant: 260)
        height.priority = .required
        heightConstraint = height
        let barHeight = barHost.view.heightAnchor.constraint(equalToConstant: 48)
        barHeightConstraint = barHeight
        let top = keyplane.topAnchor.constraint(equalTo: barHost.view.bottomAnchor)
        keyplaneTop = top
        barView = barHost.view
        NSLayoutConstraint.activate([
            height,
            barHeight,
            barHost.view.topAnchor.constraint(equalTo: root.topAnchor),
            barHost.view.leadingAnchor.constraint(equalTo: root.leadingAnchor),
            barHost.view.trailingAnchor.constraint(equalTo: root.trailingAnchor),
            top,
            keyplane.leadingAnchor.constraint(equalTo: root.leadingAnchor),
            keyplane.trailingAnchor.constraint(equalTo: root.trailingAnchor),
            keyplane.bottomAnchor.constraint(equalTo: root.bottomAnchor),
        ])
        barHost.didMove(toParent: self)
        render()
    }

    private func makeBar() -> KeyboardBar {
        KeyboardBar(model: client, emoji: emojiBar, suggestions: suggestions,
                    micDown: { [weak self] in self?.micDown() },
                    micUp: { [weak self] in self?.micUp() },
                    insertHere: { [weak self] in self?.insertHere() },
                    // Copy and Dismiss take the chip away: the places made behind it, where nothing was lit or corrected,
                    // come up to date, lit where the word's end will correct, as after Insert here.
                    copy: { [weak self] in self?.client.copy(); self?.updateSuggestions() },
                    dismiss: { [weak self] in self?.client.dismiss(); self?.updateSuggestions() },
                    searchEmoji: { [weak self] in self?.startSearch() },
                    clearSearch: { [weak self] in self?.searchKey(nil) },
                    pickSuggestion: { [weak self] in self?.pickSuggestion($0) })
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        // This keyboard's mic replaces iOS's own, so iOS hides its dictation key under it. iOS reads this as the keyboard
        // appears: set in viewDidLoad alone, its mic still showed.
        hasDictationKey = true
        client.fullAccess = hasFullAccess
        if hasFullAccess, let directory = try? AppGroup.ipcDirectory() { KeyboardMark.record(in: directory) } // the app's Setup reads it
        if client.pressing { micUp() } // defensive: a mic press whose release was lost the last time this view showed
        client.appeared()
        if statusObserver == nil {
            statusObserver = DarwinObserver(DarwinName.status) { [weak self] in self?.refresh() }
        }
        #if TF_AUTO_RETURN
        if readsHost { arbiter.connectAndHarvest() } // register again (the arbiter forgets a keyboard off screen), harvest
        #endif
        isTyping = false // the status shows until the first key
        endTyping()
        #if DEBUG
        let group = UserDefaults(suiteName: Brand.appGroupID)
        if let token = group?.string(forKey: LearnedWords.resetKey), token != UserDefaults.standard.string(forKey: LearnedWords.resetKey) {
            UserDefaults.standard.removeObject(forKey: LearnedWords.key) // a UI test's clean start
            UserDefaults.standard.set(token, forKey: LearnedWords.resetKey)
        }
        #endif
        speller.learned = LearnedWords(stored: UserDefaults.standard.stringArray(forKey: LearnedWords.key))
        suggestedFor = nil
        requestLexicon()
        refresh()
        followKind()
        followField() // a capital at the start of an empty field or a sentence, before the first key
        render()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        speller.warmUp() // once the keys show: the dictionary's data loads in the background before the first word
    }

    // `needsInputModeSwitchKey` can change after the view first appears (rotation), so `render` re-reads it here. In
    // landscape (a compact vertical size class) the keyboard is shorter, and so is the bar, so the keys keep a usable height.
    // Both heights are Apple's, measured on the iOS 26.5 Simulator on the iPhone 16 and the iPhone 17 Pro: the letters are
    // Apple's letters keyboard with its suggestions strip, whose place the bar takes (260 pt, 188 in landscape), and the
    // emoji picker is Apple's Emoji keyboard (313 pt, 251 in landscape, its top on Apple's on the iPhone Air too), whatever
    // the letters' height. Its search goes back to the letters' height and adds Apple's results row above the keys.
    override func viewWillLayoutSubviews() {
        super.viewWillLayoutSubviews()
        let compact = traitCollection.verticalSizeClass == .compact
        let resultsRow: CGFloat = emojiResults == nil ? 0 : (compact ? 38 : 41)
        let letters: CGFloat = compact ? 188 : 260, emoji: CGFloat = compact ? 251 : 313
        heightConstraint?.constant = emojiPicker != nil && emojiResults == nil ? emoji : letters + resultsRow
        barHeightConstraint?.constant = compact ? 36 : 48
        keyplaneTop?.constant = resultsRow
        render()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        if client.pressing { micUp() } // the key's release would be lost otherwise
        if emojiBar.query != nil { endSearch() } // never comes back mid-search: the letters, or the picker as it was left
        statusObserver = nil // a hidden keyboard never answers for a take: only the one on screen types or holds back
    }

    override func textDidChange(_ textInput: (any UITextInput)?) {
        super.textDidChange(textInput)
        fieldChanged()
    }

    /// The caret moved without the text changing: taken as a text change. On iOS 26.5 such a move comes as `textDidChange`
    /// (a tap in the text box, in the Try tab and in a Safari text area alike); a host that tells this instead still ends
    /// the search and a double space.
    override func selectionDidChange(_ textInput: (any UITextInput)?) {
        super.selectionDidChange(textInput)
        fieldChanged()
    }

    /// The app's text or caret changed from outside, or the field did.
    private func fieldChanged() {
        #if TF_AUTO_RETURN
        if readsHost { arbiter.harvest() } // the caret or the host may have changed
        #endif
        let tail = Self.tail(textDocumentProxy.documentContextBeforeInput)
        // A change from outside (the caret moved, the app changed the text) ends a double space in progress, and the word
        // being typed (a pending correction, its undo, a tapped suggestion's space). A late notice of this keyboard's own
        // edit finds the end of the text before the caret as it was just after that edit.
        if tail != afterLastSpace { lastSpaceAt = nil; afterLastSpace = nil }
        if tail != editTail { endTyping() }
        // A correction and its undo end at any outside change, even one that leaves the same end of text (the caret moved
        // to another spot that ends alike, where a delete must not put back a word that is not there).
        lastCorrection = nil
        undo = nil
        followKind() // a new field: its layout
        // It ends Search Emoji too, as Done does and as Apple's does: the text box tapped (iOS tells the keyboard even when
        // the caret stays put), the caret moved, the app changed its text. Delete and every key then act on the text again.
        // The keyboard's own inserts (a key, a picked result) bring no such notice (checked on the iOS 26.5 Simulator), so a
        // pick keeps the search up, as Apple's does, and a tap right after one still ends it.
        if emojiBar.query != nil { endSearch() }
        followField() // a new field, or the user moved the caret: the automatic capital follows
        updateSuggestions()
        render()
    }

    /// The app's status changed or the keyboard appeared. The client may have typed a take's text: the capital follows it.
    private func refresh() {
        let before = textDocumentProxy.documentContextBeforeInput
        client.refresh(textDocumentProxy)
        if textDocumentProxy.documentContextBeforeInput != before { endTyping() } // a take's text went in
        followField()
        updateSuggestions()
        render()
    }

    /// While the keys type into the emoji search they are Apple's search keys: the letters of a text field, no emoji key,
    /// a blue done key with a check mark (VoiceOver reads "Done").
    private func render() {
        let searching = emojiBar.query != nil
        keyplane.configure(layer: layer, kind: searching ? .text : kind, shift: shift.state, showsGlobe: needsInputModeSwitchKey,
                           searching: searching, returnKey: textDocumentProxy.returnKeyType ?? .default)
        emojiPicker?.showsGlobe = needsInputModeSwitchKey // it can change while the picker shows (rotation), as for the keys
    }

    // MARK: the mic

    private func micDown() {
        // A tap that closed a tone picker does nothing else: the next one starts or stops a take. Read once, so a VoiceOver
        // or Voice Control activation (no touch to reset it) never finds it left over from an earlier touch.
        if emojiPicker?.touchClosedPopup == true { emojiPicker?.touchClosedPopup = false; return }
        #if TF_AUTO_RETURN
        client.hostBundleID = resolveHost()
        #endif
        client.pressDown(textDocumentProxy)
        keyplane.impact(light: true) // instant feedback; the app decides the take
        isTyping = false // the status shows the take
        endTyping()
        updateSuggestions()
    }

    private func micUp() { client.pressUp(textDocumentProxy) }

    private func insertHere() {
        client.insertHere(textDocumentProxy)
        endTyping() // a take's text went in
        followField()
        updateSuggestions() // the places were for the word before it
        render()
    }

    #if TF_AUTO_RETURN
    /// The arbiter is read only when a link could carry the host: with Full Access (without it no link opens ThumbFree,
    /// and the setting in the App Group cannot even be read, so it would read as on) and with the setting on.
    private var readsHost: Bool { hasFullAccess && AutoReturn.enabled }

    /// The trusted host for automatic return: table[hostPid], only when the arbiter may be read. nil leaves the link
    /// without a host, so the app shows the swipe-back screen.
    private func resolveHost() -> String? {
        guard readsHost, let pid = HostArbiter.hostPid(of: self) else { return nil }
        return arbiter.trustedHost(hostPid: pid) // harvests fresh and trusts only a pid it sees this tap
    }
    #endif

    // MARK: the keys

    /// `text` is an alternative picked for `key` after a long press (é for e), typed in its place.
    private func handleKey(_ key: Key, typing text: String? = nil) {
        if emojiBar.query != nil { return searchKey(key, typing: text) } // the keys type into the emoji search
        let proxy = textDocumentProxy
        if key != .shift { shift.otherKey() } // only two shift taps in a row lock caps (never a stray earlier tap)
        // An edit (the layer keys and shift are none, so 123 then a comma still follows a tapped suggestion) ends the
        // tapped suggestion's space, and shows the suggestions.
        let edits = key.isCharacter || [.space, .delete, .ret].contains(key)
        let afterPick = edits && spaceAfterPick != nil && spaceAfterPick == Self.tail(proxy.documentContextBeforeInput) // not if the caret moved
        let pending = edits ? lastCorrection : nil // an edit ends the chance to undo a correction, except a delete of its space
        if edits {
            spaceAfterPick = nil
            isTyping = true
            lastCorrection = nil
            undo = nil
        }
        // Apple's autocorrection at a word's end: a space, a return or a punctuation mark.
        var corrected: (typed: String, fix: String)?
        if key == .space && !afterPick || key == .ret { corrected = correctWord() }
        if case .symbol(let character) = key, text == nil, ".,?!;:".contains(character) { corrected = correctWord(before: character) }
        switch key {
        case .letter(let character):
            // A letter right after a lone "i" that its full stop made "I.": an abbreviation ("i.e."), so the "i" goes back.
            if let pending, pending.typed == "i", pending.tail == Self.tail(proxy.documentContextBeforeInput),
               proxy.documentContextBeforeInput?.hasSuffix("I.") == true {
                for _ in 0..<2 { proxy.deleteBackward() }
                proxy.insertText("i.")
            }
            proxy.insertText(text ?? (shift.state.isUpper ? character.uppercased() : String(character)))
            shift.typedLetter()
            lastSpaceAt = nil
        case .symbol(let character):
            if afterPick, ".,?!;:".contains(character) { proxy.deleteBackward() } // the mark takes the tapped word's space
            let before = proxy.documentContextBeforeInput ?? ""
            let smart = FieldTraits.smartPunctuation(quotes: proxy.smartQuotesType, dashes: proxy.smartDashesType, type: proxy.keyboardType)
            if text == nil, smart.dashes, SmartPunctuation.makesDash(character, after: before) {
                proxy.deleteBackward()
                proxy.insertText("\u{2014}") // Apple's dash for two hyphens
            } else {
                proxy.insertText(text ?? String(smart.quotes ? SmartPunctuation.quote(character, after: before) : character))
            }
            lastSpaceAt = nil
        case .text(let string):
            proxy.insertText(text ?? string)
            lastSpaceAt = nil
        case .shift:
            shift.tap(at: Date())
        case .delete:
            let inWord = wordTail != nil && wordTail == Self.tail(proxy.documentContextBeforeInput)
            let undoes = pending.map { $0.tail == Self.tail(proxy.documentContextBeforeInput) } ?? false
            proxy.deleteBackward()
            lastSpaceAt = nil
            if inWord { wordTail = Self.tail(proxy.documentContextBeforeInput) } // fixing a typo in the word being typed
            if undoes, let pending { undo = (pending.typed, pending.fix, Self.tail(proxy.documentContextBeforeInput)) }
        case .toNumbers, .toSymbols, .toLetters:
            lastSpaceAt = nil
        case .space:
            if !afterPick { insertSpace() } // a tapped suggestion already put its space
        case .ret:
            proxy.insertText("\n")
            lastSpaceAt = nil
        case .globe:
            advanceToNextInputMode()
        case .emoji:
            lastSpaceAt = nil
        }
        if key.isCharacter { wordTail = Self.tail(proxy.documentContextBeforeInput) } // typed here: its word's end may correct it
        if let corrected { lastCorrection = (corrected.typed, corrected.fix, Self.tail(proxy.documentContextBeforeInput)) }
        advanceLayer(after: key)
        if key == .emoji { showEmojiPicker() } else if key == .toLetters { hideEmojiPicker() } // ABC leaves the picker
        switch key {
        case .shift, .toNumbers, .toSymbols, .toLetters, .globe, .emoji:
            // No edit, so nothing to follow. A follow here could even turn back on an automatic capital a shift tap just
            // turned off, when the app moved the caret without telling the keyboard.
            break
        default:
            followField()
        }
        #if TF_AUTO_RETURN
        if readsHost { arbiter.harvest() } // the caret may have moved
        #endif
        editTail = Self.tail(proxy.documentContextBeforeInput)
        updateSuggestions()
        render()
    }

    /// The switch keys, and back to letters after an apostrophe on 123 or #+=, or a space or return once a key was typed
    /// there (Apple's 123 rule, for the text and the emoji search alike).
    private func advanceLayer(after key: Key) {
        layer = layer.after(key, typedHere: typedOnLayer)
        if layer != .letters, key.isCharacter || key == .delete { typedOnLayer = true }
    }

    /// Apple's quick slide: after a key typed by sliding from 123, ABC or #+=, the layer the slide started on comes back.
    private func slideBack(to start: KeyLayer) {
        layer = start
        render()
    }

    /// Apple's slide from shift: the letter goes in as a capital, then shift is as it was before the press (off stays off).
    private func shiftSlide(to key: Key) {
        guard case .letter(let character) = key else { return handleKey(key) }
        let before = shift.state
        handleKey(key, typing: character.uppercased())
        shift.restore(before)
        render()
    }

    /// Delete held past twenty characters takes whole words: the word before the cursor and the spaces before it.
    private func deleteWord() {
        if var query = emojiBar.query { // the emoji search's query loses a word too
            query.removeLast(min(DeleteRepeat.wordLength(before: query), query.count))
            emojiBar.query = query
            emojiResults?.show(EmojiSearch.results(for: query, used: emojiUsage().picked(at: Date())))
            return render()
        }
        let proxy = textDocumentProxy
        for _ in 0..<DeleteRepeat.wordLength(before: proxy.documentContextBeforeInput ?? "") { proxy.deleteBackward() }
        shift.otherKey()
        lastSpaceAt = nil
        editTail = Self.tail(proxy.documentContextBeforeInput)
        followField()
        updateSuggestions()
        render()
    }

    /// The space bar's trackpad: the cursor moves along the text by characters, and up or down through the line breaks the
    /// keyboard can see (inside one wrapped paragraph it sees none, so it moves only along it).
    private func moveCursor(characters: Int, lines: Int) {
        let proxy = textDocumentProxy
        let before = proxy.documentContextBeforeInput ?? "", after = proxy.documentContextAfterInput ?? ""
        let steps = characters + (lines == 0 ? 0 : Trackpad.offset(lines: lines, before: before, after: after) ?? 0)
        let offset = Trackpad.utf16Offset(steps, before: before, after: after)
        guard offset != 0 else { return }
        proxy.adjustTextPosition(byCharacterOffset: offset)
        lastSpaceAt = nil
        endTyping() // the caret moved: no word is being typed there
        followField() // at a sentence start, the capital comes on
        updateSuggestions()
        render()
    }

    /// Apple's alternatives for a long press on `key`, on the keys as they show now (the emoji search types with a text
    /// field's keys), in capitals while shift is on.
    private func alternates(for key: Key) -> KeyAlternates? {
        KeyAlternates.of(key, kind: emojiBar.query != nil ? .text : kind, layer: layer, upper: shift.state.isUpper)
    }

    // MARK: suggestions

    /// Ends what the keyboard knows about the word being typed (a tapped suggestion's space, whether it was typed here,
    /// the last correction and its undo): the keyboard showed again, the mic was used, the caret moved, a take's text or
    /// an emoji went in, or the text changed from outside.
    private func endTyping() {
        spaceAfterPick = nil
        wordTail = nil
        lastCorrection = nil
        undo = nil
    }

    /// The bar's status place is the suggestions', in `KeyboardBar`'s order: not while the delivery chip, the Search Emoji
    /// field (the picker is up) or the recording line (a take records) has it.
    private var barShowsSuggestions: Bool { client.chip == nil && !emojiBar.showsSearch && client.state != .listening }

    /// The bar's suggestions for the word at the caret while typing; none (the status) otherwise and on Apple's digit pad.
    /// The autocorrection is lit only for a word typed here in a field that lets the keyboard correct, once iOS's current
    /// lexicon is in (a contact's word is never corrected because it came late), and only while the bar shows the places:
    /// behind the delivery chip, the Search Emoji field or the recording line nothing lit or an undo could be seen, so
    /// nothing is corrected (a text replacement's shortcut, which the lit place types, stays too).
    private func updateSuggestions() {
        guard isTyping, !kind.isDigitPad else {
            suggestions.slots = nil
            suggestedFor = nil
            return
        }
        let proxy = textDocumentProxy
        let before = proxy.documentContextBeforeInput ?? "", after = proxy.documentContextAfterInput ?? ""
        let corrects = lexiconRequests.current && barShowsSuggestions
            && FieldTraits.corrects(proxy.keyboardType, autocorrection: proxy.autocorrectionType)
            && wordTail != nil && wordTail == Self.tail(before)
        if let offer = undo, offer.tail != Self.tail(before) { undo = nil } // the caret moved or the text changed
        let key = "\(corrects)|\(undo?.typed ?? "")|\(Self.tail(before) ?? "")|\(after.prefix(1))"
        guard suggestions.slots == nil || suggestedFor?.key != key else { return }
        let found = speller.suggestions(before: before, after: after, corrects: corrects)
        suggestedFor = (key, found?.word, found?.token ?? false)
        var slots = found?.slots ?? []
        if FieldTraits.smartPunctuation(quotes: proxy.smartQuotesType, dashes: proxy.smartDashesType, type: proxy.keyboardType).quotes {
            slots = slots.map { $0.kind == .typed ? $0 : Suggestion(kind: $0.kind, text: SmartPunctuation.curly($0.text)) } // don’t, as Apple's
        }
        // Apple's undo, in the typed word's place (also after a phrase, where no word ends at the caret: "On my way!").
        if let offer = undo { slots = [Suggestion(kind: .undo, text: offer.typed)] + slots.dropFirst() }
        suggestions.slots = slots
    }

    /// Apple's autocorrection at a word's end: the lit suggestion takes the typed word's place before the space, return or
    /// punctuation mark (`mark`) goes in. What the bar lit is what the end types, and only in place of the word it was lit
    /// for (a lone "i" before a full stop too: a letter right after it puts the "i" back, "i.e."). Returns the word as
    /// typed and its correction.
    private func correctWord(before mark: Character? = nil) -> (typed: String, fix: String)? {
        updateSuggestions() // the bar may not have seen a change from outside yet
        wordTail = nil
        let proxy = textDocumentProxy
        guard let fix = suggestions.slots?.first(where: { $0.kind == .correction })?.text, let typed = suggestedFor?.word,
              (proxy.documentContextBeforeInput ?? "").hasSuffix(typed) else { return nil }
        for _ in 0..<typed.count { proxy.deleteBackward() }
        proxy.insertText(fix)
        return (typed, fix)
    }

    /// iOS's supplementary lexicon (text replacements, contacts' words), each time the keyboard shows, so a replacement added
    /// in Settings works the next time, with ThumbFree's Dictionary from the App Group (with Full Access). iOS answers on a
    /// background queue although the SDK marks `UILexicon` main-actor (a closure formed on the main actor traps there:
    /// seen on the Simulator), so the closure is `@Sendable` and hops to the main actor to read it. Until the answer to
    /// this request comes, the older lexicon is gone (no Dictionary words kept after Full Access went) and nothing is
    /// corrected; an older request's late answer is dropped.
    private func requestLexicon() {
        speller.lexicon = Lexicon()
        let request = lexiconRequests.start()
        requestSupplementaryLexicon { @Sendable [weak self] lexicon in
            Task { @MainActor [weak self] in
                guard let self, lexiconRequests.answered(request) else { return }
                let dictionary = hasFullAccess ? UserDefaults(suiteName: Brand.appGroupID)?.stringArray(forKey: Lexicon.dictionaryKey) ?? [] : []
                speller.lexicon = Lexicon(pairs: lexicon.entries.map { ($0.userInput, $0.documentText) }, dictionary: dictionary)
                suggestedFor = nil
                updateSuggestions()
                render()
            }
        }
    }

    /// Keeps a word the user chose over a correction, in the keyboard's own defaults: never corrected again.
    private func learn(_ word: String) {
        speller.learned.learn(word)
        UserDefaults.standard.set(speller.learned.words, forKey: LearnedWords.key)
    }

    /// A tapped suggestion: its word in the typed word's place, then a space, as on Apple's keyboard. The typed word, in
    /// quotes, stays as it is, gets its space and is kept (never corrected after). The undo place puts the word back as it
    /// was typed, with no space, as Apple's undo does, and keeps it. A tap on places made for another word or text (it
    /// changed in between) only refreshes them.
    private func pickSuggestion(_ index: Int) {
        let proxy = textDocumentProxy
        let before = proxy.documentContextBeforeInput ?? ""
        guard let slots = suggestions.slots, slots.indices.contains(index) else { return }
        let pick = slots[index]
        if pick.kind == .undo {
            // Only while the text still ends with the correction, just where the delete left it.
            guard let offer = undo, offer.tail == Self.tail(before), before.hasSuffix(offer.fix) else { return updateSuggestions() }
            for _ in 0..<offer.fix.count { proxy.deleteBackward() }
            proxy.insertText(offer.typed)
            learn(offer.typed)
            undo = nil
            wordTail = nil
            editTail = Self.tail(proxy.documentContextBeforeInput)
            followField()
            updateSuggestions()
            return render()
        }
        guard let placed = suggestedFor, let typed = placed.word,
              Typing.stillAt(typed, token: placed.token, before: before, after: proxy.documentContextAfterInput ?? "") else {
            return updateSuggestions()
        }
        if pick.kind == .typed {
            if !speller.knows(typed, before: before) { learn(typed) }
        } else {
            for _ in 0..<typed.count { proxy.deleteBackward() }
            proxy.insertText(pick.text)
        }
        proxy.insertText(" ")
        spaceAfterPick = Self.tail(proxy.documentContextBeforeInput)
        editTail = spaceAfterPick
        wordTail = nil
        shift.otherKey()
        lastSpaceAt = nil
        followField()
        updateSuggestions()
        render()
    }

    // MARK: the emoji picker

    /// The emoji key: the picker takes the keys' place below the bar, which keeps the mic and shows the search field.
    private func showEmojiPicker() {
        guard emojiPicker == nil, let root = inputView, let barView else { return }
        let picker = EmojiPickerView(sections: EmojiCatalog.sections(recents: emojiUsage().ranked(at: Date())), chosen: chosenTones,
                                      showsGlobe: needsInputModeSwitchKey)
        picker.onEmoji = { [weak self] emoji in self?.insertEmoji(emoji) }
        picker.onTone = { [weak self] base, choice in
            guard let self else { return }
            var tones = chosenTones
            EmojiTones.choose(choice, for: base, in: &tones)
            chosenTones = tones
            insertEmoji(choice)
        }
        picker.onKey = { [weak self] key in self?.handleKey(key) }
        picker.onPress = { [weak self] light in self?.feedback(light: light) }
        picker.onGlobe = { [weak self] view, event in self?.handleInputModeList(from: view, with: event) } // tap advances, long press lists
        picker.startOffset = emojiOffset // where it was left, as Apple's reopens
        picker.translatesAutoresizingMaskIntoConstraints = false
        root.addSubview(picker)
        NSLayoutConstraint.activate([
            picker.topAnchor.constraint(equalTo: barView.bottomAnchor),
            picker.leadingAnchor.constraint(equalTo: root.leadingAnchor),
            picker.trailingAnchor.constraint(equalTo: root.trailingAnchor),
            picker.bottomAnchor.constraint(equalTo: root.bottomAnchor),
        ])
        keyplane.isHidden = true // hidden keys leave VoiceOver and the UI tests too
        emojiPicker = picker
        emojiBar.showsSearch = true
        view.setNeedsLayout() // Apple's height
        UIAccessibility.post(notification: .layoutChanged, argument: picker)
    }

    private func hideEmojiPicker() {
        guard let picker = emojiPicker else { return }
        picker.removeFromSuperview()
        emojiOffset = picker.offset
        emojiPicker = nil
        emojiBar.showsSearch = false
        keyplane.isHidden = false
        view.setNeedsLayout() // the letters' height
        UIAccessibility.post(notification: .layoutChanged, argument: keyplane)
        // The picker (and, inside it, Search Emoji) took the status's place too: without this, a word typed before the
        // picker opened would leave its suggestions showing here instead of the status coming back.
        isTyping = false
        endTyping()
        updateSuggestions()
    }

    /// An emoji from the picker or the search types into the app like a character key, then counts for Frequently Used.
    /// Like a letter, it spends a one capital (caps lock stays), as Apple's shift is off once an emoji goes in: from a
    /// search, the one set before it too, which the search's end would bring back.
    private func insertEmoji(_ emoji: Emoji) {
        textDocumentProxy.insertText(emoji.text)
        endTyping()
        editTail = Self.tail(textDocumentProxy.documentContextBeforeInput)
        shift.otherKey()
        shift.typedLetter()
        shiftBeforeSearch?.typedLetter()
        lastSpaceAt = nil
        var usage = emojiUsage()
        usage.pick(EmojiTones.base(of: emoji.text), at: Date()) // every tone of an emoji counts as that emoji
        UserDefaults.standard.set(usage.scores, forKey: EmojiUsage.key)
        followField()
        #if TF_AUTO_RETURN
        if readsHost { arbiter.harvest() } // the caret moved
        #endif
        render()
    }

    /// Frequently Used, from the keyboard's own defaults (no Full Access needed); Apple's starting set before any pick.
    private func emojiUsage() -> EmojiUsage {
        EmojiUsage(stored: UserDefaults.standard.dictionary(forKey: EmojiUsage.key) as? [String: [Double]],
                   starting: EmojiCatalog.recentsDefault, now: Date())
    }

    /// The skin tone chosen for each emoji, kept like Frequently Used: [base text: variant text].
    private var chosenTones: [String: String] {
        get { UserDefaults.standard.dictionary(forKey: EmojiTones.key) as? [String: String] ?? [:] }
        set { UserDefaults.standard.set(newValue, forKey: EmojiTones.key) }
    }

    /// The click and the haptic a key gives: light for an emoji, medium for the picker's other keys.
    private func feedback(light: Bool) {
        UIDevice.current.playInputClick()
        keyplane.impact(light: light)
    }

    // MARK: emoji search

    /// The Search Emoji field: the results row comes up above the letters and the keys type into the field. The grid
    /// waits under them, hidden, until Done ends the search.
    private func startSearch() {
        guard let picker = emojiPicker, emojiResults == nil, let root = inputView, let barView else { return }
        let results = EmojiResultsView(chosen: chosenTones)
        results.onEmoji = { [weak self] emoji in self?.insertEmoji(emoji) }
        results.onPress = { [weak self] light in self?.feedback(light: light) }
        results.translatesAutoresizingMaskIntoConstraints = false
        root.insertSubview(results, belowSubview: keyplane) // the top row's balloons and alternatives draw over the row
        NSLayoutConstraint.activate([
            results.topAnchor.constraint(equalTo: barView.bottomAnchor),
            results.leadingAnchor.constraint(equalTo: root.leadingAnchor),
            results.trailingAnchor.constraint(equalTo: root.trailingAnchor),
            results.bottomAnchor.constraint(equalTo: keyplane.topAnchor),
        ])
        emojiResults = results
        emojiBar.query = ""
        picker.isHidden = true
        keyplane.isHidden = false
        layer = .letters
        shiftBeforeSearch = shift // restored when the search ends, whatever shift or caps lock is set during it
        shift = ShiftKey() // the search starts in small letters, as Apple's does, whatever shift or caps lock was on
        results.show(EmojiSearch.results(for: "", used: emojiUsage().picked(at: Date())))
        view.setNeedsLayout()
        render()
        UIAccessibility.post(notification: .layoutChanged, argument: results)
    }

    /// A key while searching (nil for the field's clear button): it edits the query, not the app's text. Done ends the
    /// search and, as on Apple's keyboard, goes back to the letters. `text` is an alternative picked for the key.
    private func searchKey(_ key: Key?, typing text: String? = nil) {
        guard var query = emojiBar.query else { return }
        if key != .shift { shift.otherKey() }
        switch key {
        case .letter(let character)?:
            query += text ?? (shift.state.isUpper ? character.uppercased() : String(character))
            shift.typedLetter()
        case .symbol(let character)?:
            query += text ?? String(character)
        case .text(let string)?:
            query += text ?? string
        case .space?:
            query.append(" ")
        case .delete?:
            if !query.isEmpty { query.removeLast() }
        case .shift?:
            shift.tap(at: Date())
        case .ret?:
            return endSearch()
        case .globe?:
            advanceToNextInputMode()
        case .toNumbers?, .toSymbols?, .toLetters?, .emoji?:
            break
        case nil:
            query = ""
        }
        if let key { advanceLayer(after: key) }
        emojiBar.query = query
        emojiResults?.show(EmojiSearch.results(for: query, used: emojiUsage().picked(at: Date())))
        render()
    }

    /// Every way out of a search comes here (Done, a change iOS reports, the keyboard going away): first the keys still
    /// under a finger let go, so a held delete or letter never goes on into the text.
    private func endSearch() {
        guard let results = emojiResults else { return }
        keyplane.cancelTouches()
        results.removeFromSuperview()
        emojiResults = nil
        emojiBar.query = nil
        hideEmojiPicker() // Done leaves the emoji for the letters, as Apple's does; the picker reopens where it was
        layer = .letters
        if let before = shiftBeforeSearch { shift = before } // caps lock (or whatever shift was) from before the search
        shiftBeforeSearch = nil
        followField()
        view.setNeedsLayout()
        render()
    }

    /// Double space inserts a full stop: a second space soon after the first replaces it with ". ".
    private func insertSpace() {
        let before = textDocumentProxy.documentContextBeforeInput
        let sinceLastSpace = lastSpaceAt.map { Int((ContinuousClock.now - $0) / .milliseconds(1)) }
        switch DoubleSpace.onSpace(before: before, sinceLastSpaceMs: sinceLastSpace) {
        case .periodSpace:
            textDocumentProxy.deleteBackward()      // remove the earlier space
            textDocumentProxy.insertText(". ")
            lastSpaceAt = nil
        case .space:
            textDocumentProxy.insertText(" ")
            lastSpaceAt = .now
            afterLastSpace = Self.tail(textDocumentProxy.documentContextBeforeInput)
        }
    }

    /// The last 16 characters of the text before the caret: all that double space compares, because in a long message
    /// the app can send back a shorter window of that text than the keyboard's own copy.
    private static func tail(_ text: String?) -> String? { text.map { String($0.suffix(16)) } }

    /// The field's layout, as Apple's keyboard follows it: a new field (or a new keyboard type in the same one) opens on
    /// its first layer, 123 for a numbers-and-punctuation field and the letters for every other.
    private func followKind() {
        let proxy = textDocumentProxy
        let kind = KeyboardKind(proxy.keyboardType)
        let field = "\(FieldTraits.documentID(of: proxy)?.uuidString ?? "none")|\(kind.rawValue)"
        guard field != openedFor else { return }
        openedFor = field
        if !kind.hasEmojiKey || kind.isDigitPad { endSearch(); hideEmojiPicker() } // no emoji key in this field: the picker goes
        self.kind = kind
        layer = kind.firstLayer
        lastSpaceAt = nil // a double space never spans two fields
        typedOnLayer = false
        isTyping = false // a new field shows the status until its first key
        endTyping()
    }

    /// Automatic capitals from the field, each time its text or caret may have moved: an automatic or off shift
    /// follows the field, on at a sentence start and off elsewhere (a typed full stop needs its space first). A one capital
    /// the user set and caps lock stay, and a capital the user turned off stays off until the text, the caret or the field
    /// changes (the field's capitalization setting is part of the context, so a capital never carries over between fields).
    private func followField() {
        guard emojiBar.query == nil else { return } // while the keys type into the emoji search, the capital is the search's
        let proxy = textDocumentProxy
        let before = proxy.documentContextBeforeInput ?? ""
        let capitalization = proxy.autocapitalizationType
        shift.follow(capsExpected: FieldTraits.shiftExpected(capitalization, before: before),
                     context: ShiftKey.context(document: FieldTraits.documentID(of: proxy), capitalization: capitalization, before: before))
    }
}
