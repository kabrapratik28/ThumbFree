package io.github.kabrapratik28.thumbfree.ui

import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.kabrapratik28.thumbfree.R
import io.github.kabrapratik28.thumbfree.core.models.Catalog
import io.github.kabrapratik28.thumbfree.core.models.ModelFile
import io.github.kabrapratik28.thumbfree.models.DownloadState
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The first-run screens, in order; the Try tab follows the last. Settings > Setup opens them again. */
enum class WelcomeStep { WELCOME, MIC, SERVICE }

/**
 * The model the welcome screen recommends to everyone, under a plain name: the default. The one other choice there is
 * the multilingual model; Settings > Speech model names them all and lets the owner switch.
 */
val RECOMMENDED_MODEL = Catalog.PARAKEET_UNIFIED_Q8

/** The model the welcome screen offers: the multilingual one once it is the [chosen] model, else the recommended one. */
fun welcomeModel(chosen: ModelFile): ModelFile = if (chosen == Catalog.PARAKEET_TDT_V3_Q8) chosen else RECOMMENDED_MODEL

/**
 * What Get started downloads: the shown [model], unless it is on the phone already or its download is under way, which a
 * second start would begin again. A failed download starts again, from its partial file.
 */
fun welcomeDownload(model: ModelFile, setup: SetupState?, download: DownloadState?): ModelFile? =
    model.takeUnless { modelReady(model, setup, download) || download.underWay }

/**
 * The welcome screen's download actions (Get started's start, a switch's cancel and its choice), each run once every
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
 * What the welcome screens do; MainActivity runs each one. [next] on the last step finishes them and opens the Try tab;
 * [getStarted] starts the model's download and moves on; [closeHelp] puts the restricted-settings help away.
 */
class WelcomeActions(
    val next: () -> Unit, val back: () -> Unit, val close: (() -> Unit)?,
    val getStarted: () -> Unit, val cancelModel: () -> Unit, val chooseModel: (ModelFile) -> Unit,
    val allowMic: () -> Unit, val openAppSettings: () -> Unit, val openAccessibility: () -> Unit, val closeHelp: () -> Unit,
)

/**
 * One welcome step, which never scrolls: the progress dots (with Back after the first step, and Close when opened from
 * Settings), a looping picture, a short title and one sentence, and the step's buttons. The words and buttons always get
 * their room; the picture takes what is left, smaller at a large font and gone when there is none. [model] is the speech
 * model the first screen offers ([welcomeModel]), [download] its download and [wifiOnly] the setting that download
 * follows; while [busy] (a download action asked for earlier still runs, ModelQueue) the model switch waits. [refusal] is
 * the last microphone answer; [restrictedHelp] shows the restricted-settings help, after a return from Accessibility
 * settings with the service still off (its switch can be greyed out for a sideloaded app).
 */
@Composable
fun WelcomeScreen(
    step: WelcomeStep, setup: SetupState?, refusal: MicRefusal?, restrictedHelp: Boolean, actions: WelcomeActions,
    download: DownloadState? = null, wifiOnly: Boolean = true, busy: Boolean = false, model: ModelFile = RECOMMENDED_MODEL,
) {
    val app = stringResource(R.string.app_name)
    val still = animationsOff()
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = MAX_CONTENT_WIDTH).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp)) {
                    if (step != WelcomeStep.WELCOME) IconButton(actions.back) {
                        Icon(AppIcons.ArrowBack, contentDescription = stringResource(R.string.welcome_back))
                    }
                }
                Dots(step, Modifier.weight(1f))
                Box(Modifier.size(48.dp)) {
                    if (actions.close != null) IconButton(actions.close) {
                        Icon(AppIcons.Close, contentDescription = stringResource(R.string.welcome_close))
                    }
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 24.dp)) {
                Fit(Modifier.weight(1f).fillMaxWidth()) {
                    when (step) {
                        WelcomeStep.WELCOME -> WelcomePicture(still)
                        WelcomeStep.MIC -> MicPicture(still)
                        WelcomeStep.SERVICE -> WalkthroughPicture(still)
                    }
                }
                Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (step) {
                        WelcomeStep.WELCOME -> {
                            Title(stringResource(R.string.welcome_title))
                            Sentence(stringResource(R.string.welcome_body))
                            ModelChip(model, modelReady(model, setup, download), wifiOnly, busy, actions)
                        }
                        WelcomeStep.MIC -> {
                            Title(stringResource(R.string.welcome_mic_title, app))
                            Sentence(stringResource(R.string.welcome_mic_body))
                            when {
                                setup?.micGranted == true -> Granted(R.string.welcome_mic_granted)
                                refusal == MicRefusal.BLOCKED -> Refused(R.string.welcome_mic_blocked)
                                refusal == MicRefusal.DENIED -> Refused(R.string.welcome_mic_denied)
                            }
                        }
                        // The disclosure Google Play requires, before the user agrees and leaves for the setting. Once the
                        // service is on nothing is asked, so a line says so instead.
                        WelcomeStep.SERVICE -> {
                            Title(stringResource(R.string.welcome_service_title))
                            if (setup?.serviceEnabled == true) Granted(R.string.welcome_service_on)
                            else Sentence(stringResource(R.string.welcome_service_body, app))
                        }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val (primary, primaryAction) = primary(step, setup, refusal, actions)
                Button(primaryAction, Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(primary)) }
                val secondary = secondary(step, setup)
                if (secondary != null) {
                    TextButton(actions.next, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(secondary)) }
                }
            }
        }
    }
    if (restrictedHelp) RestrictedHelp(actions)
}

/** Whether the model the screen shows is on the phone and checked: its own download or verdict, never the chosen model's. */
private fun modelReady(model: ModelFile, setup: SetupState?, download: DownloadState?): Boolean =
    download == DownloadState.Ready || setup?.offered?.contains(model) == true

/** The main button: Get started, then each step's request until it is granted, then Continue. */
private fun primary(step: WelcomeStep, setup: SetupState?, refusal: MicRefusal?, actions: WelcomeActions): Pair<Int, () -> Unit> =
    when (step) {
        WelcomeStep.WELCOME -> R.string.welcome_start to actions.getStarted
        WelcomeStep.MIC -> when {
            setup?.micGranted == true -> R.string.welcome_continue to actions.next
            refusal == MicRefusal.BLOCKED -> R.string.welcome_open_app_settings to actions.openAppSettings
            else -> R.string.welcome_mic_allow to actions.allowMic
        }
        WelcomeStep.SERVICE ->
            if (setup?.serviceEnabled == true) R.string.welcome_continue to actions.next
            else R.string.welcome_service_agree to actions.openAccessibility
    }

/** "Not now" while a step's request is open. */
@StringRes
private fun secondary(step: WelcomeStep, setup: SetupState?): Int? = when {
    step == WelcomeStep.MIC && setup?.micGranted != true -> R.string.welcome_not_now
    step == WelcomeStep.SERVICE && setup?.serviceEnabled != true -> R.string.welcome_not_now
    else -> null
}

/** Queued, downloading or being checked: the download goes on without the screen. */
private val DownloadState?.underWay: Boolean
    get() = this is DownloadState.Queued || this is DownloadState.Downloading || this == DownloadState.Verifying

/** One dot per step; the current one is a wide pill. TalkBack reads "Step 2 of 3". */
@Composable
private fun Dots(step: WelcomeStep, modifier: Modifier) {
    val description = stringResource(R.string.welcome_step, step.ordinal + 1, WelcomeStep.entries.size)
    Row(
        modifier.clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        for (each in WelcomeStep.entries) {
            val color = when {
                each == step -> MaterialTheme.colorScheme.primary
                each < step -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                else -> MaterialTheme.colorScheme.outlineVariant
            }
            Box(Modifier.size(width = if (each == step) 24.dp else 8.dp, height = 8.dp).clip(CircleShape).background(color))
        }
    }
}

@Composable
private fun Title(text: String) = Text(text, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)

@Composable
private fun Sentence(text: String) =
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** A green line saying the step is done. */
@Composable
private fun Granted(@StringRes text: Int) {
    val color = MaterialTheme.colorScheme.success
    Surface(shape = MaterialTheme.shapes.extraLarge, color = color.copy(alpha = 0.12f)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.CheckCircle, contentDescription = null, Modifier.size(20.dp), tint = color)
            Text(stringResource(text), Modifier.padding(start = 10.dp), style = MaterialTheme.typography.labelLarge, color = color)
        }
    }
}

/** One short line after a refused microphone request: what it means and how to get past it. */
@Composable
private fun Refused(@StringRes text: Int) = Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Icon(AppIcons.Info, contentDescription = null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.caution)
    Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
}

/**
 * The speech [model] Get started downloads, in a chip with its size and whether it waits for Wi-Fi ([wifiOnly]), or
 * "ready" once it is on the phone ([ready]); beside it, a link switches between English and the multilingual model.
 * A switch first cancels the shown model's download unless it is on the phone, so one started before a Back never runs
 * on for nothing. Both run in order behind any earlier action (ModelQueue), so neither is ever dropped.
 */
@Composable
private fun ModelChip(model: ModelFile, ready: Boolean, wifiOnly: Boolean, busy: Boolean, actions: WelcomeActions) {
    val colors = MaterialTheme.colorScheme
    val multilingual = model != RECOMMENDED_MODEL
    val size = formatSize(model.sizeBytes)
    val text = when {
        ready -> stringResource(if (multilingual) R.string.welcome_model_multilingual_ready else R.string.welcome_model_english_ready)
        multilingual && wifiOnly -> stringResource(R.string.welcome_model_multilingual, size)
        multilingual -> stringResource(R.string.welcome_model_multilingual_any_network, size)
        wifiOnly -> stringResource(R.string.welcome_model_english, size)
        else -> stringResource(R.string.welcome_model_english_any_network, size)
    }
    FlowRow(itemVerticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(16.dp), color = colors.surfaceContainerHigh) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    if (ready) AppIcons.Check else AppIcons.Download, contentDescription = null, Modifier.size(16.dp),
                    tint = if (ready) colors.success else colors.onSurfaceVariant,
                )
                Text(text, style = MaterialTheme.typography.labelLarge)
            }
        }
        TextButton(
            {
                if (!ready) actions.cancelModel()
                actions.chooseModel(if (multilingual) RECOMMENDED_MODEL else Catalog.PARAKEET_TDT_V3_Q8)
            },
            enabled = !busy,
        ) {
            Text(stringResource(if (multilingual) R.string.welcome_model_use_english else R.string.welcome_model_other_language))
        }
    }
}

/** The restricted-settings help, as a dialog, so the page itself never grows past the screen. */
@Composable
private fun RestrictedHelp(actions: WelcomeActions) = AlertDialog(
    onDismissRequest = actions.closeHelp,
    title = { Text(stringResource(R.string.welcome_restricted_title)) },
    text = { Text(stringResource(R.string.welcome_service_restricted)) },
    confirmButton = {
        TextButton({ actions.closeHelp(); actions.openAppSettings() }) { Text(stringResource(R.string.welcome_open_app_info)) }
    },
    dismissButton = { TextButton(actions.closeHelp) { Text(stringResource(R.string.welcome_close)) } },
)

/** Whether the phone's animations are off (animator duration scale 0, which Remove animations sets): pictures show stills. */
@Composable
private fun animationsOff(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/** The time through one loop of a picture, from 0 to 1 over [periodMs] and again. */
@Composable
private fun loop(periodMs: Int): Float {
    val progress by rememberInfiniteTransition("picture")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(periodMs, easing = LinearEasing)), "progress")
    return progress
}

/** Where [p] is between [from] and [to], from 0 to 1. */
private fun between(p: Float, from: Float, to: Float): Float = ((p - from) / (to - from)).coerceIn(0f, 1f)

/**
 * [content] at its own size, scaled to the room it gets and centred: down at a large font or on a small screen, a little
 * up on a big one, and not drawn at all below [MIN_PICTURE_SCALE], where it would be too small to make out.
 */
@Composable
private fun Fit(modifier: Modifier, content: @Composable () -> Unit) = Layout(content, modifier) { measurables, constraints ->
    val picture = measurables.first().measure(Constraints())
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth else picture.width
    val height = if (constraints.hasBoundedHeight) constraints.maxHeight else picture.height
    val scale = minOf(MAX_PICTURE_SCALE, width.toFloat() / picture.width, height.toFloat() / picture.height)
    layout(width, height) {
        if (scale >= MIN_PICTURE_SCALE) {
            picture.placeWithLayer((width - picture.width) / 2, (height - picture.height) / 2) {
                scaleX = scale
                scaleY = scale
            }
        }
    }
}

private const val MIN_PICTURE_SCALE = 0.5f
private const val MAX_PICTURE_SCALE = 1.25f

/**
 * Picture text at a fixed size: a picture keeps its shape at any font size and shrinks as a whole (it has its own
 * TalkBack description), while the page's own words follow the font setting.
 */
@Composable
private fun pictureText(size: Int, weight: FontWeight = FontWeight.Normal): TextStyle {
    val sp = with(LocalDensity.current) { size.dp.toSp() }
    return MaterialTheme.typography.bodyMedium.copy(fontSize = sp, lineHeight = sp * 1.35f, fontWeight = weight)
}

/** A finger's tap on this element: a ripple that grows from its centre and fades as [tap] goes from 0 to 1. */
private fun Modifier.tapRipple(tap: Float, color: Color): Modifier = drawWithContent {
    drawContent()
    if (tap > 0f && tap < 1f) drawCircle(color.copy(alpha = 0.3f * (1 - tap)), radius = size.maxDimension * 0.6f * tap)
}

/**
 * The first screen's picture, cropped to a message box and the bubble beside it: the bubble fades in, is tapped, turns
 * red and pulses while it listens, and the reply types in letter by letter; then it loops. Still: the reply typed and
 * the bubble listening.
 */
@Composable
internal fun WelcomePicture(still: Boolean) {
    val description = stringResource(R.string.welcome_scene_read)
    val reply = stringResource(R.string.welcome_scene_reply)
    val p = if (still) 0.62f else loop(WELCOME_LOOP_MS)
    val fadeOut = 1 - between(p, 0.94f, 1f)
    val listening = between(p, 0.13f, 0.16f) * (1 - between(p, 0.70f, 0.74f))
    val typing = between(p, 0.18f, 0.58f)
    Row(
        Modifier.width(300.dp).clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MessageBox(
            reply, typed = (reply.length * typing).toInt(), alpha = fadeOut, Modifier.weight(1f),
            cursor = still || typing in 0.001f..0.999f || (p * WELCOME_LOOP_MS / 500).toInt() % 2 == 0,
        )
        DrawnBubble(
            Modifier.size(72.dp), alpha = between(p, 0f, 0.06f) * fadeOut, listening = listening,
            pulse = if (still) 0f else (p * WELCOME_LOOP_MS / 900f) % 1f, wave = p * WELCOME_LOOP_MS / 450f,
            tap = if (still) 0f else between(p, 0.08f, 0.15f),
        )
    }
}

private const val WELCOME_LOOP_MS = 6_400

/** A message box with the first [typed] letters of [text] and a cursor; the whole text sizes it, so it never jumps. */
@Composable
private fun MessageBox(text: String, typed: Int, alpha: Float, modifier: Modifier, cursor: Boolean) {
    val colors = MaterialTheme.colorScheme
    val style = pictureText(15)
    Surface(modifier, shape = RoundedCornerShape(22.dp), color = colors.surfaceContainerLowest, border = BorderStroke(2.dp, colors.primary)) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("$text|", style = style, color = Color.Transparent)
            val shown = buildAnnotatedString {
                append(text.take(typed))
                if (cursor) withStyle(SpanStyle(color = colors.primary, fontWeight = FontWeight.Bold)) { append("|") }
            }
            Text(shown, Modifier.alpha(alpha), style = style)
        }
    }
}

/**
 * The bubble, drawn: the sunflower disc and the ink key with its sound wave, as in the app's icon. [listening] (0 to 1)
 * brings the red ring, a pulse around it ([pulse], the time through one pulse) and a moving wave ([wave]); [tap] (0 to
 * 1) is a finger's ripple.
 */
@Composable
private fun DrawnBubble(
    modifier: Modifier, alpha: Float = 1f, listening: Float = 0f, pulse: Float = 0f, wave: Float = 0f, tap: Float = 0f,
) {
    Canvas(modifier.alpha(alpha)) {
        val r = size.minDimension * 0.36f
        if (listening > 0f) drawCircle(RecordingRed.copy(alpha = 0.3f * (1 - pulse) * listening), radius = r * (1.12f + 0.28f * pulse))
        drawCircle(Brush.verticalGradient(listOf(SunflowerLight, SunflowerDeep), startY = center.y - r, endY = center.y + r), r)
        if (listening > 0f) drawCircle(RecordingRed.copy(alpha = listening), radius = r * 1.04f, style = Stroke(r * 0.1f))
        val key = r * 1.05f
        drawRoundRect(
            Ink, topLeft = Offset(center.x - key / 2, center.y - key * 0.42f), size = Size(key, key * 0.84f),
            cornerRadius = CornerRadius(key * 0.22f),
        )
        val bar = key * 0.09f
        val gap = key * 0.07f
        val left = center.x - (5 * bar + 4 * gap) / 2
        for ((i, base) in listOf(0.35f, 0.65f, 0.95f, 0.65f, 0.35f).withIndex()) {
            val motion = 0.55f + 0.45f * sin(wave * 2 * PI.toFloat() + i * 1.3f)
            val h = key * 0.55f * base * (1 - listening + listening * motion)
            drawRoundRect(
                Sunflower, topLeft = Offset(left + i * (bar + gap), center.y - h / 2), size = Size(bar, h),
                cornerRadius = CornerRadius(bar / 2),
            )
        }
        if (tap > 0f && tap < 1f) drawCircle(Ink.copy(alpha = 0.25f * (1 - tap)), radius = r * (0.4f + 0.9f * tap))
    }
}

/**
 * The microphone step's picture, cropped to the three answers of Android's microphone question: While using the app
 * lights up and is tapped; Only this time is faded, since that grant ends when the app leaves the screen and later
 * takes would fail. Still: the lit answer.
 */
@Composable
internal fun MicPicture(still: Boolean) {
    val colors = MaterialTheme.colorScheme
    val description = stringResource(R.string.welcome_mic_read)
    val p = if (still) 1f else loop(3_200)
    Column(
        Modifier.width(280.dp).clearAndSetSemantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(shape = RoundedCornerShape(28.dp), color = colors.surfaceContainerHigh) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val lit = if (still) 1f else between(p, 0.15f, 0.3f) * (1 - between(p, 0.9f, 1f)) // dims before the loop starts again
                Answer(R.string.welcome_mic_while_using, lit = lit, tap = if (still) 0f else between(p, 0.45f, 0.7f))
                Answer(R.string.welcome_mic_only_this_time, faded = true)
                Answer(R.string.welcome_mic_dont_allow)
            }
        }
        Text(stringResource(R.string.welcome_mic_choose), style = pictureText(13, FontWeight.SemiBold), color = colors.secondary)
    }
}

/** One answer in Android's question: [lit] (0 to 1) fills it with sunflower, [tap] is a finger's ripple on it. */
@Composable
private fun Answer(@StringRes text: Int, lit: Float = 0f, tap: Float = 0f, faded: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier.fillMaxWidth().height(44.dp).alpha(if (faded) 0.35f else 1f).clip(shape)
            .background(lerp(colors.badge, colors.primaryContainer, lit)).border(2.dp, colors.primary.copy(alpha = lit), shape)
            .tapRipple(tap, colors.onSurface),
        contentAlignment = Alignment.Center,
    ) { Text(stringResource(text), style = pictureText(15, FontWeight.SemiBold), color = colors.onSurface) }
}

/**
 * The bubble step's picture: Android's accessibility settings in three frames of 2 s, looping, each cropped to what to
 * touch and captioned with its step: 1, the app's row in the list, tapped; 2, the two switches on its page, Use turning
 * on and the shortcut staying off; 3, Android's question, with Allow tapped. Still: the three frames in a column, each
 * as it ends.
 */
@Composable
internal fun WalkthroughPicture(still: Boolean) {
    val app = stringResource(R.string.app_name)
    val description = stringResource(R.string.welcome_walk_read, app)
    Column(
        Modifier.width(300.dp).clearAndSetSemantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (still) {
            for (frame in walkthroughFrames(still = true, progress = 1f)) WalkFrame(frame, 1f, app)
        } else {
            val p = loop(6_000)
            val frame = walkthroughFrames(still = false, progress = p).single()
            val within = p * 3 - frame
            Crossfade(frame, label = "step") { shown -> WalkFrame(shown, if (shown == frame) within else 1f, app) }
        }
    }
}

/** The walkthrough's frames on screen at [progress] (0 to 1 over one loop): one at a time, or all three as stills. */
internal fun walkthroughFrames(still: Boolean, progress: Float): List<Int> =
    if (still) listOf(0, 1, 2) else listOf((progress * 3).toInt().coerceIn(0, 2))

/** One frame of the walkthrough, [q] (0 to 1) of the way through its 2 s, under its numbered caption. */
@Composable
private fun WalkFrame(frame: Int, q: Float, app: String) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(colors.primary), contentAlignment = Alignment.Center) {
                Text("${frame + 1}", style = pictureText(13, FontWeight.Bold), color = colors.onPrimary)
            }
            val caption = when (frame) {
                0 -> stringResource(R.string.welcome_walk_tap, app)
                1 -> stringResource(R.string.welcome_walk_turn_on, app)
                else -> stringResource(R.string.welcome_walk_allow)
            }
            Text(caption, style = pictureText(15, FontWeight.SemiBold))
        }
        Box(Modifier.fillMaxWidth().height(132.dp), contentAlignment = Alignment.Center) {
            when (frame) {
                0 -> ListRowFrame(app, q)
                1 -> SwitchesFrame(app, q)
                else -> AllowFrame(app, q)
            }
        }
    }
}

/** Frame 1: the app's row in Android's accessibility list, lit and tapped, with a faded hint of the rows around it. */
@Composable
private fun ListRowFrame(app: String, q: Float) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(14.dp).alpha(0.4f).clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp)).background(colors.badge))
        Row(
            Modifier.fillMaxWidth().clip(shape).background(colors.badge)
                .border(2.dp, colors.primary.copy(alpha = between(q, 0.1f, 0.3f)), shape)
                .tapRipple(between(q, 0.45f, 0.75f), colors.onSurface).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DrawnBubble(Modifier.size(44.dp))
            Column {
                Text(app, style = pictureText(16, FontWeight.SemiBold))
                Text(stringResource(R.string.welcome_walk_off), style = pictureText(14), color = colors.onSurfaceVariant)
            }
        }
        Box(Modifier.fillMaxWidth().height(14.dp).alpha(0.4f).clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(colors.badge))
    }
}

/** Frame 2: the two switches on the app's page. Use turns on halfway through; the shortcut stays off, marked Leave off. */
@Composable
private fun SwitchesFrame(app: String, q: Float) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(colors.primaryContainer).padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.welcome_walk_use, app), Modifier.weight(1f), style = pictureText(15, FontWeight.SemiBold))
            PictureSwitch(on = q >= 0.4f)
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colors.badge).padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.welcome_walk_shortcut, app), style = pictureText(15, FontWeight.SemiBold))
                Row(
                    Modifier.clip(CircleShape).background(colors.surfaceContainerHighest).padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(AppIcons.Close, contentDescription = null, Modifier.size(14.dp), tint = colors.onSurface)
                    Text(stringResource(R.string.welcome_walk_leave_off), style = pictureText(12, FontWeight.SemiBold))
                }
            }
            PictureSwitch(on = false)
        }
    }
}

/** A switch as Android draws it (a check or a cross in its thumb), only to look at. */
@Composable
private fun PictureSwitch(on: Boolean) = Switch(on, onCheckedChange = null, thumbContent = {
    Icon(if (on) AppIcons.Check else AppIcons.Close, contentDescription = null, Modifier.size(SwitchDefaults.IconSize))
})

/** Frame 3: Android's question and its Allow button, which lights up and is tapped. */
@Composable
private fun AllowFrame(app: String, q: Float) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = colors.surfaceContainerHigh) {
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.welcome_walk_question, app), style = pictureText(15), textAlign = TextAlign.Center)
            Box(
                Modifier.fillMaxWidth().clip(shape).background(lerp(Color.Transparent, colors.primaryContainer, between(q, 0.3f, 0.5f)))
                    .tapRipple(between(q, 0.45f, 0.75f), colors.onSurface).padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.welcome_walk_allow_button), style = pictureText(15, FontWeight.SemiBold), color = colors.primary) }
        }
    }
}
