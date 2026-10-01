# Google Play store assets

| File | What it is |
|---|---|
| `screenshot-1-*.png` to `screenshot-7-*.png` | Phone screenshots, 1080 x 2160 (2:1, the longest shape Play allows), 24-bit PNG without alpha: a serif headline and an illustration on cream over the real app in a navy phone frame, in the style of the App Store images |
| `feature-graphic.png` | The feature graphic, 1024 x 500: the logo and the name, "Talk. It types." in the serif, and the feature illustration on the right |
| `store-listing.md` | App name, short description and full description, with notes on the numbers |
| `art/` | The owner's illustrations: `android-1.png` to `android-7.png` (1024 x 1536) and `feature.png` (1536 x 1024) |
| `raw/` | The emulator captures (1080 x 2424) the screenshots are made from |
| `make_assets.py` | Makes the screenshots and the feature graphic from the illustrations, `raw/`, `../common/logo/` and two fonts |
| `check.py` | Checks sizes, aspect ratios, PNG format and file sizes against Play's rules, and the listing's lengths and wording |

Screenshots, in order: "Talk. It types." (a chat, the bubble listening), "Your words, in any app" (the reply typed in), "Long notes, hands free" (a long note), "Works offline. Nothing leaves your phone." (the Try tab, ready), "Names spelled your way" (the Dictionary), "Every take, saved on your phone" (History) and "Speak your language" (Settings, the multilingual model and its languages). The colours are the App Store images' navy (38, 38, 74) and cream (255, 248, 231).

Each illustration is 480 px wide at the top right, over the phone's top; it shrinks only when its drawing would reach the phone. The feature illustration is as wide as the graphic unless its drawing needs a smaller size to keep 20 px from the top and bottom, and the logo and headline stay clear of it on the left.

The chat and the note are `ChatSceneActivity` and `NotesSceneActivity` in the test APK, with made-up text; the Dictionary words and the History takes are made-up samples. The captures come from an Android 16 emulator in the system demo mode, a clean status bar at 9:30. While the bubble listens, Android's green microphone indicator shows in the status bar.

To make them again: `python3 make_assets.py --serif SourceSerif4Display-Regular.ttf --font Roboto-Regular.ttf`, then `python3 check.py`. `--art <folder>` takes new illustrations from another folder, with the same file names. Frames 1 to 6 and the feature graphic each need theirs, and a missing one stops the run before anything changes; frame 7 is made only when `android-7.png` exists. The headlines are Source Serif 4 Display (SIL Open Font License), from Adobe's source-serif releases on GitHub; the name beside the logo is Roboto, the app's type, from an Android image's `/system/fonts` or Google Fonts. The fonts are not in the repository.

`prompts/` holds the prompts that made the illustrations in `art/` (one file per image; see its README), and `app-screenshots/` the app screens the repository README shows.
