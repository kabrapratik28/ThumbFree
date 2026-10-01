import UIKit

/// Apple's key popup: the balloon from `KeyPopup.Balloon`, in the key color with a soft shadow, the letter large in its
/// head. It never takes a touch.
@MainActor final class KeyPopupView: UIView {
    private let shape = CAShapeLayer()
    private let label = UILabel()

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        isAccessibilityElement = false
        shape.shadowColor = UIColor.black.cgColor
        shape.shadowOpacity = 0.3
        shape.shadowRadius = 2
        shape.shadowOffset = CGSize(width: 0, height: 1)
        layer.addSublayer(shape)
        label.textAlignment = .center
        label.font = .systemFont(ofSize: 36, weight: .regular)
        label.textColor = .label
        addSubview(label)
    }

    required init?(coder: NSCoder) { nil }

    /// Shows `text` in the balloon, both in the superview's coordinates.
    func show(_ balloon: KeyPopup.Balloon, text: String) {
        frame = balloon.head.union(balloon.key)
        let path = balloon.path
        path.apply(CGAffineTransform(translationX: -frame.minX, y: -frame.minY))
        shape.path = path.cgPath
        shape.shadowPath = path.cgPath
        shape.fillColor = KeyplaneView.letterKeyColor.resolvedColor(with: traitCollection).cgColor
        label.text = text
        label.frame = balloon.head.offsetBy(dx: -frame.minX, dy: -frame.minY)
        isHidden = false
    }
}

/// Apple's long-press alternatives: a key-colored band with a soft shadow and a cell for each alternative, the chosen one
/// white on blue. It never takes a touch: the keyplane moves the choice with the finger.
@MainActor final class CalloutView: UIView {
    private var cells: [UILabel] = []

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        isAccessibilityElement = false
        isHidden = true
        layer.cornerRadius = 16
        layer.shadowColor = UIColor.black.cgColor
        layer.shadowOpacity = 0.3
        layer.shadowRadius = 3
        layer.shadowOffset = CGSize(width: 0, height: 1)
    }

    required init?(coder: NSCoder) { nil }

    /// Shows `items` in `layout`'s cells (the superview's coordinates), `choice` chosen.
    func show(_ items: [String], layout: KeyPopup.Callout, choice: Int?) {
        hide()
        frame = layout.band
        backgroundColor = KeyplaneView.letterKeyColor
        layer.shadowPath = UIBezierPath(roundedRect: bounds, cornerRadius: 16).cgPath
        cells = zip(items, layout.cells).map { text, cell in
            let label = UILabel(frame: cell.offsetBy(dx: -frame.minX, dy: -frame.minY))
            label.text = text
            label.textAlignment = .center
            label.font = .systemFont(ofSize: text.count > 1 ? 17 : 24) // Apple's domains (.com) are smaller than its letters
            label.adjustsFontSizeToFitWidth = true
            label.minimumScaleFactor = 0.6
            label.layer.cornerRadius = 10
            label.layer.masksToBounds = true
            addSubview(label)
            return label
        }
        choose(choice)
        isHidden = false
    }

    func choose(_ index: Int?) {
        for (i, cell) in cells.enumerated() {
            cell.backgroundColor = i == index ? .systemBlue : .clear
            cell.textColor = i == index ? .white : .label
        }
    }

    func hide() {
        isHidden = true
        cells.forEach { $0.removeFromSuperview() }
        cells = []
    }
}
