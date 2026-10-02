# ThumbFree: instructions for contributors and AI agents

This file is the one source of instructions for people and AI agents working on ThumbFree. `CLAUDE.md` files only point
here. Folders with rules of their own have an `AGENTS.md` too (listed at the end); read the one for the folder you touch.

## What ThumbFree is

Offline push-to-talk dictation. The Android app, for Android 13 and newer, lives in `android/`: a floating bubble, run
by an accessibility service, shows next to the focused text field in any app. Tap it, speak, tap again, and the words
are typed into the field. Speech becomes text on the phone: NVIDIA Parakeet models on transcribe.cpp and ggml, in a
separate `:engine` process. The app is Kotlin with Jetpack Compose; the engine is C++ built with the NDK. The iOS app,
for iOS 26 and newer, lives in `ios/`, in Swift with SwiftUI and UIKit: iOS lets only a keyboard type into other apps,
so the ThumbFree keyboard asks the ThumbFree app to record. The iOS app has its own engine, NVIDIA Parakeet models on
Core ML (`ios/ThumbFreeKit/`); `third_party/transcribe.cpp` and `third_party/patches/` are the Android app's alone.

## Repository layout

| Path | What is there |
|---|---|
| `android/` | The Android app's Gradle project; its `AGENTS.md` has the build, test and device steps |
| `android/app/src/main/kotlin/io/github/kabrapratik28/thumbfree/` | The app, one package per area (its `AGENTS.md` has the map) |
| `android/app/src/main/cpp/` | The JNI bridge to the engine and the Silero voice detector |
| `android/app/src/main/aidl/` | The Binder interface between the app and `:engine` |
| `android/app/src/main/res/`, `android/app/src/main/assets/` | Strings, drawables, themes; the bundled Silero model |
| `android/app/src/test/` | Host tests (JUnit, Robolectric), the same package layout as the app |
| `android/app/src/androidTest/` | Device tests: engine, accessibility, end to end, benchmarks |
| `android/tools/` | The Android app's build checks, phone scripts and benchmarks |
| `android/brand.properties` | The application id and app name, and nowhere else (`BrandTest`) |
| `ios/` | The iOS app: `project.yml` (xcodegen writes the Xcode project from it); its `AGENTS.md` has the build and test steps |
| `ios/App/`, `ios/Intents/`, `ios/Widgets/` | The app: session, audio, model downloads, the four tabs; Start ThumbFree |
| `ios/Keyboard/`, `ios/Shared/` | The keyboard extension, and the code it shares with the app |
| `ios/ThumbFreeKit/` | The Swift package: `TFCore` (text rules, take state, audio rules, History) and `TFEngine` (Parakeet on Core ML) |
| `ios/AppTests/`, `ios/UITests/` | App unit tests (Swift Testing) and UI tests (XCTest), run on a Simulator |
| `ios/tools/`, `ios/docs/`, `ios/testdata/public/` | The iOS scripts; the iOS contract, benchmarks, privacy and support pages; its public clips |
| `third_party/transcribe.cpp` | The Android app's speech engine, a git submodule pinned to one upstream commit |
| `third_party/patches/` | ThumbFree's patch series for that submodule, and `transcribe-patches.cmake`, the build step that applies it |
| `testdata/public-bench/` | 11 public clips (JFK and LibriSpeech) with their reference texts, for the benchmark tools |
| `docs/` | Decisions, benchmarks, brand kit (`common/`, `android/`, `ios/`), design boards, privacy policy |
| `tools/` | Tools for the whole repository: `check-agent-docs.py` for these files; `normalize-patches.py` and `v3-check.py` (the multilingual model through the patched engine, on a Mac) for the patches |

## Build

Clone with the submodule, which the Android app needs: `git clone --recursive`, or `git submodule update --init`
afterwards. Each app builds in its own folder; the Android steps are in [android/AGENTS.md](android/AGENTS.md), the iOS
steps in [ios/AGENTS.md](ios/AGENTS.md).

The first build applies `third_party/patches/` to the submodule, so `git status` then shows it as modified. That is
expected. Never commit the submodule's changes, and never commit inside it.

## Device safety

Test on an emulator or a test phone of your own, never on a personal phone by accident, and name the device in every
command. One emulator per person or agent; agents that share one take a lock around each device command. The Android
details are in `android/AGENTS.md`; the iOS ones (a Simulator of your own, no UI tests on a phone you use) are in
`ios/AGENTS.md`.

## Privacy rules

- No private recordings or transcripts in the repository, ever. Private test audio lives in `testdata/private/`, which
  git ignores. Tests and fixtures use public audio (see `THIRD_PARTY_NOTICES.md`) and made-up text and names.
- Nothing leaves the device. No accounts, analytics, crash reporting or ads. The only network use is downloading speech
  models. On Android only `core/models/Downloader.kt` may name a network API (`NetworkRegressionGuardTest`), the merged
  manifest's permissions are pinned (`ManifestContractTest`), and the app allows no backup. On iOS only
  `ios/App/Models/` uses the network, and the keyboard never does.
- Logs carry numbers and codes, never the text of a take or a field.
- Screenshots and store assets show made-up content only.

## Conventions

- Comments say why, in plain sentences. Give every new class and object a KDoc. Match the style of the file you edit.
- Commit messages are one line: the area, a colon, then what changed and why, for example
  `Settings: the live preview is off by default, since it costs 3 times the energy`.
- Every change comes with tests: a host test for logic, a device test for Android behaviour, and for a bug a test that
  fails before the fix. Say in the pull request which device classes you ran.
- Add a dependency only when a few lines of code can't do the job.
- Measurements need a method: the device, the build, the clips and how many runs (see `docs/benchmarks/`).

## Writing style

- Never name or compare other dictation apps: not in code, comments, tests, docs, commit messages or store text.
  Credit the projects we build on in `THIRD_PARTY_NOTICES.md` only.
- Plain words and short sentences. No em-dashes. Skip filler words such as delve, leverage, robust, foster, seamless,
  crucial and comprehensive.
- Numbers keep their units and their source.

## Internal planning

Plans, specs, brainstorms, review notes and task logs go in `.superpowers/` at the repository root, which git ignores.
They never go in `docs/`, which is for project documentation that stays true for readers of the code.
`docs/superpowers/` is ignored as well, because some agent tools write plans there by default.

## Agent files

Rules for every `AGENTS.md` and `CLAUDE.md` in this repository:

1. The root `AGENTS.md` has at most 200 lines.
2. A folder `AGENTS.md` has at most 60 lines, and covers the folder's purpose, its invariants, how to test it and its
   known pitfalls.
3. Every `CLAUDE.md` is exactly one line, `@AGENTS.md`. Every `AGENTS.md` has a `CLAUDE.md` beside it, and every
   `CLAUDE.md` an `AGENTS.md`.
4. No personal data, secrets or links to private resources.
5. A folder file doesn't repeat the root file; it says only what is special about its folder.
6. When a change alters a folder's rules, update that folder's `AGENTS.md` in the same change.

`python3 tools/check-agent-docs.py` checks rules 1 to 5 and prints each violation; run it before every commit that
touches an agent file. After changing the script, run it with `--self-test`. Rule 6 is for review.

Folder files:

- [Android app](android/AGENTS.md), with [its tools](android/tools/AGENTS.md)
- [App packages](android/app/src/main/kotlin/io/github/kabrapratik28/thumbfree/AGENTS.md), with
  [a11y](android/app/src/main/kotlin/io/github/kabrapratik28/thumbfree/a11y/AGENTS.md),
  [audio](android/app/src/main/kotlin/io/github/kabrapratik28/thumbfree/audio/AGENTS.md),
  [core](android/app/src/main/kotlin/io/github/kabrapratik28/thumbfree/core/AGENTS.md),
  [engine](android/app/src/main/kotlin/io/github/kabrapratik28/thumbfree/engine/AGENTS.md) and
  [ui](android/app/src/main/kotlin/io/github/kabrapratik28/thumbfree/ui/AGENTS.md)
- [Native code](android/app/src/main/cpp/AGENTS.md)
- [iOS app](ios/AGENTS.md), with [App](ios/App/AGENTS.md), [Keyboard and Shared](ios/Keyboard/AGENTS.md),
  [ThumbFreeKit](ios/ThumbFreeKit/AGENTS.md), [UI tests](ios/UITests/AGENTS.md) and [tools](ios/tools/AGENTS.md)
- [Engine patches](third_party/patches/AGENTS.md)
- [Docs](docs/AGENTS.md)
