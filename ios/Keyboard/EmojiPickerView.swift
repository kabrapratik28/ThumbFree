import UIKit
import UIKit.UIGestureRecognizerSubclass

/// ThumbFree's emoji picker, in the keys' place below the bar while it shows: iOS gives a
/// keyboard no public way to open Apple's Emoji keyboard, so this draws Apple's layout itself, at Apple's sizes. A grid
/// that scrolls sideways, grouped by category, each category's title pinned at the top left while it scrolls; a bottom
/// bar with ABC, the category icons (the one on screen lit) and delete; and Apple's skin-tone pickers on a long press
/// (a row of tones, or for two people a tone for each). The grid is one collection view of reused cells that draw each
/// emoji as text, no images, so the keyboard stays light; the controller makes the picker when the emoji key is tapped
/// and lets it go on ABC. The controller owns the text and the storage: `onEmoji`, `onTone` and `onKey` report the
/// taps.
@MainActor final class EmojiPickerView: UIView, UICollectionViewDataSource, UICollectionViewDelegate, UIGestureRecognizerDelegate {
    /// An emoji tapped (on touch-up, as a key types), in the tone chosen for it.
    var onEmoji: (Emoji) -> Void = { _ in }
    /// A tone picked in the tone picker: the emoji and the choice (the emoji itself for no tone). It is typed too.
    var onTone: (_ base: Emoji, _ choice: Emoji) -> Void = { _, _ in }
    /// ABC (`.toLetters`) and delete (`.delete`: at the touch, then repeating at the keys' pace while held, but always one
    /// emoji at a time, even once that pace would move the keys on to whole words: Apple's Emoji keyboard has no words).
    var onKey: (Key) -> Void = { _ in }
    /// A press to click and buzz for: light for an emoji, medium for ABC, a category and delete (the keys' rule).
    var onPress: (_ light: Bool) -> Void = { _ in }
    /// The globe's raw touches, while the bar shows one (`showsGlobe`): wired to `handleInputModeList(from:with:)`,
    /// exactly as the keys' globe is.
    var onGlobe: (UIView, UIEvent) -> Void = { _, _ in }

    private let sections: [EmojiSection]
    private var chosen: [String: String]
    private let grid: UICollectionView
    private let bar = UIStackView()
    private let highlight = UIView()
    private var categoryButtons: [(category: EmojiCategory, button: UIButton)] = []
    private var litCategory: EmojiCategory?
    private var deleteRepeat: Task<Void, Never>?
    private let press = UILongPressGestureRecognizer() // the tone pickers' long press
    private let watcher = TouchWatcher() // on the keyboard's root view: a touch outside a tone picker closes it
    private let globe = GlobeButton(type: .system)
    /// Where to open the grid (the controller gives it the `offset` the picker last closed at); nil once used.
    var startOffset: CGFloat?
    private var popup: UIView? // a TonePopup or, for two people, a PairPopup
    private var popupPath: IndexPath?
    /// Whether the keyboard's latest touch landed outside an open tone picker, which it closed (`watcher`): such a tap on
    /// the mic does nothing else (the controller's `micDown`, which clears it once read), so closing the balloon never
    /// starts or stops a take.
    var touchClosedPopup = false

    /// `chosen` is the tone chosen for each emoji ([base text: variant text]), which the grid shows. `showsGlobe`: the bar's
    /// globe to begin with.
    init(sections: [EmojiSection], chosen: [String: String], showsGlobe: Bool) {
        self.sections = sections
        self.chosen = chosen
        grid = UICollectionView(frame: .zero, collectionViewLayout: Self.layout())
        super.init(frame: .zero)
        accessibilityIdentifier = "keyboard.emoji.picker"
        grid.backgroundColor = .clear
        grid.showsHorizontalScrollIndicator = false
        for edge in [grid.topEdgeEffect, grid.leftEdgeEffect, grid.bottomEdgeEffect, grid.rightEdgeEffect] { edge.isHidden = true }
        grid.register(EmojiCell.self, forCellWithReuseIdentifier: EmojiCell.id)
        grid.register(EmojiTitle.self, forSupplementaryViewOfKind: UICollectionView.elementKindSectionHeader,
                      withReuseIdentifier: EmojiTitle.id)
        grid.dataSource = self
        grid.delegate = self
        press.addTarget(self, action: #selector(longPressed(_:)))
        press.minimumPressDuration = 0.35
        press.delegate = self // it begins only on an emoji with tones
        grid.addGestureRecognizer(press)
        watcher.cancelsTouchesInView = false
        watcher.delaysTouchesEnded = false
        watcher.onTouchDown = { [weak self] touch in
            guard let self else { return }
            touchClosedPopup = popup.map { !$0.frame.contains(touch.location(in: self)) } ?? false
            if touchClosedPopup { closePopup() }
        }
        addSubview(grid)

        highlight.backgroundColor = Self.circleColor
        highlight.isUserInteractionEnabled = false
        addSubview(highlight)
        bar.axis = .horizontal
        addSubview(bar)
        let abc = UIButton(type: .system)
        style(abc, title: "ABC", key: .toLetters, size: 14.5, color: Self.abcColor)
        abc.addAction(UIAction { [weak self] _ in self?.onPress(false); self?.onKey(.toLetters) }, for: .touchUpInside)
        bar.addArrangedSubview(abc)
        // The globe, beside ABC while it shows (`showsGlobe`). A real touch goes to `onGlobe` (a tap or long press, as the
        // keys' globe); VoiceOver's double tap sends no raw touch, so it advances one step instead, through `onActivate`.
        globe.setImage(UIImage(systemName: "globe"), for: .normal)
        globe.tintColor = .label
        globe.accessibilityLabel = Key.globe.label(shift: .off, returnLabel: "")
        globe.accessibilityIdentifier = Key.globe.identifier
        globe.accessibilityTraits = .keyboardKey
        globe.addTarget(self, action: #selector(globeTouched(_:with:)), for: .allTouchEvents)
        globe.onActivate = { [weak self] in self?.onKey(.globe) }
        globe.isHidden = !showsGlobe
        bar.addArrangedSubview(globe)
        let globeWidth = globe.widthAnchor.constraint(equalToConstant: 52)
        globeWidth.priority = UILayoutPriority(999) // hidden, the stack view's own zero width wins
        globeWidth.isActive = true
        let categories = UIStackView()
        categories.distribution = .fillEqually
        for category in EmojiCategory.allCases {
            let button = UIButton(type: .system) // its icon is set with the lit one (`updateCurrent`)
            button.accessibilityLabel = category.title
            button.accessibilityIdentifier = category.identifier
            button.isEnabled = sections.contains { $0.category == category } // Frequently Used is dimmed while empty
            button.addAction(UIAction { [weak self] _ in self?.onPress(false); self?.show(category) }, for: .touchUpInside)
            categories.addArrangedSubview(button)
            categoryButtons.append((category, button))
        }
        bar.addArrangedSubview(categories)
        let delete = DeleteButton(type: .system)
        style(delete, title: "\u{232B}", key: .delete, size: 25, color: Self.deleteColor) // ⌫, as on the keys
        delete.addTarget(self, action: #selector(deleteDown), for: .touchDown)
        delete.addTarget(self, action: #selector(stopDeleteRepeat), for: [.touchUpInside, .touchUpOutside, .touchCancel])
        // Sliding off delete stops it at once, as on the keys: checked against its own bounds, not UIControl's
        // `.touchDragExit` (which fires only 70 pt outside on iPhone).
        delete.onExit = { [weak self] in self?.stopDeleteRepeat() }
        delete.onActivate = { [weak self] in self?.onKey(.delete) } // VoiceOver's double tap: one delete, no repeat
        bar.addArrangedSubview(delete)
        for side in [abc, delete] { side.widthAnchor.constraint(equalToConstant: 52).isActive = true }
        registerForTraitChanges([UITraitUserInterfaceStyle.self]) { (picker: EmojiPickerView, _) in
            picker.litCategory = nil // light or dark: the icons again (`updateCurrent`)
            picker.updateCurrent()
        }
    }

    required init?(coder: NSCoder) { nil }

    /// A globe in the bar beside ABC, as Apple's bar has on a Home-button iPhone (Face ID ones draw it below the keyboard).
    /// The controller keeps it to `needsInputModeSwitchKey`, which can change while the picker shows.
    var showsGlobe: Bool {
        get { !globe.isHidden }
        set { if globe.isHidden == newValue { globe.isHidden = !newValue } }
    }

    /// Apple's grid (`Grid`): 32 pt emoji in columns that scroll sideways, each category's title in a row above its first
    /// columns, pinned while the category scrolls. A cell is a whole row tall, its emoji in the middle, so a touch between
    /// two rows still lands on an emoji.
    private static func layout() -> UICollectionViewCompositionalLayout {
        let configuration = UICollectionViewCompositionalLayoutConfiguration()
        configuration.scrollDirection = .horizontal
        return UICollectionViewCompositionalLayout(sectionProvider: { _, environment in
            let apple = Grid(width: environment.container.contentSize.width, landscape: environment.traitCollection.verticalSizeClass == .compact)
            let item = NSCollectionLayoutItem(layoutSize: NSCollectionLayoutSize(widthDimension: .fractionalWidth(1),
                                                                                heightDimension: .absolute(apple.pitch)))
            let column = NSCollectionLayoutGroup.vertical(
                layoutSize: NSCollectionLayoutSize(widthDimension: .absolute(apple.column), heightDimension: .absolute(apple.pitch * CGFloat(apple.rows))),
                repeatingSubitem: item, count: apple.rows)
            let section = NSCollectionLayoutSection(group: column)
            let top = apple.title - apple.slack // the first row's cells start half a gap above its emoji
            section.contentInsets = NSDirectionalEdgeInsets(top: top, leading: apple.leading, bottom: 0, trailing: apple.spacing - apple.leading)
            // The title sits in the row above the emoji (not in a column before them, where a sideways layout puts it).
            let title = NSCollectionLayoutBoundarySupplementaryItem(
                layoutSize: NSCollectionLayoutSize(widthDimension: .estimated(200), heightDimension: .absolute(apple.title)),
                elementKind: UICollectionView.elementKindSectionHeader, alignment: .topLeading,
                absoluteOffset: CGPoint(x: 0, y: -top))
            title.extendsBoundary = false
            title.pinToVisibleBounds = true
            section.boundarySupplementaryItems = [title]
            return section
        }, configuration: configuration)
    }

    /// Apple's Emoji keyboard grid, measured on the iOS 26.5 Simulator (its accessibility tree and screenshots): a title
    /// row (its words `titleSize` pt), then rows of 32 pt emoji in `column` pt columns, the last row ending at the grid's
    /// foot, `gap` above the bar; the first column `leading` in from the edge and `spacing` more between categories. In
    /// portrait the grid is 216 pt tall, and Apple's rows and columns change with the iPhone's width: 5 rows in 46 pt
    /// columns on the 393 pt iPhone 16, 4 rows in 42 pt columns on the 420 pt iPhone Air (the widths between were not
    /// measured; the switch is at 414 pt, where Apple's larger iPhone layouts start). In landscape, on both, it is 151.7 pt
    /// tall with 3 rows in 40 pt columns.
    private struct Grid {
        let height: CGFloat, rows: Int, column: CGFloat, title: CGFloat, titleSize: CGFloat
        let leading: CGFloat, spacing: CGFloat, gap: CGFloat

        init(width: CGFloat, landscape: Bool) {
            if landscape {
                (height, rows, column, title, titleSize, leading, spacing, gap) = (151.67, 3, 40, 29, 12, 3, 16, 7)
            } else if width < 414 {
                (height, rows, column, title, titleSize, leading, spacing, gap) = (216, 5, 46, 30, 12, 3, 14, 4)
            } else {
                (height, rows, column, title, titleSize, leading, spacing, gap) = (216, 4, 42, 26, 13, 7, 20, 4)
            }
        }

        /// From one row of emoji to the next.
        var pitch: CGFloat { (height - title - 32) / CGFloat(rows - 1) }
        /// Half the gap between two rows of emoji: a cell keeps it above and below its emoji.
        var slack: CGFloat { (pitch - 32) / 2 }
        /// Where a title's words start, 3 pt right of its first column's emoji (less the letter's own 1 pt margin), as Apple's.
        var titleInset: CGFloat { leading + (column - 32) / 2 + 2 }
    }

    /// Apple's grid for this iPhone and orientation.
    private var appleGrid: Grid { Grid(width: bounds.width, landscape: traitCollection.verticalSizeClass == .compact) }

    override func layoutSubviews() {
        super.layoutSubviews()
        // Apple's 40 pt bar at the foot and its grid right above, `gap` apart. The keyboard's own bar on top is lower than
        // Apple's search strip, so what is left stays empty above the titles. The half gap under the last row's emoji
        // reaches into that gap (on the iPhone Air a little under the bar, which is on top and takes those touches).
        let apple = appleGrid, barHeight: CGFloat = 40
        grid.frame = CGRect(x: 0, y: max(0, bounds.height - barHeight - apple.gap - apple.height), width: bounds.width,
                            height: apple.height + apple.slack)
        bar.frame = CGRect(x: 0, y: bounds.height - barHeight, width: bounds.width, height: barHeight)
        bar.layoutIfNeeded()
        if let offset = startOffset, grid.bounds.width > 0 {
            // Where the picker was left, as Apple's reopens (the first layout only), inside the grid: a picker closed
            // while it bounced past either end left an offset outside it.
            grid.layoutIfNeeded()
            grid.contentOffset.x = min(max(0, offset), max(0, grid.contentSize.width - grid.bounds.width))
            startOffset = nil
        }
        updateCurrent()
    }

    /// How far the grid is scrolled: the controller keeps it while the picker is closed and gives it back as `startOffset`.
    var offset: CGFloat { grid.contentOffset.x }

    /// The picker closed, or the keyboard went away, while delete was held or the tone picker was up: both stop with it.
    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window == nil {
            stopDeleteRepeat()
            closePopup()
        }
    }

    /// Hidden while Search Emoji's keys show in its place: no tone picker stays up in it (and `hitTest` takes no touches).
    override var isHidden: Bool { didSet { if isHidden { closePopup() } } }

    // MARK: the grid

    func numberOfSections(in collectionView: UICollectionView) -> Int { sections.count }

    func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int { sections[section].emoji.count }

    func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        let cell = collectionView.dequeueReusableCell(withReuseIdentifier: EmojiCell.id, for: indexPath)
        let emoji = shown(at: indexPath)
        (cell as? EmojiCell)?.show(emoji, identifier: sections[indexPath.section].identifier(at: indexPath.item))
        // VoiceOver: an emoji with tones offers them as actions, as the long press does (every pair, for two people).
        let base = sections[indexPath.section].emoji[indexPath.item]
        let tones = EmojiTones.pairs(for: base.text).map { [base] + $0.joined() } ?? EmojiTones.choices(for: base.text)
        cell.accessibilityCustomActions = tones?.map { choice in
            UIAccessibilityCustomAction(name: choice.name) { [weak self] _ in
                self?.pick(choice, for: indexPath)
                return true
            }
        }
        (cell as? EmojiCell)?.joinsPopup = indexPath == popupPath
        return cell
    }

    func collectionView(_ collectionView: UICollectionView, viewForSupplementaryElementOfKind kind: String,
                        at indexPath: IndexPath) -> UICollectionReusableView {
        let view = collectionView.dequeueReusableSupplementaryView(ofKind: kind, withReuseIdentifier: EmojiTitle.id, for: indexPath)
        let apple = appleGrid
        (view as? EmojiTitle)?.show(sections[indexPath.section].category.title, size: apple.titleSize, inset: apple.titleInset)
        return view
    }

    func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        collectionView.deselectItem(at: indexPath, animated: false)
        onPress(true)
        onEmoji(shown(at: indexPath))
    }

    func scrollViewDidScroll(_ scrollView: UIScrollView) {
        if scrollView.isDragging || scrollView.isDecelerating { closePopup() } // a finger's scroll, not VoiceOver's or a jump
        updateCurrent()
    }

    /// The emoji a cell shows: in the tone chosen for it (Frequently Used too, which counts every tone as its emoji).
    private func shown(at indexPath: IndexPath) -> Emoji {
        EmojiTones.shown(sections[indexPath.section].emoji[indexPath.item], chosen: chosen)
    }

    // MARK: skin tones

    /// The long press begins only on an emoji with tones: anywhere else a held press still types its emoji when let go,
    /// and a hold that turns into a drag still scrolls. (UIKit also asks a view about gestures above it: those go on.)
    override func gestureRecognizerShouldBegin(_ gesture: UIGestureRecognizer) -> Bool {
        guard gesture === press else { return super.gestureRecognizerShouldBegin(gesture) }
        guard let indexPath = grid.indexPathForItem(at: gesture.location(in: grid)) else { return false }
        let text = shown(at: indexPath).text
        return EmojiTones.pairs(for: text) != nil || EmojiTones.choices(for: text) != nil
    }

    @objc private func longPressed(_ gesture: UILongPressGestureRecognizer) {
        switch gesture.state {
        case .began:
            guard let indexPath = grid.indexPathForItem(at: gesture.location(in: grid)) else { return }
            let text = shown(at: indexPath).text
            if let pairs = EmojiTones.pairs(for: text) {
                onPress(true)
                openPairs(pairs, over: indexPath)
            } else if let choices = EmojiTones.choices(for: text) {
                onPress(true)
                openPopup(choices, over: indexPath)
            }
        case .changed:
            guard let popup = popup as? TonePopup else { return }
            popup.follow(gesture.location(in: popup))
        case .ended:
            // Let go on a tone: that tone. Let go anywhere else (the emoji itself): the picker stays up for a tap. The
            // two-person picker always stays up: it takes a tone for each person, then the pair.
            guard let popup = popup as? TonePopup, let path = popupPath else { return }
            if let index = popup.choice(at: gesture.location(in: popup)) { pick(popup.choices[index], for: path) }
        case .cancelled, .failed:
            closePopup()
        default:
            break
        }
    }

    private func openPopup(_ choices: [Emoji], over indexPath: IndexPath) {
        closePopup()
        guard let cell = grid.cellForItem(at: indexPath) else { return }
        let popup = TonePopup(choices: choices, current: shown(at: indexPath))
        popup.frame = popupFrame(popup.intrinsicContentSize, over: cell.convert(cell.bounds, to: self))
        popup.onPick = { [weak self] index in self?.pick(choices[index], for: indexPath) }
        addSubview(popup)
        self.popup = popup
        popupPath = indexPath
        (cell as? EmojiCell)?.joinsPopup = true // the pressed emoji joins the balloon, as Apple's does
        UIAccessibility.post(notification: .layoutChanged, argument: popup)
    }

    /// Apple's two-person picker: taller than the grid, so it goes as high as the keyboard reaches (a keyboard cannot draw
    /// above itself, where Apple's goes).
    private func openPairs(_ pairs: [[Emoji]], over indexPath: IndexPath) {
        closePopup()
        guard let cell = grid.cellForItem(at: indexPath) else { return }
        let popup = PairPopup(base: sections[indexPath.section].emoji[indexPath.item], pairs: pairs, current: shown(at: indexPath))
        popup.frame = popupFrame(popup.intrinsicContentSize, over: cell.convert(cell.bounds, to: self))
        popup.onPick = { [weak self] choice in self?.pick(choice, for: indexPath) }
        addSubview(popup)
        self.popup = popup
        popupPath = indexPath
        UIAccessibility.post(notification: .layoutChanged, argument: popup)
    }

    /// Where a tone picker goes: above the pressed emoji and centered on it, as Apple's, 2 pt inside the keyboard's edges
    /// (its top too), and where it reaches up into the bar, left of the mic.
    private func popupFrame(_ size: CGSize, over key: CGRect) -> CGRect {
        let y = max(key.minY - size.height, 2 - frame.minY)
        let right = bounds.width - (y < 0 ? Self.micClearance : 2)
        let x = min(max(key.midX - size.width / 2, 2), right - size.width)
        return CGRect(x: x, y: y, width: size.width, height: size.height)
    }

    /// How far from the right edge a tone picker in the bar stops: the bar's mic, 48 pt wide inside the bar's 10 pt padding
    /// (`KeyboardBar`), and a 2 pt gap, as at the keyboard's edges.
    private static let micClearance: CGFloat = 10 + 48 + 2

    /// Apple's bar colors, measured on its Emoji keyboard over the keyboard's own background (iOS 26.5 Simulator): black
    /// at `light` in light mode, white at `dark` in dark mode. The lit circle is a blue gray in light mode.
    private static func ink(light: CGFloat, dark: CGFloat) -> UIColor {
        UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 1, alpha: dark) : UIColor(white: 0, alpha: light) }
    }
    private static let abcColor = ink(light: 0.9, dark: 0.85)
    private static let iconColor = ink(light: 0.5, dark: 0.28)
    private static let litIconColor = ink(light: 1, dark: 0.47)
    private static let deleteColor = ink(light: 0.81, dark: 0.72)
    private static let circleColor = UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 1, alpha: 0.06) : UIColor(red: 0.741, green: 0.757, blue: 0.788, alpha: 1) }

    private func pick(_ choice: Emoji, for indexPath: IndexPath) {
        let base = sections[indexPath.section].emoji[indexPath.item] // sections hold each emoji without a tone
        EmojiTones.choose(choice, for: base, in: &chosen)
        closePopup()
        grid.reconfigureItems(at: grid.indexPathsForVisibleItems) // in place: it may show in Frequently Used and its category
        onPress(true)
        onTone(base, choice)
    }

    private func closePopup() {
        guard let popup else { return }
        popup.removeFromSuperview()
        self.popup = nil
        let cell = popupPath.flatMap { grid.cellForItem(at: $0) }
        (cell as? EmojiCell)?.joinsPopup = false
        popupPath = nil
        UIAccessibility.post(notification: .layoutChanged, argument: cell) // VoiceOver goes back to the emoji
    }

    /// A tone picker closes as soon as a finger lands anywhere else in the keyboard (`watcher`, on the view the picker is
    /// in), the bar too, whose controls still do their own work; a tap on the mic only closes it (`touchClosedPopup`).
    override func didMoveToSuperview() {
        super.didMoveToSuperview()
        if let superview { superview.addGestureRecognizer(watcher) } else { watcher.view?.removeGestureRecognizer(watcher) }
    }

    /// While the tone picker is up, it takes the touches on it (above the grid too).
    override func point(inside point: CGPoint, with event: UIEvent?) -> Bool {
        super.point(inside: point, with: event) || popup?.frame.contains(point) == true
    }

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        guard !isHidden, let popup else { return super.hitTest(point, with: event) } // hidden: none (super's nil)
        return popup.frame.contains(point) ? popup : (self.point(inside: point, with: event) ? self : nil)
    }

    // A touch in the picker outside the tone picker comes here while it is up (`hitTest`), which closes it as it lands
    // (`watcher`): it types nothing, and a drag moves the grid on. The picker keeps these touches, so it takes all four phases.
    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {}

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        // ponytail: the grid follows the finger with no fling; hand such a drag to the grid's own pan if the phone shows a need.
        guard let touch = touches.first else { return }
        let dx = touch.location(in: self).x - touch.previousLocation(in: self).x
        grid.contentOffset.x = min(max(0, grid.contentOffset.x - dx), max(0, grid.contentSize.width - grid.bounds.width))
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {}

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {}

    // MARK: the bar

    /// A category's icon: its first emoji at the left edge (its title pinned above).
    private func show(_ category: EmojiCategory) {
        guard let index = sections.firstIndex(where: { $0.category == category }) else { return }
        grid.scrollToItem(at: IndexPath(item: 0, section: index), at: .left, animated: false)
        updateCurrent()
    }

    /// Lights the icon of the category at the left edge, as Apple's bar does while the grid scrolls. Asks the layout, not
    /// the visible cells, which lag behind the scroll until the next layout pass.
    private func updateCurrent() {
        let left = grid.contentOffset.x
        let cells = (grid.collectionViewLayout.layoutAttributesForElements(in: grid.bounds) ?? [])
            .filter { $0.representedElementCategory == .cell && $0.frame.maxX > left }
        let index = cells.min { $0.frame.minX < $1.frame.minX }?.indexPath.section ?? 0
        guard sections.indices.contains(index) else { return }
        let category = sections[index].category
        if category != litCategory {
            litCategory = category
            let dark = traitCollection.userInterfaceStyle == .dark
            for (each, button) in categoryButtons {
                button.setImage(Self.icon(each, lit: each == category, dark: dark), for: .normal)
                button.tintColor = each == category ? Self.litIconColor : Self.iconColor
                button.accessibilityTraits = each == category ? [.button, .selected] : .button
            }
        }
        guard let button = categoryButtons.first(where: { $0.category == category })?.button else { return }
        let center = button.convert(CGPoint(x: button.bounds.midX, y: button.bounds.midY), to: self)
        let side: CGFloat = 30 // Apple's circle
        highlight.frame = CGRect(x: center.x - side / 2, y: center.y - side / 2, width: side, height: side)
        highlight.layer.cornerRadius = side / 2
    }

    /// A category's icon as Apple's: 16 pt (the SF Symbol at 12.5 pt), an outline, but filled in dark mode while it is not
    /// lit, where SF Symbols has a fill. A face is the other way round in dark mode: SF Symbols draws `face.smiling` filled
    /// there, and its fill as an outline.
    private static func icon(_ category: EmojiCategory, lit: Bool, dark: Bool) -> UIImage? {
        let fill = dark && lit == (category == .smileysAndPeople)
        let size = UIImage.SymbolConfiguration(pointSize: 12.5)
        return (fill ? UIImage(systemName: category.symbol + ".fill", withConfiguration: size) : nil)
            ?? UIImage(systemName: category.symbol, withConfiguration: size)
    }

    private func style(_ button: UIButton, title: String, key: Key, size: CGFloat, color: UIColor) {
        button.setTitle(title, for: .normal)
        button.setTitleColor(color, for: .normal)
        button.titleLabel?.font = .systemFont(ofSize: size)
        button.accessibilityLabel = key.label(shift: .off, returnLabel: "")
        button.accessibilityIdentifier = key.identifier
        button.accessibilityTraits = .keyboardKey
    }

    @objc private func deleteDown() {
        onPress(false)
        onKey(.delete)
        deleteRepeat?.cancel()
        // The picker's own pace (`DeleteRepeat.step`'s `picker`): one emoji at a time, never a word.
        deleteRepeat = Task { [weak self] in
            for n in 1... {
                try? await Task.sleep(for: DeleteRepeat.step(n, picker: true).wait)
                guard !Task.isCancelled, let self else { return } // a torn-down keyboard ends the loop too
                self.onKey(.delete)
            }
        }
    }

    @objc private func stopDeleteRepeat() {
        deleteRepeat?.cancel()
        deleteRepeat = nil
    }

    @objc private func globeTouched(_ view: UIView, with event: UIEvent) { onGlobe(view, event) }
}

/// One emoji, drawn as text at Apple's 32 pt (`size`; the search results draw theirs larger) in a reused cell; lit while
/// pressed, as Apple's are. The search results reuse it.
final class EmojiCell: UICollectionViewCell {
    static let id = "emoji"
    private let label = EmojiLabel()
    var size: CGFloat = 32 { didSet { setNeedsLayout() } }

    override init(frame: CGRect) {
        super.init(frame: frame)
        label.textAlignment = .center
        label.font = .systemFont(ofSize: 32)
        contentView.addSubview(label)
        contentView.layer.cornerRadius = 8
        isAccessibilityElement = true
        accessibilityTraits = .keyboardKey
    }

    required init?(coder: NSCoder) { nil }

    func show(_ emoji: Emoji, identifier: String) {
        label.text = emoji.text // under the memory guard (`EmojiLabel`)
        accessibilityLabel = emoji.name // VoiceOver reads the Unicode name
        accessibilityIdentifier = identifier
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        label.frame = contentView.bounds.insetBy(dx: -8, dy: 0) // room for the emoji's full width, so it stays centered
        // At most half a point more than the cell is tall, as Apple's results draw 41.5 pt in their 41 pt row: a shorter row
        // (landscape's 38 pt results) draws its emoji smaller instead of cutting off their bottom edge.
        let fitted = min(size, bounds.height + 0.5)
        if label.font.pointSize != fitted { label.font = .systemFont(ofSize: fitted) }
    }

    /// Under the tone picker the pressed emoji takes the balloon's color, so the two read as one shape.
    var joinsPopup = false { didSet { paint() } }

    override var isHighlighted: Bool { didSet { paint() } }

    private func paint() {
        contentView.backgroundColor = joinsPopup ? KeyplaneView.letterKeyColor : isHighlighted ? .systemFill : .clear
    }
}

/// An emoji drawn as text under the keyboard's memory guard (`EmojiMemory`): new emoji draw at the screen's scale while
/// the keyboard has room, from smaller bitmaps as it nears its limit. The scale is set each time the label shows an emoji
/// or changes its size in a window, and again each time it joins one, since UIKit resets a label's scale to the screen's
/// then (the cells of a newly opened picker, a tone picker's emoji). The grid, the search results and both tone pickers
/// draw with it.
private final class EmojiLabel: UILabel {
    override var text: String? { didSet { guardScale() } }
    override var font: UIFont! { didSet { guardScale() } } // the guard's bitmaps are in pixels: a new size, a new scale
    /// What VoiceOver's double tap does when the label stands for a key (a tone in the tone picker): what a tap on it does.
    var onActivate: (() -> Void)?

    override func accessibilityActivate() -> Bool {
        guard let onActivate else { return super.accessibilityActivate() }
        onActivate()
        return true
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        guardScale()
    }

    private func guardScale() {
        guard window != nil else { return } // the screen's scale is known in a window; joining one comes back here
        #if targetEnvironment(simulator)
        let available: Int? = nil // no memory limit on the Simulator
        #else
        let available: Int? = Int(os_proc_available_memory())
        #endif
        contentScaleFactor = EmojiMemory.drawScale(screen: traitCollection.displayScale, available: available, pointSize: font.pointSize)
    }
}

/// A category's title, in Apple's small gray capitals (the same gray in light and dark, as Apple's).
private final class EmojiTitle: UICollectionReusableView {
    static let id = "title"
    private let label = UILabel()
    private var inset: CGFloat = 0

    override init(frame: CGRect) {
        super.init(frame: frame)
        label.textColor = UIColor(red: 165 / 255, green: 166 / 255, blue: 169 / 255, alpha: 1)
        addSubview(label)
        isAccessibilityElement = true
        accessibilityTraits = .header
        isUserInteractionEnabled = false // it reaches into the first row's cells, above their emoji: a touch there is theirs
    }

    required init?(coder: NSCoder) { nil }

    /// `size` and `inset` are Apple's for the iPhone (`EmojiPickerView.Grid`).
    func show(_ title: String, size: CGFloat, inset: CGFloat) {
        label.text = title.uppercased()
        label.font = .systemFont(ofSize: size, weight: .semibold)
        self.inset = inset
        accessibilityLabel = title
        setNeedsLayout()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        label.frame = bounds.insetBy(dx: inset, dy: 0)
    }
}

/// Apple's skin-tone picker: the emoji, a thin line, then its five tones from light to dark, in a key-colored balloon over
/// the pressed emoji. Slide to a tone and let go, or let go and tap one; the tone in use is lit, then the one under the
/// finger.
private final class TonePopup: UIView {
    let choices: [Emoji]
    var onPick: (Int) -> Void = { _ in }
    private var cells: [EmojiLabel] = []
    private let lit = UIView()

    init(choices: [Emoji], current: Emoji) {
        self.choices = choices
        super.init(frame: .zero)
        backgroundColor = KeyplaneView.letterKeyColor
        layer.cornerRadius = 12
        layer.shadowColor = UIColor.black.cgColor
        layer.shadowOpacity = 0.25
        layer.shadowRadius = 4
        layer.shadowOffset = CGSize(width: 0, height: 1)
        lit.backgroundColor = .systemFill
        lit.layer.cornerRadius = 8
        addSubview(lit)
        let line = UIView()
        line.backgroundColor = .separator
        addSubview(line)
        for (index, choice) in choices.enumerated() {
            let cell = EmojiLabel()
            cell.text = choice.text
            cell.font = .systemFont(ofSize: 32)
            cell.textAlignment = .center
            cell.isAccessibilityElement = true
            cell.accessibilityLabel = choice.name
            cell.accessibilityTraits = .keyboardKey
            cell.accessibilityIdentifier = "keyboard.emoji.tone.\(index)"
            cell.onActivate = { [weak self] in self?.onPick(index) } // VoiceOver's double tap picks it, as a tap does
            cell.frame = frame(of: index)
            addSubview(cell)
            cells.append(cell)
        }
        accessibilityElements = cells
        line.frame = CGRect(x: frame(of: 0).maxX + 4, y: 12, width: 1, height: 32)
        light(choices.firstIndex(of: current))
    }

    required init?(coder: NSCoder) { nil }

    override var intrinsicContentSize: CGSize { CGSize(width: frame(of: choices.count - 1).maxX + 6, height: 56) }

    /// The base first, then a 9 pt gap for the line, then the tones, 42 pt apart.
    private func frame(of index: Int) -> CGRect {
        CGRect(x: 6 + CGFloat(index) * 42 + (index > 0 ? 9 : 0), y: 6, width: 42, height: 44)
    }

    /// The choice under a point in this view, if any.
    func choice(at point: CGPoint) -> Int? {
        guard bounds.contains(point) else { return nil }
        return cells.indices.first { frame(of: $0).insetBy(dx: -4, dy: -6).contains(point) }
    }

    func light(_ index: Int?) {
        lit.isHidden = index == nil
        if let index { lit.frame = frame(of: index) }
    }

    /// Lights the tone under the finger while it is over the balloon; off it (on the emoji below), the light stays.
    func follow(_ point: CGPoint) {
        if bounds.contains(point) { light(choice(at: point)) }
    }

    // The popup keeps its touches (one passed on to the picker would reach its touch end, which closes the popup): a
    // finger that lands or slides on it lights the tone under it, and letting go on a tone picks it.
    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        if let point = touches.first?.location(in: self) { follow(point) }
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        if let point = touches.first?.location(in: self) { follow(point) }
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let point = touches.first?.location(in: self), let index = choice(at: point) else { return }
        onPick(index)
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {}
}

/// Apple's two-person skin-tone picker: a row of five tones for the person on the left and a row for the person on the
/// right (the other person a gray silhouette), a line, then the emoji without tones and a preview of the pair picked.
/// Tap a tone in each row, then the preview; or the plain emoji. What is picked is lit.
private final class PairPopup: UIView {
    var onPick: (Emoji) -> Void = { _ in }
    private static let toneNames = ["light", "medium-light", "medium", "medium-dark", "dark"]
    private let base: Emoji
    private let pairs: [[Emoji]]
    private var left: Int?
    private var right: Int?
    private var rows: [[ShadedEmoji]] = []
    private let plain: ShadedEmoji
    private let preview: ShadedEmoji
    private let lit = (0..<3).map { _ in UIView() } // the left row's pick, the right row's, and the plain or the preview

    init(base: Emoji, pairs: [[Emoji]], current: Emoji) {
        self.base = base
        self.pairs = pairs
        plain = ShadedEmoji(text: base.text, size: 44, shade: .none)
        preview = ShadedEmoji(text: base.text, size: 44, shade: .all)
        super.init(frame: .zero)
        accessibilityIdentifier = "keyboard.emoji.pair"
        backgroundColor = KeyplaneView.letterKeyColor
        layer.cornerRadius = 12
        layer.shadowColor = UIColor.black.cgColor
        layer.shadowOpacity = 0.25
        layer.shadowRadius = 4
        layer.shadowOffset = CGSize(width: 0, height: 1)
        for view in lit {
            view.backgroundColor = .systemFill
            view.layer.cornerRadius = 8
            addSubview(view)
        }
        let line = UIView()
        line.backgroundColor = .separator
        line.frame = CGRect(x: 12, y: 106, width: 212, height: 1)
        addSubview(line)
        for (row, side) in ["left", "right"].enumerated() {
            rows.append((0..<5).map { tone in
                let cell = ShadedEmoji(text: pairs[tone][tone].text, size: 30, shade: row == 0 ? .right : .left)
                cell.frame = CGRect(x: 8 + CGFloat(tone) * 44, y: 8 + CGFloat(row) * 46, width: 44, height: 46)
                cell.accessibilityLabel = "\(side == "left" ? "Left" : "Right") person, \(Self.toneNames[tone]) skin tone"
                cell.accessibilityIdentifier = "keyboard.emoji.pair.\(side).\(tone)"
                addSubview(cell)
                return cell
            })
        }
        plain.frame = CGRect(x: 32, y: 114, width: 64, height: 58)
        plain.accessibilityLabel = base.name
        plain.accessibilityIdentifier = "keyboard.emoji.pair.plain"
        preview.frame = CGRect(x: 140, y: 114, width: 64, height: 58)
        preview.accessibilityIdentifier = "keyboard.emoji.pair.preview"
        addSubview(plain)
        addSubview(preview)
        accessibilityElements = rows[0] + rows[1] + [plain, preview]
        for (l, row) in pairs.enumerated() {
            if let r = row.firstIndex(of: current) { (left, right) = (l, r) }
        }
        update()
    }

    required init?(coder: NSCoder) { nil }

    override var intrinsicContentSize: CGSize { CGSize(width: 236, height: 180) }

    /// The preview shows the pair once both people have a tone (a gray silhouette until then), and the picks are lit.
    private func update() {
        let pair = left.flatMap { l in right.map { pairs[l][$0] } }
        preview.text = pair?.text ?? base.text
        preview.shade = pair == nil ? .all : .none
        preview.accessibilityLabel = pair?.name ?? "Pick a tone for each person"
        for (index, pick) in [left, right].enumerated() {
            lit[index].isHidden = pick == nil
            if let pick { lit[index].frame = rows[index][pick].frame }
        }
        lit[2].frame = (pair == nil ? plain : preview).frame
        for (index, row) in rows.enumerated() {
            for (tone, cell) in row.enumerated() { cell.accessibilityTraits = tone == [left, right][index] ? [.keyboardKey, .selected] : .keyboardKey }
        }
    }

    // The popup keeps its touches (one passed on to the picker would reach its touch end, which closes the popup): a
    // finger that lands or slides on a tone lights it for that person, and letting go on the plain emoji or the preview
    // types it.
    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        if let point = touches.first?.location(in: self) { pickTone(at: point) }
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        if let point = touches.first?.location(in: self) { pickTone(at: point) }
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        if let point = touches.first?.location(in: self) { tap(at: point) }
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {}

    private func tap(at point: CGPoint) {
        if plain.frame.contains(point) { return onPick(base) }
        if preview.frame.contains(point) {
            if let l = left, let r = right { onPick(pairs[l][r]) }
            return
        }
        pickTone(at: point)
    }

    /// The tone under a point, in either row, becomes that person's.
    private func pickTone(at point: CGPoint) {
        for (index, row) in rows.enumerated() {
            guard let tone = row.firstIndex(where: { $0.frame.contains(point) }) else { continue }
            if index == 0 { left = tone } else { right = tone }
            update()
        }
    }

    /// VoiceOver: a double tap on a tone, the plain emoji or the preview does what a tap does.
    fileprivate func activate(_ cell: ShadedEmoji) {
        tap(at: CGPoint(x: cell.frame.midX, y: cell.frame.midY))
    }
}

/// An emoji drawn as text, with a gray silhouette over its left half, its right half or all of it: how Apple's two-person
/// picker shows the person a row is not for, and a pair not picked yet. The silhouette is the emoji's own shape (a gray
/// view masked by the same text), so no image is involved; both draw under the memory guard.
private final class ShadedEmoji: UIView {
    enum Shade { case none, left, right, all }
    private let label = EmojiLabel()
    private let clip = UIView()
    private let gray = UIView()
    private let shape = EmojiLabel() // the gray view's mask: the emoji's own outline
    var shade: Shade { didSet { setNeedsLayout() } }

    init(text: String, size: CGFloat, shade: Shade) {
        self.shade = shade
        super.init(frame: .zero)
        for view in [label, shape] {
            view.text = text
            view.font = .systemFont(ofSize: size)
            view.textAlignment = .center
        }
        clip.clipsToBounds = true
        clip.isUserInteractionEnabled = false
        gray.backgroundColor = .systemGray2
        gray.mask = shape
        addSubview(label)
        addSubview(clip)
        clip.addSubview(gray)
        isAccessibilityElement = true
        accessibilityTraits = .keyboardKey
    }

    required init?(coder: NSCoder) { nil }

    var text: String? {
        get { label.text }
        set {
            label.text = newValue
            shape.text = newValue
        }
    }

    override func accessibilityActivate() -> Bool {
        (superview as? PairPopup)?.activate(self)
        return true
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        label.frame = bounds
        label.alpha = shade == .all ? 0 : 1 // all of it: only the silhouette
        clip.isHidden = shade == .none
        let half = bounds.width / 2
        switch shade {
        case .left: clip.frame = CGRect(x: 0, y: 0, width: half, height: bounds.height)
        case .right: clip.frame = CGRect(x: half, y: 0, width: half, height: bounds.height)
        case .none, .all: clip.frame = bounds
        }
        gray.frame = CGRect(x: -clip.frame.minX, y: 0, width: bounds.width, height: bounds.height)
        shape.frame = gray.bounds
    }
}

/// The bar's delete fires at the touch and repeats while held; VoiceOver's double tap sends no touch-down, so it
/// deletes once here instead. Sliding off calls `onExit` the moment the finger leaves the button's own bounds, not
/// UIControl's default `.touchDragExit` (70 pt outside on iPhone).
private final class DeleteButton: UIButton {
    var onActivate: () -> Void = {}
    var onExit: () -> Void = {}

    override func continueTracking(_ touch: UITouch, with event: UIEvent?) -> Bool {
        guard bounds.contains(touch.location(in: self)) else {
            onExit()
            return false
        }
        return super.continueTracking(touch, with: event)
    }

    override func accessibilityActivate() -> Bool {
        onActivate()
        return true
    }
}

/// Sees every touch that lands in the view it is on, and never takes one: it fails at once, so every view there still gets
/// its touches and every other gesture goes on (`EmojiPickerView.watcher`).
private final class TouchWatcher: UIGestureRecognizer {
    var onTouchDown: (UITouch) -> Void = { _ in }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
        touches.forEach(onTouchDown)
        state = .failed
    }
}

/// The picker's globe (`EmojiPickerView.showsGlobe`): a real touch's `.allTouchEvents` reach `onGlobe` (a tap or
/// long press, as the keys' globe). VoiceOver's double tap has no raw touch to hand `onGlobe`, so `onActivate` gives it
/// a plain step to the next input mode instead, as the keys' globe gives VoiceOver too.
private final class GlobeButton: UIButton {
    var onActivate: () -> Void = {}

    override func accessibilityActivate() -> Bool {
        onActivate()
        return true
    }
}
