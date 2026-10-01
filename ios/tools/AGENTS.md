# ios/tools: the iOS scripts

Scripts for the iOS app: tests (`test-kit.sh`, `test-app.sh`), the Simulator (`sim-enable-keyboard.sh`), the App
Store (`store-*.sh`, `store-frames.py`, `store-compose.swift`, `asc.py`, `publish-store-pages.sh`), models
(`fetch-models.py`, `gen-catalog.py`) and data (`gen-emoji.py`, `dump-apple-emoji.sh`, `dump-apple-keyboard.sh`,
`fetch-public-audio.py`).

## Invariants

- Each script finds `ios/` from its own path, so it runs from any folder, and runs git as `${GIT:-git}`.
- `asc.py`, `store-upload.sh` and `publish-store-pages.sh` act on App Store Connect or GitHub with the maintainer's
  accounts: only the maintainer runs them. `asc.py` keeps its key and contact details in `~/.appstoreconnect/`, never
  in the repository, and reads the listing by its table labels and `<!-- tag -->` blocks: keep them. It takes the
  review notes and contact email from a git-ignored file, named at the top of `asc.py`, when the listing has none.
- The store assets live in the repository's `docs/brand/ios/` (the listing, `art/`, `raw/`, `screenshots/`):
  `store-screenshots.sh` writes `raw/`, `store-frames.py` builds `screenshots/`, and `asc.py` uploads them.
- `publish-store-pages.sh` publishes `docs/privacy.md` and `docs/support.md`, which must match what the app does, as
  `privacy.html` and `support.html` on the website's `gh-pages` branch, and commits nothing else there.
- The store scripts leave `build/StoreRelease` and `build/StoreShots`: delete both when you are done.

## Pitfalls

- `dump-apple-keyboard.sh` and `dump-apple-emoji.sh` read Apple's keyboard on a Simulator: rerun them after each iOS
  release, then the tests that hold the code to their output.
