import CoreGraphics
import UIKit

/// What Apple's keyboard shows above a pressed key, measured on the iOS 26.5 Simulator: pure geometry, so a test can
/// check the shapes without a screen.
enum KeyPopup {
    /// The balloon over a pressed letter or symbol: a head 18 pt wider than the key on each side, rising 67 pt above it,
    /// that narrows into the key itself. At an edge key the head stays inside the side margins (it leans inward, as
    /// Apple's does), and it never rises above `top`, the keyboard's top edge: a keyboard cannot draw outside itself.
    struct Balloon: Equatable {
        let head: CGRect
        let key: CGRect

        init(key: CGRect, bounds: CGRect, top: CGFloat, sideInset: CGFloat) {
            let minX = max(key.minX - 18, bounds.minX + sideInset)
            let maxX = min(key.maxX + 18, bounds.maxX - sideInset)
            let minY = max(top, key.minY - 67)
            head = CGRect(x: minX, y: minY, width: maxX - minX, height: max(key.minY - 13 - minY, 24))
            self.key = key
        }

        /// The outline: the head's rounded top, its sides curving in to the key, the key's rounded bottom.
        var path: UIBezierPath {
            let r: CGFloat = 10, k: CGFloat = 6 // the head's and the key's corner radii
            let neck = (head.maxY + key.minY) / 2
            let p = UIBezierPath()
            p.move(to: CGPoint(x: head.minX, y: head.minY + r))
            p.addArc(withCenter: CGPoint(x: head.minX + r, y: head.minY + r), radius: r, startAngle: .pi, endAngle: 1.5 * .pi, clockwise: true)
            p.addArc(withCenter: CGPoint(x: head.maxX - r, y: head.minY + r), radius: r, startAngle: 1.5 * .pi, endAngle: 0, clockwise: true)
            p.addLine(to: CGPoint(x: head.maxX, y: head.maxY))
            p.addCurve(to: CGPoint(x: key.maxX, y: key.minY), controlPoint1: CGPoint(x: head.maxX, y: neck), controlPoint2: CGPoint(x: key.maxX, y: neck))
            p.addArc(withCenter: CGPoint(x: key.maxX - k, y: key.maxY - k), radius: k, startAngle: 0, endAngle: .pi / 2, clockwise: true)
            p.addArc(withCenter: CGPoint(x: key.minX + k, y: key.maxY - k), radius: k, startAngle: .pi / 2, endAngle: .pi, clockwise: true)
            p.addLine(to: CGPoint(x: key.minX, y: key.minY))
            p.addCurve(to: CGPoint(x: head.minX, y: head.maxY), controlPoint1: CGPoint(x: key.minX, y: neck), controlPoint2: CGPoint(x: head.minX, y: neck))
            p.close()
            return p
        }
    }

    /// Where Apple draws a key's long-press alternatives: a row of cells 4 pt wider and 7 pt taller than the key (narrower
    /// when they would not all fit between 13.5 pt margins) in a band with a 10 pt margin that ends 6.6 pt above the key,
    /// the start cell over the key, the row slid inward when it would leave the keyboard. Without room above (the top row)
    /// the band covers the top of the key's row instead of rising above the keyboard's `top`.
    struct Callout: Equatable {
        let band: CGRect
        let cells: [CGRect]
        let key: CGRect

        init(count: Int, start: Int, key: CGRect, bounds: CGRect, top: CGFloat) {
            let margin: CGFloat = 13.5, pad: CGFloat = 10
            let width = min(key.width + 4, (bounds.width - margin * 2) / CGFloat(max(count, 1)))
            let height = key.height + 7
            let left = min(max(key.midX - width * (CGFloat(start) + 0.5), bounds.minX + margin), bounds.maxX - margin - width * CGFloat(count))
            let y = max(top + pad, key.minY - 6.6 - pad - height)
            cells = (0..<count).map { CGRect(x: left + CGFloat($0) * width, y: y, width: width, height: height) }
            band = CGRect(x: left - pad, y: y - pad, width: width * CGFloat(count) + pad * 2, height: height + pad * 2)
            self.key = key
        }

        /// The cell a release at `point` types, as on Apple's (measured on the iOS 26.5 Simulator): the one under the finger
        /// from the row's top down to the key's bottom edge; none past the row's ends, above the row or below the key, where
        /// a release types the key's own character.
        func index(at point: CGPoint) -> Int? {
            guard band.minY <= point.y, point.y <= key.maxY else { return nil }
            return cells.firstIndex { $0.minX <= point.x && point.x < $0.maxX }
        }

        /// A release more than a key's height below the key types nothing at all, as on Apple's.
        func cancels(at point: CGPoint) -> Bool { point.y > key.maxY + key.height }
    }
}
