package io.github.kabrapratik28.thumbfree.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kabrapratik28.thumbfree.R
import io.github.kabrapratik28.thumbfree.models.DownloadState
import io.github.kabrapratik28.thumbfree.models.Readiness

/**
 * The first tab. While a take can't run yet, the Finish setup card leads, with the one thing it needs next as its row
 * (models.Readiness: the microphone, the bubble, then the chosen model, whose row opens the speech models,
 * [onOpenModels]); nothing to try until then. Once everything works: Ready, and how to use the bubble in one sentence,
 * text only. The card turns into Ready with a crossfade and a settle, and TalkBack hears "Ready. Works offline." once,
 * when it happens here. Nothing to type into here: the bubble works in other apps.
 * [setup] is null before the first read; [dictionaryHint] puts the one-time Dictionary card at the top.
 */
@Composable
fun HomeTab(
    setup: SetupState?, onFix: (SetupItem) -> Unit, modelDownload: DownloadState? = null,
    dictionaryHint: Boolean = false, onDictionary: () -> Unit = {}, onHintDone: () -> Unit = {}, onOpenModels: () -> Unit = {},
) {
    val still = animationsOff()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle(R.string.app_name)
        if (dictionaryHint) DictionaryHint(onDictionary, onHintDone)
        if (setup != null) {
            val state = when {
                setup.ready -> HomeState.READY
                // Only the model check is left, the seconds-long hash after an install: a card that vanishes would flash.
                setup.model == null && setup.micGranted && setup.serviceEnabled -> HomeState.CHECKING
                else -> HomeState.SETUP
            }
            // Read aloud only when something was missing as Home first showed, not after the seconds-long check every
            // start begins with.
            val missingAtFirst = remember { state == HomeState.SETUP }
            AnimatedContent(
                state,
                transitionSpec = {
                    if (still) EnterTransition.None togetherWith ExitTransition.None using null
                    else fadeIn(tween(QuickMs, easing = EaseEnter)) togetherWith fadeOut(tween(QuickMs, easing = EaseStandard)) using
                        SizeTransform(clip = false) { _, _ -> settle() }
                },
                label = "home",
            ) { shown ->
                when (shown) {
                    HomeState.READY -> ReadyCard(announce = missingAtFirst)
                    HomeState.CHECKING -> StatusLine(R.string.ui_model_checking, done = false)
                    HomeState.SETUP -> SetupCard(setup, onFix, modelDownload, onOpenModels)
                }
            }
        }
        Spacer(Modifier.padding(bottom = 24.dp))
    }
}

/** What Home leads with: what a take still needs, the model's check, or Ready. */
private enum class HomeState { SETUP, CHECKING, READY }

/**
 * Ready, and how to use the bubble: Ready · works offline (read aloud once when it has just become so, [announce]), then
 * one sentence on a card. No picture: the bubble lives in other apps, and a yellow bubble is always the real one.
 */
@Composable
private fun ReadyCard(announce: Boolean) = Column {
    StatusLine(R.string.ui_ready, done = true, read = if (announce) stringResource(R.string.ui_ready_read) else null)
    SectionLabel(R.string.ui_home_how)
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Text(
            stringResource(R.string.ui_home_how_body), Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * The one-time card at the top, on the first visit after a take that typed text: the Dictionary spells names the user's
 * way. Open Dictionary and Not now both put it away ([onDone]).
 */
@Composable
private fun DictionaryHint(onOpen: () -> Unit, onDone: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(bottom = 8.dp), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(AppIcons.Book, MaterialTheme.colorScheme.onPrimaryContainer, MaterialTheme.colorScheme.primaryContainer)
            Text(
                stringResource(R.string.ui_dictionary_hint, stringResource(R.string.app_name)), Modifier.weight(1f).padding(top = 2.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Row(Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onDone) { Text(stringResource(R.string.ui_dictionary_hint_later)) }
            TextButton({ onDone(); onOpen() }) { Text(stringResource(R.string.ui_dictionary_hint_open)) }
        }
    }
}

/**
 * What is missing before a take can run, one thing at a time: the next one's row, with its fix; it goes once everything
 * is ready. The model's row opens the speech models.
 */
@Composable
private fun SetupCard(setup: SetupState, onFix: (SetupItem) -> Unit, modelDownload: DownloadState?, onOpenModels: () -> Unit) {
    val needs = SetupItem.entries
    val next = when (Readiness.of(setup.micGranted, setup.serviceEnabled, setup.ok(SetupItem.MODEL), modelDownload)) {
        Readiness.MicOff -> SetupItem.MIC
        Readiness.BubbleOff -> SetupItem.SERVICE
        else -> SetupItem.MODEL
    }
    Card(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.ui_setup_card_title), Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 26.sp),
                )
                Text(
                    stringResource(R.string.ui_setup_card_count, needs.count(setup::ok), needs.size),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            // The one row, on a raised neutral surface, the whole of it a target: the model's opens the speech models,
            // the others fix their item, as their buttons do. A download under way has no button: a chevron says where
            // the row goes.
            val underWay = next == SetupItem.MODEL && modelDownload.let {
                it is DownloadState.Queued && (!it.wifiOnly || it.retrying) || it is DownloadState.Downloading || it == DownloadState.Verifying
            }
            Surface(
                onClick = if (next == SetupItem.MODEL) onOpenModels else { { onFix(next) } },
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth().heightIn(min = 56.dp),
                shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
            ) {
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SetupRows(setup, listOf(next), onFix, modelDownload, thinBar = true) }
                    if (underWay) {
                        Icon(AppIcons.ChevronRight, contentDescription = null, Modifier.padding(start = 8.dp).size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** One short line with a check (done) or a spinner; with [read], TalkBack reads that as it appears. */
@Composable
private fun StatusLine(@StringRes text: Int, done: Boolean, read: String? = null) {
    val color = if (done) MaterialTheme.colorScheme.success else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(Modifier.padding(top = 4.dp), shape = MaterialTheme.shapes.extraLarge, color = color.copy(alpha = 0.12f)) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics(mergeDescendants = true) {
                if (read != null) {
                    contentDescription = read
                    liveRegion = LiveRegionMode.Polite
                }
            },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (done) Icon(AppIcons.CheckCircle, contentDescription = null, Modifier.size(20.dp), tint = color)
            else CircularProgressIndicator(Modifier.size(18.dp), color = color, strokeWidth = 2.dp)
            Text(stringResource(text), style = MaterialTheme.typography.labelLarge, color = color)
        }
    }
}
