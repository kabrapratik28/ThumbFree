# ThumbFree: App Store listing

Text to paste into App Store Connect for version 1.0 (or for `ios/tools/asc.py fill` to send, which reads this file by its table labels and `<!-- tag -->` blocks). The lengths were checked when this was written (counts in brackets); keep them under Apple's limits if you edit.

The description lists only what the app has today.

## App Information

| Field | Value |
|---|---|
| Name (30 at most) | ThumbFree: AI Voice Keyboard [28] |
| Subtitle (30 at most) | Private, offline dictation [26] |
| Primary category | Productivity |
| Secondary category | Utilities |
| Content rights | "Yes, it contains third-party content, and I have the rights to use it": the speech models are NVIDIA's Parakeet (CC BY 4.0) and Silero VAD (MIT), downloaded from Hugging Face, and the app includes the wordfreq word list (CC BY-SA 4.0); all are credited in Settings > About. |
| Age rating | 4+ (the answers are below) |
| Price | Free |
| Devices | iPhone only, iOS 26.0 or later |
| Copyright | 2026 Pratik Kabara |

If the name is taken: "ThumbFree: Offline Dictation" [28], the Android app's name, with the subtitle "Private AI voice keyboard" [25].

## Promotional text (170 at most)

<!-- promo -->
Talk instead of typing in your apps. Private, offline AI turns speech into text on your iPhone. Free, with no account, ads, tracking, in-app purchases or word limits.
<!-- /promo -->

[166] It can change at any time without a new review.

## Description (4,000 at most)

<!-- description -->
Talk instead of typing, right where your cursor is. ThumbFree is a private, offline voice keyboard for iPhone. Tap the mic, speak, and your words are typed into messages, email, notes and search boxes.

HOW IT WORKS
1. Tap a text box and switch to the ThumbFree keyboard with the globe key.
2. Tap the yellow mic at the top right and speak.
3. Tap it again. Your words are typed where your cursor is.

iOS does not let keyboards use the microphone, so the first tap of a session opens ThumbFree to turn it on, and one swipe takes you back to your app. While the session lasts, every tap starts at once, right where you are. Want to practice first? Tap Try it on ThumbFree's Home.

PRIVATE BY DESIGN
• Speech recognition runs on your iPhone, with NVIDIA's open Parakeet AI model. Your voice and your words are never uploaded.
• The internet is used only to download the speech model (about 465 MB for English, over Wi-Fi unless you allow mobile data). After that, ThumbFree works in Airplane Mode.
• No account, no sign-up and no tracking.

FREE
No subscriptions, no in-app purchases, no ads and no word limits.

FAST
Your text is ready about 0.1 seconds after you tap stop at the end of a sentence (measured on an iPhone 16 with iOS 26). Long takes are transcribed while you talk, so there is little left to do when you stop.

A FULL KEYBOARD
Type whenever you like: letters, numbers and symbols in the layout you know, emoji with search and skin tones, suggestions, autocorrect you can undo, your iOS text replacements, smart punctuation, accents when you hold a key, a space bar that moves the cursor, and a number pad for number fields.

YOUR DICTIONARY
Add the names and terms you say often. ThumbFree fixes close misspellings of them in your dictation, and autocorrect leaves them alone. Ums and uhs are left out.

HISTORY
Your recordings and transcripts are kept on your iPhone and left out of backups. Search it, copy it or transcribe it again. Choose how long takes are kept, or clear them all.

LANGUAGES
Choose English, or a Multilingual model that understands 25 European languages.

WHY FULL ACCESS
iOS asks for Full Access before a keyboard can work with its own app. ThumbFree's keyboard uses it to start the mic in ThumbFree, bring your words back and share your Dictionary. The keyboard makes no network requests. Without Full Access it still types, with suggestions and emoji.

GOOD TO KNOW
• Needs iOS 26 or later on iPhone.
• Password and phone number fields always use the iPhone's own keyboard.
• The open speech models and word list are credited in Settings > About.
<!-- /description -->

[2,588]

## Keywords (100 at most)

<!-- keywords -->
speech,text,typing,transcribe,transcription,dictate,talk,notes,write,email,memo,local,privacy,mic
<!-- /keywords -->

[97] Single words, because App Store search combines them with each other and with the name and subtitle ("voice" and "typing" also find "voice typing"). The name and subtitle are searched already, so "ThumbFree", "AI", "voice", "keyboard", "private", "offline" and "dictation" are left out. No other app's name or trademark, no "free" (a price, not a description of the app) and no "unlimited" (promotional, not descriptive).

## What's New

Not asked for a first version (1.0.1 became the first, after 1.0 was rejected). For a later version [124]:

<!-- whatsnew -->
A simpler setup: pick your language, and a short video floats over Settings to show exactly what to tap to add the keyboard.
<!-- /whatsnew -->

## Screenshots

Eight, at Apple's 6.9 inch size (1320 x 2868), in `screenshots/`: the maintainer's illustrations (`art/`), a headline and the real app in a phone frame, built by `ios/tools/store-frames.py` from the raw captures of `ios/tools/store-screenshots.sh` (`raw/`). The headlines:

1. Talk. It types.
2. Your words, right where you type
3. Dictate long notes
4. Works offline. Your voice is never uploaded.
5. Speak in 25 languages
6. A full keyboard, too
7. Names spelled your way
8. Your takes, saved on your iPhone

## URLs

| Field | Value |
|---|---|
| Privacy Policy URL (required) | https://kabrapratik28.github.io/ThumbFree/privacy.html |
| Support URL (required) | https://kabrapratik28.github.io/ThumbFree/support.html |
| Marketing URL (optional) | https://kabrapratik28.github.io/ThumbFree/ |

`ios/tools/publish-store-pages.sh` publishes the privacy and support pages from `ios/docs/privacy.md` and `ios/docs/support.md`; the maintainer runs it. The home page belongs to the website, which the script never changes.

## Age rating answers (4+)

Every answer is None or No:

- Parental controls: No. Age assurance: No.
- Unrestricted web access: No. Its few links (the models, their licenses and the project website) open in Safari.
- User-generated content, messaging and chat, advertising: No.
- Violence of any kind, sexual content or nudity, profanity or crude humor, horror or fear themes, mature or suggestive themes: None.
- Alcohol, tobacco or drug use, medical or treatment information, health or wellness topics, guns or other weapons: None.
- Gambling, simulated gambling, contests, loot boxes: None.

## App Privacy: "Data Not Collected"

In App Store Connect, App Privacy: "Do you or your third-party partners collect data from this app?" No.

Why that is the right answer:

- Speech is turned into text on the iPhone. Recordings and transcripts stay on the iPhone and out of backups; the Dictionary and settings are in the iPhone's own backups, like any app's settings. None of it is sent to you or anyone else.
- There are no accounts and no analytics, crash reporting, advertising or tracking code, and no third-party code at all that talks to a server.
- The one network use is downloading the speech model files, which are public, from Hugging Face at a pinned revision. The request is an ordinary HTTPS download with no account, no identifier made by ThumbFree and no dictation content. Hugging Face sees the IP address, as any web server does; the app neither receives nor uses it.
- The privacy manifests agree: `NSPrivacyTracking` is false, with no tracking domains and no collected data types.

Export compliance: the app's Info.plist says `ITSAppUsesNonExemptEncryption` NO (it uses only the HTTPS that iOS provides), so App Store Connect does not ask.
