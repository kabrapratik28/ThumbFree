package io.github.kabrapratik28.thumbfree.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.kabrapratik28.thumbfree.R
import io.github.kabrapratik28.thumbfree.models.DownloadState

private val EXAMPLES = listOf(R.string.ui_example_1, R.string.ui_example_2, R.string.ui_example_3)

/**
 * The practice tab: while a take can't run yet, a card with what is missing and a button for each; then a big field to
 * dictate into with the real bubble, how to use it, and sentences to read out. [setup] is null before the first read;
 * [modelDownload] shows the chosen model's download on its row.
 */
@Composable
fun TryScreen(
    setup: SetupState?, onFix: (SetupItem) -> Unit, modelDownload: DownloadState? = null,
    dictionaryHint: Boolean = false, onDictionary: () -> Unit = {}, onHintDone: () -> Unit = {},
) {
    var practice by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle(R.string.ui_try_title)
        if (dictionaryHint) DictionaryHint(onDictionary, onHintDone)
        when {
            setup == null -> Unit
            setup.ready -> StatusLine(R.string.ui_ready, done = true)
            // Only the model check is left, the seconds-long hash after an install: a card that vanishes would flash.
            setup.model == null && setup.micGranted && setup.serviceEnabled -> StatusLine(R.string.ui_model_checking, done = false)
            else -> SetupCard(setup, onFix, modelDownload)
        }
        val context = LocalContext.current
        OutlinedTextField(
            practice, { practice = it; logPracticeChange(context, it) },
            Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 168.dp),
            label = { Text(stringResource(R.string.ui_practice)) },
            placeholder = { Text(stringResource(R.string.ui_practice_placeholder)) },
            textStyle = MaterialTheme.typography.bodyLarge,
            shape = MaterialTheme.shapes.large,
            minLines = 5,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.ui_practice_note), Modifier.weight(1f).padding(start = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton({ practice = "" }, enabled = practice.isNotEmpty()) {
                Icon(AppIcons.Close, contentDescription = null, Modifier.size(18.dp))
                Text(stringResource(R.string.ui_clear), Modifier.padding(start = 6.dp))
            }
        }
        HowTo()
        SectionLabel(R.string.ui_examples_title)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (example in EXAMPLES) Example(example)
        }
        Spacer(Modifier.padding(bottom = 24.dp))
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

/** What is missing before a take can run, each with its fix; it goes once everything is ready. */
@Composable
private fun SetupCard(setup: SetupState, onFix: (SetupItem) -> Unit, modelDownload: DownloadState?) {
    val needs = SetupItem.entries
    Card(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ui_setup_card_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.ui_setup_card_count, needs.count(setup::ok), needs.size),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(
                stringResource(R.string.ui_setup_card_body), Modifier.padding(top = 4.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            SetupRows(setup, needs, onFix, modelDownload)
        }
    }
}

/** One short line with a check (done) or a spinner. */
@Composable
private fun StatusLine(@StringRes text: Int, done: Boolean) {
    val color = if (done) MaterialTheme.colorScheme.success else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(Modifier.padding(top = 4.dp), shape = MaterialTheme.shapes.extraLarge, color = color.copy(alpha = 0.12f)) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (done) Icon(AppIcons.CheckCircle, contentDescription = null, Modifier.size(20.dp), tint = color)
            else CircularProgressIndicator(Modifier.size(18.dp), color = color, strokeWidth = 2.dp)
            Text(stringResource(text), style = MaterialTheme.typography.labelLarge, color = color)
        }
    }
}

/** The three steps of a take, and holding as the other way. */
@Composable
private fun HowTo() {
    SectionLabel(R.string.ui_how_title)
    Card(
        Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            listOf(R.string.ui_how_1, R.string.ui_how_2, R.string.ui_how_3).forEachIndexed { i, step ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(
                        Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${i + 1}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Text(stringResource(step), style = MaterialTheme.typography.bodyLarge)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                IconBadge(AppIcons.TouchApp, MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceContainerHighest, size = 28)
                Text(stringResource(R.string.ui_how_hold), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Example(@StringRes text: Int) {
    Card(
        Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(AppIcons.Quote, contentDescription = null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.secondary)
            Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/**
 * Debug builds only: when the practice field changes, log its length and the wall-clock time, so a speed test can time
 * dictation into this field without recording the screen. Never the text itself: logs carry numbers only.
 */
private fun logPracticeChange(context: Context, text: String) {
    if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
    Log.i("ThumbFree", "practice_text len=${text.length} ms=${System.currentTimeMillis()}")
}
