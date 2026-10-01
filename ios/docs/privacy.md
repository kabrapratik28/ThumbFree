# ThumbFree for iPhone: privacy policy

Last updated: September 29, 2026

ThumbFree turns your speech into text on your iPhone. It is built so that your voice and your words stay with you.

## The short version

- Your audio and your text never leave your iPhone.
- ThumbFree has no accounts, no analytics, no ads and no tracking.
- It uses the internet for one thing only: downloading its speech models from Hugging Face.

## What stays on your iPhone

Speech is turned into text on your iPhone, by NVIDIA's Parakeet speech model. No audio or text is sent to us or to anyone else.

ThumbFree keeps these on your iPhone only:

- **History:** the recording of each take (an audio file), so you can transcribe it again, and its text. History follows your retention setting (Settings > History), and it is left out of your iCloud and computer backups.
- **Your Dictionary** (the names and terms you added) **and your settings.** Like any app's settings, they are part of your iPhone's backups.
- **A small folder shared by the app and the keyboard,** which passes your words from the app to the keyboard. It is left out of backups too.
- **The keyboard's own settings,** such as the emoji you use most, your skin tones and the words you keep from being corrected (when you undo a correction, or tap your own spelling in the suggestions). They are part of your iPhone's backups, like other app settings.
- **The speech models** you download. They are left out of backups.

When you tap Copy, the text goes to this iPhone's clipboard only, not to your other Apple devices.

## The speech model download

When you set up ThumbFree, it downloads its English speech model (about 465 MB) from Hugging Face (huggingface.co), over Wi-Fi unless you allow mobile data. If you choose the Multilingual model, it downloads that one the same way. This is an ordinary HTTPS download of public files: as with any download, Hugging Face's servers see your IP address and which file was requested. No audio, text or information about you is sent with it. ThumbFree checks every file against a fixed fingerprint (SHA-256) before it uses it. After that, ThumbFree works without the internet. Hugging Face's own privacy policy covers their servers: https://huggingface.co/privacy

## Permissions, and why

- **Microphone:** to hear you while you dictate. It turns on when you tap the mic, and it stays on for the session length you choose in Settings (5 minutes unless you change it), so your next tap starts at once. iOS shows an orange dot while it is on. End session in ThumbFree turns it off at once.
- **Background audio:** so a dictation session keeps recording while you are in another app.
- **The ThumbFree keyboard and Full Access** (you turn them on in Settings): iOS asks for Full Access before a keyboard can work with its own app. ThumbFree's keyboard uses it to reach ThumbFree's shared folder on this iPhone, to start the mic in ThumbFree and to get your words back, and so Copy can use this iPhone's clipboard. The keyboard reads only the text around the cursor in the field you are typing in, so your words, spacing and capitals land in the right place and its suggestions fit. iOS also gives it your text replacements and the words on your contact cards, as it does any keyboard that asks, so it can suggest them and never correct them; it holds them in memory only. It stores none of that text apart from the words you keep, never goes online and never sends anything anywhere. To take you back to your app after the first tap of a session, the keyboard also notes which app you are typing in (its identifier, such as com.apple.MobileSMS for Messages) and passes it to ThumbFree, which opens that app again; ThumbFree remembers which apps it has taken you back to, so a first-time tip shows once per app. That stays on your iPhone too. Settings > Go back to the app automatically turns it off.
- **Network:** only to download the speech models.

## No accounts, analytics or ads

ThumbFree has no sign-in and no account. It has no analytics, crash reporting, advertising or tracking code. It does not share or sell data, because it does not collect any.

If you turned on Share With App Developers in iOS's settings (Privacy & Security > Analytics & Improvements), Apple may pass us crash reports when ThumbFree crashes. They describe the crash and your iPhone model and iOS version. They contain no audio and no text.

## How to delete your data

- In History, delete a take, or tap Clear all to delete every take and its recording.
- In Settings > History, choose how long takes are kept (7, 30 or 90 days, or forever) and how many (50, 200 or 1,000, or no limit).
- In the Dictionary tab, delete any entry.
- In Settings > Speech model, delete a model.
- Deleting ThumbFree from your iPhone deletes everything it kept.

## Changes

If this policy changes, the new version will be posted here with a new date.

## Contact

Questions about this policy: thumbfree.app@gmail.com
