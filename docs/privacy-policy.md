# ThumbFree for Android: privacy policy

Last updated: September 30, 2026

ThumbFree turns your speech into text on your Android phone. It is built so that your voice and your words stay with you.

## The short version

- Your audio and your text never leave your phone.
- ThumbFree has no accounts, no analytics, no ads and no tracking.
- It uses the internet for one thing only: downloading its speech models from Hugging Face.

## What stays on your phone

Speech is turned into text on your phone, by NVIDIA's Parakeet speech model. No audio or text is sent to us or to anyone else.

ThumbFree keeps these in its private storage on your phone only:

- **History:** the recording of each take (an audio file), so you can transcribe it again, and its text, with the name of the app it was typed into. History follows your retention setting (Settings > History). By default it keeps takes for 7 days and at most 200 takes.
- **Your Dictionary** (the names and terms you added) **and your settings.**
- **The speech models** you download.

ThumbFree turns Android backups off, so none of this is copied to Google Drive or to another phone. When you tap Copy, the text goes to your phone's clipboard.

## The speech model download

When you set up ThumbFree, it downloads its English speech model (about 731 MB) from Hugging Face (huggingface.co), over Wi-Fi unless you allow mobile data. If you choose the Multilingual model (about 740 MB) or the experimental Canary model (about 218 MB), it downloads that one the same way. This is an ordinary HTTPS download of public files: as with any download, Hugging Face's servers see your IP address and which file was requested. No audio, text or information about you is sent with it. ThumbFree checks every file against a fixed fingerprint (SHA-256) before it uses it. After that, ThumbFree works without the internet. Hugging Face's own privacy policy covers their servers: https://huggingface.co/privacy

## Permissions, and why

- **Microphone:** to hear you while you dictate, only from your tap on the bubble until you stop. Android shows its microphone indicator while it is on, and the bubble shows a red ring.
- **Accessibility service** (you turn on "Use ThumbFree" in Android's accessibility settings): to show the bubble next to the text field you are typing in, and to type your dictation into that field after you tap the bubble. It reads only the field you are typing in and the text around the cursor, so your words, spacing and capitals land in the right place. It does not read the rest of your screen, does not record what you type yourself, and never sends anything anywhere. It never types into password fields.
- **Foreground services:** a microphone service while a take records, and a data sync service while a model downloads, so Android doesn't stop them halfway.
- **Internet and network state:** only to download the speech models, and to wait for Wi-Fi when you choose Wi-Fi only.
- **Keep awake and start at boot:** used by Android's download scheduler, so a model download can finish or resume after a restart.
- **Seeing which apps you have:** so History can say which app a take was typed into (for example "Typed into Messages"). That app name stays with the take on your phone.

ThumbFree does not ask for notification, contacts, location, camera or storage permissions.

## No accounts, analytics or ads

ThumbFree has no sign-in and no account. It has no analytics, crash reporting, advertising or tracking code. It does not share or sell data, because it does not collect any.

If you allow your phone to share usage and diagnostics data with Google, Google Play may show us reports when ThumbFree crashes or freezes. They describe the problem and your phone model and Android version. They contain no audio and no text.

## How to delete your data

- In History, delete a take, or tap Clear all to delete every take and its recording.
- In Settings > History, choose how long takes are kept (7, 30 or 90 days, or forever) and how many (50, 200 or 1,000, or no limit).
- In the Dictionary tab, delete any entry.
- In Settings > Speech model, delete a model.
- Uninstalling ThumbFree, or clearing its storage in Android's app settings, deletes everything it kept.

## Changes

If this policy changes, the new version will be posted here with a new date.

## Contact

Questions about this policy: thumbfree.app@gmail.com
