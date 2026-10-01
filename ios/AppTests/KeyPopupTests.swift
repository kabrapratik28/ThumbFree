import CoreGraphics
import Testing
import UIKit
@testable import ThumbFree

/// What shows above a pressed key, as Apple's keyboard draws it: the balloon.
@MainActor @Suite struct KeyPopupTests {
    private let bounds = CGRect(x: 0, y: 0, width: 390, height: 212)

    // Apple's balloon: 18 pt wider than the key on each side, rising 67 pt above it and narrowing into the key.
    @Test func theBalloonRisesFromTheKey() {
        let key = CGRect(x: 100, y: 60, width: 33, height: 42)
        let balloon = KeyPopup.Balloon(key: key, bounds: bounds, top: -48, sideInset: 3)
        #expect(balloon.head == CGRect(x: 82, y: -7, width: 69, height: 54))
        #expect(abs(balloon.path.bounds.minY - -7) < 0.5)
        #expect(abs(balloon.path.bounds.maxY - key.maxY) < 0.5)
        #expect(balloon.path.contains(CGPoint(x: key.midX, y: key.midY))) // it covers the key
        #expect(balloon.path.contains(CGPoint(x: 84, y: 20)))            // and reaches the head's side
    }

    // At an edge key the head leans inward, inside the side margin; on the top row it stays inside the keyboard, shorter,
    // since a keyboard cannot draw above itself.
    @Test func theBalloonStaysInsideTheKeyboard() {
        let q = CGRect(x: 3, y: 5.5, width: 33, height: 42)
        let left = KeyPopup.Balloon(key: q, bounds: bounds, top: -48, sideInset: 3)
        #expect(left.head.minX == 3)
        #expect(left.head.maxX == q.maxX + 18)
        #expect(left.head.minY == -48)
        #expect(left.head.height >= 24)
        let p = CGRect(x: 354, y: 5.5, width: 33, height: 42)
        #expect(KeyPopup.Balloon(key: p, bounds: bounds, top: -48, sideInset: 3).head.maxX == 387)
    }
}
