# App Store assets

| File | What it is |
|---|---|
| `screenshots/1-talk.png` to `8-history.png` | The eight App Store screenshots, 1320 x 2868 (Apple's 6.9 inch iPhone size), RGB PNG without alpha: a serif headline and an illustration on cream over the real app in a navy phone frame. `ios/tools/asc.py fill` uploads every numbered PNG here, in order, so keep only the current set. |
| `raw/` | The Simulator captures (1320 x 2868) the screenshots are made from, with the same names |
| `art/` | The maintainer's illustrations, 1024 x 1536: `1.png` to `6.png`, `languages.png` and `names.png` (the last two are the Android set's `android-7.png` and `android-5.png`) |
| `app-store-icon-1024.png` | The App Store icon, 1024 x 1024, RGB without alpha. Apple takes it from the build: it is the same file as `ios/App/Assets.xcassets/AppIcon.appiconset/AppIcon.png` and the shared logo's `docs/brand/common/logo/app-icon.png`, so change all three together. |
| `listing.md` | The App Store listing: name, subtitle, promotional text, description, keywords, What's New, URLs, and the age rating and App Privacy answers. `ios/tools/asc.py` and `ios/tools/store-review.sh` read it by its table labels and `<!-- tag -->` blocks: keep them. |
| `prompts.md` | The prompts that made the illustrations in `art/` |

Screenshots, in order, with their illustration: "Talk. It types." (a message dictated in Messages, `1.png`), "Your words, right where you type" (the words in the message box, `2.png`), "Talk as long as you like" (a long note in Reminders, `3.png`), "Works offline. Your voice is never uploaded." (Home ready, with Try it and the walkthrough, `4.png`), "Speak in 25 languages" (a message in Spanish, `languages.png`), "A full keyboard, too" (emoji and suggestions, `5.png`), "Names spelled your way" (the Dictionary, `names.png`) and "Every take, saved on your iPhone" (History, `6.png`). The screens show made-up text and names, 555 phone numbers and the Simulator's sample contacts, with 9:41 in the status bar.

Apple's rules, checked 2026-09-29 (App Store Connect Help, Screenshot specifications): 6.9 inch portrait screenshots are 1260 x 2736, 1290 x 2796 or 1320 x 2868, PNG or JPEG, with no alpha, one to ten per set; App Store Connect scales them down for the smaller iPhones. The icon is 1024 x 1024 with no alpha.

To make them again, on a Mac with Xcode (from the repository root):

1. `ios/tools/store-screenshots.sh` runs `ios/UITests/StoreScreenshotsUITests.swift` on the "iPhone 17 Pro Max (store)" Simulator (the command that creates it is in the script's header) and writes the eight captures to `raw/`.
2. `python3 ios/tools/store-frames.py` (needs Pillow) builds `screenshots/` from `art/` and `raw/`. Each frame names its illustration, so reordering frames keeps their art; new art goes in `art/` under the same names.
3. `ios/tools/store-review.sh` makes a review page of the listing and the screenshots in `ios/build/store-review/index.html`.

The headlines and the name are set in Apple's system fonts (New York and SF), which Apple licenses for its own platforms: they stay on the Mac, are never committed, and are never used in the Android images. The logo and the brand colours are in `../common/`.
