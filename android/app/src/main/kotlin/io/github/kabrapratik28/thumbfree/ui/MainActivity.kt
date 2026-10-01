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
import io.github.kabrapratik28.thumbfree.BuildConfig
import io.github.kabrapratik28.thumbfree.R
import io.github.kabrapratik28.thumbfree.a11y.DictationAccessibilityService
import io.github.kabrapratik28.thumbfree.app.AppGraph
import io.github.kabrapratik28.thumbfree.app.DbThread
import io.github.kabrapratik28.thumbfree.core.models.Catalog
import io.github.kabrapratik28.thumbfree.core.models.ModelFile
import io.github.kabrapratik28.thumbfree.core.session.BubbleStyle
import io.github.kabrapratik28.thumbfree.core.text.CustomWords
import io.github.kabrapratik28.thumbfree.data.Dictation
import io.github.kabrapratik28.thumbfree.data.HistoryDb
import io.github.kabrapratik28.thumbfree.data.HistoryWriteException
import io.github.kabrapratik28.thumbfree.data.Retention
import io.github.kabrapratik28.thumbfree.data.Status
import io.github.kabrapratik28.thumbfree.models.DownloadState
import io.github.kabrapratik28.thumbfree.models.ModelDownloads
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
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
    private var dictionaryHint by mutableStateOf(false) // the Try tab's one-time card, up for this visit
    private var checkHint = false // a visit began: the next history read decides whether the card shows
    private var retention by mutableStateOf(AppGraph.settings.retention)
    private var askRetention by mutableStateOf<Pair<Retention, Int>?>(null)   // a stricter rule and the takes it would delete
    private var showModels by mutableStateOf(false)
    private var downloads by mutableStateOf(emptyMap<ModelFile, DownloadState>())   // each catalog model's download
    private val models = ModelQueue(lifecycleScope)   // the welcome screen's download actions, in order
    private var tab by mutableStateOf(Tab.TRY)   // here, not in the composition, so finishing the welcome screens can open Try
    // The welcome step on screen, or null for the tabs. [revisit]: opened from Settings, so it doesn't move the
    // first-run bookmark and Close returns to Settings.
    private var welcome by mutableStateOf<WelcomeStep?>(null)
    private var revisit by mutableStateOf(false)
    // Set only by a setup row's Turn on (see [discloseService]): the disclosure is the whole visit, so Back, Close and
    // Not now all return to the tab it opened from, instead of the MIC step or Try.
    private var discloseOnly by mutableStateOf(false)
    private var refusal by mutableStateOf<MicRefusal?>(null)
    private var restrictedHelp by mutableStateOf(false)
    private var leftForAccessibility = false
    private var backFromAccessibility = false // the resume after it: the setup read it starts says if the switch is on
    private val snackbar = SnackbarHostState()
    private var loading: Job? = null

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refusal = when {
            granted -> null
            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> MicRefusal.DENIED
            else -> MicRefusal.BLOCKED // "Don't allow" twice, or "don't ask again": only App info can grant it now
        }
        if (granted && welcome == WelcomeStep.MIC) goTo(WelcomeStep.SERVICE)
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
        showModels = savedInstanceState?.getBoolean(MODELS) ?: false
        dictionaryHint = savedInstanceState?.getBoolean(HINT) ?: false
        tab = Tab.entries.getOrNull(savedInstanceState?.getInt(TAB) ?: 0) ?: Tab.TRY
        welcome = when {
            savedInstanceState != null -> WelcomeStep.entries.getOrNull(shown)
            !AppGraph.settings.welcomeDone -> WelcomeStep.entries[AppGraph.settings.welcomeStep.coerceIn(0, WelcomeStep.entries.lastIndex)]
            else -> null
        }
        setContent {
            AppTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    var showRetention by rememberSaveable { mutableStateOf(false) }
                    val step = welcome
                    when {
                        showModels -> {
                            // The model can finish verifying while this screen is open; refresh so the setup rows don't
                            // keep showing what they said before.
                            ModelsRoute(onBack = { showModels = false; refresh() })
                        }
                        step != null -> {
                            BackHandler {
                                if (discloseOnly) closeWelcome()
                                else if (step.ordinal > 0) goTo(WelcomeStep.entries[step.ordinal - 1])
                                else if (revisit) closeWelcome()
                                else finish()
                            }
                            val model = welcomeModel(setup?.chosen ?: AppGraph.settings.model)
                            WelcomeScreen(step, setup, refusal, restrictedHelp, WelcomeActions(
                                next = { if (step == WelcomeStep.entries.last()) finishWelcome() else goTo(WelcomeStep.entries[step.ordinal + 1]) },
                                back = { if (discloseOnly) closeWelcome() else goTo(WelcomeStep.entries[step.ordinal - 1]) },
                                close = if (revisit) ::closeWelcome else null,
                                getStarted = ::getStarted,
                                // Cancel writes a tombstone before it returns, so off the main thread.
                                cancelModel = {
                                    models.enqueue { withContext(Dispatchers.IO) { ModelDownloads.cancel(this@MainActivity, model) } }
                                },
                                chooseModel = { next -> models.enqueue { choose(next) } },
                                allowMic = ::allowMic,
                                openAppSettings = ::openAppSettings,
                                openAccessibility = ::openAccessibility,
                                closeHelp = { restrictedHelp = false },
                            ), download = downloads[model], wifiOnly = AppGraph.settings.wifiOnly, busy = models.pending > 0, model = model)
                        }
                        else -> {
                            BackHandler(enabled = tab != Tab.TRY) { tab = Tab.TRY }
                            HomeScreen(tab, { tab = it }, snackbar) { current ->
                                when (current) {
                                    Tab.TRY -> TryScreen(
                                        setup, ::fix, setup?.let { downloads[it.chosen] },
                                        dictionaryHint = dictionaryHint && words.isEmpty(),
                                        onDictionary = { tab = Tab.DICTIONARY },
                                        onHintDone = { AppGraph.settings.dictionaryHintDone = true; dictionaryHint = false },
                                    )
                                    Tab.HISTORY -> HistoryScreen(
                                        history, apps, retention, storage,
                                        onCopy = ::copy, onDelete = ::delete,
                                        onTranscribe = { row -> AppGraph.controller.startRetranscribe(row.sessionId, insertAfter = false) },
                                        onClearAll = ::clearAll,
                                        onRetention = { showRetention = true; tab = Tab.SETTINGS },
                                        onTry = { tab = Tab.TRY },
                                    )
                                    Tab.DICTIONARY -> {
                                        // Seen: the Try tab's card never needs to point here.
                                        LaunchedEffect(Unit) { AppGraph.settings.dictionaryHintDone = true; dictionaryHint = false }
                                        // Try a phrase matches as the chosen model's takes do.
                                        val chosen = setup?.chosen ?: AppGraph.settings.model
                                        DictionaryScreen(words, exactOnly = CustomWords.exactOnlyFor(chosen.languageHint)) {
                                            AppGraph.settings.customWords = it
                                            words = it
                                        }
                                    }
                                    Tab.SETTINGS -> SettingsScreen(
                                        setup, ::fix, onWelcome = { revisit = true; restrictedHelp = false; welcome = WelcomeStep.WELCOME },
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
        // History writes while the screen is open (a Transcribe or Retry result, a take in the practice field) show at once.
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
                combine(Catalog.all.map { model -> ModelDownloads.state(this@MainActivity, model).map { model to it } }) { it.toMap() }
                    .collect { now ->
                        val readyChanged = now.any { (model, state) -> (state == DownloadState.Ready) != (downloads[model] == DownloadState.Ready) }
                        downloads = now
                        if (readyChanged) refresh()
                    }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(SHOWN_STEP, welcome?.ordinal ?: -1)
        outState.putBoolean(REVISIT, revisit)
        outState.putBoolean(DISCLOSE_ONLY, discloseOnly)
        outState.putBoolean(MODELS, showModels)
        outState.putBoolean(HINT, dictionaryHint)
        outState.putInt(TAB, tab.ordinal)
    }

    // Grants change in Settings while we are away, and takes add rows, so both are read again on every resume.
    override fun onResume() {
        super.onResume()
        checkHint = true
        // Only a read that starts now can tell whether the switch was turned on: one that started before the user left
        // (the first read after a launch is slow) would finish with the switch as it was, and lose the return.
        if (leftForAccessibility) {
            leftForAccessibility = false
            backFromAccessibility = true
        }
        refresh()
    }

    // The Try tab's card is for one visit: leaving the app puts it away for good (its flag is already set).
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) dictionaryHint = false
    }

    /**
     * The Try tab's card: on the first visit after a take that typed text, and only if the Dictionary is still empty
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
            setup = withContext(Dispatchers.IO) { state.withModels(AppGraph.modelStore) }
        }
    }

    /** The welcome steps move on by themselves once the grant they asked for arrives from a settings screen. */
    private fun onSetupRead(state: SetupState) {
        when (welcome) {
            WelcomeStep.MIC -> if (state.micGranted && refusal == MicRefusal.BLOCKED) { refusal = null; goTo(WelcomeStep.SERVICE) }
            WelcomeStep.SERVICE -> if (backFromAccessibility) {
                backFromAccessibility = false
                if (state.serviceEnabled) finishWelcome() else restrictedHelp = true
            }
            else -> Unit
        }
    }

    private fun goTo(step: WelcomeStep) {
        welcome = step
        if (!revisit) AppGraph.settings.welcomeStep = step.ordinal
    }

    /**
     * Get started: the shown model's download starts at once (unless it is here or under way) and runs while the user
     * does the other steps. It waits in the queue behind a switch still running, so it starts the model the screen
     * shows by then.
     */
    private fun getStarted() {
        models.enqueue {
            val model = welcomeModel(AppGraph.settings.model)
            welcomeDownload(model, setup, downloads[model])?.let { ModelDownloads.start(this, it) }
        }
        goTo(WelcomeStep.MIC)
    }

    /**
     * The last step done: the Try tab, which shows the model's download until it is ready. A disclosure opened from a
     * setup row ([discloseService]) is not the first-run flow, so it returns to the tab it opened from instead.
     */
    private fun finishWelcome() {
        val toTry = !discloseOnly
        closeWelcome()
        if (toTry) tab = Tab.TRY
    }

    /** Done or closed: the tabs show, and the first-run screens never open by themselves again. */
    private fun closeWelcome() {
        if (!revisit) AppGraph.settings.welcomeDone = true
        welcome = null
        revisit = false
        discloseOnly = false
        restrictedHelp = false
    }

    private fun fix(item: SetupItem) = when (item) {
        SetupItem.MIC -> allowMic()
        SetupItem.SERVICE -> discloseService()
        SetupItem.MODEL -> {
            // A wait for Wi-Fi offers mobile data instead: a start without Wi-Fi only replaces the wait.
            val waitsForWifi = (downloads[AppGraph.settings.model] as? DownloadState.Queued)?.wifiOnly == true
            if (waitsForWifi) ModelDownloads.start(this, AppGraph.settings.model, wifiOnly = false) else getModel()
        }
    }

    // Start and Try again are the same call: a failed download keeps its partial file and goes on from there.
    private fun getModel() = ModelDownloads.start(this, AppGraph.settings.model)

    // The microphone only: Android shows its own dot while the mic is on, and the recording and download services run
    // without the notification permission (their notifications then show only in Android's list of active apps).
    private fun allowMic() {
        if (refusal == MicRefusal.BLOCKED) openAppSettings() else askMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun openAppSettings() {
        start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    // The Dictation bubble row's Turn on, on the Try tab and in Settings: the disclosure (WelcomeStep.SERVICE) first,
    // since Google Play wants it shown right before every request for the setting, not only the welcome flow's first
    // one. Its own "I agree" (openAccessibility) is the only way from here to the setting; Back, Close and Not now
    // undo just this screen, back to the tab that opened it (see discloseOnly in finishWelcome, closeWelcome).
    private fun discloseService() {
        revisit = true
        discloseOnly = true
        restrictedHelp = false
        welcome = WelcomeStep.SERVICE
    }

    // A phone without the accessibility list gets App info instead, and a line saying where the switch is.
    private fun openAccessibility() {
        leftForAccessibility = welcome == WelcomeStep.SERVICE
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
    // verified model; the welcome screen may choose the multilingual one before its download, which Get started then
    // fetches. The screens follow at once.
    private fun choose(model: ModelFile) {
        AppGraph.settings.selectedModelId = model.id
        setup = setup?.choosing(model)
        refresh()
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
private const val MODELS = "models"
private const val HINT = "dictionary_hint"
private const val TAB = "tab"

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
