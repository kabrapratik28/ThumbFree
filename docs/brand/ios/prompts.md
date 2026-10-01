# ThumbFree: ChatGPT prompts for the App Store images

ChatGPT draws the **art only** (illustrations, no text, no phone screens). `ios/tools/store-frames.py` then builds the eight final App Store screenshots at Apple's size (1320 x 2868 px). Each one has:
- the illustration;
- the headline in a crisp font;
- the **real** app inside a phone frame, never a mock-up.

## Sizes

| What | Size | Who makes it |
|---|---|---|
| **The art from ChatGPT** | Portrait, **1024 x 1536 px** (2:3), PNG. The largest portrait size ChatGPT offers; bigger is fine. | The maintainer, with ChatGPT |
| **The final App Store screenshots** | **1320 x 2868 px**, portrait, PNG or JPEG, no transparency (Apple's 6.9-inch iPhone size) | `ios/tools/store-frames.py` |

Apple only needs the 6.9-inch size for iPhone and scales it down for smaller iPhones. Upload 1 to 10 screenshots; this set has 8.

## How to use this

1. Open a new ChatGPT chat with image generation.
2. Paste the **style prompt** below, once.
3. Then paste the **image prompts** one at a time, and generate each image.
4. If an image has any text, letters, logos or a phone screen in it, ask ChatGPT to regenerate it without them.
5. Save the images as `1.png` to `6.png` in `docs/brand/ios/art/`.
6. Build the screenshots and review them before anything goes to Apple (`docs/brand/ios/README.md`).

`languages.png` and `names.png` are the Android set's `android-7.png` and `android-5.png`, the same files: their prompts are in `docs/brand/android/prompts/` ("Speak your language" and "Names spelled your way").

---

## Style prompt (paste once, first)

```
I'm making illustrations for an iPhone app's App Store screenshots. The app is ThumbFree, a private, offline voice keyboard: you talk, and it types in any app.

Style: warm, friendly editorial illustration. Hand-drawn navy line art with flat color fills, generous empty space.

Palette:
- cream background #FFF8E7
- sunflower yellow #FFC83D as the main accent
- deep navy #26264A for lines
- a small touch of coral #FF6B5B

Rules for every image:
- no text, letters or numbers
- no logos, brand names or real app icons
- no phone screens or user interfaces
- one illustration, isolated on a plain cream background, so it can be cut out
- portrait, 1024 x 1536 pixels
- the same style and palette in every image, like one set
```

---

## Image prompts (one at a time)

### 1.png: frame 1, "Talk. It types."

```
A person walking with a coffee in one hand and a phone in the other, speaking into the phone. Soft yellow sound waves leave their mouth and turn into neat flowing lines that land in a speech bubble.
```

### 2.png: frame 2, "Your words, right where you type"

```
A loose arc of floating rounded-square tiles in cream, yellow, navy and coral. Each tile has a simple generic symbol (a speech bubble, an envelope, a note page, a calendar grid, a pencil). One sunflower-yellow circle with a small navy microphone sits at the center of the arc.
```

### 3.png: frame 3, "Talk as long as you like"

```
A person leaning back relaxed, hands behind their head, talking. A long sheet of paper covered in wavy lines unrolls from a phone on the table beside them.
```

### 4.png: frame 4, "Works offline. Your voice is never uploaded."

```
A phone held safely inside a soft rounded shield. A small padlock on the shield. Outside, a little cloud with a gentle line through it drifts away. Calm and safe.
```

### 5.png: frame 6, "A full keyboard, too"

```
A playful scatter of floating keyboard keys, a few round smiling faces (simple drawn ones, not emoji from any phone), and one larger sunflower-yellow key with a navy microphone in the middle.
```

### 6.png: frame 8, "Every take, saved on your iPhone"

```
A tidy stack of cards with wavy lines and small check marks, like a saved notebook, with a sunflower-yellow ribbon bookmark.
```

---

## Why these rules

- **Real screens only:** the screenshots show the actual app, as people will get it. The phone screens come from the real app, never from ChatGPT.
- **No text in the art:** image generators often misspell words. `ios/tools/store-frames.py` adds the headlines in a clean font.
- **No other companies' logos:** third-party app logos (WhatsApp, Slack and the like) are other companies' trademarks, so the art leaves them out.
- **Eight images:** Apple allows 1 to 10. The first three show in search results, so they carry the message: talk and it types, any app, long notes.
