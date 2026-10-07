# Clean up: on-device model APIs on Android and iOS

Research for GitHub issue #1, 2026-10-07. Private planning note (git-ignored). Each answer is marked **Verified** (a
source URL, a quote from the installed SDK, or a file I inspected) or **Unverified** (a third-party report or my own
inference).

What I checked first-hand:

- iOS: Xcode 27.0, the iPhoneOS 27.0 and iPhoneSimulator 27.0 SDKs. `FoundationModels.framework`'s `.swiftinterface`
  (the API) and `.swiftdoc` (Apple's doc comments); the device and Simulator interfaces are identical. UIKit headers for
  Writing Tools and `UITextDocumentProxy`.
- Android: `~/.gradle/caches` has no GenAI artifacts, so I downloaded the AARs and POMs from Google Maven to `/tmp`
  and read their manifests, dependencies and classes: `genai-proofreading` and `genai-rewriting` 1.0.0-beta1,
  `genai-prompt` and `genai-common` 1.0.0-beta4, their transitive libraries, and `litertlm-android` 0.18.0. AOSP sources
  (main branch) for how Android ranks an accessibility service. ThumbFree's code for the bubble's window type and the
  pinned manifest.
- No phone, emulator or Simulator. Claims that need one are Unverified and listed under "Checks to run".

## Summary

**What works**

- **iOS.** Foundation Models gives the ThumbFree app Apple's on-device model with our own instructions, so every style
  is a prompt. The app may call it while in the background. `.permissiveContentTransformations` stops guardrail errors
  for plain-text output. iPhone 15 Pro and newer, Apple Intelligence on, 16 languages.
- **Android.** ML Kit GenAI runs Gemini Nano inside AICore with no model in our APK: Proofreading (with a `VOICE` input
  type), Rewriting (`SHORTEN`, `FRIENDLY`, `PROFESSIONAL` and three more) and a Prompt API for our own instructions.
  All beta, on 74 flagship phone and tablet models (Pixel 9 and newer, Galaxy S25 and newer, and others).

**What blocks tap-to-clean**

- **Android, hard block.** AICore runs inference "only when the app is the top foreground application". Any other
  call, "including using a foreground service", gets `ErrorCode.BACKGROUND_USE_BLOCKED` (30) or `BUSY` (9). An
  accessibility service is bound as a foreground service (importance 125, not the top app's 100), and our bubble is an
  accessibility overlay, which Android does not even count as overlay UI. So the bubble cannot call Gemini Nano while
  the user's app is in front, and "Clean up every time" (option C) cannot work at all. The only allowed way is a
  ThumbFree activity in front during the call, which takes the field's focus. (The rule is verified; the error from our
  service is unverified on a phone but follows from it.)
- **Android, other ML Kit costs.** The GenAI terms bar users under 18 and any product "likely to be accessed by
  individuals under the age of 18". The SDK uploads usage metrics to Google (IDs, latency, sizes, language settings,
  error codes; not the text), and the terms make us tell users, which contradicts "Nothing leaves your phone".
  Proofreading and Rewriting take under 256 tokens per call and are unchanged since May 2025. The terms forbid
  circumventing "technical ... restrictions", so an invisible activity that exists only to pass the foreground check
  is a risk; a visible sheet the user works in is ordinary use.
- **iOS, soft limits.** In the background the model has a "system defined rate limit"; Apple says "There is no rate
  limit when your device is connected to power, or when your app runs in the foreground". Our app runs in the
  background only during its live session (5 minutes by default, `AppSettings.defaultSessionMinutes`); after that a
  keyboard tap reaches a suspended app. The keyboard calling the model itself is allowed by the SDK, but I found no
  proof that it works.
- **iOS, no shortcut.** A keyboard cannot open Writing Tools on the host's text.

**Recommended architecture**

| | Android | iOS |
|---|---|---|
| Where the model runs | Our own small model in a ThumbFree process (LiteRT-LM or llama.cpp), called by the accessibility service on the tap. No foreground rule, no Google metrics, no age clause. If ML Kit is chosen instead: the tap opens a ThumbFree sheet activity that runs the call while it is in front (supported phones only, no option C). | The ThumbFree app, in the background during its live session: `SystemLanguageModel(useCase: .general, guardrails: .permissiveContentTransformations)`, a new `LanguageModelSession` per request with the style's instructions, non-streaming `respond(to:options:)`, greedy sampling. The keyboard sends a `clean` command (take id, style) through the App Group like a press; the app writes the result to the outbox. If the keyboard spike passes (check 3), the keyboard calls the model itself instead, which removes the live-session window and the background rate limit. |
| How the text is replaced | In the take's own input session: `getSurroundingText` confirms the raw text sits right before the cursor, then `setSelection(start, end)` and one `commitText(cleaned)`, then a read-back. Any mismatch: no write, offer Copy. Undo is the same in reverse. | The keyboard checks its pin (same `documentIdentifier`, `documentContextBeforeInput` ends with the raw text, nothing selected), calls `deleteBackward()` once per character of the raw text, then `insertText(cleaned)`, then reads back. If the host's context is too short to show the raw text, no write; offer Copy. Undo is the same in reverse. |
| Availability | 8 GB of RAM or more, 64-bit, and the model downloaded and verified by size and SHA-256 like the speech models. ML Kit route: `checkFeatureStatus()` / `checkStatus()` gives `UNAVAILABLE`, `DOWNLOADABLE`, `DOWNLOADING` or `AVAILABLE`. | `availability` is `.available` or `.unavailable(.deviceNotEligible / .appleIntelligenceNotEnabled / .modelNotReady)`, plus `supportsLocale()` and the take's language against `supportedLanguages`. The app publishes it in `status.json`; the keyboard shows Clean up only when it is available and the app is live. |
| Fallback | The raw text stays. | The raw text stays (also after `rateLimited`, a guardrail error or a refusal). |

Why our own model on Android: the tap comes from the bubble while another app is in front, which is exactly the case
AICore refuses. The price is a 0.5 to 2.6 GB download, about 1 to 2 GB of RAM while it runs, a few seconds per take on
a Pixel, and our own quality checks. If that price is too high, ML Kit with a sheet is the fallback design; it gives
up "Clean up every time" and needs the age, metrics and terms questions answered first.

**Checks to run** (device spikes, about a day)

1. Android, Pixel 9 or 10: call `checkFeatureStatus()` and `runInference()` from the accessibility service with another
   app in front and log the error code. Then the same from a ThumbFree sheet activity, and check whether the field gets
   back an input session that our pin can accept when the sheet closes.
2. Android, only if ML Kit is chosen: exclude `transport-backend-cct` in Gradle and confirm GenAI still works.
3. iPhone 15 Pro or newer: from the app in the background on battery, run 20 cleanups in a row and count `rateLimited`.
   From the keyboard extension, read `availability`, run one `respond`, and watch the keyboard's memory.
4. Both: run the five styles over the transcripts of `testdata/public-bench/` and compare outputs and times.

---

## Android: ML Kit GenAI and Gemini Nano

### 1. Which APIs fit (Verified)

| API | Artifact, latest on Google Maven on 2026-10-07 | Status | minSdk | Use for Clean up |
|---|---|---|---|---|
| Proofreading | `com.google.mlkit:genai-proofreading:1.0.0-beta1` (May 2025, the only version) | Beta | 26 | Clean. `ProofreaderOptions.InputType.VOICE`: "The input is produced through converting voice to text." |
| Rewriting | `com.google.mlkit:genai-rewriting:1.0.0-beta1` (the only version) | Beta | 26 | Shorter, Friendly, Professional = `SHORTEN`, `FRIENDLY`, `PROFESSIONAL`. Also `ELABORATE`, `EMOJIFY`, `REPHRASE`. |
| Prompt API | `com.google.mlkit:genai-prompt:1.0.0-beta4` (2026-07-21) | Beta (system instructions beta, structured output alpha, prefix caching experimental) | 26 | Any style with our own instructions, including Simple. Needs a phone with nano-v2 or newer. |

`genai-common` is at 1.0.0-beta4. No GenAI artifact is stable. Source: Google Maven
`com/google/mlkit/group-index.xml`, the release notes, and the API pages.

Names from the docs' samples:

- Proofreading: `ProofreaderOptions.builder(context).setInputType(ProofreaderOptions.InputType.VOICE).setLanguage(ProofreaderOptions.Language.ENGLISH).build()`,
  `Proofreading.getClient(options)`, `checkFeatureStatus()`, `downloadFeature(DownloadCallback)`,
  `ProofreadingRequest.builder(text).build()`, `runInference(request)` (a streaming overload takes a callback), `close()`.
- Rewriting: the same shape with `RewriterOptions.builder(context).setOutputType(RewriterOptions.OutputType.SHORTEN)`,
  `Rewriting.getClient(...)`, `RewritingRequest.builder(text).build()`.
- Prompt API: `Generation.getClient(generationConfig { modelConfig = modelConfig { releaseStage = ModelReleaseStage.STABLE; preference = ModelPreference.FAST } })`,
  `checkStatus()`, `download()` (a Flow of `DownloadStatus`), `generateContent(generateContentRequest(TextPart(text)) { temperature = ...; topK = ...; seed = ...; candidateCount = ...; maxOutputTokens = ... })`,
  `generateContentStream(...)`, `warmup()`, `countTokens(...)`, `getBaseModelName()`.

Notes:

- Proofreading's docs say dictated text "tends to have sound-alike word mix-ups"; their VOICE example is "Please meat me
  at the bear," to "Please meet me at the bar." Removing fillers, repeats and self-corrections ("seven, no, seven
  thirty") is not documented; test it. You always get "at least one suggestion", the most confident first.
- The fixed APIs have tuned prompts and LoRA adapters but preset styles only; the Prompt API needs our own prompt work
  (the docs rate its effort "High").
- Prompt API model choice: `ModelReleaseStage.STABLE` (default) or `PREVIEW` (needs the AICore Developer Preview; the
  terms keep "Preview" features out of production), and `ModelPreference.FULL` or `FAST`.

### 2. Devices, availability check, download, unsupported phones

- **Devices** (Verified, overview page updated 2026-10-07). Proofreading and Rewriting: Pixel 9, 10 and 11 (base, Pro,
  Pro XL, Pro Fold); Galaxy S25 and S26 (base, +, Ultra), Z Fold7, Z Flip8, Z Fold8 and Fold8 Ultra, Z TriFold; Honor
  400 Pro, Magic 7, 7 Pro, 8 Pro, V5; iQOO 13, 15; Lenovo Idea Tab Pro Gen 2, Legion Tab Gen 5; Motorola Razr 60 Ultra,
  Razr Ultra 2025, Signature; OnePlus 13, 13s, 15, 15R; OPPO Find N5, Find X8 and X8 Pro, Find X9 and X9 Pro, Reno 14
  Pro 5G, Reno 15 Pro, Pro Mini, Pro Max; POCO F7 Ultra, F8 Pro, F8 Ultra, X7 Pro, X8 Pro; realme GT 7 Pro, GT 7T; Sharp
  AQUOS R11; Sony Xperia 1 VIII; vivo X200, X200 Pro, X200T, X200 FE, X300, X300 Pro, X Fold3 Pro, X Fold5, T4 Ultra;
  Xiaomi 14T Pro, 15, 15T, 15T Pro, 15 Ultra, 17, 17 Ultra, Pad Mini.
- **Prompt API by Nano version** (Verified, same page): nano-v3 on Pixel 9 and 10, Galaxy S26, OnePlus 15, OPPO Find
  X8 and X9 and others; nano-v4 (built on Gemma 4) on Pixel 11 and Galaxy Z Flip8, Z Fold8; nano-v2 on the rest. The
  Galaxy S25 family, Honor 400 Pro, vivo X Fold3 Pro and X Fold5 have the fixed APIs but not the Prompt API. Output
  can differ between Nano versions; `getBaseModelName()` tells which one a phone has.
- **Requirements** (Verified): API 26 or newer, the AICore app, and a locked bootloader ("Unlocked bootloaders are
  unsupported").
- **Check** (Verified): `checkFeatureStatus()` (fixed APIs) or `checkStatus()` (Prompt API) returns
  `FeatureStatus.UNAVAILABLE`, `DOWNLOADABLE`, `DOWNLOADING` or `AVAILABLE`, "the definitive status of feature
  availability on the device at runtime". Creating a client never throws for an unsupported setup; the status is the
  only signal. Whether calling the check from the accessibility service is also blocked is unverified (check 1).
- **Download** (Verified): `downloadFeature(DownloadCallback)` with `onDownloadStarted(bytesToDownload: Long)`,
  `onDownloadProgress(totalBytesDownloaded: Long)`, `onDownloadCompleted()`, `onDownloadFailed(e: GenAiException)`; the
  Prompt API's `download()` emits the same steps. Optional: the first inference starts it. If Nano is already on the
  phone, only the feature's small LoRA adapter downloads.
- **Size**: Google publishes none; read `bytesToDownload` (Verified). Reports: a feature adapter about 27 MB; the base
  model, when missing, 4.2 GB (Galaxy S26, Nano 3) and 5.7 GB (Pixel 10 Pro XL, a preview model,
  googlesamples/mlkit #1078) (Unverified).
- **Unsupported phones** (Verified): the status is `UNAVAILABLE`, or calls fail with `AICORE_INCOMPATIBLE` (-101,
  "AICore is not installed or its version is too low") or `NEEDS_SYSTEM_UPDATE` (604). Right after a reset: `601
  BINDING_FAILURE`, `606 FEATURE_NOT_FOUND` (config not synced yet, "a few minutes to a few hours"), or a download error.
  Hide Clean up.

### 3. The foreground rule (critical)

**The rule** (Verified, overview page, section "Background usage"): "GenAI API inference is permitted only when the app
is the top foreground application." A call when the app is "not in the foreground, including using a foreground
service, will result in an `ErrorCode.BACKGROUND_USE_BLOCKED` response."

**The errors** (Verified, `GenAiException.ErrorCode` reference):

- `BACKGROUND_USE_BLOCKED` = 30: "Background usage is blocked. Please use the API when your app is in the foreground
  instead."
- `BUSY` = 9: "The service is currently busy", either because the app "is out of usage quota (please retry with
  exponential backoff)" or because of background use that isn't permitted. Handle both codes.

**Exceptions**: none documented. No page mentions accessibility services or input methods (Verified by absence). An
open issue reports error 30 for an overlay sidebar app: "Even overlays are not considered usage"
(googlesamples/mlkit #1013, February 2026, no reply from Google) (Unverified).

**Why our service is not the top app** (Verified from AOSP and our code; the conclusion for AICore is inference):

- AOSP `AccessibilityServiceConnection` binds every accessibility service with
  `BIND_AUTO_CREATE | BIND_FOREGROUND_SERVICE_WHILE_AWAKE | BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS | BIND_INCLUDE_CAPABILITIES`.
  That gives the process a foreground-service state, which `ActivityManager.RunningAppProcessInfo.procStateToImportance`
  maps to `IMPORTANCE_FOREGROUND_SERVICE` (125). Only the top states map to `IMPORTANCE_FOREGROUND` (100). During a take
  our microphone foreground service gives the same 125.
- Our bubble and preview are `TYPE_ACCESSIBILITY_OVERLAY` (`a11y/BubbleWindow.kt`, `a11y/PreviewPanel.kt`). AOSP
  `Session.onWindowSurfaceVisibilityChanged` counts only `isSystemAlertWindowType` windows as overlay UI, and that list
  (`TYPE_PHONE`, `TYPE_PRIORITY_PHONE`, `TYPE_SYSTEM_ALERT`, `TYPE_SYSTEM_ERROR`, `TYPE_SYSTEM_OVERLAY`,
  `TYPE_APPLICATION_OVERLAY`) has no accessibility overlay. Even an app overlay only reaches
  `PROCESS_STATE_IMPORTANT_FOREGROUND` (`OomAdjuster`), which is importance 200.
- `genai-prompt` 1.0.0-beta4 checks this in our own process too: class `zzec` calls
  `ActivityManager.getMyMemoryState(RunningAppProcessInfo)` and returns `importance <= threshold`, with the threshold
  coming from configuration at run time (read with `javap`). The docs' foreground-service example implies 100.
- So from the bubble, with another app in front, expect error 30 or 9 (Unverified on a phone; check 1).

**Allowed ways around it**

1. **A ThumbFree activity in front during the call.** An accessibility service may start activities from the
   background (its binding carries `BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS`) (Verified, AOSP). Costs (Unverified until
   check 1): the activity takes window focus, the keyboard closes, and the field's input session ends. When the sheet
   closes the field gets a new session, which our pin refuses today ("Another field, even in the same view, refuses the
   write", `a11y/AGENTS.md`), so the pin rule must learn to accept the same node again. Make it real UI (Cleaning up,
   the result, Replace). A transparent activity whose only job is to pass the check is a risk under the terms: "You
   won't circumvent any technical or geographical restrictions of the Services." (Verified, GenAI terms.)
2. **Not allowed**: a foreground service (named in the rule), the bubble or any overlay (not top), a notification or
   PendingIntent alone (it still has to open an activity).
3. **Our own model** (question 6): AICore's rule does not apply to it.

"Clean up every time" (option C) needs inference right after the take while the user's app is in front: impossible
with ML Kit.

### 4. Languages, limits, speed, quota, battery, error codes

- **Languages** (Verified): Proofreading and Rewriting take English, Japanese, French, German, Italian, Spanish and
  Korean (`ProofreaderOptions.Language`, `RewriterOptions.Language`). The Prompt API has no published list ("may vary
  with the device's configuration"). Many of our multilingual model's languages (Dutch, Portuguese, Polish and others)
  are outside the fixed APIs' list.
- **Limits** (Verified): Proofreading and Rewriting: "Input should be less than 256 tokens." Prompt API: "Input must be
  under 4000 tokens (or approximately 3000 English words)"; output up to 4096 tokens since beta3. Long takes need
  splitting by sentence for the fixed APIs.
- **Speed** (Verified figures, older models): nano-v2 on Pixel 9 Pro, prefill 510 tokens/s, decode 11 tokens/s (May
  2025 blog). nano-v3 on Pixel 10 Pro, prefill 940 tokens/s, no decode figure (August 2025 blog). One developer: about
  1.5 s per Prompt API call on a Pixel 10 Pro XL (googlesamples/mlkit #1070) (Unverified). At 11 tokens/s an 80-token
  cleaned take takes about 7 s (my arithmetic). `warmup()` loads Nano ahead of the first call.
- **Quota** (Verified): AICore "enforces an inference quota per app". Bursts get `BUSY` (retry with exponential
  backoff; `GenAiException.getRetryDelay()`); a long, for example daily, quota gets `PER_APP_BATTERY_USE_QUOTA_EXCEEDED`
  (27). No numbers. One report: `BUSY` after 36 to 41 calls in about 60 s, recovering over a sliding window (#1070)
  (Unverified). One tap per take will not reach it. The terms add: "Google may enforce rate limits."
- **Battery**: no figures for Gemini Nano.
- **Error codes** worth handling (Verified): 30 `BACKGROUND_USE_BLOCKED`, 9 `BUSY`, 27 `PER_APP_BATTERY_USE_QUOTA_EXCEEDED`,
  8 `NOT_AVAILABLE`, 12 `REQUEST_TOO_LARGE`, 4 `REQUEST_PROCESSING_ERROR` and 15 `RESPONSE_GENERATION_ERROR` (the
  request fails a policy check), 11 `RESPONSE_PROCESSING_ERROR` (the output fails a policy check; a stream may stop
  half way), 7 `CANCELLED`, 501 `NOT_ENOUGH_DISK_SPACE`, -101 `AICORE_INCOMPATIBLE`, 604 `NEEDS_SYSTEM_UPDATE`.

### 5. What the dependency adds, and what leaves the phone

**Manifest and code** (Verified, read from the AARs):

- Each GenAI AAR declares `<uses-permission android:name="com.google.android.apps.aicore.service.BIND_SERVICE"/>`,
  `<queries><package android:name="com.google.android.aicore"/></queries>` and minSdk 26. `ManifestContractTest`
  pins the merged permissions, so it would have to list the new one.
- Transitive libraries: `com.google.mlkit:common:18.11.0` (adds `MlKitInitProvider`, a content provider that runs at
  start in every process, `:engine` included, and `MlKitComponentDiscoveryService`), `play-services-basement`,
  `play-services-tasks`, `firebase-encoders`, `com.google.guava:listenablefuture`, and
  `com.google.android.datatransport` `transport-api`, `transport-runtime` (a `JobInfoSchedulerService` and an alarm
  receiver) and `transport-backend-cct` (declares `INTERNET` and `ACCESS_NETWORK_STATE`; we already have both, for model
  downloads). `genai-prompt` also brings `genai-schema` and Kotlin coroutines.
- Size: the AARs are 0.58 MB (proofreading), 0.58 MB (rewriting), 1.1 MB (prompt) and 0.06 MB (common), plus the shared
  libraries above. The model lives in AICore, not in the APK.

**Data leaving the phone**

- Prompts and outputs: no (Verified). "Input, inference, and output data is processed locally" (May 2025 blog). AICore
  "does not have direct internet access"; downloads go through Private Compute Services, and AICore "doesn't store any
  record of the input data or the resulting outputs" (developer.android.com, Gemini Nano).
- SDK metrics: yes (Verified). ML Kit's Android data disclosure lists, for the GenAI APIs: device and app information,
  per-install IDs and "User, device or other identifiers used for diagnostics and usage analytics", performance metrics,
  API configuration, input and output sizes, language settings, event types and error codes. "ML Kit encrypts the data
  in transit using HTTPS." No opt-out is described. The GenAI terms: "You are responsible for informing users of your
  API Clients, including your app, about Google's processing of metrics data."
- Removing it: excluding `transport-backend-cct` from the Gradle graph should leave the transport with no backend to
  send to (Unverified; Google does not document it; check 2). `NetworkRegressionGuardTest` scans only our own sources,
  so it would not catch this upload.

**Terms and Play policy**

- GenAI terms (Verified): "You must be 18 years of age or older to use the APIs", and no product "that is directed
  towards or is likely to be accessed by individuals under the age of 18". The Prohibited Use Policy applies; "You may
  not attempt to bypass these protective measures" (the safety filters); no circumventing technical restrictions; "You
  will only use the Services to facilitate the features described in the ML Kit GenAI API documentation" (proofreading
  and rewriting are described ones). The age clause needs the owner's call on ThumbFree's audience.
- Play AI-Generated Content policy (Verified): out of scope "at this time" are "Productivity apps that use AI to improve
  an existing feature". Tidying our own dictation looks like that (my reading).
- Accessibility declaration: `a11y/AGENTS.md` says a change to the service changes what the Play declaration and the
  welcome disclosure say; a replace write is new behavior to describe there.

### 6. Our own model instead of AICore

**Runtimes**

- **LiteRT-LM** (Google, Apache 2.0), `com.google.ai.edge.litertlm:litertlm-android:0.18.0`, the latest on Google Maven
  (Verified, read from the AAR): 19.9 MB AAR, `liblitertlm_jni.so` 22.2 MB for arm64-v8a, minSdk 24, no permissions, no
  telemetry library (its dependencies are gson, kotlin-reflect and coroutines). Runs `.litertlm` files on CPU (XNNPACK)
  or GPU.
- **MediaPipe LLM Inference**: maintenance only; Google points Android apps to LiteRT-LM (Verified, MediaPipe docs).
- **llama.cpp** (MIT): GGUF files. We already build ggml 0.25.3 inside `third_party/transcribe.cpp`, but llama.cpp
  carries its own ggml copy, so the two would clash in one library. Build it as its own library in its own process
  (`:llm`), or pin a llama.cpp commit with the same ggml (Unverified engineering note).
- Run it in its own process, loaded on the tap and dropped after a short idle time, so it never sits next to Parakeet in
  `:engine`.

**Models**

| Model | License | File | RAM while running | Speed (decode) | Expected cleanup quality |
|---|---|---|---|---|---|
| Gemma 4 E2B | Apache 2.0 | 2.58 GB `.litertlm` (0.79 GB decoder weights, 1.12 GB embeddings memory-mapped) | 676 MB (GPU) to 1,733 MB (CPU) on Galaxy S26 Ultra | S26 Ultra: 47 tok/s CPU, 52 GPU (TTFT 1.8 s CPU, 0.3 s GPU). Pixel 10 CPU: about 10 to 23 tok/s (third-party) | Best here; 140+ languages (Unverified for our task) |
| Gemma 3n E2B | Gemma terms, gated download | not stated on the card | not stated | Galaxy S24 Ultra: 16.1 tok/s CPU, 15.6 GPU | Good; replaced by Gemma 4 E2B |
| Gemma 3 1B | Gemma terms, gated download | 529 MB (int4 QAT) | about 1.0 to 1.1 GB | S24 Ultra CPU: 47 tok/s (MediaPipe), 55 (LiteRT-LM) | Fair in English with a tight prompt (Unverified) |
| Gemma 3 270M | Gemma terms | not stated (270M parameters, 170M of them embeddings) | small | fast | Too weak without fine-tuning; Google says it is made to be fine-tuned. INT4 used "0.75% of the battery for 25 conversations" on a Pixel 9 Pro |
| Qwen3.5 0.8B | Apache 2.0 | 0.58 GB (Q4_K_M) | under 1 GB (Unverified) | about 20 to 30 tok/s on a Pixel 9 class phone (estimate from similar sizes) | Weak to fair; may answer instead of rewrite (Unverified) |
| Qwen3.5 2B | Apache 2.0 | 1.40 GB (Q4_K_M) | 1.5 to 2 GB (Unverified) | about 12 to 18 tok/s on a Pixel 9 class phone (estimate); 30 to 50 tok/s on iPhone 17 Pro (community) | Fair to good; 201 languages claimed (Unverified) |
| Qwen3 0.6B, 1.7B | Apache 2.0 | 0.48 GB, 1.28 GB (Q4_K_M) | under 1 GB, about 1.5 GB (Unverified) | similar to the Qwen3.5 sizes above (estimate) | Replaced by Qwen3.5 |
| Llama 3.2 1B | Llama 3.2 Community License ("Built with Llama", an acceptable use policy) | about 1 GB quantized (Unverified) | not checked | Meta reports 2.5x faster decode than BF16 on a OnePlus 12, no table in text | Fair in English; 8 official languages |

Speed sources: the litert-community model cards (Google's figures, 1,024 prefill and 256 decode tokens, 4 CPU threads)
and, for Pixels, third-party tests (Beebom, a llama.cpp discussion on a Pixel 9 Pro XL). Tensor chips are slower than
Snapdragon on CPU and throttle under load (Unverified).

**Quality evidence** (Verified papers, not our task): small models used zero-shot over-correct and invent more. In a
2026 study the best small model (Gemma 2B) reached F0.5 of about 16 on JFLEG against about 51 for GPT-3.5 Turbo; a
fine-tuned Qwen 2.5 1.5B reached F0.5 of 53.9 in a 2025 minimal-edit study. Removing fillers and repeats is easier than
learner grammar, but plan an evaluation on our clips and a guard that keeps the raw text when the output looks wrong
(for Clean: too many words dropped, or an answer instead of a rewrite).

**Pick for the spike**: Gemma 4 E2B on LiteRT-LM if a 2.6 GB download is acceptable; otherwise Qwen3.5 2B (0.8B on
6 GB phones) on llama.cpp. Both are Apache 2.0, which suits a public Apache 2.0 repository; the Gemma 3 family has use
restrictions and gated downloads. Try them first on the Mac (the `litert-lm` CLI and `llama-cli`) with our clips'
transcripts, then on a phone.

### Replacing the typed text on Android (Verified API, design proposal)

- `InputMethod.AccessibilityInputConnection` (API 33; read from the local `android-37.0` `android.jar`) has
  `commitText(CharSequence, int, TextAttribute)`, `setSelection(int, int)`,
  `getSurroundingText(int, int, int)`, `deleteSurroundingText(int, int)`, `sendKeyEvent`, `performContextMenuAction`,
  `performEditorAction`, `getCursorCapsMode`, `clearMetaKeyStates`. The writes return nothing, so every write needs a
  read-back.
- Steps: keep the take's raw text, its input session and node. On the result, in that same session:
  `getSurroundingText(raw.length + margin, margin, 0)` must show the raw text ending at a collapsed cursor; then
  `setSelection(end - raw.length, end)` and `commitText(cleaned, 1, null)`; then read back. Any mismatch: no write, keep
  the cleaned text for Copy.
- Use `setSelection` plus `commitText`, not `deleteSurroundingText` plus `commitText`: if the second call fails, the
  first way leaves the user's words selected, the second way loses them.
- `a11y/AGENTS.md` allows "one write (`commitText`) at the cursor and one check that it landed" per take. Replace and
  Undo are new writes after a new tap, so that invariant needs a sentence for them.

---

## iOS: Foundation Models

### 7. SystemLanguageModel (Verified from the iOS 27.0 SDK unless marked)

Declarations relied on (namespaces trimmed):

```swift
@available(iOS 26.0, macOS 26.0, visionOS 26.0, *)
final public class SystemLanguageModel : Sendable
extension SystemLanguageModel {
  final public var availability: SystemLanguageModel.Availability { get }
  final public var isAvailable: Bool { get }
  public static var `default`: SystemLanguageModel { get }
  convenience public init(useCase: SystemLanguageModel.UseCase = .general, guardrails: SystemLanguageModel.Guardrails = Guardrails.default)
  final public var supportedLanguages: Set<Locale.Language> { get }
  final public func supportsLocale(_ locale: Locale = Locale.current) -> Bool
}
@frozen public enum Availability : Equatable, Sendable {
  case available
  case unavailable(SystemLanguageModel.Availability.UnavailableReason)
}
public enum UnavailableReason : Equatable, Sendable {
  case deviceNotEligible
  case appleIntelligenceNotEnabled
  case modelNotReady
}
public struct UseCase : Sendable, Equatable {
  public static let general: SystemLanguageModel.UseCase
  public static let contentTagging: SystemLanguageModel.UseCase
}
```

- **Availability reasons**, from Apple's doc comments: `deviceNotEligible` "The device does not support Apple
  Intelligence."; `appleIntelligenceNotEnabled` "Apple Intelligence is not enabled on the system."; `modelNotReady`
  "The model isn't ready because it's downloading or because of other system reasons." The model is observable, so the
  app can watch `availability` change.
- **Eligible iPhones** (Verified, apple.com/apple-intelligence, October 2026): iPhone 15 Pro and 15 Pro Max; iPhone 16,
  16 Plus, 16e, 16 Pro, 16 Pro Max; iPhone 17, 17e, 17 Pro, 17 Pro Max, iPhone Air; iPhone 18 Pro, 18 Pro Max, iPhone
  Duo. Apple Intelligence needs up to 8 GB of storage (14 GB on iPhone 17 Pro, 17 Pro Max and Air).
- **Languages** (Verified, support.apple.com/121115, iOS 27): Chinese (Simplified and Traditional), Danish, Dutch,
  English, French, German, Italian, Japanese, Korean, Norwegian, Portuguese, Spanish, Swedish, Turkish, Vietnamese.
  Apple Intelligence needs "Device language and Siri language set to the same supported language". Check
  `supportsLocale()` (it "takes language fallbacks into consideration") and compare the take's language (for example
  from `NLLanguageRecognizer`) with `supportedLanguages`; a prompt in another language throws
  `unsupportedLanguageOrLocale`.
- **Context window**: iOS 26 doc comment: "the context window of 4,096 tokens. The token count includes instructions,
  prompts, and outputs for a session instance", about 3 to 4 characters per token in English. `contextSize` is
  back-deployed and returns 4096 before iOS 27; on iOS 27 it returns the model's own size (the WWDC26 sample prints
  8192; device not named). `tokenCount(for:)` is iOS 26.4 and newer.

```swift
@backDeployed(before: iOS 26.4, macOS 26.4, visionOS 26.4)
final public var contextSize: Int { get { if #available(iOS 27.0, ...) { return _contextSize }; return 4096 } }
@available(iOS 26.4, macOS 26.4, visionOS 26.4, *)
nonisolated(nonsending) final public func tokenCount(for prompt: some PromptRepresentable) async throws -> Int
```

- **Generation options**: `GenerationOptions(samplingMode:temperature:maximumResponseTokens:)`; `SamplingMode.greedy`,
  `.random(top:seed:)`, `.random(probabilityThreshold:seed:)`. Use greedy for cleanup. iOS 27 adds `toolCallingMode`.
- **Guided generation**: `@Generable`, `@Guide`, `GenerationGuide` (`anyOf`, `pattern`, ranges, counts),
  `DynamicGenerationSchema`. Not needed for cleanup, and permissive guardrails do not cover it (question 8).
- **Streaming**: `streamResponse(to:options:)` returns a `ResponseStream` of snapshots. For a replace we need the whole
  text anyway, and Apple says to avoid streaming in the background (question 9).
- **Concurrency**: "A language model session only supports one request at a time" (iOS 27 throws
  `LanguageModelSession.Error.concurrentRequests`). Use a new session per cleanup.
- **Prewarm**: `final public func prewarm(promptPrefix: Prompt? = nil)`. Doc: use it "when you have a window of at least
  1 second" before the request; it "doesn't guarantee that the system loads your assets immediately, particularly if
  your app is running in the background or the system is under load." Apple's Q&A: "prewarm can easily save 500ms" when
  the model is not loaded yet.
- **Model versions**: "Currently, there are 3 model versions that align with: iOS ... 26.0 - 26.3, 26.4, 27.0." Our
  prompts need checking on each.
- **iOS 27 variants**: `SystemLanguageModel.variant` returns `Variant.core3` ("AFM 3 Core") or `.coreAdvanced3` ("AFM 3
  Core Advanced"). Apple's bigger local model needs 12 GB of RAM (iPhone 17 Pro, Air and later Pro models), so the
  advanced variant is likely those (Unverified mapping, 9to5Mac and Apple's storage footnote).
- **Latency**: Apple's only official figure is for the 2024 model on iPhone 15 Pro: "about 0.6 millisecond per prompt
  token" before the first token and "30 tokens per second" (Verified, Apple ML research). Third-party, two runs: iPhone
  16 Pro Max, about 860 ms warm and 2.8 s cold for one short request (Unverified). I found no careful published
  comparison of iPhone 15 Pro, 16 and 17. Estimate for an 80-token take: about 3 s on iPhone 15 Pro, plus up to about
  2.5 s if the model is cold (my arithmetic).

### 8. Guardrails (Verified unless marked)

```swift
@available(iOS 26.0, macOS 26.0, visionOS 26.0, *)
extension SystemLanguageModel.Guardrails {
  public static let `default`: SystemLanguageModel.Guardrails
  public static let permissiveContentTransformations: SystemLanguageModel.Guardrails
}
```

- **Exact name and OS**: `SystemLanguageModel.Guardrails.permissiveContentTransformations`, available from iOS 26.0
  (the SDK annotation), so on every iOS 26 and 27 release. One Apple doc comment line and an Apple forum reply call it
  `permissiveContentTransform`; the API name is the one above.
- **What it does** (doc comment): it "lets the model handle potentially unsafe content, such as summarizing a news
  article. In this mode, requests you make to the model that generate a `String` will not throw
  `LanguageModelError/guardrailViolation(_:)` errors. However, the model may still sometimes refuse to respond to a
  sensitive prompt, in which case it generates a `String` refusal message. When you generate responses other than
  `String`, this mode behaves the same way as `SystemLanguageModel/Guardrails/default` mode".
- **Default mode**: "all guardrails are turned on. When the guardrails block unsafe content from either the prompt
  input or model response, the framework throws a `LanguageModelError/guardrailViolation(_:)` error."
- **Errors**: iOS 26 `LanguageModelSession.GenerationError.guardrailViolation(_:)` and `.refusal(_:_:)` (the refusal
  carries `explanation`). iOS 27 deprecates these for `LanguageModelError.guardrailViolation(_:)` and `.refusal(_:)`.
  Refusal doc: it "can happen for prompts that do not violate any guardrail policy, but the model isn't able to provide
  the kind of response you requested."
- **How often on ordinary text**: no official rate. Forum reports from the 2025 betas: about half of news summaries and
  camping questions blocked under default guardrails (Apple forum thread 787736) (Unverified). Apple's reply there:
  "We're actively working to improve the guardrails", the "#1 workaround" is permissive mode, and "the guardrails are
  influenced by locale". WWDC26: "adjustments in iOS 26.4 to reduce the number of false positives, and we're continuing
  to make even more improvements in iOS 27."
- **For us**: plain `String` output with permissive guardrails, and a check that catches a text refusal ("Sorry, I
  can't...") or an answer instead of a rewrite, which keeps the raw text. Using the permissive mode Apple provides is
  not circumvention; Apple's acceptable use rules forbid circumventing "any safety policies, guardrails, or
  restrictions" and set no age limit (Verified, acceptable use page).

### 9. Background, keyboard extension, iOS 27

**The app in the background**: allowed, with a rate limit (Verified).

- iOS 26 `GenerationError.rateLimited`: "This error will only happen if your app is running in the background and
  exceeds the system defined rate limit."
- On every `streamResponse`: "If running in the background, use the non-streaming `respond(to:options:)` method to
  reduce the likelihood of encountering `LanguageModelError/rateLimited(_:)` errors."
- iOS 27 `LanguageModelError.rateLimited(RateLimited)` carries `resetDate: Date?`: "This failure can happen if you make
  too many requests in a short window. You can recover from this error by spacing your requests or reducing system
  load."
- Apple's answer in the September 2025 code-along (third-party notes): "There is no rate limit when your device is
  connected to power, or when your app runs in the foreground". The numbers are not published (check 3).
- Our app is alive in the background only within its live session (`host.idleTimeout`, 5 minutes by default). A tap
  after that reaches a suspended app, so the keyboard should show Clean up only while `status.json` is fresh, unless the
  keyboard can call the model itself.

**The keyboard calling it directly**: allowed by the SDK, unproven at run time.

- The iOS 27 `.swiftinterface` has no `@available(iOSApplicationExtension, unavailable)` (0 matches), so a keyboard
  target can import and call the framework (Verified).
- Memory: the model runs in a system process, not ours. Apple: "A system daemon combines constrained decoding and
  speculative decoding" (Apple ML research, 2025), and the OS loads and unloads the models itself. So the weights
  should not count against the keyboard's limit, only the framework's client code and the strings (Unverified; measure,
  check 3). Apple publishes no keyboard memory limit; reports say about 48 to 70 MB (Unverified).
- Whether a visible keyboard counts as foreground for the rate limit, and whether it needs Full Access, is unknown. Our
  keyboard requires Full Access for dictation anyway.
- `ios/Keyboard/AGENTS.md` says the keyboard "never loads a model". Calling the system model loads nothing into the
  keyboard's process, but it is still a policy change for that file.

**What changes in iOS 27** (Verified, SDK): typed errors (`LanguageModelError`: `contextSizeExceeded`, `rateLimited`
with `resetDate`, `guardrailViolation`, `refusal`, `unsupportedLanguageOrLocale`, `timeout` and others;
`LanguageModelSession.Error.concurrentRequests`; `SystemLanguageModel.Error.assetsUnavailable`); the real
`contextSize`; `variant`; token `usage` on responses; `ContextOptions.reasoningLevel`; image attachments; a
`LanguageModel` protocol so other models can sit behind the same session API; custom adapters removed.
`PrivateCloudComputeLanguageModel` is a server model (it needs an entitlement and the network): never use it here,
since nothing may leave the phone. Nothing in the iOS 27 SDK changes the background or extension rules.

### 10. Can a keyboard open Writing Tools on the host's text? No (Verified)

- `UITextDocumentProxy` in the iOS 27 SDK has only `documentContextBeforeInput`, `documentContextAfterInput`,
  `selectedText`, `documentInputMode`, `documentIdentifier`, `adjustTextPositionByCharacterOffset(_:)`,
  `setMarkedText(_:selectedRange:)`, `unmarkText()`, plus `UIKeyInput`'s `insertText`, `deleteBackward`, `hasText`.
- `- (void)showWritingTools:(id)sender API_AVAILABLE(ios(18.2))` is a `UIResponderStandardEditActions` action. It acts
  on the first responder in the host's own process, which a keyboard extension cannot reach.
- The user can still select text and pick Writing Tools from the host's edit menu while our keyboard is up, unless the
  host turns it off with `writingToolsBehavior = .none` (Unverified reports).

### 11. Does the iOS Simulator run Foundation Models? Yes, with conditions (Verified)

- Apple DTS (forum thread 787199): "Foundation Models framework can indeed run in iOS and visionOS simulators running
  26+ as long as the Mac it's running on also supports Apple Intelligence and has it enabled." The simulators "use the
  models shipped with the macOS", so the Mac needs macOS 26 or newer. Code-along: "Simulator is supported if you're
  running on macOS 26.0!" and for `appleIntelligenceNotEnabled`, turn it on "on the Mac where Xcode is running, too".
- It says nothing about phone speed, memory or the background rate limit, and one developer reports failures on some
  26.2 to 26.4 beta simulators (Unverified). Apple: "running on a physical device is important for testing".

### Replacing the typed text on iOS (Verified API, design proposal)

- The keyboard cannot select, so a replace is `deleteBackward()` once per character of the raw text (Swift `Character`
  count) and then `insertText(cleaned)`.
- Before deleting: same `documentIdentifier` (read through `FieldTraits.documentID(of:)`), `documentContextBeforeInput`
  ends with the raw text, `selectedText` empty, and the text after the cursor unchanged since the pin. Hosts return only
  the text near the cursor, and a paragraph break can cut it short (Unverified reports); if the raw text is not all
  visible, do not delete; offer Copy.
- Put a "replace began" record (raw and cleaned) on disk before the first delete, like `insertionBegan`, so a crash
  between the delete and the insert leaves both texts for Copy and Undo. Read back after the insert.

---

## 12. Styles

**What each platform offers natively** (Verified):

- ML Kit Rewriting `RewriterOptions.OutputType`: `ELABORATE`, `EMOJIFY`, `SHORTEN`, `FRIENDLY`, `PROFESSIONAL`,
  `REPHRASE`. ML Kit Proofreading: fixes grammar and spelling, with `KEYBOARD` or `VOICE` input.
- Apple Writing Tools (iOS 27 support page): Proofread, Rewrite, Friendly, Professional, Concise, "Describe a change",
  plus Summary, Key Points, List and Table. ThumbFree cannot call Writing Tools on a string (question 10), so on iOS
  every style is a Foundation Models prompt that borrows these names.

**Recommended shared set**: keep the five in the design brief, with Clean as the default.

| Style | Android, ML Kit | Android, own model | iOS |
|---|---|---|---|
| Clean (default) | Proofreading `VOICE`; filler removal untested. Prompt API where present | Our prompt | Our prompt |
| Shorter | Rewriting `SHORTEN` | Our prompt | Our prompt (Writing Tools calls it Concise) |
| Friendly | Rewriting `FRIENDLY` | Our prompt | Our prompt |
| Professional | Rewriting `PROFESSIONAL` | Our prompt | Our prompt |
| Simple | Prompt API only (no Rewriting type; hidden on the Galaxy S25 family and other phones without it) | Our prompt | Our prompt |

- Leave out `ELABORATE`, `EMOJIFY` and `REPHRASE`: they add words or emoji, which goes against "your words".
- For similar results on both platforms, use the same instruction text everywhere (Prompt API or our model on Android,
  Foundation Models on iOS) rather than mixing ML Kit's fixed styles with our prompts. Every instruction says: keep the
  language and the meaning, do not answer the message, return only the rewritten text. Greedy sampling on both.
- "Shorter" is the plainer word; Apple users know the same thing as "Concise". Either works if both apps use one.

---

## Sources

Android

- ML Kit GenAI overview (devices, foreground rule, quota): https://developers.google.com/ml-kit/genai
- Proofreading: https://developers.google.com/ml-kit/genai/proofreading/android
- Rewriting: https://developers.google.com/ml-kit/genai/rewriting/android
- Prompt API: https://developers.google.com/ml-kit/genai/prompt/android ,
  https://developers.google.com/ml-kit/genai/prompt/android/get-started ,
  https://developers.google.com/ml-kit/genai/prompt/android/select-model
- Release notes: https://developers.google.com/ml-kit/release-notes
- Google Maven indexes: https://dl.google.com/android/maven2/com/google/mlkit/group-index.xml ,
  https://dl.google.com/android/maven2/com/google/ai/edge/litertlm/group-index.xml
- Error codes: https://developers.google.com/android/reference/com/google/mlkit/genai/common/GenAiException.ErrorCode
- Proofreading input types: https://developers.google.com/android/reference/com/google/mlkit/genai/proofreading/ProofreaderOptions.InputType
- ML Kit data disclosure: https://developers.google.com/ml-kit/android-data-disclosure
- GenAI terms: https://developers.google.com/ml-kit/genai-terms
- Gemini Nano and AICore privacy: https://developer.android.com/ai/gemini-nano
- Speed: https://android-developers.googleblog.com/2025/05/on-device-gen-ai-apis-ml-kit-gemini-nano.html ,
  https://android-developers.googleblog.com/2025/08/the-latest-gemini-nano-with-on-device-ml-kit-genai-apis.html
- Developer reports: https://github.com/googlesamples/mlkit/issues/1013 , https://github.com/googlesamples/mlkit/issues/1070 ,
  https://github.com/googlesamples/mlkit/issues/1078
- AOSP: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/accessibility/java/com/android/server/accessibility/AccessibilityServiceConnection.java ,
  https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/wm/Session.java ,
  https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/ActivityManager.java ,
  https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/WindowManager.java ,
  https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/am/OomAdjuster.java
- Background activity starts: https://developer.android.com/guide/components/activities/background-starts
- Play AI-Generated Content policy: https://support.google.com/googleplay/android-developer/answer/14094294
- MediaPipe LLM Inference status: https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference
- Models: https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm ,
  https://huggingface.co/litert-community/Gemma3-1B-IT , https://huggingface.co/google/gemma-3n-E2B-it-litert-lm ,
  https://developers.googleblog.com/en/introducing-gemma-3-270m/ ,
  https://huggingface.co/bartowski/Qwen_Qwen3.5-0.8B-GGUF , https://huggingface.co/bartowski/Qwen_Qwen3.5-2B-GGUF ,
  https://huggingface.co/bartowski/Qwen_Qwen3-0.6B-GGUF , https://huggingface.co/bartowski/Qwen_Qwen3-1.7B-GGUF ,
  https://huggingface.co/Qwen/Qwen3-0.6B , https://ai.meta.com/blog/meta-llama-quantized-lightweight-models/
- Pixel speeds (third-party): https://gadgets.beebom.com/guides/google-tensor-g6-vs-tensor-g5-benchmark-specs ,
  https://github.com/ggml-org/llama.cpp/discussions/23193
- Quality: https://arxiv.org/abs/2601.03874 , https://arxiv.org/abs/2506.13148

iOS

- Installed SDK: `Xcode.app/.../iPhoneOS27.0.sdk/System/Library/Frameworks/FoundationModels.framework/Modules/FoundationModels.swiftmodule/arm64e-apple-ios.swiftinterface`
  and `.swiftdoc`; the Simulator copy is identical. UIKit `UIInputViewController.h`, `UIResponder.h`, `UITextInput.h`.
- Devices: https://www.apple.com/apple-intelligence/ ; languages: https://support.apple.com/en-us/121115
- Latency: https://machinelearning.apple.com/research/introducing-apple-foundation-models ;
  model and daemon: https://machinelearning.apple.com/research/apple-foundation-models-2025-updates ;
  third-party: https://github.com/MGRL2201/mise/pull/107
- WWDC26 "What's new in the Foundation Models framework": https://developer.apple.com/videos/play/wwdc2026/241/
- iOS 27 local model tiers: https://9to5mac.com/2026/06/08/ios-27s-most-powerful-on-device-ai-requires-iphone-17-pro-iphone-air/
- Code-along Q&A notes (Apple's answers, third-party transcript): https://gist.github.com/stinger/81891077161002819c0ec233f73edc74
- Forums: Simulator https://developer.apple.com/forums/thread/787199 ; guardrails https://developer.apple.com/forums/thread/787736
- Acceptable use: https://developer.apple.com/apple-intelligence/acceptable-use-requirements-for-the-foundation-models-framework/
- Writing Tools: https://support.apple.com/guide/iphone/use-writing-tools-iph6f08da1d2/ios
