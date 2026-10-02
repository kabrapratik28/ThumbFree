package io.github.kabrapratik28.thumbfree.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PersistableBundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withResumed
import io.github.kabrapratik28.thumbfree.BuildConfig
import io.github.kabrapratik28.thumbfree.R
import io.github.kabrapratik28.thumbfree.a11y.DictationAccessibilityService
import io.github.kabrapratik28.thumbfree.app.AndroidPorts
import io.github.kabrapratik28.thumbfree.app.AppGraph
import io.github.kabrapratik28.thumbfree.app.DbThread
import io.github.kabrapratik28.thumbfree.app.TrialHost
import io.github.kabrapratik28.thumbfree.core.models.Catalog
import io.github.kabrapratik28.thumbfree.core.models.ModelFile
import io.github.kabrapratik28.thumbfree.core.session.BubbleStyle
import io.github.kabrapratik28.thumbfree.core.session.ChipAction
import io.github.kabrapratik28.thumbfree.core.session.TouchOutput
import io.github.kabrapratik28.thumbfree.core.text.CustomWords
import io.github.kabrapratik28.thumbfree.data.Dictation
import io.github.kabrapratik28.thumbfree.data.HistoryDb
import io.github.kabrapratik28.thumbfree.data.HistoryWriteException
import io.github.kabrapratik28.thumbfree.data.Retention
import io.github.kabrapratik28.thumbfree.data.Settings.Companion.recentWait
import io.github.kabrapratik28.thumbfree.data.Status
import io.github.kabrapratik28.thumbfree.models.DownloadState
import io.github.kabrapratik28.thumbfree.models.ModelDownloads
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var setup by mutableStateOf<SetupState?>(null)
    private var history by mutableStateOf(emptyList<Dictation>())
    private var apps by mutableStateOf(emptyMap<String, String>())   // app names by package, for "Typed into Messages"
    private var storage by mutableStateOf(0L)
    private var words by mutableStateOf(AppGraph.settings.customWords)
    private var bubbleStyle by mutableStateOf(AppGraph.settings.bubbleStyle)
    private var bubblePlaced by mutableStateOf(AppGraph.settings.bubbleSpot != null) // dropped by the owner somewhere
    private var bubbleSnap by mutableStateOf(AppGraph.settings.bubbleSnap)
    private var dictionaryHint by mutableStateOf(false) // Home's one-time card, up for this visit
    private var checkHint = false // a visit began: the next history read decides whether the card shows
    private var retention by mutableStateOf(AppGraph.settings.retention)
    private var askRetention by mutableStateOf<Pair<Retention, Int>?>(null)   // a stricter rule and the takes it would delete
    private var showModels by mutableStateOf(false)
    private var downloads by mutableStateOf(emptyMap<ModelFile, DownloadState>())   // each catalog model's download
    private val models = ModelQueue(lifecycleScope)   // the welcome screen's download actions, in order
    private var tab by mutableStateOf(Tab.HOME)   // here, not in the composition, so finishing the welcome screens can open Home
    // The welcome step on screen, or null for the tabs. [revisit]: opened from Settings, so it doesn't move the
    // first-run bookmark and Close returns to Settings.
    private var welcome by mutableStateOf<WelcomeStep?>(null)
    private var revisit by mutableStateOf(false)
    // Set only by a setup row's Turn on (see [discloseService]): the disclosure is the whole visit, so Back, Close, Not
    // now and the return with the switch on all go back to the tab it opened from, instead of the try or the last step.
    private var discloseOnly by mutableStateOf(false)
    private var refusal by mutableStateOf<MicRefusal?>(null)
    private var stillOff by mutableStateOf(false) // back from the setting with the bubble's switch still off
    private var leftForAccessibility = false // Agree left for Android's settings in this process: a return of any age
    private var broughtBack = false // by the service, once the switch was on (AndroidPorts.returnToApp)
    private var backFromAccessibility = false // the resume after the trip: the setup read it starts says if the switch is on
    private var retryOnReturn = false // left for Android's storage settings from the finish: the download tries again on return
    // Step 1: the language choice shows again (Change, or Back from the try); the language was tapped in this visit (a
    // revisit asks again); a model whose load failed (the one loaded is welcomeLoaded), and whether a load runs.
    private var choosing by mutableStateOf(false)
    private var picked by mutableStateOf(false)
    private var loadFailedModel by mutableStateOf<ModelFile?>(null)
    private var loadingModel = false
    private var listenAfterGrant = false // the microphone was asked for by the try's bubble: a grant starts that take
    private var micAsking = false // a microphone request is out: another tap waits for its answer
    private var micAskedAt = 0L // when the microphone request went out: a refusal back at once was never asked
    private var resumeChecked = false // a first run left on step 1 with its download lost was started again
    private val snackbar = SnackbarHostState()
    private var loading: Job? = null

    // The welcome's try: its bubble's takes run through the app's one take machine, which keeps nothing of them.
    private val trialLink = object : TrialLink {
        override fun attach(host: TrialHost) = AppGraph.ports.attachTrial(host)
        override fun detach(host: TrialHost) = AppGraph.ports.detachTrial(host)
        override fun touch(output: TouchOutput) = AppGraph.ports.trialTouch(output)
        override fun chip(action: ChipAction) = AppGraph.controller.onChip(action)
    }

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micAsking = false
        // "Don't allow" twice, "don't ask again" or a device policy: only App info can grant it now. The first answer
        // ever may instead be a question dismissed with a tap outside, which reads the same to Android but can be asked
        // again.
        val rationale = shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        refusal = micRefusal(granted, rationale, askedBefore = AppGraph.settings.micAsked)
        AppGraph.settings.micAsked = true
        // Asked at the try's first tap: allowed, it listens at once, as the tap meant; asked by Allow microphone, it only
        // grants. Once resumed, so the try is attached.
        val listen = listenAfterGrant
        listenAfterGrant = false
        if (granted && listen && welcome == WelcomeStep.TRY) lifecycleScope.launch { lifecycle.withResumed { startTrialTake() } }
        val atOnce = appInfoAtOnce(refusal, fromBubble = listen, askedAt = micAskedAt, answeredAt = SystemClock.elapsedRealtime())
        if (welcome != null && atOnce) openAppSettings()
        if (refusal == MicRefusal.BLOCKED && welcome == null) say(R.string.ui_mic_blocked, R.string.ui_open, ::openAppSettings)
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // targetSdk 35+ draws edge to edge; this also picks dark or light status bar icons to match the theme. Every
        // screen pads itself: under the status bar, accessibility marks text invisible and UiAutomator can't find it.
        enableEdgeToEdge()
        // A rotation keeps what was on screen; a first launch opens the welcome screens where they were left.
        val shown = savedInstanceState?.getInt(SHOWN_STEP, -1) ?: -1
        revisit = savedInstanceState?.getBoolean(REVISIT) ?: false
        discloseOnly = savedInstanceState?.getBoolean(DISCLOSE_ONLY) ?: false
        choosing = savedInstanceState?.getBoolean(CHOOSING) ?: false
        picked = savedInstanceState?.getBoolean(PICKED) ?: false
        showModels = savedInstanceState?.getBoolean(MODELS) ?: false
        dictionaryHint = savedInstanceState?.getBoolean(HINT) ?: false
        tab = Tab.entries.getOrNull(savedInstanceState?.getInt(TAB) ?: 0) ?: Tab.HOME
        welcome = when {
            savedInstanceState != null -> WelcomeStep.entries.getOrNull(shown)
            !AppGraph.settings.welcomeDone -> AppGraph.settings.run { resumeAt(welcomeScreen, welcomeStepBefore) }
            else -> null
        }
        if (savedInstanceState == null) noteIntent(intent)
        setContent {
            AppTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    var showRetention by rememberSaveable { mutableStateOf(false) }
                    val step = welcome
                    when {
                        showModels -> {
                            // The model can finish verifying while this screen is open; refresh so the setup rows don't
                            // keep showing what they said before.
                            ModelsRoute(onBack = { showModels = false; refresh() }, chosen = setup?.chosen ?: AppGraph.settings.model, onChoose = ::useModel)
                        }
                        step != null -> {
                            BackHandler {
                                when {
                                    discloseOnly -> closeWelcome()
                                    step.ordinal > 0 -> back(step)
                                    // Back to the choice's download, unless it is loaded already: that would move on to the try again.
                                    choosing && welcomeChoice(ignoreChoosing = true).let { it != null && it != welcomeLoaded } -> choosing = false
                                    revisit -> closeWelcome()
                                    else -> finish()
                                }
                            }
                            val choice = welcomeChoice()
                            val inUse = setup?.chosen ?: AppGraph.settings.model // the model takes use
                            WelcomeScreen(step, setup, refusal, stillOff, WelcomeActions(
                                next = {
                                    if (discloseOnly || step == WelcomeStep.entries.last()) finishWelcome()
                                    else goTo(WelcomeStep.entries[step.ordinal + 1])
                                },
                                back = { if (discloseOnly) closeWelcome() else back(step) },
                                close = if (revisit) ::closeWelcome else null,
                                chooseLanguage = ::chooseLanguage,
                                change = { choosing = true },
                                loadModel = ::loadModel,
                                allowMic = { allowMic(listen = false) },
                                openAppSettings = ::openAppSettings,
                                openAccessibility = ::openAccessibility,
                                turnOnBubble = { goTo(WelcomeStep.SERVICE) },
                                trial = trialLink,
                                allowMicAndListen = { allowMic(listen = true) },
                                useMobileData = { ModelDownloads.start(this, AppGraph.settings.model, wifiOnly = false) },
                                retryDownload = ::getModel,
                                openStorage = { retryOnReturn = start(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) },
                            ), chosen = choice, download = choice?.let { downloads[it] },
                                loaded = welcomeLoaded == inUse, loadFailed = loadFailedModel == inUse,
                                suggested = welcomeModel(AppGraph.settings.selectedModelId?.let(Catalog::byId)), busy = models.pending > 0,
                                // The last step judges the model takes use, as Home and the bubble do.
                                inUseDownload = downloads[inUse])
                        }
                        else -> {
                            BackHandler(enabled = tab != Tab.HOME) { tab = Tab.HOME }
                            HomeScreen(tab, { tab = it }, snackbar) { current ->
                                when (current) {
                                    Tab.HOME -> HomeTab(
                                        setup, ::fix, setup?.let { downloads[it.chosen] },
                                        dictionaryHint = dictionaryHint && words.isEmpty(),
                                        onDictionary = { tab = Tab.DICTIONARY },
                                        onHintDone = { AppGraph.settings.dictionaryHintDone = true; dictionaryHint = false },
                                        onOpenModels = { showModels = true },
                                    )
                                    Tab.HISTORY -> HistoryScreen(
                                        history, apps, retention, storage,
                                        onCopy = ::copy, onDelete = ::delete,
                                        onTranscribe = { row -> AppGraph.controller.startRetranscribe(row.sessionId, insertAfter = false) },
                                        onClearAll = ::clearAll,
                                        onRetention = { showRetention = true; tab = Tab.SETTINGS },
                                        onHome = { tab = Tab.HOME },
                                    )
                                    Tab.DICTIONARY -> {
                                        // Seen: Home's card never needs to point here.
                                        LaunchedEffect(Unit) { AppGraph.settings.dictionaryHintDone = true; dictionaryHint = false }
                                        // Try a phrase matches as the chosen model's takes do.
                                        val chosen = setup?.chosen ?: AppGraph.settings.model
                                        DictionaryScreen(words, exactOnly = CustomWords.exactOnlyFor(chosen.languageHint)) {
                                            AppGraph.settings.customWords = it
                                            words = it
                                        }
                                    }
                                    Tab.SETTINGS -> SettingsScreen(
                                        setup, ::fix, onWelcome = ::revisitWelcome,
                                        onChoose = ::choose, onManageModels = { showModels = true }, downloads = downloads,
                                        bubbleStyle = bubbleStyle, onBubbleStyle = ::changeBubble,
                                        bubblePlaced = bubblePlaced, bubbleSnap = bubbleSnap, onBubbleSnap = ::snapBubble,
                                        onResetBubblePosition = ::resetBubblePosition,
                                        wordCount = words.size, onDictionary = { tab = Tab.DICTIONARY },
                                        retention = retention, storageBytes = storage, onRetention = ::changeRetention,
                                        showRetention = showRetention, onRetentionShown = { showRetention = false },
                                        version = BuildConfig.VERSION_NAME, onWebsite = ::openWebsite,
                                        onLink = { start(Intent(Intent.ACTION_VIEW, Uri.parse(it))) },
                                    )
                                }
                            }
                        }
                    }
                    askRetention?.let { (rule, count) ->
                        AlertDialog(
                            onDismissRequest = { askRetention = null },
                            title = { Text(stringResource(R.string.ui_retention_confirm_title)) },
                            text = { Text(pluralStringResource(R.plurals.ui_retention_confirm_body, count, count)) },
                            confirmButton = { Button({ askRetention = null; applyRetention(rule) }, colors = destructive()) { Text(stringResource(R.string.ui_delete)) } },
                            dismissButton = { TextButton({ askRetention = null }) { Text(stringResource(R.string.ui_cancel)) } },
                        )
                    }
                }
            }
        }
        // History writes while the screen is open (a Transcribe or Retry result, a take in another app) show at once.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { AppGraph.historyVersion.collect { refresh() } }
        }
        lifecycleScope.launch {
            // The owner can drag the bubble anywhere, in this app too: Reset position follows.
            repeatOnLifecycle(Lifecycle.State.STARTED) { AppGraph.bubbleMoves.collect { bubblePlaced = AppGraph.settings.bubbleSpot != null } }
        }
        // Each model's download as it goes. One that becomes ready (or stops being ready, after a delete) changes what the
        // model check says, so the setup is read again.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ModelDownloads.states(this@MainActivity)
                    .collect { now ->
                        val readyChanged = now.any { (model, state) -> (state == DownloadState.Ready) != (downloads[model] == DownloadState.Ready) }
                        downloads = now
                        if (readyChanged) refresh()
                        resumeDownload(now)
                    }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(SHOWN_STEP, welcome?.ordinal ?: -1)
        outState.putBoolean(REVISIT, revisit)
        outState.putBoolean(DISCLOSE_ONLY, discloseOnly)
        outState.putBoolean(CHOOSING, choosing)
        outState.putBoolean(PICKED, picked)
        outState.putBoolean(MODELS, showModels)
        outState.putBoolean(HINT, dictionaryHint)
        outState.putInt(TAB, tab.ordinal)
    }

    // The service brought the app to the front, which reuses this activity (AndroidPorts).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        noteIntent(intent)
    }

    /**
     * What the service started the app for: the return once the bubble's switch is on after Agree and open settings (the
     * next resume is the return), or the not-ready panel's Open, which opens the speech models (over a welcome step, if
     * one shows).
     */
    private fun noteIntent(intent: Intent) {
        if (intent.getBooleanExtra(AndroidPorts.EXTRA_BACK_FROM_ACCESSIBILITY, false)) broughtBack = true
        if (intent.getBooleanExtra(AndroidPorts.EXTRA_OPEN_SPEECH_MODELS, false)) showModels = true
    }

    // Grants change in Settings while we are away, and takes add rows, so both are read again on every resume.
    override fun onResume() {
        super.onResume()
        checkHint = true
        // The resume after Agree and open settings is the return: the service brought the app back, or the owner came back
        // by hand, in this process at any time (as always) or in a new one while the wait kept in Settings is recent. The
        // wait ends here either way, so an old one never counts on a later visit; a refused start leaves it for this
        // resume. Only a read that starts now can tell whether the switch was turned on: one that started before the user
        // left (the first read after a launch is slow) would finish with the switch as it was, and lose the return.
        val wait = AppGraph.settings.accessibilityWait
        if (broughtBack || leftForAccessibility || (wait != null && recentWait(wait, System.currentTimeMillis()))) {
            backFromAccessibility = true
        }
        broughtBack = false
        leftForAccessibility = false
        if (wait != null) AppGraph.settings.accessibilityWait = null
        // Back from freeing space: the download tries again, so the finish says what it found instead of "Not enough
        // space." until the next start.
        if (retryOnReturn) getModel()
        retryOnReturn = false
        refresh()
    }

    // Home's card is for one visit: leaving the app puts it away for good (its flag is already set).
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) dictionaryHint = false
    }

    /**
     * Home's card: on the first visit after a take that typed text, and only if the Dictionary is still empty
     * and was never opened. It shows at most once ever: showing it sets the flag that Open Dictionary, any added entry
     * and Not now set too, so a later visit never brings it back. A failed, cancelled or no-speech take never leads to
     * it, nor does the bubble or a notification.
     */
    private fun showDictionaryHint(rows: List<Dictation>) {
        if (welcome != null || AppGraph.settings.dictionaryHintDone || words.isNotEmpty()) return
        if (rows.none { it.status == Status.INSERTED && !it.finalText.isNullOrBlank() }) return
        dictionaryHint = true
        AppGraph.settings.dictionaryHintDone = true
    }

    private fun refresh() {
        loading?.cancel()   // an older load that finishes late must not overwrite a newer one
        loading = lifecycleScope.launch {
            // The setup rows and history show at once. The model row and the choice keep what they said ("Checking" at
            // first) until the check comes: right after a push that is a SHA-256 of each whole file, which takes seconds.
            val known = setup
            val rows = readHistory(AppGraph.ports.db, AppGraph.history)
            val state = withContext(Dispatchers.IO) {
                val chosen = AppGraph.settings.model
                SetupState.read(this@MainActivity, known?.choosing(chosen)?.model)
                    .copy(chosen = chosen, offered = known?.offered.orEmpty())
            }
            setup = state
            onSetupRead(state)
            retention = AppGraph.settings.retention
            if (rows != null) history = rows else say(R.string.ui_history_read_failed)
            if (checkHint && rows != null) {
                checkHint = false
                showDictionaryHint(rows)
            }
            withContext(Dispatchers.IO) {
                val labels = rows.orEmpty().mapNotNull { it.targetPackage }.distinct().mapNotNull { pkg -> appName(pkg)?.let { pkg to it } }.toMap()
                val bytes = File(filesDir, "recordings").listFiles()?.sumOf { it.length() } ?: 0L
                labels to bytes
            }.let { (labels, bytes) -> apps = labels; storage = bytes }
            val checked = withContext(Dispatchers.IO) { state.withModels(AppGraph.modelStore) }
            setup = checked
            // The try needs a model that makes words: without one (a bookmark from an older order, a model deleted
            // since), the first step waits for it.
            if (welcome == WelcomeStep.TRY && !checked.ok(SetupItem.MODEL) && downloads[checked.chosen] != DownloadState.Ready) {
                goTo(WelcomeStep.WELCOME)
            }
        }
    }

    /**
     * The welcome steps move on by themselves once the grant they asked for arrives from a settings screen: the bubble
     * step to the last one (or, for a setup row's disclosure, back to its tab). A return with the switch still off stays
     * on the step, which then says so, with Open settings again and the restricted-settings help. A microphone allowed in
     * App info ends the try's refusal.
     */
    private fun onSetupRead(state: SetupState) {
        val back = backFromAccessibility // only the first read after the return answers it
        backFromAccessibility = false
        if (state.micGranted) refusal = null
        when (welcome) {
            WelcomeStep.SERVICE -> if (back) {
                when {
                    !state.serviceEnabled -> stillOff = true
                    discloseOnly -> finishWelcome()
                    else -> goTo(WelcomeStep.READY)
                }
            }
            else -> Unit
        }
    }

    /** Back a step; from the try, the first step shows the language choice again rather than its finished wait. */
    private fun back(step: WelcomeStep) {
        if (step == WelcomeStep.TRY) choosing = true
        goTo(WelcomeStep.entries[step.ordinal - 1])
    }

    /**
     * The model step 1's choice picked: none while the choice shows ([choosing], unless [ignoreChoosing]), and on a
     * revisit none until a language is tapped again; a first run keeps the choice it made, across a restart too.
     */
    private fun welcomeChoice(ignoreChoosing: Boolean = false): ModelFile? {
        if (choosing && !ignoreChoosing) return null
        val chosen = AppGraph.settings.selectedModelId?.let(Catalog::byId) ?: return null
        return chosen.takeIf { picked || !revisit }
    }

    /**
     * English or Other languages: that model becomes the choice and its download starts at once, unless it is here or
     * under way already; one the choice leaves stops (as the speech models' Use this model does).
     */
    private fun chooseLanguage(model: ModelFile) {
        choosing = false
        picked = true
        forgetLoads()
        useModel(model)
    }

    /** Settings > Show the welcome screens: the first step asks the language again, and asks the engine afresh. */
    private fun revisitWelcome() {
        revisit = true
        stillOff = false
        picked = false
        choosing = false
        forgetLoads()
        welcome = WelcomeStep.WELCOME
    }

    /**
     * The welcome asks the engine again, rather than trusting an earlier load: it may have been unloaded after 5 idle
     * minutes, or died. A model still loaded answers at once.
     */
    private fun forgetLoads() {
        welcomeLoaded = null
        loadFailedModel = null
    }

    /**
     * Step 1, once the chosen model is on the phone and checked, and the last step should a new start of the app have lost
     * that load: the model takes use loads into the engine now, so the first take answers at once. Loaded, the welcome
     * moves on; a load that fails stops it there, with Try again, which calls this again. A choice changed while a load
     * ran loads in its turn.
     */
    private fun loadModel() {
        val model = AppGraph.settings.model
        if (loadingModel) return
        loadingModel = true
        loadFailedModel = null
        AppGraph.queue.preload { ok ->
            runOnUiThread {
                loadingModel = false
                when {
                    AppGraph.settings.model != model -> if (welcome != null) loadModel()
                    ok -> welcomeLoaded = model
                    else -> {
                        Log.w("ThumbFree", "welcome_load_failed")
                        loadFailedModel = model
                    }
                }
            }
        }
    }

    /**
     * A first run left on step 1 whose download was never queued (the process ended first) starts it again once its
     * state is known, the first time only.
     */
    private fun resumeDownload(now: Map<ModelFile, DownloadState>) {
        if (resumeChecked || welcome != WelcomeStep.WELCOME) return
        val choice = welcomeChoice() ?: return
        val state = now[choice] ?: return
        resumeChecked = true
        if (state == DownloadState.NotDownloaded && setup?.offered?.contains(choice) != true) ModelDownloads.start(this, choice)
    }

    /** The microphone just allowed at the try's first tap: that tap's take starts, as the tap meant. */
    private fun startTrialTake() {
        trialLink.touch(TouchOutput.Press)
        trialLink.touch(TouchOutput.Release(0))
    }

    private fun goTo(step: WelcomeStep) {
        welcome = step
        stillOff = false
        if (!revisit) AppGraph.settings.welcomeScreen = step.name
    }

    /**
     * The last step done: Home, which shows the model's download until it is ready. A disclosure opened from a setup row
     * ([discloseService]) is not the first-run flow, so it returns to the tab it opened from instead.
     */
    private fun finishWelcome() {
        val toHome = !discloseOnly
        closeWelcome()
        if (toHome) tab = Tab.HOME
    }

    /** Done or closed: the tabs show, and the first-run screens never open by themselves again. */
    private fun closeWelcome() {
        if (!revisit) AppGraph.settings.welcomeDone = true
        welcome = null
        revisit = false
        discloseOnly = false
        stillOff = false
        choosing = false
        picked = false
    }

    private fun fix(item: SetupItem) = when (item) {
        SetupItem.MIC -> allowMic()
        SetupItem.SERVICE -> discloseService()
        SetupItem.MODEL -> {
            // A wait for Wi-Fi offers mobile data instead: a start without Wi-Fi only replaces the wait.
            val waitsForWifi = (downloads[AppGraph.settings.model] as? DownloadState.Queued)?.let { it.wifiOnly && !it.retrying } == true
            if (waitsForWifi) ModelDownloads.start(this, AppGraph.settings.model, wifiOnly = false) else getModel()
        }
    }

    // Start and Try again are the same call: a failed download keeps its partial file and goes on from there.
    private fun getModel() = ModelDownloads.start(this, AppGraph.settings.model)

    // The microphone only: Android shows its own dot while the mic is on, and the recording and download services run
    // without the notification permission (their notifications then show only in Android's list of active apps). One
    // request at a time (micTap); listen: the try's bubble asked, so a grant starts that take.
    private fun allowMic(listen: Boolean = false) {
        when (micTap(micAsking, refusal)) {
            MicTap.WAIT -> Unit
            MicTap.APP_INFO -> openAppSettings()
            MicTap.ASK -> {
                listenAfterGrant = listen
                micAsking = true
                micAskedAt = SystemClock.elapsedRealtime()
                askMic.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun openAppSettings() {
        start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    // The Dictation bubble row's Turn on, on Home and in Settings: the disclosure (WelcomeStep.SERVICE) first,
    // since Google Play wants it shown right before every request for the setting, not only the welcome flow's first
    // one. Its own "I agree" (openAccessibility) is the only way from here to the setting; Back, Close and Not now
    // undo just this screen, back to the tab that opened it (see discloseOnly in finishWelcome, closeWelcome).
    private fun discloseService() {
        revisit = true
        discloseOnly = true
        stillOff = false
        welcome = WelcomeStep.SERVICE
    }

    // A phone without the accessibility list gets App info instead, and a line saying where the switch is. The wait, with
    // its time, lets the service bring the app back once the switch is on (AndroidPorts.returnToApp), and makes the
    // resume after the trip the return.
    private fun openAccessibility() {
        if (welcome == WelcomeStep.SERVICE) {
            leftForAccessibility = true
            AppGraph.settings.accessibilityWait = System.currentTimeMillis()
        }
        if (!openAccessibilitySettings(ComponentName(this, DictationAccessibilityService::class.java), ::start)) {
            Toast.makeText(this, getString(R.string.ui_accessibility_elsewhere, getString(R.string.app_name)), Toast.LENGTH_LONG).show()
        }
    }

    private fun openWebsite() {
        start(Intent(Intent.ACTION_VIEW, Uri.parse(WEBSITE)))
    }

    /** Opens [intent]; false when no app on the phone handles it. */
    private fun start(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.w("ThumbFree", "no_activity ${intent.action}")
        false
    }

    private fun appName(pkg: String): String? = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { // not installed any more, or not visible to us
        null
    }

    // Saved, and a bubble on screen takes it at once: its window is sized and placed again, no restart.
    private fun changeBubble(style: BubbleStyle) {
        AppGraph.settings.bubbleStyle = style
        bubbleStyle = style
        AppGraph.ports.bubbleSettingsChanged()
    }

    // Turned on, it tidies a bubble already dropped mid-screen at once, as the next drag would.
    private fun snapBubble(on: Boolean) {
        AppGraph.settings.bubbleSnap = on
        bubbleSnap = on
        if (on) AppGraph.settings.bubbleSpot?.let { AppGraph.settings.bubbleSpot = it.snapped() }
        AppGraph.ports.bubbleSettingsChanged()
    }

    // Back beside the keyboard, where it starts, until the next drag.
    private fun resetBubblePosition() {
        AppGraph.settings.bubbleSpot = null
        bubblePlaced = false
        AppGraph.ports.bubbleSettingsChanged()
    }

    // The next take loads it, as after an idle unload; a take already running keeps its model. Settings offers only a
    // verified model; the welcome's language may be chosen before its download, which useModel then starts. The screens
    // follow at once.
    private fun choose(model: ModelFile) {
        AppGraph.settings.selectedModelId = model.id
        setup = setup?.choosing(model)
        refresh()
    }

    /**
     * The speech models' Use this model: takes use [model] from the next one. As step 1's Change does, the switch first
     * stops the unfinished download of the model it leaves (removing its partial file), so it never runs on or stays for
     * nothing; a model not on the phone yet starts its download, whose state its card, the welcome's first step and the
     * setup rows then show. In order behind any earlier model action (ModelQueue), with the cancel's record written off
     * the main thread.
     */
    private fun useModel(model: ModelFile) {
        models.enqueue {
            val leaving = AppGraph.settings.model
            if (leaving != model && stopsOnSwitch(downloads[leaving])) withContext(Dispatchers.IO) { ModelDownloads.cancel(this@MainActivity, leaving) }
            choose(model)
            val state = downloads[model]
            val underWay = state is DownloadState.Queued || state is DownloadState.Downloading || state == DownloadState.Verifying
            if (state != DownloadState.Ready && !underWay && setup?.offered?.contains(model) != true) ModelDownloads.start(this@MainActivity, model)
        }
    }

    /** A rule that would delete takes asks first, with how many; one that deletes nothing applies at once. */
    private fun changeRetention(rule: Retention) {
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                try {
                    AppGraph.history.overRetention(rule, System.currentTimeMillis()).size
                } catch (e: Exception) {
                    Log.w("ThumbFree", "retention_read_error ${e.javaClass.name}")
                    0 // the rule still applies; retention's own write logs a failure
                }
            }
            if (count == 0) applyRetention(rule) else askRetention = rule to count
        }
    }

    // On the history thread, in order with the takes' writes; its commit bumps historyVersion, which refreshes this screen.
    private fun applyRetention(rule: Retention) {
        AppGraph.settings.retention = rule
        retention = rule
        AppGraph.ports.db.write { AppGraph.history.applyRetention(rule, System.currentTimeMillis(), filesDir) }
    }

    // Marked sensitive so the system clipboard preview hides it. "Copied." only when the clip reads back.
    private fun copy(row: Dictation) {
        val text = row.copyText ?: return
        val clipboard = getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText(null, text)
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        clipboard.setPrimaryClip(clip)
        say(if (clipboard.primaryClip?.getItemAt(0)?.text?.toString() == text) R.string.code_copied else R.string.code_copy_failed)
    }

    private fun delete(row: Dictation) {
        lifecycleScope.launch {
            val deleted = withContext(Dispatchers.IO) { deleteTake(row.sessionId) }
            // Back on main. A chip still showing this take must not paste the text just deleted.
            if (deleted) AppGraph.controller.forget(row.sessionId) else say(R.string.ui_delete_failed)
            refresh()
        }
    }

    /** Every ended take; one in progress stays, as a single Delete never offers it. */
    private fun clearAll() {
        lifecycleScope.launch {
            val (deleted, kept) = withContext(Dispatchers.IO) {
                (readHistory(AppGraph.ports.db, AppGraph.history, Int.MAX_VALUE) ?: emptyList())
                    .filter { it.status.terminal }.map { it.sessionId }.partition(::deleteTake)
            }
            deleted.forEach(AppGraph.controller::forget)
            if (kept.isNotEmpty()) say(R.string.ui_delete_failed)
            refresh()
        }
    }

    private fun deleteTake(sessionId: String): Boolean = try {
        AppGraph.history.delete(sessionId, filesDir)
        true
    } catch (e: HistoryWriteException) {
        false
    }

    private fun say(@StringRes message: Int, @StringRes action: Int? = null, onAction: () -> Unit = {}) {
        lifecycleScope.launch {
            val result = snackbar.showSnackbar(getString(message), action?.let(::getString))
            if (result == SnackbarResult.ActionPerformed) onAction()
        }
    }
}

private const val SHOWN_STEP = "welcome_step"
private const val REVISIT = "welcome_revisit"
private const val DISCLOSE_ONLY = "welcome_disclose_only"
private const val CHOOSING = "welcome_choosing"
private const val PICKED = "welcome_picked"

/**
 * The model the welcome loaded into the engine in this process: a rotation or a new activity keeps it, a new process,
 * whose engine has nothing loaded, doesn't. Tests that start a first run forget it.
 */
internal var welcomeLoaded by mutableStateOf<ModelFile?>(null)
private const val MODELS = "models"
private const val HINT = "dictionary_hint"
private const val TAB = "tab"

/**
 * Whether a switch away from a model with this download stops it and removes its partial file: one waiting or under way,
 * and one that failed, whose partial file (up to the model's size) nothing else would remove.
 */
internal fun stopsOnSwitch(download: DownloadState?): Boolean =
    download is DownloadState.Queued || download is DownloadState.Downloading || download is DownloadState.Failed

/**
 * Opens Android's accessibility list, which Pixel scrolls to the [service] and highlights (other phones ignore the
 * extras). The service's own page is a system API that refuses apps, so the list is as close as an app gets. A phone
 * without the list gets App info instead, and false, so the caller can say where the switch is. [start] opens an intent
 * and says whether an app took it.
 */
internal fun openAccessibilitySettings(service: ComponentName, start: (Intent) -> Boolean): Boolean {
    val key = service.flattenToString()
    val list = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        .putExtra(":settings:fragment_args_key", key)
        .putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", key) })
    if (start(list)) return true
    start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", service.packageName, null)))
    return false
}

/**
 * The newest history rows, once every earlier history write is done: Recovery first, so a take a crash ended shows
 * INTERRUPTED, never "In progress". Null when the history cannot be read: in the refresh an SQLite error would reach
 * the uncaught handler and end the app.
 */
// ponytail: History shows the newest 1,000 takes; page through older ones if "No limit" histories grow past that.
internal suspend fun readHistory(db: DbThread, history: HistoryDb, limit: Int = 1_000): List<Dictation>? = try {
    db.barrier()
    withContext(Dispatchers.IO) { history.list(limit) }
} catch (e: CancellationException) {
    throw e // a newer refresh replaced this one
} catch (e: Exception) {
    Log.w("ThumbFree", "history_read_error ${e.javaClass.name}") // the class only, as for every error line
    null
}
