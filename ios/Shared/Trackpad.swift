import CoreGraphics

/// The space bar's trackpad, as Apple's keyboard does it (measured on the iOS 26.5 Simulator): touch and hold space, then
/// the finger moves the cursor, one character as soon as it moves and one more for every 10 points (fewer points when it
/// moves fast: 40 points moved 5 characters slowly and 7 quickly), and a line for every 30 points up or down.
struct Trackpad {
    static let startDelay = Duration.milliseconds(250) // on space before the keys fade (Apple's started within 0.2 s; a tap is shorter)
    private static let perCharacter: CGFloat = 10
    private static let perLine: CGFloat = 30

    private var last: (point: CGPoint, time: Double)
    private var carry = CGSize.zero      // points moved, not yet spent on a whole step
    private var moved = false

    init(at point: CGPoint, time: Double) { last = (point, time) }

    /// The finger moved to `point` at `time` (seconds): the steps to make now, in characters (negative: left) and lines
    /// (negative: up).
    mutating func move(to point: CGPoint, at time: Double) -> (characters: Int, lines: Int) {
        let dx = point.x - last.point.x, dy = point.y - last.point.y
        let speed = (dx * dx + dy * dy).squareRoot() / max(time - last.time, 0.001)
        let gain = 1 + min(speed / 250, 1) * 0.8 // ponytail: a straight-line speed curve fit to three Simulator drags; tune on a device
        last = (point, time)
        carry.width += dx * gain
        carry.height += dy // lines keep Apple's 30 points at any speed
        var characters = 0
        if !moved, abs(carry.width) >= 2 { // Apple moves the first character at once
            characters = carry.width < 0 ? -1 : 1
            moved = true
        }
        let more = Int(carry.width / Self.perCharacter)
        carry.width -= CGFloat(more) * Self.perCharacter
        let lines = Int(carry.height / Self.perLine)
        carry.height -= CGFloat(lines) * Self.perLine
        return (characters + more, lines)
    }

    /// How many characters the cursor moves to go `lines` lines up (negative) or down, keeping its column, through the line
    /// breaks in the text around it (`before` and `after` the cursor, as the app lets a keyboard see it), as many lines as
    /// that text holds. A keyboard sees that text but not how the app wraps it, so within one wrapped paragraph there is no
    /// line above or below: nil.
    static func offset(lines: Int, before: String, after: String) -> Int? {
        let text = Array(before + after)
        var cursor = before.count
        var moved = 0
        for _ in 0..<abs(lines) {
            let lineStart = (text[..<cursor].lastIndex(where: \.isNewline) ?? -1) + 1
            let column = cursor - lineStart
            if lines < 0 {
                guard lineStart > 0 else { break }
                let above = (text[..<(lineStart - 1)].lastIndex(where: \.isNewline) ?? -1) + 1
                cursor = above + min(column, lineStart - 1 - above)
            } else {
                guard let lineEnd = text[cursor...].firstIndex(where: \.isNewline) else { break }
                let below = text[(lineEnd + 1)...].firstIndex(where: \.isNewline) ?? text.count
                cursor = lineEnd + 1 + min(column, below - lineEnd - 1)
            }
            moved += 1
        }
        return moved == 0 ? nil : cursor - before.count
    }

    /// `characters` (negative: left) as the offset `adjustTextPosition(byCharacterOffset:)` takes, which counts UTF-16
    /// units: an emoji is two. Never past the text the keyboard can see.
    static func utf16Offset(_ characters: Int, before: String, after: String) -> Int {
        characters < 0 ? -before.suffix(-characters).utf16.count : after.prefix(characters).utf16.count
    }
}
