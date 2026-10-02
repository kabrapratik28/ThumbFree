package io.github.kabrapratik28.thumbfree.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// The welcome screens' motion: a few named easings, durations and springs, and nothing timed by hand anywhere else.
// Linear easing drives only raw clocks (a loop's phase, a level meter's phase), never what shows.

/** Settles state changes: icons, labels, progress. */
internal val EaseStandard = CubicBezierEasing(0.20f, 0.00f, 0.00f, 1.00f)

/** Things arriving: page content, rings, checks, pictures' parts. */
internal val EaseEnter = CubicBezierEasing(0.05f, 0.70f, 0.10f, 1.00f)

/** Things leaving: outgoing page content only. */
internal val EaseExit = CubicBezierEasing(0.30f, 0.00f, 0.80f, 0.15f)

internal const val PressInMs = 90
internal const val FadeMs = 160
internal const val QuickMs = 220
internal const val MoveMs = 320
internal const val SceneMs = 420
internal const val HaloMs = 520
internal const val StaggerMs = 40

// The few other times the design names: outgoing page content and a frame's outgoing screen, a progress segment
// emptying, incoming page content, its delay, a frame's incoming screen, and a download's progress.
internal const val ExitMs = 120
internal const val BriefMs = 180
internal const val EnterMs = 280
internal const val EnterDelayMs = 80
internal const val FrameInMs = 240
internal const val ProgressMs = 450

/** A release to rest, or a small settle: at most one 2% overshoot. [settle] is the same for sizes. */
internal val SettleSpring = settle<Float>()

internal fun <T> settle() = spring<T>(dampingRatio = 0.82f, stiffness = 550f)

/** The try's one cue on the bubble, never repeated. */
internal val CueSpring = spring<Float>(dampingRatio = 0.72f, stiffness = 420f)

/** A pressed button back to its size. */
internal val ReleaseSpring = spring<Float>(dampingRatio = 0.85f, stiffness = 800f)

/** Whether things move: with the phone's animations off (Remove animations) every picture holds its last still. */
data class MotionPolicy(val animationsEnabled: Boolean)

/** The motion policy for the screens below; AppTheme provides it. */
val LocalMotionPolicy = staticCompositionLocalOf { MotionPolicy(animationsEnabled = true) }

/**
 * The phone's animator duration scale (Settings > Accessibility > Remove animations), as Compose reads it for its own
 * animations (`MotionDurationScale`, which follows the setting as it changes) and as the setting says: a test's
 * composition has no reading of its own, so the screenshots turn the setting off for their stills. 0 turns every picture
 * to stills and every transition into a cut; both are read live, so a change recomposes the screens below.
 */
@Composable
internal fun rememberMotionPolicy(): MotionPolicy {
    val compose = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    val resolver = LocalContext.current.contentResolver
    fun read() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    var system by remember(resolver) { mutableFloatStateOf(read()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                system = read()
            }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    val enabled = compose > 0f && system > 0f
    return remember(enabled) { MotionPolicy(animationsEnabled = enabled) }
}

/** Provides [rememberMotionPolicy] to [content]. */
@Composable
internal fun ProvideMotion(content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalMotionPolicy provides rememberMotionPolicy(), content = content)

/** Whether the phone's animations are off: pictures then show stills, and transitions cut. */
@Composable
internal fun animationsOff(): Boolean = !LocalMotionPolicy.current.animationsEnabled

/** Whether TalkBack is on for the screens below, when a test says so; null (the default) asks the phone. */
internal val LocalTalkBack = staticCompositionLocalOf<Boolean?> { null }

/** Whether TalkBack (or another touch-exploration reader) is on: then nothing plays or points by itself. */
@Composable
internal fun talkBackOn(): Boolean = LocalTalkBack.current
    ?: (LocalContext.current.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true)

/**
 * How far into one part of a timeline [t] (ms) is: 0 before [startMs], 1 once [durationMs] have passed, eased between
 * by [easing].
 */
internal fun seg(t: Float, startMs: Int, durationMs: Int, easing: Easing = EaseEnter): Float =
    easing.transform(((t - startMs) / durationMs).coerceIn(0f, 1f))

/**
 * A looping scene's clock, in ms from 0 to [periodMs] and again: its raw phase, linear, which each part of the scene
 * eases on its own ([seg]). A state, read only where the scene draws, so its frames never compose anything again. Call
 * it only with animations on; a still scene has no clock.
 */
@Composable
internal fun loopClockState(periodMs: Int): State<Float> = rememberInfiniteTransition("scene")
    .animateFloat(0f, periodMs.toFloat(), infiniteRepeatable(tween(periodMs, easing = LinearEasing)), "clock")

/**
 * A one-time scene's clock, in ms from its first composition (or from [key] changing) until [endMs], where it stays;
 * at [endMs] at once with animations off.
 */
@Composable
internal fun onceClock(endMs: Int, key: Any? = Unit): Float {
    val still = animationsOff()
    val clock = remember(key) { Animatable(if (still) endMs.toFloat() else 0f) }
    LaunchedEffect(key, still) {
        if (still) clock.snapTo(endMs.toFloat()) else clock.animateTo(endMs.toFloat(), tween(endMs - clock.value.toInt(), easing = LinearEasing))
    }
    return clock.value
}

/**
 * The tap halo, drawn outside its target so it reads as where to tap, not as a press: a 3 dp ring centred on [center],
 * growing from 0.65 to 1.45 of [radius] and fading from 28% to nothing as [progress] (0 to 1, over [HaloMs]) goes, in
 * [color] (sunflower, or the dark theme's primary).
 */
internal fun DrawScope.halo(center: Offset, radius: Float, progress: Float, color: Color) {
    if (progress <= 0f || progress >= 1f) return
    val p = EaseEnter.transform(progress)
    drawCircle(color.copy(alpha = 0.28f * (1 - p)), radius = radius * (0.65f + 0.8f * p), center = center, style = Stroke(3.dp.toPx()))
}

/**
 * The welcome's big button: 56 dp high at full width, its bounds fixed, with Material's own ripple; a press shrinks the
 * drawn surface to 98.5% (never its layout) and a release springs it back. [label] crossfades within it. No vibration:
 * the only ticks are a take's start and stop.
 */
@Composable
internal fun PrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val presses = remember { MutableInteractionSource() }
    val pressed by presses.collectIsPressedAsState()
    val scale = remember { Animatable(1f) }
    val still = animationsOff()
    LaunchedEffect(pressed, still) {
        when {
            still -> scale.snapTo(1f)
            pressed -> scale.animateTo(0.985f, tween(PressInMs, easing = EaseStandard))
            else -> scale.animateTo(1f, ReleaseSpring)
        }
    }
    Button(
        onClick, modifier.fillMaxWidth().heightIn(min = 56.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value },
        shape = RoundedCornerShape(20.dp), interactionSource = presses,
    ) { Crossfaded(label) { Text(it) } }
}

/** [value] shown through [content], crossfading over [FadeMs] when it changes; a cut with animations off. */
@Composable
internal fun <T> Crossfaded(value: T, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) = Crossfade(
    value, modifier, animationSpec = if (animationsOff()) snap() else tween(FadeMs, easing = EaseStandard), label = "crossfade",
) { content(it) }
