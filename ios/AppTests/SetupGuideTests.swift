import Testing
@testable import ThumbFree

/// The setup guide's beats are the taps in Settings, in order; "Put ThumbFree first" has its own beats, then its tip.
@MainActor @Suite struct SetupGuideTests {
    // Only the taps in Settings, in order, and the one-line path under the drawing names the same four steps.
    @Test func theSetupBeatsAreTheTapsInSettings() {
        #expect(SetupGuideView.beats.map(\.caption) == [
            "Tap Keyboards.",
            "Turn on ThumbFree.",
            "Turn on Allow Full Access. The mic needs it.",
            "Tap Allow.",
        ])
        #expect(SetupGuideView.beats.map(\.scene) == SetupGuideView.Scene.allCases)
        #expect(SetupGuideView.path == ["Keyboards", "ThumbFree", "Allow Full Access", "Allow"])
        #expect(SetupGuideView.path.count == SetupGuideView.beats.count)
    }

    @Test func puttingThumbFreeFirstGoesInOrderThenTheTip() {
        #expect(PutFirstView.beats.map(\.caption) == [
            "Open Settings, General, Keyboard, Keyboards.",
            "Tap Edit.",
            "Drag ThumbFree to the top.",
            "Tap Done.",
        ])
        #expect(PutFirstView.beats.map(\.scene) == PutFirstView.Scene.allCases)
        #expect(PutFirstView.tip == "Touch and hold the globe key to jump straight to ThumbFree.")
    }

    // While ThumbFree is dragged to the top, no two rows are ever in one place: every row always shows at least half.
    @Test func theDragNeverStacksTwoRows() {
        #expect(PutFirstView.slots(dragged: 0) == [0, 1, 2])
        #expect(PutFirstView.slots(dragged: 1) == [1, 2, 0])
        var closest = Double.infinity
        for step in 0...400 {
            let slots: [Double] = PutFirstView.slots(dragged: Double(step) / 400)
            closest = min(closest, abs(slots[0] - slots[1]), abs(slots[0] - slots[2]), abs(slots[1] - slots[2]))
        }
        #expect(closest >= 0.5)
    }
}
