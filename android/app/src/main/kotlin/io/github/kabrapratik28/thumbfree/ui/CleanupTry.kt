package io.github.kabrapratik28.thumbfree.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.mlkit.genai.common.FeatureStatus
import io.github.kabrapratik28.thumbfree.R
import io.github.kabrapratik28.thumbfree.app.AppGraph
import io.github.kabrapratik28.thumbfree.app.GeminiCleanup
import io.github.kabrapratik28.thumbfree.core.text.CleanupCheck
import io.github.kabrapratik28.thumbfree.core.text.CleanupStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Clean up's beat in the welcome's try (issue #1, design O1), where Gemini Nano is ready and Clean up is on: once the
 * take's words show, a sparkle with its cue, pulsing every 1.4 s until it is used. A tap tidies the words on Sam's card
 * with the default style; a hold shows the five styles. ThumbFree is in front, so the model is called directly; nothing
 * is typed or kept, and a style always starts from the words as heard.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun CleanupTry(trial: TrialState) {
    if (!AppGraph.initialized) return // host tests and screenshots build no graph
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { ready = AppGraph.settings.cleanupOn && GeminiCleanup.status() == FeatureStatus.AVAILABLE }
    val heard = trial.heard
    if (!ready || heard == null) return
    var styles by remember(heard) { mutableStateOf(false) }
    var busy by remember(heard) { mutableStateOf(false) }
    var used by remember(heard) { mutableStateOf(false) }
    var picked by remember(heard) { mutableStateOf<CleanupStyle?>(null) }
    var failed by remember(heard) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun run(style: CleanupStyle) {
        used = true
        picked = style
        busy = true
        failed = false
        scope.launch {
            val answer = try {
                GeminiCleanup.clean(heard, style)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            when (val verdict = CleanupCheck.check(heard, answer, style)) {
                is CleanupCheck.Verdict.Ok -> trial.tidied(verdict.text)
                CleanupCheck.Verdict.Same -> trial.tidied(heard)
                is CleanupCheck.Verdict.Rejected -> failed = true
            }
            busy = false
        }
    }
    val still = animationsOff()
    val pulse = if (!used && !still && !talkBackOn()) loopClockState(1_400) else null
    val label = stringResource(R.string.cleanup_label)
    val choose = stringResource(R.string.cleanup_choose_style)
    Column(Modifier.padding(top = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Surface(
                    shape = CircleShape, color = Color.White, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.size(56.dp)
                        .drawBehind { pulse?.let { halo(center, size.minDimension / 2, (it.value / HaloMs).coerceAtLeast(0f), Sunflower) } }
                        .semantics { contentDescription = label }
                        .combinedClickable(
                            role = Role.Button, onLongClickLabel = choose,
                            onClick = { if (!busy) run(AppGraph.settings.cleanupStyle) },
                            onLongClick = {
                                used = true
                                styles = true
                            },
                        ),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Image(painterResource(R.drawable.cleanup_sparkle), contentDescription = null, Modifier.size(28.dp))
                    }
                }
                if (busy) CircularProgressIndicator(Modifier.size(56.dp), strokeWidth = 3.dp)
            }
            Text(
                stringResource(if (failed) R.string.cleanup_failed else R.string.welcome_cleanup_cue), Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (styles) {
            FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (style in CleanupStyle.entries) {
                    FilterChip(selected = style == picked, onClick = { if (!busy) run(style) }, label = { Text(stringResource(styleName(style))) })
                }
            }
        }
    }
}
