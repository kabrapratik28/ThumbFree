import SwiftUI
import UIKit

/// ThumbFree's colors, from the Android app's theme: sunflower yellow and dark navy on warm paper, in light and dark.
enum Theme {
    /// The page behind everything.
    static let paper = Color(light: 0xFFFBF5, dark: 0x15122B)
    static let ink = Color(light: 0x1F1B3A, dark: 0xEDE9F7)
    /// Body text under a title.
    static let inkSoft = Color(light: 0x4A4563, dark: 0xC8C3DB)
    /// Main buttons and the current step's dot: navy on light, sunflower on dark.
    static let primary = Color(light: 0x39335F, dark: 0xFFC83D)
    static let onPrimary = Color(light: 0xFFFFFF, dark: 0x1F1B3A)
    /// The big circle behind a step's symbol, and the symbol on it.
    static let hero = Color(light: 0xFFD35A, dark: 0x39335F)
    static let onHero = Color(light: 0x1F1B3A, dark: 0xFFC83D)
    /// Cards, and the small circles behind a point's symbol.
    static let card = Color(light: 0xFFF6E8, dark: 0x221E3D)
    static let chip = Color(light: 0xF5E9D6, dark: 0x2E2950)
    /// The "Recommended" tag and the mic key in the welcome picture: sunflower with ink, in both.
    static let sunflower = Color(light: 0xFFD35A, dark: 0xFFC83D)
    static let onSunflower = Color(light: 0x1F1B3A, dark: 0x1F1B3A)
    /// Help cards.
    static let notice = Color(light: 0xFFE8B0, dark: 0x3B3322)
    static let success = Color(light: 0x1B7A4A, dark: 0x7DDBA3)
    /// Section headers ("How it works", "Today"): the Android app's dark gold, sunflower on dark.
    static let heading = Color(light: 0x7A5900, dark: 0xFFD35A)
    /// The stop key while a take records.
    static let recording = Color(light: 0xFF3B30, dark: 0xFF6961)
    static let error = Color(light: 0xBA1A1A, dark: 0xFFB4AB)

    // Illustrations: every drawing of a chat, a keyboard or Settings sits on these, in a soft labelled frame
    // (`IllustrationFrame`), with thin light lines, so it reads as a picture and never as a control.

    /// The frame's background.
    static let canvas = Color(light: 0xF5F0E7, dark: 0x211D38)
    /// Drawn rows and text boxes.
    static let surface = Color(light: 0xFFFCF7, dark: 0x2C2742)
    /// The fine lines of a drawing, and the frame's edge.
    static let line = Color(light: 0xCFC7BA, dark: 0x554E69)
    /// Empty fields, switches that are off, and the frame's label.
    static let muted = Color(light: 0xE8E2D7, dark: 0x3A344F)
    /// The drawn keyboard and its keys.
    static let keyboardGlass = Color(light: 0xDDE0E5, dark: 0x19191C)
    static let keyFace = Color(light: 0xFFFFFF, dark: 0x3A3A3C)
    /// A drawn Settings switch that is on: iOS's green, as the person will see it in Settings.
    static let switchOn = Color(light: 0x34C759, dark: 0x30D158)
    /// Sam's message in a drawn chat: the chip color, a step lighter in Dark Mode, so it shows on the chat's card.
    static let bubble = Color(light: 0xF5E9D6, dark: 0x3A344F)
    /// The thin edge just outside a tap cue's yellow ring, so the ring stands out on light and dark canvases alike.
    static let cueEdge = Color(light: 0x7A5900, dark: 0xFFF0B8)
    /// A drawn switch's knob: white in both, as iOS draws it.
    static let knob = Color(light: 0xFFFFFF, dark: 0xFFFFFF)
    /// Shadows, at the opacity each one sets.
    static let shadow = Color(light: 0x000000, dark: 0x000000)
}

extension Color {
    /// One color for light mode and one for dark, each as 0xRRGGBB.
    init(light: UInt32, dark: UInt32) {
        self.init(uiColor: UIColor { traits in
            let hex = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: CGFloat(hex >> 16 & 0xFF) / 255, green: CGFloat(hex >> 8 & 0xFF) / 255,
                           blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
        })
    }
}
