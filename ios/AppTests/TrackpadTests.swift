import CoreGraphics
import Testing
@testable import ThumbFree

/// The space bar's trackpad: Apple's pace (measured on the iOS 26.5 Simulator), and up and down through line breaks.
@MainActor @Suite struct TrackpadTests {
    /// A drag `distance` points left at `speed` points a second, in 2-point moves: the characters the cursor went.
    private func drag(_ distance: CGFloat, speed: Double) -> Int {
        var pad = Trackpad(at: .zero, time: 0)
        var x: CGFloat = 0, time = 0.0, total = 0
        while x > -distance {
            x -= 2
            time += 2 / speed
            total += pad.move(to: CGPoint(x: x, y: 0), at: time).characters
        }
        return total
    }

    // One character as soon as the finger moves, then one for every 10 points at a slow drag, farther at a fast one: the
    // counts Apple's keyboard gave for the same drags.
    @Test func theCursorFollowsTheFingerAtApplesPace() {
        #expect(drag(20, speed: 20) == -3)
        #expect(drag(40, speed: 20) == -5)
        #expect(drag(80, speed: 20) == -9)
        #expect(drag(40, speed: 200) == -7)
        var pad = Trackpad(at: .zero, time: 0)
        #expect(pad.move(to: CGPoint(x: 1, y: 0), at: 0.1) == (0, 0)) // a tremble moves nothing
        #expect(pad.move(to: CGPoint(x: 1, y: -33), at: 1).lines == -1) // a line for every 30 points
    }

    // Up and down keep the column through the line breaks the keyboard sees; inside one paragraph there is no line to go to.
    @Test func upAndDownGoThroughTheLineBreaks() {
        let text = "abc\ndefgh\nijklmno"
        #expect(Trackpad.offset(lines: -1, before: text, after: "") == -8)  // the end of line 3 to the end of "defgh"
        #expect(Trackpad.offset(lines: -2, before: text, after: "") == -14) // to the end of "abc"
        #expect(Trackpad.offset(lines: 1, before: "abc\nde", after: "fgh\nijklmno") == 6) // column 2 of the next line
        #expect(Trackpad.offset(lines: -3, before: text, after: "") == -14) // as far as the text goes: two lines
        #expect(Trackpad.offset(lines: -1, before: "one long wrapped paragraph", after: "") == nil)
        #expect(Trackpad.offset(lines: 1, before: "abc", after: "def") == nil)
        var fast = Trackpad(at: .zero, time: 0)
        #expect(fast.move(to: CGPoint(x: 0, y: -40), at: 0.05).lines == -1) // 800 pt/s: still one line for 40 points
    }

    // The proxy counts UTF-16 units: an emoji is two, and the cursor never goes past the text the keyboard sees.
    @Test func stepsAreCountedAsTheProxyCountsThem() {
        #expect(Trackpad.utf16Offset(-1, before: "a\u{1F600}", after: "") == -2)
        #expect(Trackpad.utf16Offset(2, before: "", after: "b\u{1F600}c") == 3)
        #expect(Trackpad.utf16Offset(-5, before: "ab", after: "") == -2)
    }
}
