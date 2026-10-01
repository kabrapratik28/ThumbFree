import UIKit

/// The keyboard's root view. It must be a `UIInputView` that adopts `UIInputViewAudioFeedback` and returns true from
/// `enableInputClicksWhenVisible`, or `UIDevice.current.playInputClick()` is silent.
/// The click still follows the user's Settings, Sounds, Keyboard Clicks switch.
final class KeyboardInputView: UIInputView, UIInputViewAudioFeedback {
    var enableInputClicksWhenVisible: Bool { true }
}
