package io.github.kabrapratik28.thumbfree.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.kabrapratik28.thumbfree.R
import io.github.kabrapratik28.thumbfree.a11y.BubbleView
import io.github.kabrapratik28.thumbfree.app.AndroidPorts
import io.github.kabrapratik28.thumbfree.app.TouchRelay
import io.github.kabrapratik28.thumbfree.app.TrialHost
import io.github.kabrapratik28.thumbfree.core.session.BubbleStyle
import io.github.kabrapratik28.thumbfree.core.session.BubbleUi
import io.github.kabrapratik28.thumbfree.core.session.ChipAction
import io.github.kabrapratik28.thumbfree.core.session.Code
import io.github.kabrapratik28.thumbfree.core.session.Gesture
import io.github.kabrapratik28.thumbfree.core.session.Grey
import io.github.kabrapratik28.thumbfree.core.session.HapticKind
import io.github.kabrapratik28.thumbfree.core.session.TouchOutput

/**
 * The try's link to the take machine. MainActivity's goes to AndroidPorts and the controller; screen tests pass their own,
 * and screenshots none, which leaves the bubble still.
 */
interface TrialLink {
    fun attach(host: TrialHost)
    fun detach(host: TrialHost)
    fun touch(output: TouchOutput)
    fun chip(action: ChipAction)
}

/** The try's bubble, for tests: a real BubbleView inside the welcome screen. */
const val TRY_BUBBLE = "try-bubble"

/** The "Tap" label over the try's bubble before its first tap, for tests. */
const val TAP_LABEL = "tap-label"

/**
 * The try on screen, and the take machine's host for it: what its bubble draws, the microphone's level, and how far the
 * try got. [done] after one start and stop: Continue then shows. A warning while the take still records (a silent
 * microphone, the time limit coming) is not the stop. A silent microphone sets [unheard] while the take records, and so
 * does a take that heard no speech once it ends; one that never listened (the microphone wasn't ready, say) leaves its
 * reason in [problem]. [reset] forgets it all, as the step goes.
 */
internal class TrialState : TrialHost {
    var listening by mutableStateOf(false)
        private set

    /** The microphone's level, 0 to 1, sampled at most 20 times a second and smoothed: quick to rise, slow to fall. */
    var level by mutableFloatStateOf(0f)
        private set
    var done by mutableStateOf(false)
        private set
    var words by mutableStateOf<String?>(null)
        private set

    /** The take's words as heard, which Clean up's beat starts every style from; [words] may show a tidy of them. */
    var heard by mutableStateOf<String?>(null)
        private set

    /** Counts takes and resets, so a tidy that comes late never lands on the next take's card. */
    var takeNumber = 0
        private set
    var unheard by mutableStateOf(false)
        private set
    var problem by mutableStateOf<Code?>(null)
        private set

    /** The take stopped and its words aren't here yet: the bubble turns while they are worked out. */
    var transcribing by mutableStateOf(false)
        private set

    /** A finger is on the bubble. */
    var pressed by mutableStateOf(false)

    /** The bubble was tapped at least once on this visit: its cue (the halo and the "Tap" label) is over. */
    var tapped by mutableStateOf(false)

    /** Whether a take may start: the bubble is yellow. A grey one starts nothing; a tap on it counts in [greyTaps]. */
    var enabled = false

    /** Taps on the grey bubble on this visit, each answered with a small shake. */
    var greyTaps by mutableIntStateOf(0)
        private set

    fun greyTap() {
        greyTaps++
    }

    var view: BubbleView? = null
        set(value) {
            field = value
            value?.let(::draw)
        }
    private var drawn: BubbleUi = BubbleUi.Idle
    private var sampledAt = 0L

    override fun render(ui: BubbleUi) {
        when {
            ui is BubbleUi.Recording -> {
                if (!listening) {
                    takeNumber++
                    words = null
                    heard = null
                    unheard = false
                    problem = null
                }
                listening = true
                val now = SystemClock.uptimeMillis()
                if (now - sampledAt >= METER_SAMPLE_MS) {
                    sampledAt = now
                    level += (ui.level - level) * if (ui.level > level) ATTACK else RELEASE
                }
            }
            // A warning while the take still records: it goes on listening, the bubble as it was.
            ui is BubbleUi.Chip && listening && ui.code in WARNINGS -> {
                if (ui.code == Code.MIC_SILENT) unheard = true
                return
            }
            ui == BubbleUi.Arming || ui == BubbleUi.Idle && !listening -> Unit
            // The take left listening: a stop, unless the user cancelled it with the X.
            listening -> {
                listening = false
                level = 0f
                unheard = false // a silence it warned of may have ended; only a take that heard nothing says so
                if (ui !is BubbleUi.Chip || ui.code != Code.CANCELLED) done = true
            }
        }
        if (ui is BubbleUi.Chip) when (ui.code) {
            Code.NO_SPEECH -> unheard = true
            Code.CANCELLED, Code.NO_MODEL -> Unit // back to the start
            in ENGINE_CODES -> problem = ui.code // the engine failed, after the stop too: said plainly, never "speak nearer"
            else -> if (!done) problem = ui.code
        }
        transcribing = ui is BubbleUi.Processing
        drawn = ui
        view?.let(::draw)
    }

    /** Only listening's start and stop tick, one each, as the floating bubble's do; a try never buzzes otherwise. */
    override fun haptic(kind: HapticKind) {
        if (kind == HapticKind.TICK || kind == HapticKind.STOP) view?.performHapticFeedback(AndroidPorts.hapticConstant(kind))
    }

    override fun words(text: String) {
        words = text
        heard = text
    }

    /** Clean up's beat: the card shows [text], a tidy of the words as heard in take [number]. */
    fun tidied(text: String, number: Int) {
        if (number == takeNumber && heard != null) words = text
    }

    /** Forgets the try: nothing of it stays once its step goes. */
    fun reset() {
        listening = false
        level = 0f
        takeNumber++
        done = false
        words = null
        heard = null
        unheard = false
        problem = null
        transcribing = false
        pressed = false
        tapped = false
        greyTaps = 0
        drawn = BubbleUi.Idle
    }

    /**
     * The bubble as the take machine drew it, without its chips and progress notes, whose news this step says in its own
     * words, and without the grey of the microphone opening. TalkBack hears the bubble's own one label throughout.
     */
    private fun draw(view: BubbleView) {
        view.render(
            when (val ui = drawn) {
                // The moment the microphone opens stays yellow here: the press already answered the finger, and the red
                // ring fades in as listening starts.
                is BubbleUi.Chip, BubbleUi.Arming -> BubbleUi.Idle
                is BubbleUi.Processing -> BubbleUi.Processing(false, 0, null)
                else -> ui
            },
        )
    }

    companion object {
        private const val METER_SAMPLE_MS = 50L // 20 a second
        private const val ATTACK = 0.55f
        private const val RELEASE = 0.18f

        /** What the take machine shows while a take still records (DictationController.onWarning). */
        private val WARNINGS = setOf(Code.MIC_SILENT, Code.TAKE_ENDS_SOON)

        /** The engine's own failures: the try says speech couldn't run, whenever in the take they come. */
        val ENGINE_CODES = setOf(Code.LOAD_FAILED, Code.NO_MEMORY, Code.ENGINE_CRASHED)
    }
}

/**
 * Attaches the try to the take machine while the screen is started: going to the background ends a take the try
 * started (and deletes its recording), and leaving the step forgets the try.
 */
@Composable
internal fun TrialAttached(trial: TrialState, link: TrialLink?) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(trial, link, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) link?.attach(trial)
            if (event == Lifecycle.Event.ON_STOP) link?.detach(trial)
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            link?.detach(trial)
            trial.reset()
        }
    }
}

/**
 * Sam's card for the first two steps, the same card throughout, with the real bubble in its slot once there is one
 * ([showBubble]): grey with its ring and badge while it can't listen ([grey]), then yellow. The reply is the try's
 * words, never a field; while the bubble listens the card shows Listening with the microphone's level. [cue] gives the
 * yellow bubble its "Tap" label and its halo every 1.4 s until the first tap. A [compact] card (a large font, or a short
 * screen) drops the card around the reply and the bubble, which keep their places.
 */
@Composable
internal fun TryCard(
    trial: TrialState, link: TrialLink?, showBubble: Boolean, grey: Grey?, cue: Boolean, compact: Boolean,
    micGranted: Boolean?, onNeedsMic: () -> Unit,
) {
    val still = animationsOff()
    val words = trial.words
    // The words cross in once, rising 4 dp; never typed letter by letter.
    val wordsIn = remember { Animatable(0f) }
    LaunchedEffect(words, still) {
        when {
            words == null -> wordsIn.snapTo(0f)
            still -> wordsIn.snapTo(1f)
            else -> {
                wordsIn.snapTo(0f)
                wordsIn.animateTo(1f, tween(QuickMs, delayMillis = QuickMs, easing = EaseEnter))
            }
        }
    }
    val pill by animateFloatAsState(
        if (trial.listening) 1f else 0f,
        if (still) snap() else if (trial.listening) tween(QuickMs, PressInMs + StaggerMs, EaseEnter) else tween(QuickMs, easing = EaseStandard),
        label = "pill",
    )
    val level by animateFloatAsState(trial.level, tween(METER_FRAME_MS, easing = LinearEasing), label = "level")
    val bars = remember { FloatArray(5) }
    val reply = if (words == null) stringResource(R.string.welcome_reply_read) else stringResource(R.string.welcome_reply_words_read, words)
    SamChatCard(
        reply = words.orEmpty(), words = { wordsIn.value }, replyRise = 1 - wordsIn.value, replyLines = 2, compact = compact,
        description = stringResource(R.string.welcome_card_read), replyDescription = reply,
        pill = {
            // The meter is the microphone's own level, drawn each frame without measuring anything (a picture, which
            // TalkBack skips); the word Listening is there only while it shows.
            if (pill > 0f) ListeningPill(
                { for (i in bars.indices) bars[i] = level * PROFILE[i]; bars },
                Modifier.graphicsLayer { alpha = pill; translationY = 4.dp.toPx() * (1 - pill) },
            )
        },
    ) {
        AnimatedVisibility(
            showBubble,
            enter = if (still) EnterTransition.None else fadeIn(tween(SceneMs, easing = EaseEnter)) + scaleIn(tween(SceneMs, easing = EaseEnter), 0.84f),
            exit = if (still) ExitTransition.None else fadeOut(tween(ExitMs, easing = EaseExit)),
        ) { TrialBubble(trial, link, grey, cue, micGranted, onNeedsMic) }
    }
}

/** The five bars' heights for one level, highest in the middle. */
private val PROFILE = floatArrayOf(0.55f, 0.8f, 1f, 0.8f, 0.55f)

/** How long the meter takes from one sampled level to the next: the 20-a-second sampling period. */
private const val METER_FRAME_MS = 50

/**
 * The try's bubble: a BubbleView, the floating bubble's own, at the size it has in other apps until the owner changes it,
 * fully solid, with a 72 dp target and no X. Grey ([grey]) it isn't a button: a tap gives it a small shake and nothing
 * starts. Turning yellow it gives one small bounce; yellow, a tap does what the floating bubble's does, except that the
 * first, before the microphone is allowed ([micGranted], or Android's answer before it is read), asks Android for it
 * ([onNeedsMic]). The view is made once and outlives the step it was made on (the first step shows it grey, the try
 * makes it yellow), so its listeners read the [link] and [onNeedsMic] of now, never those it was made with. With [cue],
 * until that first tap, a soft halo every 1.4 s and a dark "Tap" label above it say where to tap; neither with
 * animations off (the label stays, still) or, for the halo, with TalkBack on. A finger down presses it to 96%, and it
 * settles back. TalkBack's double-tap is a click, not a touch, so a click is a tap.
 */
@Composable
private fun TrialBubble(trial: TrialState, link: TrialLink?, grey: Grey?, cue: Boolean, micGranted: Boolean?, onNeedsMic: () -> Unit) {
    val still = animationsOff()
    val talkBack = talkBackOn()
    val enabled = grey == null
    SideEffect { trial.enabled = enabled }
    // What the listeners read when a touch comes: the grant as the screen last read it (asked for again on every resume;
    // before the first read, Android's answer now), the take machine's link and the microphone's question.
    val granted by rememberUpdatedState(micGranted)
    val currentLink by rememberUpdatedState(link)
    val needsMic by rememberUpdatedState(onNeedsMic)
    val bounce = remember { Animatable(1f) }
    val shake = remember { Animatable(0f) }
    // Grey to yellow: one small bounce, as the bubble becomes something to tap.
    var wasGrey by remember { mutableStateOf(grey != null) }
    LaunchedEffect(enabled) {
        if (enabled && wasGrey && !still) {
            bounce.animateTo(1.08f, CueSpring)
            bounce.animateTo(1f, CueSpring)
        }
        wasGrey = !enabled
    }
    // A tap on the grey bubble: a small shake, 6 dp each way and back over 300 ms.
    LaunchedEffect(trial.greyTaps) {
        if (trial.greyTaps > 0 && !still) {
            shake.snapTo(0f)
            shake.animateTo(0f, keyframes { durationMillis = SHAKE_MS; 6f at 60; -6f at 140; 4f at 210; 0f at SHAKE_MS })
        }
    }
    val untapped = cue && !trial.tapped && !trial.pressed && !trial.listening && !trial.done
    // The halo's clock, read only where it draws, so its frames never compose the bubble again.
    val pulse = if (untapped && enabled && !still && !talkBack) loopClockState(PULSE_MS) else null
    val press = remember { Animatable(1f) }
    LaunchedEffect(trial.pressed, still) {
        when {
            still -> press.snapTo(1f)
            trial.pressed -> press.animateTo(0.96f, tween(PressInMs, easing = EaseStandard))
            else -> press.animateTo(1f, SettleSpring)
        }
    }
    val colors = MaterialTheme.colorScheme
    val sunflower = if (colors.isDark) colors.primary else Sunflower
    Box(contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { context ->
                BubbleView(context) { currentLink?.chip(it) }.apply {
                    style = BubbleStyle.RECOMMENDED.copy(opacity = BubbleStyle.MAX_OPACITY)
                    targetDp = TARGET_DP
                    cancelable = false
                    val relay = TouchRelay(Gesture(ViewConfiguration.get(context).scaledTouchSlop.toFloat())) { currentLink?.touch(it) }
                    val micAllowed = { granted ?: (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
                    // How the touch that began goes: to the take machine, to Android's microphone question, or a shake.
                    var route = Route.TAKE
                    setOnTouchListener { _, event ->
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                            route = when {
                                !trial.enabled -> Route.SHAKE
                                currentLink == null -> Route.NONE
                                !micAllowed() -> Route.MIC
                                else -> Route.TAKE
                            }
                        }
                        when (route) {
                            Route.TAKE -> when (event.actionMasked) {
                                MotionEvent.ACTION_DOWN -> {
                                    trial.pressed = true
                                    trial.tapped = true
                                    relay.down(event.rawX, event.rawY, event.eventTime)
                                }
                                MotionEvent.ACTION_MOVE -> relay.move(event.rawX, event.rawY, event.eventTime)
                                MotionEvent.ACTION_UP -> {
                                    trial.pressed = false
                                    relay.move(event.rawX, event.rawY, event.eventTime)
                                    relay.up(event.eventTime)
                                }
                                MotionEvent.ACTION_CANCEL -> {
                                    trial.pressed = false
                                    relay.hide()
                                }
                            }
                            Route.MIC -> if (event.actionMasked == MotionEvent.ACTION_UP) {
                                trial.tapped = true
                                needsMic()
                            }
                            Route.SHAKE -> if (event.actionMasked == MotionEvent.ACTION_UP) trial.greyTap()
                            Route.NONE -> Unit
                        }
                        true
                    }
                    setOnClickListener {
                        val now = currentLink
                        when {
                            !trial.enabled || now == null -> Unit
                            !micAllowed() -> {
                                trial.tapped = true
                                needsMic()
                            }
                            else -> {
                                trial.tapped = true
                                now.touch(TouchOutput.Press)
                                now.touch(TouchOutput.Release(0))
                            }
                        }
                    }
                    trial.view = this
                }
            },
            // Grey, it stays enabled, so its touches still reach the listener for the shake, but it isn't a button.
            update = {
                it.grey = grey
                it.isClickable = enabled && link != null
            },
            onRelease = { if (trial.view === it) trial.view = null },
            modifier = Modifier.testTag(TRY_BUBBLE).semantics { if (!enabled) disabled() }.requiredSize(TARGET_DP.dp)
                .drawBehind { pulse?.let { halo(center, ART_RADIUS.dp.toPx(), (it.value / HaloMs).coerceAtLeast(0f), sunflower) } }
                .graphicsLayer {
                    (bounce.value * press.value).let { scaleX = it; scaleY = it }
                    translationX = shake.value.dp.toPx()
                },
        )
        if (cue && enabled) {
            val label by animateFloatAsState(if (untapped) 1f else 0f, if (still) snap() else tween(QuickMs, easing = EaseStandard), label = "tap label")
            if (label > 0f) TapLabel(Modifier.align(Alignment.TopCenter).offset(y = (-38).dp).graphicsLayer { alpha = label })
        }
    }
}

/** Where a touch on the try's bubble goes, decided as it begins. */
private enum class Route { TAKE, MIC, SHAKE, NONE }

/**
 * The "Tap" label over the bubble, its point toward it, in the theme's inverse colours (dark on a light page, light on a
 * dark one); in picture text, so its place over the bubble holds at any font size. Only to look at: TalkBack skips it.
 */
@Composable
private fun TapLabel(modifier: Modifier) = Column(
    modifier.wrapContentSize(unbounded = true).testTag(TAP_LABEL).clearAndSetSemantics {},
    horizontalAlignment = Alignment.CenterHorizontally,
) {
    val colors = MaterialTheme.colorScheme
    Text(
        stringResource(R.string.welcome_tap_label),
        Modifier.background(colors.inverseSurface, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 4.dp),
        style = pictureText(14, FontWeight.SemiBold), color = colors.inverseOnSurface,
    )
    Box(Modifier.size(width = 12.dp, height = 6.dp).background(colors.inverseSurface, POINT))
}

/** The label's point: a small triangle under it, toward the bubble. */
private val POINT = GenericShape { size, _ ->
    moveTo(0f, 0f)
    lineTo(size.width, 0f)
    lineTo(size.width / 2, size.height)
    close()
}

/** The try's bubble target, larger than its 60 dp art, and the art's radius, where its halo centres. */
private const val TARGET_DP = 72
private const val ART_RADIUS = 30

/** The halo's period until the first tap, and a grey bubble's shake. */
private const val PULSE_MS = 1_400
private const val SHAKE_MS = 300
