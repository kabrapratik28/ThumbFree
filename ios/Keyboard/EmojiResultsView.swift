import UIKit

/// The emoji search's results, in one row above the letters as in Apple's Search Emoji: best match first, each in the tone
/// chosen for it. A tap types it and the search stays up, as Apple's does. Reused cells, as in the picker, at Apple's
/// sizes (iOS 26.5 Simulator): 43 pt wide, 10 pt apart from 8 pt in, each emoji drawn 38 pt across (41.5 pt text).
@MainActor final class EmojiResultsView: UIView, UICollectionViewDataSource, UICollectionViewDelegate {
    var onEmoji: (Emoji) -> Void = { _ in }
    var onPress: (_ light: Bool) -> Void = { _ in }

    private let chosen: [String: String]
    private let row: UICollectionView
    private var results: [Emoji] = []

    init(chosen: [String: String]) {
        self.chosen = chosen
        let layout = UICollectionViewFlowLayout()
        layout.scrollDirection = .horizontal
        layout.minimumLineSpacing = 10
        layout.sectionInset = UIEdgeInsets(top: 0, left: 8, bottom: 0, right: 8)
        row = UICollectionView(frame: .zero, collectionViewLayout: layout)
        super.init(frame: .zero)
        accessibilityIdentifier = "keyboard.emoji.results"
        row.backgroundColor = .clear
        row.showsHorizontalScrollIndicator = false
        for edge in [row.topEdgeEffect, row.leftEdgeEffect, row.bottomEdgeEffect, row.rightEdgeEffect] { edge.isHidden = true }
        row.register(EmojiCell.self, forCellWithReuseIdentifier: EmojiCell.id)
        row.dataSource = self
        row.delegate = self
        addSubview(row)
    }

    required init?(coder: NSCoder) { nil }

    func show(_ results: [Emoji]) {
        self.results = results.map { EmojiTones.shown($0, chosen: chosen) }
        row.reloadData()
        row.setContentOffset(.zero, animated: false)
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        row.frame = bounds
        (row.collectionViewLayout as? UICollectionViewFlowLayout)?.itemSize = CGSize(width: 43, height: bounds.height)
    }

    func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int { results.count }

    func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        let cell = collectionView.dequeueReusableCell(withReuseIdentifier: EmojiCell.id, for: indexPath)
        (cell as? EmojiCell)?.size = 41.5
        (cell as? EmojiCell)?.show(results[indexPath.item], identifier: "keyboard.emoji.result.\(indexPath.item)")
        return cell
    }

    func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        collectionView.deselectItem(at: indexPath, animated: false)
        onPress(true)
        onEmoji(results[indexPath.item])
    }
}
