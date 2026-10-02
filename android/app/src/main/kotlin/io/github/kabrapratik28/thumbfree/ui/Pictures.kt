package io.github.kabrapratik28.thumbfree.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import io.github.kabrapratik28.thumbfree.R
import io.github.kabrapratik28.thumbfree.core.session.SpeechWait
import io.github.kabrapratik28.thumbfree.models.Readiness
import kotlin.math.PI
import kotlin.math.sin

// The welcome's pictures: Sam's chat card (the try's, with the real bubble in it), the five-bar meter, the Android
// Settings picture and the speech pack, each at one fixed size, on explicit timelines in Motion.kt's tokens. No picture
// ever shows the bubble: a yellow bubble is always the real one. Every picture has one TalkBack description that never
// changes as it plays.

/**
 * Picture text at a fixed size: a picture keeps its shape at any font size and shrinks as a whole (it has its own
 * TalkBack description), while the page's own words follow the font setting.
 */
@Composable
internal fun pictureText(size: Int, weight: FontWeight = FontWeight.Normal): TextStyle {
    val sp = with(LocalDensity.current) { size.dp.toSp() }
    return MaterialTheme.typography.bodyMedium.copy(fontSize = sp, lineHeight = sp * 1.35f, fontWeight = weight)
}

/** The width the pictures are drawn at; a step scales them to the room its words leave. */
internal val PICTURE_WIDTH = 312.dp

// ---- The level meter ----

/**
 * Five level bars, 4 dp wide and 3 dp apart in a fixed 32 x 20 dp box, each 4 to 18 dp tall from [levels] (five values,
 * 0 to 1). Read in the draw phase, so a new level redraws the bars and never measures anything again.
 */
@Composable
internal fun LevelMeter(levels: () -> FloatArray, color: Color, modifier: Modifier = Modifier) =
    Canvas(modifier.size(width = 32.dp, height = 20.dp)) {
        val values = levels()
        val bar = 4.dp.toPx()
        val gap = 3.dp.toPx()
        for (i in 0 until 5) {
            val h = lerp(4.dp.toPx(), 18.dp.toPx(), values[i].coerceIn(0f, 1f))
            drawRoundRect(color, topLeft = Offset(i * (bar + gap), (size.height - h) / 2), size = Size(bar, h), cornerRadius = CornerRadius(bar / 2))
        }
    }

/** What a listening bubble says where there is room: the five bars and the word Listening, in recording red. */
@Composable
internal fun ListeningPill(levels: () -> FloatArray, modifier: Modifier = Modifier) = Row(
    modifier.background(RecordingRed.copy(alpha = 0.12f), CircleShape).padding(horizontal = 8.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
) {
    LevelMeter(levels, RecordingRed)
    Text(stringResource(R.string.welcome_listening), style = pictureText(13, FontWeight.SemiBold), color = RecordingRed)
}

// ---- Sam's chat card ----

/**
 * The one example, Sam's chat, at one fixed size (up to 312 dp wide, never elevated): its header, Sam's message, a slot
 * for what a listening bubble says ([pill]), and the reply beside the bubble's slot ([bubble], 56 dp). The reply is a
 * plain read-only tray, never a field: no hint, no cursor, no outline; word i of [reply] shows at [words] (i), and
 * [replyRise] lifts the words in. Nothing in it is measured again as these change. A [compact] card (a large font, or a
 * short screen) drops the card itself, its header and Sam's message, and keeps the pill's slot and the reply beside the
 * bubble. TalkBack hears [description] for the card and [replyDescription] for the reply.
 */
@Composable
internal fun SamChatCard(
    reply: String, words: (Int) -> Float, replyRise: Float, replyLines: Int, compact: Boolean, description: String,
    replyDescription: String, pill: @Composable () -> Unit, bubble: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val lower = @Composable {
        Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).fillMaxWidth().height(28.dp), contentAlignment = Alignment.CenterEnd) {
            pill()
        }
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            ReplyTray(reply, words, replyRise, replyLines, replyDescription, Modifier.weight(1f))
            Box(Modifier.padding(start = 8.dp).size(56.dp), contentAlignment = Alignment.Center) { bubble() }
        }
    }
    if (compact) {
        Column(Modifier.widthIn(max = PICTURE_WIDTH).fillMaxWidth()) { lower() }
        return
    }
    Surface(
        Modifier.widthIn(max = PICTURE_WIDTH).fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = colors.artSurface,
        border = BorderStroke(2.dp, colors.artLine),
    ) {
        Column {
            Column(Modifier.clearAndSetSemantics { contentDescription = description }) {
                Row(Modifier.height(48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    val name = stringResource(R.string.welcome_scene_name)
                    Box(Modifier.size(30.dp).background(SunflowerLight, CircleShape), contentAlignment = Alignment.Center) {
                        Text(name.take(1), style = pictureText(14, FontWeight.SemiBold), color = Ink)
                    }
                    Text(name, Modifier.padding(start = 8.dp), style = pictureText(15, FontWeight.SemiBold), color = colors.onSurface)
                }
                Box(Modifier.fillMaxWidth().height(2.dp).background(colors.artLine))
                Text(
                    stringResource(R.string.welcome_scene_message),
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)
                        .background(colors.surfaceContainerHigh, RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    style = pictureText(15), color = colors.onSurface,
                )
            }
            lower()
        }
    }
}

/** The card's reply: a plain tray, one fixed 52 dp box whatever it shows; see [SamChatCard]. */
@Composable
private fun ReplyTray(reply: String, words: (Int) -> Float, rise: Float, lines: Int, description: String, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val all = reply.split(' ').filter { it.isNotEmpty() }
    val alphas = all.indices.map(words)
    Box(
        modifier.height(52.dp).clearAndSetSemantics { contentDescription = description }
            .background(colors.surfaceContainer, RoundedCornerShape(24.dp)).padding(horizontal = 16.dp, vertical = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            buildAnnotatedString {
                all.forEachIndexed { i, word ->
                    if (i > 0) append(' ')
                    pushStyle(SpanStyle(color = colors.onSurface.copy(alpha = alphas[i])))
                    append(word)
                    pop()
                }
            },
            Modifier.graphicsLayer { translationY = 4.dp.toPx() * rise },
            style = pictureText(15), maxLines = lines, overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---- The Android Settings picture ----

/** The screens of Android's settings the bubble step's picture plays, one at a time. */
internal enum class SettingsScreen { LIST, PAGE, ALLOW }

/** One play of the picture: find ThumbFree, Use turns on, the shortcut's cross pulses, Allow. It plays twice, then rests. */
internal const val SETTINGS_PLAY_MS = 9_000

/** When each part of one play starts. */
private const val PAGE_AT = 2_000
private const val PULSE_AT = 4_600
private const val ALLOW_AT = 6_400

/** Which screen shows [t] ms into the picture, and whether it rests (after its two plays) on the two switches. */
internal fun settingsScreen(t: Float): SettingsScreen {
    if (t >= 2 * SETTINGS_PLAY_MS) return SettingsScreen.PAGE
    val local = t % SETTINGS_PLAY_MS
    return when {
        local < PAGE_AT -> SettingsScreen.LIST
        local < ALLOW_AT -> SettingsScreen.PAGE
        else -> SettingsScreen.ALLOW
    }
}

/**
 * The bubble step's picture: one phone frame headed Android Settings that never moves, playing by itself, twice: the
 * accessibility list with ThumbFree lit; ThumbFree's page, where Use ThumbFree (a plain off switch until then) turns on
 * under a tap halo while the shortcut's switch keeps its red cross; that cross pulses once; Android's question, a tap halo
 * on Allow. Then it rests on the two switches. Its screens cross (out 120 ms, in 240 ms). With animations off or TalkBack
 * on it shows the two switches, still; [list] holds it on the list instead (back with the bubble off). TalkBack hears one
 * description, which never changes. The shortcut's switch always shows the red cross: the switch to leave alone, never
 * shown turning on.
 */
@Composable
internal fun SettingsPicture(list: Boolean = false) {
    val app = stringResource(R.string.app_name)
    val description = if (list) stringResource(R.string.welcome_list_read, app) else stringResource(R.string.welcome_guide_read, app)
    val still = animationsOff() || talkBackOn()
    val t = if (list) 0f else if (still) 2f * SETTINGS_PLAY_MS else onceClock(2 * SETTINGS_PLAY_MS)
    val screen = if (list) SettingsScreen.LIST else settingsScreen(t)
    val local = if (t >= 2 * SETTINGS_PLAY_MS) Float.MAX_VALUE else t % SETTINGS_PLAY_MS
    Box(Modifier.clearAndSetSemantics { contentDescription = description }) {
        SettingsPhoneFrame {
            AnimatedContent(
                screen,
                transitionSpec = {
                    if (still) EnterTransition.None togetherWith ExitTransition.None
                    else fadeIn(tween(FrameInMs, easing = EaseEnter)) togetherWith fadeOut(tween(ExitMs, easing = EaseExit))
                },
                label = "settings",
            ) { shown ->
                // An outgoing screen holds its last state while it fades.
                val at = if (shown == screen) local else Float.MAX_VALUE
                SettingsScreenContent(shown, at, entrance = list || still)
            }
        }
    }
}

/**
 * A phone frame, 280 x 248 dp with 28 dp corners and a 2 dp line, headed Android Settings with a back arrow in a fixed
 * 40 dp bar, in Android's neutral surfaces rather than ThumbFree's cards: a picture of an outside screen.
 */
@Composable
private fun SettingsPhoneFrame(content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        Modifier.size(width = 280.dp, height = 248.dp), shape = RoundedCornerShape(28.dp), color = colors.surfaceContainerLowest,
        border = BorderStroke(2.dp, colors.artLine),
    ) {
        Column {
            Row(Modifier.height(40.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.ArrowBack, contentDescription = null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                Text(
                    stringResource(R.string.welcome_guide_header), Modifier.padding(start = 8.dp),
                    style = pictureText(14, FontWeight.SemiBold), color = colors.onSurface,
                )
            }
            Box(Modifier.fillMaxWidth().height(2.dp).background(colors.artLine))
            Box(Modifier.fillMaxSize().padding(16.dp)) { content() }
        }
    }
}

/**
 * One of Android's screens in the frame, [at] ms into the play (past every event once it rests): the list with ThumbFree
 * lit (its light comes in unless [entrance] is done already); ThumbFree's page with its two switches; Android's question
 * over the dimmed page.
 */
@Composable
private fun SettingsScreenContent(screen: SettingsScreen, at: Float, entrance: Boolean) {
    val colors = MaterialTheme.colorScheme
    val app = stringResource(R.string.app_name)
    val sunflower = if (colors.isDark) colors.primary else Sunflower
    when (screen) {
        SettingsScreen.LIST -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val highlight = if (entrance) 1f else seg(at, 0, MoveMs)
            Text(stringResource(R.string.welcome_guide_accessibility), style = pictureText(16, FontWeight.SemiBold), color = colors.onSurface)
            Text(stringResource(R.string.welcome_card_downloaded), style = pictureText(12, FontWeight.SemiBold), color = colors.secondary)
            Blank()
            val shape = RoundedCornerShape(16.dp)
            Row(
                Modifier.fillMaxWidth().background(lerp(Color.Transparent, sunflower.copy(alpha = 0.35f), highlight), shape)
                    .border(3.dp, colors.artTarget.copy(alpha = highlight), shape).padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppIcon()
                Column {
                    Text(app, style = pictureText(14, FontWeight.SemiBold), color = colors.onSurface)
                    Text(stringResource(R.string.welcome_card_off), style = pictureText(12), color = colors.onSurfaceVariant)
                }
            }
            Blank()
        }
        SettingsScreen.PAGE -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val page = at - PAGE_AT
            Text(app, style = pictureText(16, FontWeight.SemiBold), color = colors.onSurface)
            SwitchRow(stringResource(R.string.welcome_card_use, app), on = page >= 900f, halo = (page - 600) / HaloMs, target = true)
            // The cross pulses once: up to 125% and back.
            val pulse = sin(seg(at, PULSE_AT, 500, LinearEasing) * PI.toFloat())
            SwitchRow(stringResource(R.string.welcome_card_shortcut_row, app), on = false, muted = true, cross = true, crossPulse = pulse)
        }
        SettingsScreen.ALLOW -> Box(Modifier.fillMaxSize()) {
            Column(Modifier.graphicsLayer { alpha = 0.5f }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(app, style = pictureText(16, FontWeight.SemiBold), color = colors.onSurface)
                SwitchRow(stringResource(R.string.welcome_card_use, app), on = true, target = true)
            }
            Box(Modifier.fillMaxSize().background(colors.scrim.copy(alpha = 0.32f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Surface(shape = RoundedCornerShape(20.dp), color = colors.surfaceContainerHigh) {
                    Column(Modifier.padding(16.dp).width(200.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.welcome_guide_question, app), style = pictureText(13, FontWeight.SemiBold), color = colors.onSurface)
                        Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                stringResource(R.string.welcome_guide_deny), Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = pictureText(13, FontWeight.SemiBold), color = colors.onSurfaceVariant,
                            )
                            val allow = RoundedCornerShape(12.dp)
                            Text(
                                stringResource(R.string.welcome_guide_allow_button),
                                Modifier.drawWithContent {
                                    drawContent()
                                    halo(center, size.maxDimension * 0.7f, (at - ALLOW_AT - 1_100) / HaloMs, sunflower)
                                }.border(3.dp, colors.artTarget, allow).padding(horizontal = 8.dp, vertical = 4.dp),
                                style = pictureText(13, FontWeight.SemiBold), color = colors.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The app's icon as Android's list shows it: the launcher icon's two layers in a circle. */
@Composable
private fun AppIcon() = Box(Modifier.size(32.dp).clip(CircleShape)) {
    Image(painterResource(R.drawable.ic_launcher_background), contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
    Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, Modifier.fillMaxSize())
}

/** A row in a settings picture that only stands for other rows: a grey dot and a grey line, no words. */
@Composable
private fun Blank() = Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    val grey = MaterialTheme.colorScheme.outlineVariant
    Box(Modifier.size(24.dp).background(grey, CircleShape))
    Box(Modifier.padding(start = 8.dp).size(width = 112.dp, height = 8.dp).background(grey, CircleShape))
}

/**
 * A switch row in a settings picture: [text] and its switch, [on] or off. The [target] row (Use ThumbFree) has the 3 dp
 * target line on a lit surface and a tap halo on its switch ([halo], 0 to 1); a [muted] one steps back. The [cross] row's
 * switch (the shortcut's, the one to leave alone) has a red knob with a white cross, which [crossPulse] (0 to 1 and back)
 * swells once; any other switch, off, looks as Android draws it.
 */
@Composable
private fun SwitchRow(
    text: String, on: Boolean, halo: Float = 0f, target: Boolean = false, muted: Boolean = false, cross: Boolean = false, crossPulse: Float = 0f,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    val sunflower = if (colors.isDark) colors.primary else Sunflower
    Row(
        Modifier.fillMaxWidth().background(if (target) sunflower.copy(alpha = 0.18f) else colors.surfaceContainer, shape)
            .border(if (target) 3.dp else 2.dp, if (target) colors.artTarget else colors.artLine, shape)
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text, Modifier.weight(1f), style = pictureText(14, FontWeight.SemiBold),
            color = if (muted) colors.onSurfaceVariant else colors.onSurface,
        )
        Box(
            Modifier.graphicsLayer { (1 + 0.25f * crossPulse).let { scaleX = it; scaleY = it } }.drawWithContent {
                drawContent()
                halo(center, size.maxDimension * 0.6f, halo, sunflower)
            },
        ) { PictureSwitch(on, cross) }
    }
}

/**
 * A switch as Android draws it, only to look at: on, a check in its knob; off, Android's plain off switch, or with
 * [cross] the red knob with a white cross.
 */
@Composable
private fun PictureSwitch(on: Boolean, cross: Boolean) = Switch(
    on, onCheckedChange = null,
    thumbContent = when {
        on -> { { Icon(AppIcons.Check, contentDescription = null, Modifier.size(SwitchDefaults.IconSize)) } }
        cross -> { { Icon(AppIcons.Close, contentDescription = null, Modifier.size(SwitchDefaults.IconSize)) } }
        else -> null
    },
    colors = if (cross) SwitchDefaults.colors(
        uncheckedThumbColor = MaterialTheme.colorScheme.error, uncheckedIconColor = Color.White,
        uncheckedBorderColor = MaterialTheme.colorScheme.outline,
    ) else SwitchDefaults.colors(),
)

/** The two rules under the bubble step's picture: turn on Use ThumbFree (a green check), not the shortcut (a red cross). */
@Composable
internal fun SettingsRules() = Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    val app = stringResource(R.string.app_name)
    val colors = MaterialTheme.colorScheme
    Rule(stringResource(R.string.welcome_rule_use, app), AppIcons.Check, colors.success, colors.onSurface)
    Rule(stringResource(R.string.welcome_rule_shortcut, app), AppIcons.Close, colors.error, colors.error)
}

@Composable
private fun Rule(text: String, icon: ImageVector, mark: Color, color: Color) =
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(22.dp).background(mark, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, Modifier.size(16.dp), tint = if (MaterialTheme.colorScheme.isDark) Ink else Color.White)
        }
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = color)
    }

// ---- The finish's other picture ----

/**
 * The speech pack's badge, as the words say why the model waits: an arrow (travelling down) while it downloads, Wi-Fi,
 * a pause only for a retry's pause, the stop mark (the grey bubble's) for a stop or a damaged file, the storage; none
 * while it waits for any connection, isn't started, or is prepared.
 */
internal fun packBadge(wait: SpeechWait): ImageVector? = when (wait) {
    SpeechWait.DOWNLOADING -> AppIcons.Download
    SpeechWait.WIFI -> AppIcons.Wifi
    SpeechWait.RETRYING -> AppIcons.Pause
    SpeechWait.PAUSED, SpeechWait.CHECK_FAILED -> AppIcons.Warning
    SpeechWait.NO_SPACE -> AppIcons.Storage
    SpeechWait.CONNECTION, SpeechWait.NOT_STARTED, SpeechWait.PREPARING -> null
}

/**
 * The finish's picture while the speech model isn't usable: a 96 dp ring around a flat speech pack (a navy pack with five
 * sunflower bars), never a bubble or a chat. The ring shows the download's part ([fraction], animated by the caller, never
 * backwards) and turns while the file is prepared; a badge says why it waits ([packBadge]), in the error colour for too
 * little space or a damaged file. Its clocks are read only where it draws. TalkBack hears how far the download is, or
 * [description] when that isn't what it shows.
 */
@Composable
internal fun SpeechPack(need: Readiness.Speech, fraction: Float, description: String = stringResource(R.string.welcome_download_picture_read, need.percent)) {
    val colors = MaterialTheme.colorScheme
    val still = animationsOff()
    val attention = need.wait == SpeechWait.NO_SPACE || need.wait == SpeechWait.CHECK_FAILED
    val preparing = need.wait == SpeechWait.PREPARING
    val turn = if (preparing && !still) {
        rememberInfiniteTransition("prepare").animateFloat(0f, 360f, infiniteRepeatable(tween(1_200, easing = LinearEasing)), "angle")
    } else null
    val arrow = if (need.wait == SpeechWait.DOWNLOADING && !still) loopClockState(1_600) else null
    Box(Modifier.size(96.dp).clearAndSetSemantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 4.dp.toPx()
            val inset = stroke / 2
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(colors.artLine, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
            val sweep = if (preparing) 270f else 360f * fraction.coerceIn(0f, 1f)
            drawArc(colors.primary, -90f + (turn?.value ?: 0f), sweep, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Canvas(Modifier.size(width = 44.dp, height = 40.dp)) {
            drawRoundRect(Ink, cornerRadius = CornerRadius(10.dp.toPx()))
            val bar = 4.dp.toPx()
            val gap = 3.dp.toPx()
            val left = (size.width - (5 * bar + 4 * gap)) / 2
            for ((i, h) in listOf(0.35f, 0.65f, 0.95f, 0.65f, 0.35f).withIndex()) {
                val height = size.height * 0.6f * h
                drawRoundRect(
                    Sunflower, topLeft = Offset(left + i * (bar + gap), (size.height - height) / 2), size = Size(bar, height),
                    cornerRadius = CornerRadius(bar / 2),
                )
            }
        }
        val badge = packBadge(need.wait)
        if (badge != null) {
            Box(
                Modifier.align(Alignment.BottomEnd).size(32.dp)
                    .background(if (attention) colors.errorContainer else colors.primaryContainer, CircleShape)
                    .border(2.dp, colors.background, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                // The arrow travels 3 dp down and dims a little, back to the same pixels each 1.6 s.
                Icon(
                    badge, contentDescription = null,
                    Modifier.size(16.dp).graphicsLayer {
                        val p = arrow?.let { sin(it.value / 1_600f * PI.toFloat()) } ?: 0f
                        translationY = 3.dp.toPx() * p
                        alpha = 1 - 0.35f * p
                    },
                    tint = if (attention) colors.onErrorContainer else colors.onPrimaryContainer,
                )
            }
        }
    }
}
