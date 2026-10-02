import Testing
@testable import ThumbFree

/// The keyboard step's list is the taps in Settings; a tap in a picture takes its beat; "Put ThumbFree first" has its own
/// beats, then its tip.
@MainActor @Suite struct SetupGuideTests {
    // With Reduce Motion or VoiceOver, the keyboard step lists the rows to tap in Settings, in order, and VoiceOver hears
    // the picture once. iOS's Allow question is named in words, never drawn.
    @Test func theKeyboardStepsListIsTheTapsInSettings() {
        #expect(SetupGuideView.names == ["Keyboards", "ThumbFree", "Allow Full Access", "Allow", "Return to ThumbFree"])
        #expect(SetupGuideView.description
            == "In Settings, open Keyboards, turn on ThumbFree, turn on Allow Full Access, tap Allow, then return to ThumbFree.")
    }

    // A tap in a picture takes 1.3 s: the cue holds 0.25 s, its ripple grows over 0.35 s, the control changes over
    // 0.25 s, then the result holds 0.45 s. A still picture shows the result.
    @Test func aTapTakesItsBeat() {
        #expect(TapTimeline.length == 1.3)
        #expect(TapTimeline.ripple(at: 0.2) == nil)
        #expect(TapTimeline.ripple(at: 0.25) == 0)
        #expect(abs((TapTimeline.ripple(at: 0.425) ?? -1) - 0.5) < 0.001)
        #expect(TapTimeline.ripple(at: 0.6) == nil)
        #expect(TapTimeline.change(at: 0.5) == 0)
        #expect(abs(TapTimeline.change(at: 0.725) - 0.5) < 0.001)
        #expect(TapTimeline.change(at: 0.85) == 1)
        #expect(TapTimeline.change(at: TapTimeline.length) == 1)
        #expect(TapTimeline.ripple(at: TapTimeline.length) == nil)
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
