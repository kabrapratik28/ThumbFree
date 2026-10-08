package io.github.kabrapratik28.thumbfree.ui

import android.os.LocaleList
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kabrapratik28.thumbfree.R
import io.github.kabrapratik28.thumbfree.a11y.CodeMessages
import io.github.kabrapratik28.thumbfree.core.models.Catalog
import io.github.kabrapratik28.thumbfree.core.models.Downloader
import io.github.kabrapratik28.thumbfree.core.models.ModelFile
import io.github.kabrapratik28.thumbfree.core.session.Grey
import io.github.kabrapratik28.thumbfree.core.session.SpeechWait
import io.github.kabrapratik28.thumbfree.models.DownloadState
import io.github.kabrapratik28.thumbfree.models.FailReason
import io.github.kabrapratik28.thumbfree.models.ModelDownloads
import io.github.kabrapratik28.thumbfree.models.Readiness
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The first-run screens, in order, each with the name TalkBack hears for it: get ready (what ThumbFree does, the
 * language, and the wait for the speech model), a try of the bubble, the bubble's accessibility setting, and all set;
 * Home follows the last. Settings > Show the welcome screens opens them again.
 */
enum class WelcomeStep(@StringRes val label: Int) {
    WELCOME(R.string.welcome_step_ready), TRY(R.string.welcome_step_try), SERVICE(R.string.welcome_step_service),
    READY(R.string.welcome_step_finish),
}

/**
 * Where a first run left off: the step kept by name ([saved], Settings.welcomeScreen), else one an earlier build kept by
 * its index in its order ([oldIndex]: what it is, how it works, the microphone, the bubble, ready). Earlier orders had a
 * microphone step, which is now part of the try and came after the download started, so it resumes on the first step,
 * which shows the download; every other name is a step here too.
 */
fun resumeAt(saved: String?, oldIndex: Int): WelcomeStep =
    (if (saved == "MIC") WelcomeStep.WELCOME else WelcomeStep.entries.firstOrNull { it.name == saved })
        ?: listOf(WelcomeStep.WELCOME, WelcomeStep.WELCOME, WelcomeStep.WELCOME, WelcomeStep.SERVICE, WelcomeStep.READY)
            .getOrElse(oldIndex) { WelcomeStep.WELCOME }

/**
 * The model the welcome screen recommends to everyone, under a plain name: English. The one other choice there is the
 * multilingual model ("Other languages"); Settings > Speech model names them all and lets the owner switch.
 */
val RECOMMENDED_MODEL = Catalog.PARAKEET_UNIFIED_Q8

/** The welcome's Other languages: the multilingual model. */
val OTHER_LANGUAGES_MODEL = Catalog.PARAKEET_TDT_V3_Q8

/**
 * Which of the first step's two language buttons is filled, the smart default. The user's [chosen] model wins once there
 * is one: the multilingual one if that is it, else English. With no choice yet, the multilingual model when any of the
 * phone's [languages] is one of its languages other than English, else English. The buttons' order never changes.
 */
fun welcomeModel(chosen: ModelFile?, languages: () -> List<Locale> = ::phoneLanguages): ModelFile = when {
    chosen != null -> if (chosen == OTHER_LANGUAGES_MODEL) chosen else RECOMMENDED_MODEL
    languages().any { it.language != "en" && it.language in OTHER_LANGUAGES_MODEL.languages } -> OTHER_LANGUAGES_MODEL
    else -> RECOMMENDED_MODEL
}

/** All the phone's languages, in the order set in Android's language settings. */
fun phoneLanguages(): List<Locale> = LocaleList.getDefault().let { list -> List(list.size()) { list[it] } }

/**
 * The welcome screen's download actions (a language's choice and its download, a switch's cancel), each run once every
 * action asked for before it is done, and none dropped: a second quick switch still cancels the download it leaves,
 * however long the first switch's cancel takes. [pending] counts those asked for and not yet done.
 */
class ModelQueue(private val scope: CoroutineScope) {
    private val order = Mutex()

    var pending by mutableIntStateOf(0)
        private set

    fun enqueue(action: suspend () -> Unit) {
        pending++
        scope.launch {
            try {
                order.withLock { action() }
            } finally {
                pending--
            }
        }
    }
}

/** After a microphone request the user refused: [DENIED] can ask again; [BLOCKED] ("don't ask again") needs App info. */
enum class MicRefusal { DENIED, BLOCKED }

/**
 * Android's answer to a microphone request, as the screens take it: none when [granted]; [MicRefusal.DENIED] when
 * Android would ask again ([rationale]), or on the first answer ever ([askedBefore] false), where a refusal without a
 * rationale may be a question dismissed with a tap outside; else [MicRefusal.BLOCKED], which only App info can undo
 * ("don't ask again", or a device policy, where Android refuses at once).
 */
fun micRefusal(granted: Boolean, rationale: Boolean, askedBefore: Boolean): MicRefusal? = when {
    granted -> null
    rationale || !askedBefore -> MicRefusal.DENIED
    else -> MicRefusal.BLOCKED
}

/**
 * Whether Allow microphone (not the bubble's tap, which the screen answers) should go on to App info at once: only App
 * info can grant it now ([refusal] BLOCKED), and the refusal came back within [NO_QUESTION_MS] of the request
 * ([askedAt] to [answeredAt], on one clock), too soon for anyone to read and answer Android's question, so it never
 * showed and the tap would otherwise seem to do nothing. The request may still run Android's activity, which shows
 * nothing and closes, so a pause can't tell.
 */
fun appInfoAtOnce(refusal: MicRefusal?, fromBubble: Boolean, askedAt: Long, answeredAt: Long): Boolean =
    refusal == MicRefusal.BLOCKED && !fromBubble && answeredAt - askedAt < NO_QUESTION_MS

/**
 * A microphone refusal back sooner than this never showed Android's question, which takes longer than this just to
 * appear and be read. It allows for Android's activity starting cold on a mid-range phone, which can take over 300 ms;
 * one slower still is taken as answered, and the next tap on Allow microphone opens App info.
 */
const val NO_QUESTION_MS = 700L

/** What a tap that needs the microphone does: nothing yet, App info, or ask Android (see [micTap]). */
enum class MicTap { WAIT, APP_INFO, ASK }

/**
 * What a tap that needs the microphone (Allow microphone, Home's row, or the try's bubble) does: nothing while a
 * request is out ([asking]), as a second one comes back refused at once, which could open App info over Android's
 * question or say the microphone is off while it is still up; App info when only it can grant ([refusal] BLOCKED); else
 * ask.
 */
fun micTap(asking: Boolean, refusal: MicRefusal?): MicTap = when {
    asking -> MicTap.WAIT
    refusal == MicRefusal.BLOCKED -> MicTap.APP_INFO
    else -> MicTap.ASK
}

/**
 * What the welcome screens do; MainActivity runs each one. [next] on the last step finishes them and opens Home;
 * [chooseLanguage] is the first step's English or Other languages: it chooses that model and starts its download;
 * [change] goes back to that choice; [loadModel] loads the downloaded model into the engine (again, after a load that
 * failed); [turnOnBubble] goes back to the bubble step from the last one; [trial] is the try's link to the take machine
 * (none in screenshots, which leaves its bubble still). [allowMic] asks Android for the microphone (Allow microphone: a
 * grant only grants), [allowMicAndListen] the same from the try's bubble, whose tap then listens once it is allowed. The
 * first step's remedies: [useMobileData] starts the download without waiting for Wi-Fi, [retryDownload] starts it again
 * (from its partial file, or over after a damaged one); and on the last step [openStorage] opens Android's storage
 * settings.
 */
class WelcomeActions(
    val next: () -> Unit, val back: () -> Unit, val close: (() -> Unit)?,
    val chooseLanguage: (ModelFile) -> Unit, val change: () -> Unit, val loadModel: () -> Unit,
    val allowMic: () -> Unit, val openAppSettings: () -> Unit, val openAccessibility: () -> Unit,
    val turnOnBubble: () -> Unit, val trial: TrialLink? = null,
    val useMobileData: () -> Unit = {}, val retryDownload: () -> Unit = {}, val openStorage: () -> Unit = {},
    val allowMicAndListen: () -> Unit = {},
)

/**
 * The first step's state, after the language's tap: the choice itself, the download (with its ways to stop: no Wi-Fi,
 * a pause before it starts again by itself, a stop it picks up from, a damaged file it starts over, no space), the
 * file's check and the engine's load ("Almost ready"), a load that failed, and the model loaded, when the step moves on
 * by itself.
 */
internal enum class ReadyPhase { CHOOSE, DOWNLOADING, WIFI, RETRYING, STOPPED, FAILED, NO_SPACE, ALMOST, LOAD_FAILED, LOADED }

/**
 * The first step's phase for the [chosen] model (null: not chosen yet, or Change), its [download], whether it is on the
 * phone and checked ([verified]), whether it is loaded into the engine ([loaded], which counts only while it is here and
 * checked) and whether that load failed ([loadFailed]). A state not known yet, or a start not under way yet, counts as
 * downloading; Queued without Wi-Fi is a download about to start.
 */
internal fun readyPhase(chosen: ModelFile?, download: DownloadState?, verified: Boolean, loaded: Boolean, loadFailed: Boolean = false): ReadyPhase = when {
    chosen == null -> ReadyPhase.CHOOSE
    // Loaded counts only while the file is here and checked: a model deleted since must download again.
    loaded && (verified || download == DownloadState.Ready) -> ReadyPhase.LOADED
    verified || download == DownloadState.Ready || download == DownloadState.Verifying -> if (loadFailed) ReadyPhase.LOAD_FAILED else ReadyPhase.ALMOST
    download is DownloadState.Queued && download.retrying -> ReadyPhase.RETRYING
    download is DownloadState.Queued && download.wifiOnly -> ReadyPhase.WIFI
    download is DownloadState.Failed -> when (download.reason) {
        FailReason.NOT_ENOUGH_SPACE -> ReadyPhase.NO_SPACE
        FailReason.FILE_CHECK_FAILED -> ReadyPhase.FAILED
        FailReason.NO_INTERNET, FailReason.INTERRUPTED -> ReadyPhase.STOPPED
    }
    else -> ReadyPhase.DOWNLOADING
}

/** The grey bubble's look in each of the first step's phases; null once it is yellow, or before there is a bubble. */
internal fun readyGrey(phase: ReadyPhase, percent: Int): Grey? = when (phase) {
    ReadyPhase.CHOOSE, ReadyPhase.LOADED -> null
    ReadyPhase.DOWNLOADING -> Grey(Grey.Badge.DOWNLOAD, percent / 100f)
    ReadyPhase.WIFI -> Grey(Grey.Badge.WIFI, percent / 100f)
    ReadyPhase.RETRYING -> Grey(Grey.Badge.RETRYING, percent / 100f)
    ReadyPhase.STOPPED, ReadyPhase.FAILED, ReadyPhase.NO_SPACE -> Grey(Grey.Badge.STOPPED, percent / 100f)
    ReadyPhase.ALMOST -> Grey.PREPARING
    ReadyPhase.LOAD_FAILED -> Grey(Grey.Badge.LOAD_FAILED, 1f)
}

/**
 * The welcome steps, each in fixed places that never move: the progress bar of four (with Back after the first step,
 * and Close when opened from Settings), the step's middle, and at the bottom the big button, the action needed now, with
 * a quiet one under it. The first two steps share one screen, Sam's card with the real bubble: step 1 asks the language,
 * then waits there through the download, the file's check and the engine's load, the grey bubble filling up; loaded, the
 * bubble turns yellow and the step moves to the try by itself, where only the card's words change. The other steps cross
 * (the old fades out 8 dp toward where it came from, the new in from 12 dp the other way; a cut with animations off),
 * and TalkBack goes to the new heading. [chosen] is the model the first step's choice picked (null until the language's
 * tap, and after Change), [download] its download; [loaded] says whether the model takes use is loaded into the engine,
 * and [loadFailed] whether its last load failed; [suggested] is the filled language button. [refusal] is the last
 * microphone answer; [stillOff]: back from Accessibility settings with the service still off, the bubble step says so,
 * with Open settings again and the restricted-settings help (its switch can be greyed out for a sideloaded app). The last
 * step judges the model takes use (`setup.chosen`) by [inUseDownload], and says all set only once that model is loaded
 * too: it loads it, should a new start of the app have lost the first step's load.
 */
@Composable
fun WelcomeScreen(
    step: WelcomeStep, setup: SetupState?, refusal: MicRefusal?, stillOff: Boolean, actions: WelcomeActions,
    chosen: ModelFile? = null, download: DownloadState? = null, loaded: Boolean = false, loadFailed: Boolean = false,
    suggested: ModelFile = RECOMMENDED_MODEL, inUseDownload: DownloadState? = download, busy: Boolean = false,
) {
    // The try's state, forgotten as its step goes (TrialAttached): nothing of a try stays.
    val trial = remember { TrialState() }
    val still = animationsOff()
    val density = LocalDensity.current
    val large = density.fontScale >= LARGE_FONT
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val usable = setup?.ok(SetupItem.MODEL) == true || inUseDownload == DownloadState.Ready
    // What the last step asks for, one thing at a time; nothing before the first read of the grants, so nothing is
    // claimed ready, or missing, early. Ready needs the model loaded as well: until then it is almost ready.
    val need = setup?.let {
        val base = Readiness.of(it.micGranted, it.serviceEnabled, usable, inUseDownload)
        if (base == Readiness.Ready && !loaded) Readiness.Speech(SpeechWait.PREPARING, 100) else base
    }
    val loadNeeded = step == WelcomeStep.READY && setup != null && usable && !loaded && !loadFailed
    LaunchedEffect(loadNeeded) { if (loadNeeded) actions.loadModel() }
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        val pad = if (large || maxWidth < 340.dp) 16.dp else 24.dp
        Column(Modifier.widthIn(max = PAGE_WIDTH).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().height(if (large) 48.dp else 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp)) {
                    val back = step == WelcomeStep.TRY || step == WelcomeStep.SERVICE || step == WelcomeStep.READY && need != Readiness.Ready
                    if (back) IconButton(actions.back) { Icon(AppIcons.ArrowBack, contentDescription = stringResource(R.string.welcome_back)) }
                }
                Segments(step, Modifier.weight(1f).padding(horizontal = 8.dp))
                Box(Modifier.size(48.dp)) {
                    if (actions.close != null) IconButton(actions.close) {
                        Icon(AppIcons.Close, contentDescription = stringResource(R.string.welcome_close))
                    }
                }
            }
            // The first two steps are one page: the card stays and only its words change.
            val page = if (step == WelcomeStep.SERVICE || step == WelcomeStep.READY) step else WelcomeStep.WELCOME
            val enter = with(density) { 12.dp.roundToPx() }
            val exit = with(density) { 8.dp.roundToPx() }
            AnimatedContent(
                page, Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    if (still) EnterTransition.None togetherWith ExitTransition.None using null
                    else {
                        val sign = (if (targetState > initialState) 1 else -1) * (if (rtl) -1 else 1)
                        val enterSpec = tween<IntOffset>(EnterMs, EnterDelayMs, EaseEnter)
                        val exitSpec = tween<IntOffset>(ExitMs, easing = EaseExit)
                        (fadeIn(tween(EnterMs, EnterDelayMs, EaseEnter)) + slideInHorizontally(enterSpec) { sign * enter }) togetherWith
                            (fadeOut(tween(ExitMs, easing = EaseExit)) + slideOutHorizontally(exitSpec) { -sign * exit }) using null
                    }
                },
                label = "step",
            ) { shown ->
                when (shown) {
                    WelcomeStep.SERVICE -> ServicePage(setup, stillOff, actions, pad)
                    WelcomeStep.READY -> FinishPage(need, setup?.chosen ?: chosen ?: RECOMMENDED_MODEL, loadFailed, refusal, actions, pad)
                    else -> CardPage(step, setup, refusal, actions, trial, chosen, download, loaded, loadFailed, suggested, busy, pad)
                }
            }
        }
    }
}

/** The welcome's big button, for tests. */
const val PRIMARY_BUTTON = "primary-button"

/** The page's widest, centred on larger phones and tablets. */
private val PAGE_WIDTH = 440.dp

/** The font scale from which the page takes its compact grid (16 dp sides, a 48 dp top bar). */
private const val LARGE_FONT = 1.6f

/** From this font scale, or below [CARD_ROOM] of height, the card drops its frame and keeps the reply and the bubble. */
private const val COMPACT_FONT = 1.3f
private val CARD_ROOM = 560.dp

/** How long a wait for Wi-Fi must last before the first step says so: a download's start passes through it briefly. */
private const val WIFI_SETTLE_MS = 1_500L

/**
 * The first two steps' page: Sam's card, the same throughout, with the real bubble in its slot once the download starts;
 * under it the step's words; at the bottom the language buttons, the speech model's line, or the big button. Once the
 * model is loaded the step moves on at once: the bubble turns yellow, with its bounce, as the try takes over, so it is
 * never yellow before it takes taps.
 */
@Composable
private fun CardPage(
    step: WelcomeStep, setup: SetupState?, refusal: MicRefusal?, actions: WelcomeActions, trial: TrialState,
    chosen: ModelFile?, download: DownloadState?, loaded: Boolean, loadFailed: Boolean, suggested: ModelFile, busy: Boolean, pad: Dp,
) {
    val verified = chosen != null && (download == DownloadState.Ready || setup?.offered?.contains(chosen) == true)
    val raw = readyPhase(chosen, download, verified, loaded, loadFailed)
    // A download's start passes through a wait for Wi-Fi: only one that lasts says so.
    val wifiSettled by produceState(false, raw) {
        value = false
        if (raw == ReadyPhase.WIFI) {
            delay(WIFI_SETTLE_MS)
            value = true
        }
    }
    val phase = if (raw == ReadyPhase.WIFI && !wifiSettled) ReadyPhase.DOWNLOADING else raw
    val reported = Readiness.percentOf(download)
    // The percentage and the ring only ever move forward, until another model's download starts, or a damaged one
    // starts over.
    val most = remember(chosen) { intArrayOf(0) }
    most[0] = if (raw == ReadyPhase.FAILED) 0 else maxOf(most[0], reported)
    val percent = if (phase == ReadyPhase.ALMOST || phase == ReadyPhase.LOAD_FAILED || phase == ReadyPhase.LOADED) 100 else most[0]
    // Not known yet (the first moment after a start, before the download's state is read): no number rather than 0%.
    val shownPercent = percent.takeIf { download != null || phase != ReadyPhase.DOWNLOADING }
    if (step == WelcomeStep.WELCOME) {
        // Downloaded and checked: the model loads into the engine, so the first take answers at once.
        LaunchedEffect(phase, verified) { if (phase == ReadyPhase.ALMOST && verified) actions.loadModel() }
        // Loaded: the try takes over by itself, as the bubble turns yellow.
        LaunchedEffect(phase) { if (phase == ReadyPhase.LOADED) actions.next() }
    }
    val micOff = step == WelcomeStep.TRY && refusal != null && setup?.micGranted != true
    if (step == WelcomeStep.TRY) TrialAttached(trial, actions.trial)
    val grey = when {
        step == WelcomeStep.TRY -> if (micOff) Grey.MIC_OFF else null
        else -> readyGrey(phase, percent)
    }
    val line = tryLine(trial, micOff)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < CARD_ROOM || LocalDensity.current.fontScale > COMPACT_FONT
        val top = if (maxHeight > 640.dp) 40.dp else 8.dp
        val scroll = rememberScrollState()
        Column(Modifier.fillMaxSize()) {
            // Scrollable only once there is something to scroll (a large font), so TalkBack never calls a step that fits a list.
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll, enabled = scroll.maxValue > 0).padding(horizontal = pad).padding(top = top),
            ) {
                // A compact card is the reply beside the bubble: before the choice, with no bubble yet, it is left out
                // rather than shown as a lone empty tray.
                if (!compact || step == WelcomeStep.TRY || phase != ReadyPhase.CHOOSE) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TryCard(
                            trial, if (step == WelcomeStep.TRY) actions.trial else null,
                            showBubble = step == WelcomeStep.TRY || phase != ReadyPhase.CHOOSE, grey = grey,
                            cue = step == WelcomeStep.TRY && !micOff, compact = compact, micGranted = setup?.micGranted,
                            onNeedsMic = actions.allowMicAndListen,
                        )
                    }
                    // A short screen's gaps close a little, so the try's listening words fit without scrolling.
                    Spacer(Modifier.height(if (compact) 16.dp else 24.dp))
                }
                val words: Any = if (step == WelcomeStep.TRY) line else phase
                Crossfaded(words) { shown ->
                    when (shown) {
                        is ReadyPhase -> ReadyWords(shown, chosen ?: suggested, shownPercent)
                        is TryLine -> TryWords(shown, trial, compact)
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(start = pad, end = pad, top = 12.dp, bottom = 16.dp)) {
                if (step == WelcomeStep.WELCOME) ReadyBottom(phase, chosen, suggested, busy, actions)
                else TryBottom(line, refusal, actions)
            }
        }
    }
}

/** What the try's words say: what to do now, that it listens, that it worked, how to try again, or the microphone. */
internal enum class TryLine { TAP, SPEAK, DONE, AGAIN, MIC_OFF }

/**
 * The try's line for its state now: the microphone first ([micOff]), then a take that listens, then how the last went.
 * A take that stopped keeps its listening line until its words come (or don't), so nothing says it failed meanwhile.
 */
internal fun tryLine(trial: TrialState, micOff: Boolean): TryLine = when {
    micOff -> TryLine.MIC_OFF
    trial.listening -> TryLine.SPEAK
    trial.done && trial.words != null -> TryLine.DONE
    trial.transcribing -> TryLine.SPEAK
    trial.done || trial.problem != null -> TryLine.AGAIN
    else -> TryLine.TAP
}

/** The first step's words under the card, by its phase. The percentage is the biggest thing on the screen. */
@Composable
private fun ReadyWords(phase: ReadyPhase, model: ModelFile, percent: Int?) = Column(Modifier.fillMaxWidth()) {
    val app = stringResource(R.string.app_name)
    when (phase) {
        ReadyPhase.CHOOSE -> {
            Heading(stringResource(R.string.welcome_title))
            Sentence(stringResource(R.string.welcome_body))
        }
        ReadyPhase.DOWNLOADING, ReadyPhase.ALMOST, ReadyPhase.LOADED -> {
            val downloading = phase == ReadyPhase.DOWNLOADING
            Heading(stringResource(if (downloading) R.string.welcome_getting_ready else R.string.welcome_almost))
            Percent(percent)
            Sentence(if (downloading) stringResource(R.string.welcome_downloads_once, app) else stringResource(R.string.welcome_loading_body))
        }
        ReadyPhase.WIFI -> {
            Heading(stringResource(R.string.welcome_wifi_title))
            val body = if (model == RECOMMENDED_MODEL) R.string.welcome_wifi_body_english else R.string.welcome_wifi_body
            Sentence(stringResource(body, formatSize(model.sizeBytes)))
        }
        ReadyPhase.RETRYING -> {
            Heading(stringResource(R.string.welcome_retrying_title))
            Sentence(stringResource(R.string.welcome_retrying_body))
        }
        ReadyPhase.STOPPED -> {
            Heading(stringResource(R.string.welcome_stopped_title))
            Sentence(stringResource(R.string.welcome_stopped_body))
        }
        ReadyPhase.FAILED -> {
            Heading(stringResource(R.string.welcome_failed_title))
            Sentence(stringResource(R.string.welcome_failed_body))
        }
        ReadyPhase.LOAD_FAILED -> {
            Heading(stringResource(R.string.welcome_load_failed_title))
            Sentence(stringResource(R.string.welcome_load_failed_body))
        }
        ReadyPhase.NO_SPACE -> {
            Heading(stringResource(R.string.welcome_space_title))
            val context = LocalContext.current
            val needed by produceState(model.sizeBytes + Downloader.EXTRA_FREE_BYTES, model) {
                value = withContext(Dispatchers.IO) { ModelDownloads.spaceNeeded(context, model) }
            }
            Sentence(stringResource(R.string.welcome_space_free, formatSize(needed)))
        }
    }
}

/**
 * The download's percentage, about 56 sp, in a slot that keeps its height while there is no number yet ([percent] null).
 * TalkBack hears it as it changes, in steps of 10 only, so it never becomes a running count: the number on screen moves
 * every 1%, but what TalkBack is given (its live region) changes only every 10%.
 */
@Composable
private fun Percent(percent: Int?) {
    val style = MaterialTheme.typography.displayLarge.copy(fontSize = 56.sp, lineHeight = 64.sp, fontWeight = FontWeight.SemiBold)
    Box(Modifier.padding(vertical = 4.dp).heightIn(min = with(LocalDensity.current) { style.lineHeight.toDp() })) {
        if (percent == null) return@Box
        val heard = stringResource(R.string.welcome_percent, percent / 10 * 10)
        Text(
            stringResource(R.string.welcome_percent, percent),
            Modifier.clearAndSetSemantics { contentDescription = heard; liveRegion = LiveRegionMode.Polite },
            style = style, color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** The try's words under the card; on a [compact] page (a large font, or a short screen) with closer gaps. */
@Composable
private fun TryWords(line: TryLine, trial: TrialState, compact: Boolean) = Column(Modifier.fillMaxWidth()) {
    val app = stringResource(R.string.app_name)
    when (line) {
        TryLine.TAP -> Heading(stringResource(R.string.welcome_try_tap))
        TryLine.SPEAK -> {
            // Nothing here is read aloud, nor takes TalkBack's focus: the microphone is open, and the bubble's own label
            // says it listens.
            Heading(stringResource(R.string.welcome_try_speak), focus = false)
            // The line to say and how to finish come in as listening starts, rising 4 dp, and go with the words.
            val still = animationsOff()
            val shown = remember { Animatable(if (still) 1f else 0f) }
            LaunchedEffect(still) { if (still) shown.snapTo(1f) else shown.animateTo(1f, tween(QuickMs, easing = EaseEnter)) }
            Column(Modifier.graphicsLayer { alpha = shown.value; translationY = 4.dp.toPx() * (1 - shown.value) }) {
                TrySaying(Modifier.padding(top = if (compact) 8.dp else 16.dp))
                Text(
                    stringResource(R.string.welcome_try_finish), Modifier.padding(top = 16.dp),
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // A silent microphone while it records: quietly, in a line kept for it, so nothing moves when it shows.
                val unheard = trial.unheard
                Note(
                    stringResource(R.string.welcome_try_closer),
                    Modifier.padding(top = if (compact) 4.dp else 8.dp).alpha(if (unheard) 1f else 0f)
                        .then(if (unheard) Modifier else Modifier.clearAndSetSemantics {}),
                )
            }
        }
        TryLine.DONE -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DoneCheck(Modifier.size(28.dp))
                // Read aloud as it comes: the microphone is closed by then, so TalkBack can't speak into a take.
                Heading(stringResource(R.string.welcome_try_done), live = true)
            }
            Sentence(stringResource(R.string.welcome_try_done_body))
            CleanupTry(trial)
        }
        TryLine.AGAIN -> {
            Heading(stringResource(R.string.welcome_try_tap))
            val problem = trial.problem
            val why = when {
                problem == null -> R.string.welcome_try_closer
                problem in TrialState.ENGINE_CODES -> R.string.welcome_try_engine
                else -> CodeMessages.of(problem)
            }
            Sentence(stringResource(why))
        }
        TryLine.MIC_OFF -> {
            Heading(stringResource(R.string.welcome_mic_off_title))
            Sentence(stringResource(R.string.welcome_mic_off_body, app))
        }
    }
}

/**
 * The line the try suggests while it listens, in a plain card, not a button: "Try saying", the line in large type, and
 * that anything will do. TalkBack hears it as one label, when the user explores to it.
 */
@Composable
private fun TrySaying(modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val line = stringResource(R.string.welcome_try_saying_line)
    val read = stringResource(R.string.welcome_try_saying_read, line)
    Column(
        modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = read }
            .background(colors.surfaceContainer, RoundedCornerShape(20.dp)).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val type = MaterialTheme.typography
        Text(stringResource(R.string.welcome_try_saying), style = type.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = colors.secondary)
        Text(line, style = type.headlineSmall.copy(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold), color = colors.onSurface)
        Text(stringResource(R.string.welcome_try_saying_other), style = type.bodyMedium.copy(fontSize = 14.sp), color = colors.onSurfaceVariant)
    }
}

/**
 * The first step's bottom: the question and its two language buttons, the speech model's line (with Change while it
 * downloads), or the one remedy for a download that stopped or a load that failed, with the small Change under it.
 */
@Composable
private fun ReadyBottom(phase: ReadyPhase, chosen: ModelFile?, suggested: ModelFile, busy: Boolean, actions: WelcomeActions) {
    when (phase) {
        ReadyPhase.CHOOSE -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.welcome_language_question), Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface,
            )
            LanguageButton(RECOMMENDED_MODEL, filled = suggested == RECOMMENDED_MODEL, enabled = !busy) { actions.chooseLanguage(RECOMMENDED_MODEL) }
            LanguageButton(OTHER_LANGUAGES_MODEL, filled = suggested == OTHER_LANGUAGES_MODEL, enabled = !busy) { actions.chooseLanguage(OTHER_LANGUAGES_MODEL) }
        }
        ReadyPhase.DOWNLOADING, ReadyPhase.ALMOST, ReadyPhase.LOADED -> {
            // Where the big button would be, so the line never moves as the phases change.
            Box(Modifier.fillMaxWidth().heightIn(min = 56.dp), contentAlignment = Alignment.Center) {
                ModelLine(chosen ?: suggested, change = if (phase == ReadyPhase.DOWNLOADING) actions.change else null)
            }
            QuietSlot()
        }
        ReadyPhase.WIFI, ReadyPhase.RETRYING, ReadyPhase.STOPPED, ReadyPhase.FAILED, ReadyPhase.NO_SPACE, ReadyPhase.LOAD_FAILED -> {
            PrimarySlot(
                when (phase) {
                    ReadyPhase.WIFI -> R.string.ui_use_mobile_data to actions.useMobileData
                    ReadyPhase.LOAD_FAILED -> R.string.ui_models_try_again to actions.loadModel
                    else -> R.string.ui_models_try_again to actions.retryDownload
                },
            )
            QuietSlot { SmallButton(stringResource(R.string.welcome_model_change), actions.change) }
        }
    }
}

/**
 * The try's bottom: Continue once a take has worked; after one that didn't, Continue quietly (the bubble is what to tap
 * now); or the microphone's request while it is off, with Not now.
 */
@Composable
private fun TryBottom(line: TryLine, refusal: MicRefusal?, actions: WelcomeActions) {
    PrimarySlot(
        when (line) {
            TryLine.DONE -> R.string.welcome_continue to actions.next
            TryLine.MIC_OFF -> R.string.welcome_mic_allow to (if (refusal == MicRefusal.BLOCKED) actions.openAppSettings else actions.allowMic)
            TryLine.TAP, TryLine.SPEAK, TryLine.AGAIN -> null
        },
        delay = if (line == TryLine.MIC_OFF) 0 else MoveMs,
    )
    QuietSlot {
        when (line) {
            TryLine.MIC_OFF -> SecondaryButton(stringResource(R.string.welcome_not_now), actions.next)
            TryLine.AGAIN -> SecondaryButton(stringResource(R.string.welcome_continue), actions.next)
            else -> Unit
        }
    }
}

/**
 * One of the first step's two language buttons, the whole of it a target with an arrow at its end: the model's plain name
 * over what it is best for. The [filled] one is the smart default, the other outlined.
 */
@Composable
private fun LanguageButton(model: ModelFile, filled: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val english = model == RECOMMENDED_MODEL
    val title = stringResource(if (english) R.string.welcome_language_english else R.string.welcome_language_other)
    val note = if (english) stringResource(R.string.welcome_language_english_note)
    else stringResource(R.string.welcome_language_other_note, model.languages.size - 4)
    val colors = MaterialTheme.colorScheme
    val content = @Composable {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(note, style = MaterialTheme.typography.bodyMedium)
            }
            Icon(AppIcons.ArrowForward, contentDescription = null, Modifier.size(24.dp))
        }
    }
    val modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag(if (english) LANGUAGE_ENGLISH else LANGUAGE_OTHER)
    val padding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
    if (filled) Button(onClick, modifier, enabled = enabled, shape = RoundedCornerShape(20.dp), contentPadding = padding) { content() }
    else OutlinedButton(
        onClick, modifier, enabled = enabled, shape = RoundedCornerShape(20.dp), contentPadding = padding,
        border = BorderStroke(2.dp, colors.primary), colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.primary),
    ) { content() }
}

/** The language buttons, for tests. */
const val LANGUAGE_ENGLISH = "language-english"
const val LANGUAGE_OTHER = "language-other"

/** "Speech model: English · 731 MB", and the small outlined Change while it downloads ([change]). */
@Composable
private fun ModelLine(model: ModelFile, change: (() -> Unit)?) = Row(
    Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
) {
    Icon(AppIcons.Download, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface)
    Text(
        stringResource(R.string.welcome_model_row, languageName(model), formatSize(model.sizeBytes)), Modifier.weight(1f),
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface,
    )
    if (change != null) SmallButton(stringResource(R.string.welcome_model_change), change)
}

/** A small outlined pill, 40 dp high in a 48 dp target: the speech model's Change, and the greyed-out switch's help. */
@Composable
private fun SmallButton(label: String, onClick: () -> Unit) = OutlinedButton(
    onClick, Modifier.heightIn(min = 40.dp), shape = RoundedCornerShape(20.dp),
    contentPadding = PaddingValues(horizontal = 16.dp), border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
) { Text(label) }

/** English, or how many languages a multilingual model knows, for "Speech model: …". */
@Composable
private fun languageName(model: ModelFile): String =
    if (model.languages.size > 1) stringResource(R.string.welcome_model_languages, model.languages.size)
    else stringResource(R.string.welcome_model_english)

/**
 * The bubble step: "Turn on the bubble." over the Settings picture, which plays by itself and rests on the two switches,
 * and the two rules under it that never move; at the bottom the disclosure Google Play requires, word for word, right
 * above Agree and open settings, and Not now. Back with the bubble still off, the picture holds on the list, the heading
 * says so, and the big button opens the setting again, with the restricted-settings help by Not now. Once the service is
 * on nothing is asked, so a line says so and Continue goes on.
 */
@Composable
private fun ServicePage(setup: SetupState?, stillOff: Boolean, actions: WelcomeActions, pad: Dp) {
    val app = stringResource(R.string.app_name)
    val on = setup?.serviceEnabled == true
    val off = stillOff && !on
    var help by rememberSaveable { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The bottom, the disclosure and the buttons, takes at most 60% of the page: at a large font on a small phone the
        // disclosure scrolls there, still right above its button, and the heading, picture and rules keep the rest.
        val bottomMost = maxHeight * 0.6f
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = pad)) {
                StepBody(
                    headingFirst = true, top = true,
                    heading = { Heading(stringResource(if (off) R.string.welcome_service_still_off else R.string.welcome_service_title)) },
                    visual = { SettingsPicture(list = off) },
                    fixed = {
                        if (on) Granted(R.string.welcome_service_on)
                        else Column {
                            SettingsRules()
                            // Back with it still off: the help for a switch Android greys out (a sideloaded app).
                            if (off) SmallButton(stringResource(R.string.welcome_restricted_title)) { help = true }
                        }
                    },
                )
            }
            Column(Modifier.fillMaxWidth().heightIn(max = bottomMost).padding(start = pad, end = pad, top = 12.dp, bottom = 16.dp)) {
                if (!on) {
                    // Scrollable only once there is something to scroll, so TalkBack never calls a disclosure that fits a list.
                    val scroll = rememberScrollState()
                    Text(
                        stringResource(R.string.welcome_service_body, app),
                        Modifier.weight(1f, fill = false).verticalScroll(scroll, enabled = scroll.maxValue > 0).padding(bottom = 16.dp),
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                PrimarySlot(
                    when {
                        on -> R.string.welcome_continue to actions.next
                        off -> R.string.welcome_service_again to actions.openAccessibility
                        else -> R.string.welcome_service_agree to actions.openAccessibility
                    },
                )
                QuietSlot { if (!on) SecondaryButton(stringResource(R.string.welcome_not_now), actions.next) }
            }
        }
    }
    if (help) {
        RestrictedHelp(onClose = { help = false }) {
            help = false
            actions.openAppSettings()
        }
    }
}

/**
 * The last step, by what a take still needs ([need], null until the grants are first read, when it shows nothing yet):
 * "You're all set." with a plain green check only once the microphone, the bubble and the model all work, the model
 * loaded; else what is missing, one thing at a time, with its fix as the big button and Not now, or Go to Home under a
 * download's remedy (no bubble picture); a model that wouldn't load ([loadFailed]) says so, with Try again. Its words
 * crossfade (220 ms) as that changes, never as a percentage does. TalkBack goes to the heading it opened with and hears
 * any later one as it comes. [model] is the model takes use.
 */
@Composable
private fun FinishPage(need: Readiness?, model: ModelFile, loadFailed: Boolean, refusal: MicRefusal?, actions: WelcomeActions, pad: Dp) {
    if (need == null) return
    val still = animationsOff()
    val failed = loadFailed && need == Readiness.Speech(SpeechWait.PREPARING, 100)
    val key: Any = if (failed) LoadFailed else (need as? Readiness.Speech)?.wait ?: need
    val entry = remember { key }
    Column(Modifier.fillMaxSize()) {
        AnimatedContent(
            key, Modifier.weight(1f).fillMaxWidth().padding(horizontal = pad),
            transitionSpec = {
                if (still) EnterTransition.None togetherWith ExitTransition.None using null
                else fadeIn(tween(QuickMs, easing = EaseEnter)) togetherWith fadeOut(tween(QuickMs, easing = EaseStandard)) using null
            },
            label = "finish",
        ) { shown ->
            val first = shown == entry
            val app = stringResource(R.string.app_name)
            when (shown) {
                Readiness.MicOff -> StepBody(heading = { Heading(stringResource(R.string.welcome_mic_off_title), focus = first, live = !first) }, visual = { BigMark(AppIcons.MicOff) }) {
                    Sentence(stringResource(R.string.welcome_mic_off_body, app))
                }
                Readiness.BubbleOff -> StepBody(
                    headingFirst = true, heading = { Heading(stringResource(R.string.welcome_service_still_off), focus = first, live = !first) },
                    visual = { SettingsPicture(list = true) }, fixed = { SettingsRules() },
                )
                Readiness.Ready -> StepBody(heading = { Heading(stringResource(R.string.welcome_all_set), focus = first, live = !first) }, visual = { DrawnCheck() }) {
                    Sentence(stringResource(R.string.welcome_all_set_body))
                }
                LoadFailed -> StepBody(
                    heading = { Heading(stringResource(R.string.welcome_load_failed_title), focus = first, live = !first) },
                    visual = { SpeechPack(Readiness.Speech(SpeechWait.CHECK_FAILED, 100), 1f, stringResource(R.string.welcome_load_failed_picture_read)) },
                ) { Note(stringResource(R.string.welcome_load_failed_retry)) }
                is SpeechWait -> {
                    val speech = need as? Readiness.Speech ?: Readiness.Speech(shown, 100) // leaving, once the model is usable
                    val most = remember { floatArrayOf(0f) }
                    most[0] = maxOf(most[0], speech.percent / 100f)
                    val fraction by animateFloatAsState(most[0], if (still) snap() else tween(ProgressMs, easing = EaseStandard), label = "ring")
                    StepBody(heading = { Heading(stringResource(speechTitle(shown)), focus = first, live = !first) }, visual = { SpeechPack(speech, fraction) }) {
                        Note(speechSupport(shown, model))
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(start = pad, end = pad, top = 12.dp, bottom = 16.dp)) {
            val primary = if (failed) R.string.ui_models_try_again to actions.loadModel else finishButton(need, refusal, actions)
            PrimarySlot(primary)
            // Not now while a grant is off, and Go to Home under a download's remedy: the steps always let the user go on.
            QuietSlot {
                when {
                    need == Readiness.MicOff || need == Readiness.BubbleOff -> SecondaryButton(stringResource(R.string.welcome_not_now), actions.next)
                    need is Readiness.Speech && primary.first != R.string.welcome_go_home -> SecondaryButton(stringResource(R.string.welcome_go_home), actions.next)
                }
            }
        }
    }
}

/** The last step's look for a model that wouldn't load into the engine. */
private object LoadFailed

/** The last step's big button: the missing thing's fix, or Done. */
private fun finishButton(need: Readiness, refusal: MicRefusal?, actions: WelcomeActions): Pair<Int, () -> Unit> = when (need) {
    Readiness.MicOff -> R.string.welcome_mic_allow to (if (refusal == MicRefusal.BLOCKED) actions.openAppSettings else actions.allowMic)
    Readiness.BubbleOff -> R.string.welcome_turn_on_bubble to actions.turnOnBubble
    is Readiness.Speech -> when (need.wait) {
        SpeechWait.WIFI -> R.string.ui_use_mobile_data to actions.useMobileData
        SpeechWait.RETRYING, SpeechWait.PAUSED, SpeechWait.CHECK_FAILED, SpeechWait.NOT_STARTED -> R.string.ui_models_try_again to actions.retryDownload
        SpeechWait.NO_SPACE -> R.string.welcome_open_storage to actions.openStorage
        SpeechWait.DOWNLOADING, SpeechWait.CONNECTION, SpeechWait.PREPARING -> R.string.welcome_go_home to actions.next
    }
    Readiness.Ready -> R.string.welcome_done to actions.next
}

/** The last step's heading while the speech model isn't usable yet, by why. */
@StringRes
private fun speechTitle(wait: SpeechWait): Int = when (wait) {
    SpeechWait.DOWNLOADING -> R.string.welcome_finishing
    SpeechWait.WIFI -> R.string.welcome_wifi_title
    SpeechWait.CONNECTION -> R.string.welcome_connection_title
    SpeechWait.PREPARING -> R.string.welcome_almost
    SpeechWait.RETRYING -> R.string.welcome_retrying_title
    SpeechWait.PAUSED -> R.string.welcome_stopped_title
    SpeechWait.NO_SPACE -> R.string.welcome_space_title
    SpeechWait.CHECK_FAILED -> R.string.welcome_failed_title
    SpeechWait.NOT_STARTED -> R.string.welcome_not_started_title
}

/**
 * The line under that heading: when dictation starts, what was kept, how much space the download needs (what is left to
 * fetch plus a margin, as its own check counts it), or the model and its size.
 */
@Composable
private fun speechSupport(wait: SpeechWait, model: ModelFile): String = when (wait) {
    SpeechWait.DOWNLOADING, SpeechWait.PREPARING -> stringResource(R.string.welcome_finishing_body)
    SpeechWait.RETRYING -> stringResource(R.string.welcome_retrying_body)
    SpeechWait.PAUSED -> stringResource(R.string.welcome_stopped_body)
    SpeechWait.NO_SPACE -> {
        val context = LocalContext.current
        val needed by produceState(model.sizeBytes + Downloader.EXTRA_FREE_BYTES, model) {
            value = withContext(Dispatchers.IO) { ModelDownloads.spaceNeeded(context, model) }
        }
        stringResource(R.string.welcome_space_free, formatSize(needed))
    }
    SpeechWait.CHECK_FAILED -> stringResource(R.string.welcome_failed_body)
    SpeechWait.WIFI, SpeechWait.CONNECTION, SpeechWait.NOT_STARTED -> stringResource(R.string.welcome_model_row, languageName(model), formatSize(model.sizeBytes))
}

/**
 * "You're all set."'s plain green check, no circle around it, drawing itself once over 420 ms; whole at once with
 * animations off. TalkBack skips it: the heading says the same.
 */
@Composable
private fun DrawnCheck() {
    val drawn = onceClock(SceneMs)
    DoneCheck(Modifier.size(120.dp), part = drawn / SceneMs)
}

/** A plain green check, drawn up to [part] (0 to 1) of its stroke. */
@Composable
private fun DoneCheck(modifier: Modifier, part: Float = 1f) {
    val color = MaterialTheme.colorScheme.success
    Canvas(modifier.clearAndSetSemantics {}) {
        val path = Path().apply {
            moveTo(size.width * 0.16f, size.height * 0.52f)
            lineTo(size.width * 0.40f, size.height * 0.76f)
            lineTo(size.width * 0.86f, size.height * 0.26f)
        }
        val measure = PathMeasure().apply { setPath(path, false) }
        val partial = Path()
        measure.getSegment(0f, measure.length * part.coerceIn(0f, 1f), partial, true)
        drawPath(partial, color, style = Stroke(size.minDimension * 0.09f, cap = StrokeCap.Square, join = StrokeJoin.Miter))
    }
}

/** A large mark for a missing grant, in the error colour, with no circle around it. */
@Composable
private fun BigMark(icon: ImageVector) =
    Icon(icon, contentDescription = null, Modifier.size(96.dp), tint = MaterialTheme.colorScheme.error)

/**
 * The big button's slot, 56 dp high and always there, so the button never moves: the button fades in after [delay] ms
 * when there comes to be one (the try's Continue) and out when there is none.
 */
@Composable
private fun PrimarySlot(main: Pair<Int, () -> Unit>?, delay: Int = 0) {
    val still = animationsOff()
    val last = remember { arrayOfNulls<Pair<Int, () -> Unit>>(1) }
    if (main != null) last[0] = main
    Box(Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        AnimatedVisibility(
            main != null,
            enter = if (still) EnterTransition.None else fadeIn(tween(QuickMs, delay, EaseEnter)),
            exit = if (still) ExitTransition.None else fadeOut(tween(ExitMs, easing = EaseExit)),
        ) {
            val (label, action) = main ?: last[0] ?: return@AnimatedVisibility
            PrimaryButton(stringResource(label), action, Modifier.testTag(PRIMARY_BUTTON))
        }
    }
}

/**
 * The quiet slot under the big button, 48 dp and always there, so the big button keeps one place on every step whether
 * or not there is another choice ([content]).
 */
@Composable
private fun QuietSlot(content: @Composable () -> Unit = {}) =
    Box(Modifier.padding(top = 8.dp).fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) { content() }

/** The other choice: outlined, a 2 dp line in the main colour, its words in it too, 48 dp high at full width. */
@Composable
private fun SecondaryButton(label: String, onClick: () -> Unit) = OutlinedButton(
    onClick, Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(20.dp),
    border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
) { Text(label) }

/**
 * The progress bar: four fixed 4 dp tracks 8 dp apart, filled up to this step. Going on, the new step's track fills from
 * its start over 320 ms; going back, it empties toward its start over 180 ms. TalkBack reads it as one line, "Step 2 of 4,
 * Try it".
 */
@Composable
private fun Segments(step: WelcomeStep, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val description = stringResource(R.string.welcome_step, step.ordinal + 1, WelcomeStep.entries.size, stringResource(step.label))
    val still = animationsOff()
    val filled = remember { Animatable(step.ordinal + 1f) }
    LaunchedEffect(step, still) {
        val target = step.ordinal + 1f
        when {
            still -> filled.snapTo(target)
            target > filled.value -> filled.animateTo(target, tween(MoveMs, easing = EaseStandard))
            else -> filled.animateTo(target, tween(BriefMs, easing = EaseStandard))
        }
    }
    val fill = colors.primary
    val track = colors.outlineVariant
    Canvas(modifier.height(4.dp).clearAndSetSemantics { contentDescription = description }) {
        val count = WelcomeStep.entries.size
        val gap = 8.dp.toPx()
        val width = (size.width - gap * (count - 1)) / count
        val corner = CornerRadius(size.height / 2)
        val rtl = layoutDirection == LayoutDirection.Rtl
        for (i in 0 until count) {
            val left = if (rtl) size.width - (i + 1) * width - i * gap else i * (width + gap)
            drawRoundRect(track, Offset(left, 0f), Size(width, size.height), corner)
            val part = width * (filled.value - i).coerceIn(0f, 1f)
            if (part > 0f) drawRoundRect(fill, Offset(if (rtl) left + width - part else left, 0f), Size(part, size.height), corner)
        }
    }
}

/**
 * A step's middle, which never scrolls while its words fit: a picture zone and the words, in one of two orders. Most
 * steps lead with the picture, then 24 dp to the [heading] and 8 dp to the rest ([support]); the bubble step leads with
 * the heading, then 16 dp to the picture ([visual]), 16 dp to what stays with it ([fixed], its two rules) and 16 dp to
 * the rest. The heading, the fixed part and the support show whole; the picture zone gets what they leave, from 176 to
 * 272 dp on a normal phone (no more than the picture after a heading), and the picture is scaled into it, never below
 * [MIN_SCALE] of its size ([LARGE_MIN_SCALE] at a larger font); on a short screen the larger gaps close to 8 dp first.
 * Only then, when the support's words don't fit, the support scrolls and the rest stays; and should even that leave it
 * too little room (a very large font on a small phone) the whole middle scrolls, so no word is ever cut off. The group
 * sits in the middle of its room, or at its [top] (the bubble step, whose words then sit right above its buttons).
 */
@Composable
private fun StepBody(
    heading: @Composable () -> Unit, headingFirst: Boolean = false, top: Boolean = false, visual: (@Composable () -> Unit)? = null,
    fixed: (@Composable () -> Unit)? = null, support: (@Composable ColumnScope.() -> Unit)? = null,
) = BoxWithConstraints(Modifier.fillMaxSize()) {
    val room = constraints.maxHeight
    val minScale = if (LocalDensity.current.fontScale > 1f) LARGE_MIN_SCALE else MIN_SCALE
    val outer = rememberScrollState()
    val inner = rememberScrollState()
    // Scrollable only once there is something to scroll, so TalkBack never calls a step that fits a list.
    Layout(
        contents = listOf(
            heading, { if (visual != null) visual() }, { if (fixed != null) fixed() },
            { if (support != null) Column(Modifier.verticalScroll(inner, enabled = inner.maxValue > 0)) { support() } },
        ),
        modifier = Modifier.verticalScroll(outer, enabled = outer.maxValue > 0),
    ) { (headings, visuals, fixeds, supports), constraints ->
        val width = constraints.maxWidth
        val loose = Constraints(maxWidth = width)
        val h = headings.single().measure(loose)
        val v = visuals.firstOrNull()?.measure(Constraints())
        val f = fixeds.firstOrNull()?.measure(loose)
        val s = supports.firstOrNull()
        // The gap before each part that shows, in the step's order; on a short screen where the words wouldn't fit, the
        // larger gaps close to 8 dp first.
        fun gaps(tight: Boolean) = if (headingFirst) {
            listOf(0, if (v != null) 16 else 0, if (f != null) 16 else 0, if (s != null) 16 else 0)
        } else {
            listOf(0, if (v != null) 24 else 0, 0, if (s != null) 8 else 0)
        }.map { (if (tight) minOf(it, 8) else it).dp.roundToPx() }
        val natural = s?.maxIntrinsicHeight(width) ?: 0
        val least = if (v == null) 0 else (v.height * minScale).roundToInt()
        // At its top, the group starts 16 dp down, which counts in its height: a part pushed past the bottom would be cut
        // off where no scroll reaches it.
        val lead = if (top) 16.dp.roundToPx() else 0
        val roomy = room - lead - h.height - (f?.height ?: 0) - gaps(false).sum() - least >= natural
        val gaps = gaps(tight = !roomy)
        val pinned = lead + h.height + (f?.height ?: 0) + gaps.sum()
        val zone = if (v == null) 0 else {
            val most = if (headingFirst) v.height else maxOf(v.height, MAX_ZONE.roundToPx())
            (room - pinned - natural).coerceIn(least, most)
        }
        val left = room - pinned - zone
        val whole = natural <= left || left < MIN_SUPPORT.roundToPx() // fits, or too little room: the whole middle scrolls
        val sp = s?.measure(Constraints(maxWidth = width, maxHeight = if (whole) natural else maxOf(0, left)))
        val scale = if (v == null) 1f else minOf(1f, zone.toFloat() / v.height, width.toFloat() / v.width)
        val total = pinned + zone + (sp?.height ?: 0)
        layout(width, maxOf(room, total)) {
            var y = if (top) lead else maxOf(0, (room - total) / 2)
            fun place(i: Int, block: () -> Unit) {
                y += gaps[i]
                block()
            }
            fun visualPart() = v?.let {
                place(1) {
                    it.placeWithLayer((width - it.width) / 2, y + (zone - it.height) / 2) {
                        scaleX = scale
                        scaleY = scale
                    }
                    y += zone
                }
            }
            if (!headingFirst) visualPart()
            place(0) { h.place(0, y); y += h.height }
            if (headingFirst) visualPart()
            f?.let { place(2) { it.place((width - it.width) / 2, y); y += it.height } }
            sp?.let { place(3) { it.place(0, y); y += it.height } }
        }
    }
}

/** The picture zone's tallest on a normal phone; a taller phone leaves the rest around the group. */
private val MAX_ZONE = 272.dp

/** The smallest a picture is drawn, at the default font and at larger ones, before the words start to scroll. */
private const val MIN_SCALE = 0.35f
private const val LARGE_MIN_SCALE = 0.6f

/** The least room the support may scroll in; with less, the whole middle scrolls instead. */
private val MIN_SUPPORT = 64.dp

/**
 * A step's heading, 28/34 sp: TalkBack goes to it when the step opens ([focus]), and hears a [live] one as it comes in
 * place of another (the last step's, as what a take needs changes; the try's, as it listens and when it worked).
 */
@Composable
private fun Heading(text: String, focus: Boolean = true, live: Boolean = false) {
    val requester = remember { FocusRequester() }
    val talkBack = talkBackOn()
    Text(
        text, Modifier.focusRequester(requester).focusable().semantics { heading(); if (live) liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.headlineMedium.copy(letterSpacing = (-0.2).sp),
    )
    LaunchedEffect(Unit) { if (focus && talkBack) requester.requestFocus() }
}

@Composable
private fun Sentence(text: String) =
    Text(text, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** A short line of help, quieter than the step's sentence. */
@Composable
private fun Note(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** A green line saying the step is done. */
@Composable
private fun Granted(@StringRes text: Int) {
    val color = MaterialTheme.colorScheme.success
    Surface(shape = MaterialTheme.shapes.extraLarge, color = color.copy(alpha = 0.12f)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.CheckCircle, contentDescription = null, Modifier.size(20.dp), tint = color)
            Text(stringResource(text), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge, color = color)
        }
    }
}

/** The restricted-settings help, as a dialog, so the page itself never grows past the screen; [onAppInfo] opens App info. */
@Composable
private fun RestrictedHelp(onClose: () -> Unit, onAppInfo: () -> Unit) = AlertDialog(
    onDismissRequest = onClose,
    title = { Text(stringResource(R.string.welcome_restricted_title)) },
    text = { Text(stringResource(R.string.welcome_service_restricted)) },
    confirmButton = { TextButton(onAppInfo) { Text(stringResource(R.string.welcome_open_app_info)) } },
    dismissButton = { TextButton(onClose) { Text(stringResource(R.string.welcome_close)) } },
)
