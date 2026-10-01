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
    /// The mic key's ring while it listens.
    static let recording = Color(light: 0xFF3B30, dark: 0xFF3B30)
    static let error = Color(light: 0xBA1A1A, dark: 0xFFB4AB)
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
