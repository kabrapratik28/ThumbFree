# docs: project documentation

| Path | What is there |
|---|---|
| `decisions/` | Why the build and the engine are the way they are, with the measurements behind each choice |
| `benchmarks/` | Dated speed and accuracy results, each with its method and caveats |
| `brand/` | The brand kit: `common/` (logo, bubble art, colours, fonts, illustration style), `android/` (Google Play) and `ios/` (App Store) |
| `privacy-policy.md` | The Android privacy policy's source text; the website publishes it as `android-privacy.html`. The iPhone app's pages come from `ios/docs/privacy.md` and `ios/docs/support.md` |

## Invariants

- Everything here describes the project as it is. A plan, a task log, a brainstorm or a review note is not
  documentation and does not belong in this folder.
- A decision record says what was decided, when, why, and how it was measured. Change it in the same commit as the code
  it describes; `native-build.md`'s patch table follows the patch series file by file.
- A benchmark names the device, the build, the clips, the number of runs and how the numbers were summed up, and says
  what it can't show. Private clips appear only as counts and error rates, never as text.
- The README's speed and accuracy figures and the store listing's come from `benchmarks/`. When a result changes,
  change all three.
- `privacy-policy.md` and its published copy, `android-privacy.html` on the public repository's `gh-pages` branch, say
  the same thing: change both together. Both match what the app does: its permissions, its one network use and what it
  keeps on the phone. The store listing, the welcome screen and Settings > About must not contradict them.
- Code changes never touch `gh-pages`, the website. Maintainers commit under the project's name and email, never a
  personal one.
- Screenshots and store images show made-up content only: the chat and notes scenes from the test APK, and sample
  history and Dictionary words.
- Links between documents are relative, so they work on GitHub and in a clone.

## Testing

- `python3 docs/brand/android/check.py` (needs Pillow) checks the store images against Google Play's rules and
  the listing's lengths and wording.
- `python3 docs/brand/android/make_assets.py --serif <Source Serif 4 Display .ttf> --font <Roboto .ttf>` rebuilds
  the store images from `art/`, `raw/` and the logo; `--art <folder>` takes the illustrations from elsewhere.
- `python3 ios/tools/store-frames.py` rebuilds the iPhone store images from `docs/brand/ios/art/` and `raw/`
  (`docs/brand/ios/README.md` has the fonts and the capture step).
- Preview Markdown on GitHub before merging: the README uses a Mermaid diagram.

## Pitfalls

- Name a build by its date, or by a commit in this repository's history. Commits from before the public history
  began don't exist here.
- The website is the public repository's `gh-pages` branch, not this folder. The store listing and the app (Settings >
  About) link `https://kabrapratik28.github.io/ThumbFree/android-privacy.html`, and the App Store listing links
  `privacy.html` and `support.html`, so renaming those pages breaks them.
