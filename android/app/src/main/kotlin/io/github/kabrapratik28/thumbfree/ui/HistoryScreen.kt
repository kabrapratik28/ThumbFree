package io.github.kabrapratik28.thumbfree.ui

import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kabrapratik28.thumbfree.R
import io.github.kabrapratik28.thumbfree.data.Dictation
import io.github.kabrapratik28.thumbfree.data.Retention
import io.github.kabrapratik28.thumbfree.data.Status
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The text the row shows: for a take transcribed again from history, that new transcript; for an inserted take, what was
 * typed into the field, without the spacing the formatter adds around it; else the transcript. None while a take is in progress, since its text is unconfirmed until
 * it ends with speech, nor for a take that ended without that confirmation: NO_SPEECH, or INTERRUPTED (it died before
 * its speech decision; recovery clears its text, older builds kept it).
 */
internal val Dictation.finalText: String?
    get() = when {
        !mayShowText -> null
        retranscribed -> text
        else -> insertedText?.trim()?.takeIf { status == Status.INSERTED || status == Status.UNVERIFIED } ?: text
    }

/** What Copy copies: the final text, else the partial text of a take that failed or was cancelled part way. */
internal val Dictation.copyText: String? get() = finalText ?: partialText?.ifBlank { null }?.takeIf { mayShowText }

private val Dictation.mayShowText get() = status.terminal && status != Status.NO_SPEECH && status != Status.INTERRUPTED

/** Search looks only at text the row may show, so a hidden (unconfirmed) transcript never matches. */
internal fun Dictation.matches(query: String): Boolean = query.isBlank() || copyText?.contains(query.trim(), ignoreCase = true) == true

/**
 * The history tab, newest first under a header per day: search, the retention rule with the storage it uses and a
 * link to its setting ([onRetention]), and per ended take Copy, Transcribe again and Delete.
 * [apps] names the apps takes were typed into, by package.
 */
@Composable
fun HistoryScreen(
    rows: List<Dictation>, apps: Map<String, String>, retention: Retention, storageBytes: Long,
    onCopy: (Dictation) -> Unit, onDelete: (Dictation) -> Unit, onTranscribe: (Dictation) -> Unit,
    onClearAll: () -> Unit, onRetention: () -> Unit, onTry: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val shown = remember(rows, query) { rows.filter { it.matches(query) } }
    val days = remember(shown) { shown.groupBy { day(it.startedAt) } }
    val today = LocalDate.now()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "title") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScreenTitle(R.string.ui_history, Modifier.weight(1f))
                if (rows.any { it.status.terminal }) {
                    TextButton({ confirmClear = true }, Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.ui_history_clear_all)) }
                }
            }
        }
        if (rows.isNotEmpty()) item(key = "search") {
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.ui_history_search)) },
                leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton({ query = "" }) {
                        Icon(AppIcons.Close, contentDescription = stringResource(R.string.ui_history_search_clear))
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
            )
        }
        item(key = "retention") { RetentionLine(retention, storageBytes, onRetention) }
        when {
            rows.isEmpty() -> item(key = "empty") { EmptyHistory(onTry) }
            shown.isEmpty() -> item(key = "no-match") {
                Text(
                    stringResource(R.string.ui_history_no_match, query.trim()), Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> days.forEach { (day, takes) ->
                item(key = "day-$day") { DayHeader(day, today) }
                items(takes, key = { it.id }) { row -> TakeCard(row, apps, onCopy, onDelete, onTranscribe) }
            }
        }
    }
    if (confirmClear) {
        val count = rows.count { it.status.terminal }
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.ui_history_clear_title)) },
            text = { Text(pluralStringResource(R.plurals.ui_history_clear_body, count, count)) },
            confirmButton = {
                Button({ confirmClear = false; onClearAll() }, colors = destructive()) { Text(stringResource(R.string.ui_history_clear_confirm)) }
            },
            dismissButton = { TextButton({ confirmClear = false }) { Text(stringResource(R.string.ui_cancel)) } },
        )
    }
}

/** "0:07", "12:40", "1:02:05": minutes without a leading zero, as a player shows a length. */
internal fun duration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

private fun day(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

@Composable
private fun DayHeader(day: LocalDate, today: LocalDate) {
    val context = LocalContext.current
    val text = when (day) {
        today -> stringResource(R.string.ui_today)
        today.minusDays(1) -> stringResource(R.string.ui_yesterday)
        else -> DateUtils.formatDateTime(
            context, day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL,
        )
    }
    Text(
        text, Modifier.semantics { heading() }.padding(start = 4.dp, top = 14.dp),
        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary,
    )
}

/** "Keeps your last 200 takes · 12 MB", and Change, which opens the setting. */
@Composable
private fun RetentionLine(retention: Retention, storageBytes: Long, onRetention: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(AppIcons.Schedule, contentDescription = null, Modifier.padding(start = 4.dp, end = 10.dp).size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        val summary = retentionSummary(retention)
        Text(
            when {
                storageBytes == 0L -> summary
                storageBytes < 1_000_000 -> stringResource(R.string.ui_retention_line, summary, stringResource(R.string.ui_under_1_mb))
                else -> stringResource(R.string.ui_retention_line, summary, formatSize(storageBytes))
            },
            Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onRetention) { Text(stringResource(R.string.ui_change)) }
    }
}

/** The rule in words: "Keeps your last 200 takes", "Keeps takes for 30 days, up to 200", "Keeps every take". */
@Composable
fun retentionSummary(retention: Retention): String {
    val takes = retention.maxTakes?.let { NumberFormat.getIntegerInstance().format(it) }
    val days = retention.maxDays
    return when {
        days != null && takes != null -> stringResource(R.string.ui_keep_both, days, takes)
        days != null -> stringResource(R.string.ui_keep_days, days)
        takes != null -> stringResource(R.string.ui_keep_takes, takes)
        else -> stringResource(R.string.ui_keep_all)
    }
}

@Composable
private fun EmptyHistory(onTry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 48.dp, start = 16.dp, end = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        IconBadge(AppIcons.History, MaterialTheme.colorScheme.onPrimaryContainer, MaterialTheme.colorScheme.primaryContainer, size = 88)
        Text(stringResource(R.string.ui_history_empty_title), Modifier.padding(top = 20.dp), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.ui_history_empty_body), Modifier.padding(top = 8.dp, bottom = 20.dp),
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onTry) { Text(stringResource(R.string.ui_history_empty_action)) }
    }
}

/** One take: status and time, its text, then its actions. */
@Composable
private fun TakeCard(
    row: Dictation, apps: Map<String, String>,
    onCopy: (Dictation) -> Unit, onDelete: (Dictation) -> Unit, onTranscribe: (Dictation) -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val (icon, tint) = statusLook(row.status)
    // One TalkBack stop for the status, time and text; the buttons stay separate stops.
    Card(
        Modifier.fillMaxWidth().testTag("history_row").semantics(mergeDescendants = true) {}, shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp)) {
            Column(Modifier.padding(end = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) Icon(icon, contentDescription = null, Modifier.size(18.dp), tint = tint)
                    else CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        statusText(row, apps), Modifier.weight(1f).padding(start = 8.dp),
                        style = MaterialTheme.typography.labelLarge, color = tint,
                    )
                    val time = DateUtils.formatDateTime(context, row.startedAt, DateUtils.FORMAT_SHOW_TIME)
                    val length = if (row.durationMs > 0) " · " + duration(row.durationMs) else ""
                    Text(time + length, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                }
                val text = row.finalText ?: row.copyText?.let { stringResource(R.string.ui_partial, it) }
                Text(
                    text ?: stringResource(R.string.ui_audio_only), Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyLarge, maxLines = 6, overflow = TextOverflow.Ellipsis,
                    color = if (text == null) colors.onSurfaceVariant else colors.onSurface,
                    fontStyle = if (text == null) FontStyle.Italic else FontStyle.Normal,
                )
                // The status is the take's own (it may say Typed into Messages); this text came later and was never typed.
                if (row.retranscribed && text != null) {
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(AppIcons.Refresh, contentDescription = null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                        Text(
                            stringResource(if (row.status == Status.NOT_INSERTED) R.string.ui_retranscribed else R.string.ui_retranscribed_not_typed),
                            Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
            // A live row belongs to a take in progress: transcribing it again, or deleting its WAV under the recorder, would
            // break the take. An ended take can be transcribed again from its audio (HistoryDb.saveRetranscription).
            if (row.status.terminal) Row(verticalAlignment = Alignment.CenterVertically) {
                FlowRow(Modifier.weight(1f)) {
                    if (row.copyText != null) Action(AppIcons.Copy, R.string.ui_copy) { onCopy(row) }
                    Action(AppIcons.Refresh, R.string.ui_transcribe) { onTranscribe(row) }
                }
                IconButton({ onDelete(row) }) {
                    Icon(AppIcons.Delete, contentDescription = stringResource(R.string.ui_delete), tint = colors.onSurfaceVariant)
                }
            } else Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun Action(icon: ImageVector, @StringRes label: Int, onClick: () -> Unit) {
    TextButton(onClick, contentPadding = PaddingValues(horizontal = 8.dp)) {
        Icon(icon, contentDescription = null, Modifier.size(18.dp))
        Text(stringResource(label), Modifier.padding(start = 6.dp))
    }
}

/** The status icon and colour; no icon (a spinner) while the take runs. */
@Composable
private fun statusLook(status: Status): Pair<ImageVector?, Color> {
    val colors = MaterialTheme.colorScheme
    return when (status) {
        Status.INSERTED -> AppIcons.CheckCircle to colors.success
        Status.UNVERIFIED, Status.NEEDS_REVIEW -> AppIcons.Info to colors.caution
        Status.NOT_INSERTED -> AppIcons.Keyboard to colors.onSurfaceVariant
        Status.NO_SPEECH -> AppIcons.MicOff to colors.onSurfaceVariant
        Status.CANCELLED -> AppIcons.Close to colors.onSurfaceVariant
        Status.FAILED, Status.INTERRUPTED -> AppIcons.Error to colors.error
        Status.RECORDING, Status.TRANSCRIBING, Status.STAGED, Status.INSERTING -> null to colors.primary
    }
}

/** "Typed into Messages" when the app is known, else the status. */
@Composable
private fun statusText(row: Dictation, apps: Map<String, String>): String {
    val app = row.targetPackage?.let(apps::get)
    return if (row.status == Status.INSERTED && app != null) stringResource(R.string.ui_status_inserted_into, app)
    else stringResource(statusLabel(row.status))
}

@StringRes
private fun statusLabel(status: Status): Int = when (status) {
    Status.INSERTED -> R.string.ui_status_inserted
    Status.UNVERIFIED -> R.string.ui_status_unverified
    Status.NOT_INSERTED -> R.string.ui_status_not_inserted
    Status.NO_SPEECH -> R.string.ui_status_no_speech
    Status.CANCELLED -> R.string.ui_status_cancelled
    Status.FAILED -> R.string.ui_status_failed
    Status.INTERRUPTED -> R.string.ui_status_interrupted
    Status.NEEDS_REVIEW -> R.string.ui_status_needs_review
    Status.RECORDING, Status.TRANSCRIBING, Status.STAGED, Status.INSERTING -> R.string.ui_status_live
}
