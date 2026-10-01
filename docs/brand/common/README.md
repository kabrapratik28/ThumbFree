# ThumbFree brand: shared by both apps

## Logo

A keyboard key tilted up off its own shadow, printed with a sound wave: the key presses itself, so your thumbs are free. The floating bubble uses the same artwork in a circle, so the bubble and the app icon always match.

| File | Use |
|---|---|
| `logo/app-icon.svg`, `logo/app-icon.png` | The app icon (square master artwork) |
| `logo/app-icon-preview.svg`, `logo/app-icon-preview.png` | The icon on a rounded tile, for web pages and slides |
| `logo/adaptive-icon-preview.png` | How the Android adaptive icon renders (circle, squircle, rounded square) |
| `logo/bubble-idle.svg`, `.png` | The floating bubble, ready to listen |
| `logo/bubble-recording.svg`, `.png` | The bubble while listening (red ring and pulse) |

The Android app's own vector drawables (launcher icon with a monochrome layer, bubble states) live in `android/app/src/main/res/`.

## Colours

| Role | Hex |
|---|---|
| Sunflower (background, light to deep) | `#FFD35A` to `#FFB61E`, accent `#FFC83D` |
| Key ink (text, dark surfaces) | `#1F1B3A`, top face `#39335F` |
| Recording | `#FF3B30` |

The app's Material 3 light and dark themes are built from these colors (cream backgrounds, charcoal text, sunflower highlights).

The store images add a cream background `#FFF8E7` and navy text `#26264A`.

## Fonts

- The Android app uses the system font (Roboto).
- The Google Play images set their headlines in Source Serif 4 Display (SIL Open Font License), downloaded from its official source and never committed.
- The App Store images use Apple's system fonts, which Apple licenses for Apple platforms only, so they are never used in the Android images.

## Illustration style

The store illustrations share one style. Use this as the style part of any new image prompt:

> Hand-drawn illustration with thick navy outlines (#26264A) and flat fills in warm yellow (#FFC83D), soft orange and coral red accents. Plain cream background (#FFF8E7) edge to edge, with no frame, border or shadow box. Small playful motion marks around the main objects. Friendly, simple, modern editorial doodle look. No gradients, no photo textures. No text, letters, numbers, logos or brand marks: squiggly lines where words would be. The app's signature element is a round, glossy yellow floating button with a small dark rounded-square icon showing a sound wave.

The prompts for each platform's images are in `android/prompts/` and `ios/`.
