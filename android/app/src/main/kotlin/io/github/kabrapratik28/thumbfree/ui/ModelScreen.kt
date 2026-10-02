package io.github.kabrapratik28.thumbfree.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.ConnectivityManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.kabrapratik28.thumbfree.R
import io.github.kabrapratik28.thumbfree.app.AppGraph
import io.github.kabrapratik28.thumbfree.core.models.Catalog
import io.github.kabrapratik28.thumbfree.core.models.ModelFile
import io.github.kabrapratik28.thumbfree.models.DownloadState
import io.github.kabrapratik28.thumbfree.models.FailReason
import io.github.kabrapratik28.thumbfree.models.ModelDownloads
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ModelRow(val model: ModelFile, val state: DownloadState)

/** Decimal units, rounded: under 1,000,000,000 bytes "<n> MB", else "<n.n> GB". */
fun formatSize(bytes: Long): String {
    if (bytes < 1_000_000_000L) return "${(bytes + 500_000) / 1_000_000} MB"
    return "%.1f GB".format(Locale.ROOT, bytes / 1_000_000_000.0)
}

/**
 * One card per catalog model, following its [DownloadState]: its size and Download; a wait, a percentage or the file
 * check, each with a bar and Cancel; Installed and Delete once ready; or what went wrong and Try again (and Open storage
 * when there is no room, [onOpenStorage]). With [wifiOnly] on, a tap on a [metered] network asks first whether to use
 * mobile data now or wait for Wi-Fi. [onWifiOnly] shows the Wi-Fi-only switch, [onBack] a back arrow. A model in
 * [pending] has a Cancel or Delete still running: its buttons wait. The [chosen] model, the one takes use, says In use;
 * every other one offers Use this model ([onChoose]), on the phone or not yet.
 */
@Composable
fun ModelScreen(
    rows: List<ModelRow>,
    metered: Boolean,
    onDownload: (ModelFile, Boolean) -> Unit,
    onCancel: (ModelFile) -> Unit,
    onDelete: (ModelFile) -> Unit,
    wifiOnly: Boolean = true,
    onWifiOnly: ((Boolean) -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    pending: Set<ModelFile> = emptySet(),
    chosen: ModelFile? = null,
    onChoose: ((ModelFile) -> Unit)? = null,
    onOpenStorage: (() -> Unit)? = null,
) {
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = MAX_CONTENT_WIDTH),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "title") {
                Column {
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (onBack != null) IconButton(onBack) { Icon(AppIcons.ArrowBack, stringResource(R.string.welcome_back)) }
                        Text(
                            stringResource(R.string.ui_models_title), Modifier.semantics { heading() }.padding(start = if (onBack != null) 4.dp else 0.dp),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                    }
                    Text(
                        stringResource(R.string.ui_models_body), Modifier.padding(top = 4.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onWifiOnly != null) item(key = "wifi") { WifiOnlyRow(wifiOnly, onWifiOnly) }
            items(rows, key = { it.model.id }) { row ->
                ModelRowItem(
                    row, metered, wifiOnly, onDownload, onCancel, onDelete, row.model in pending, row.model == chosen,
                    onChoose?.takeIf { row.model != chosen }, onOpenStorage,
                )
            }
        }
    }
}

/** "Download on Wi-Fi only", a switch row (Settings.wifiOnly). */
@Composable
fun WifiOnlyRow(wifiOnly: Boolean, onWifiOnly: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(wifiOnly, role = Role.Switch, onValueChange = onWifiOnly),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(AppIcons.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.ui_wifi_only), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.ui_wifi_only_body), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(wifiOnly, onCheckedChange = null) // the row takes the tap
    }
}

@Composable
private fun ModelRowItem(
    row: ModelRow,
    metered: Boolean,
    wifiOnly: Boolean,
    onDownload: (ModelFile, Boolean) -> Unit,
    onCancel: (ModelFile) -> Unit,
    onDelete: (ModelFile) -> Unit,
    pending: Boolean,
    inUse: Boolean,
    onChoose: ((ModelFile) -> Unit)?,
    onOpenStorage: (() -> Unit)?,
) {
    var askWifi by rememberSaveable(row.model.id) { mutableStateOf(false) }
    var askDelete by rememberSaveable(row.model.id) { mutableStateOf(false) } // a model is a big download: Delete asks first
    val colors = MaterialTheme.colorScheme
    if (askDelete) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            title = { Text(stringResource(R.string.ui_models_delete_title)) },
            text = { Text(stringResource(R.string.ui_models_delete_body, stringResource(modelName(row.model)), formatSize(row.model.sizeBytes))) },
            confirmButton = {
                Button({ askDelete = false; onDelete(row.model) }, colors = destructive()) { Text(stringResource(R.string.ui_models_delete_confirm)) }
            },
            dismissButton = { TextButton({ askDelete = false }) { Text(stringResource(R.string.ui_cancel)) } },
        )
    }
    Card(
        Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(AppIcons.Waveform, colors.onSurface, colors.badge)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(modelName(row.model)), style = MaterialTheme.typography.titleMedium)
                    Text(modelDetail(row.model), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                if (inUse) {
                    Text(
                        stringResource(R.string.ui_models_chosen), Modifier.clip(CircleShape).background(colors.chosen).padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium, color = colors.onChosen,
                    )
                }
            }
            when (val state = row.state) {
                // Cancel stays for as long as work is unfinished, a wait with no progress data included: a second
                // Download tap would otherwise need the enqueue policy just to register.
                is DownloadState.Queued, is DownloadState.Downloading, DownloadState.Verifying -> {
                    val fraction = downloadFraction(state)
                    if (fraction != null) LinearProgressIndicator({ fraction }, Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(progressLabel(state), style = MaterialTheme.typography.titleSmall)
                        if (state is DownloadState.Downloading && state.total > 0) {
                            Text(
                                stringResource(R.string.ui_models_progress_bytes, formatSize(state.bytes), formatSize(state.total)),
                                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = { onCancel(row.model) }, enabled = !pending) { Text(stringResource(R.string.ui_models_cancel)) }
                    }
                    // A Wi-Fi wait can use mobile data now instead (start with wifiOnly false replaces the wait).
                    if (state is DownloadState.Queued && state.wifiOnly && !state.retrying) {
                        FilledTonalButton(onClick = { onDownload(row.model, false) }) { Text(stringResource(R.string.ui_use_mobile_data)) }
                    }
                }
                DownloadState.Ready -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(AppIcons.CheckCircle, contentDescription = null, Modifier.size(20.dp), tint = colors.success)
                    Text(stringResource(R.string.ui_models_installed), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = colors.success)
                    if (onChoose != null) FilledTonalButton(onClick = { onChoose(row.model) }) { Text(stringResource(R.string.ui_models_use)) }
                    TextButton(onClick = { askDelete = true }, enabled = !pending) { Text(stringResource(R.string.ui_delete), color = colors.error) }
                }
                DownloadState.NotDownloaded, is DownloadState.Failed -> if (askWifi) {
                    Text(stringResource(R.string.ui_models_metered), style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { askWifi = false; onDownload(row.model, false) }) {
                            Text(stringResource(R.string.ui_models_download_now))
                        }
                        OutlinedButton(onClick = { askWifi = false; onDownload(row.model, true) }) {
                            Text(stringResource(R.string.ui_models_wait_for_wifi))
                        }
                    }
                } else {
                    val failed = state as? DownloadState.Failed
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (failed != null) Icon(AppIcons.Error, contentDescription = null, Modifier.size(20.dp), tint = colors.error)
                        Text(
                            failed?.let { stringResource(failLabel(it.reason)) } ?: formatSize(row.model.sizeBytes), Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall, color = if (failed != null) colors.error else colors.onSurface,
                        )
                        Button(onClick = { if (metered && wifiOnly) askWifi = true else onDownload(row.model, wifiOnly) }) {
                            Text(stringResource(if (failed != null) R.string.ui_models_try_again else R.string.ui_models_download))
                        }
                    }
                    // No room: the remedy is outside the app, so it offers the way there too.
                    if (failed?.reason == FailReason.NOT_ENOUGH_SPACE && onOpenStorage != null) {
                        OutlinedButton(onClick = onOpenStorage) { Text(stringResource(R.string.ui_models_open_storage)) }
                    }
                }
            }
            // Not on the phone yet: the switch starts its download too (MainActivity), whose state this card then shows.
            if (onChoose != null && row.state != DownloadState.Ready) {
                FilledTonalButton(onClick = { onChoose(row.model) }) { Text(stringResource(R.string.ui_models_use)) }
            }
        }
    }
}

/** How far a download is, 0 to 1; null while it waits or the file is checked (the bar then runs without a value). */
internal fun downloadFraction(state: DownloadState): Float? =
    (state as? DownloadState.Downloading)?.let { if (it.total > 0) (it.bytes.toFloat() / it.total).coerceIn(0f, 1f) else 0f }

@Composable
internal fun progressLabel(state: DownloadState): String = when (state) {
    is DownloadState.Queued -> stringResource(
        when {
            state.retrying -> R.string.ui_models_retrying
            state.wifiOnly -> R.string.ui_models_waiting_for_wifi
            else -> R.string.ui_models_waiting_for_network
        },
    )
    is DownloadState.Downloading -> "${if (state.total > 0) (state.bytes * 100 / state.total).coerceIn(0, 100) else 0}%"
    else -> stringResource(R.string.ui_models_verifying)
}

@StringRes
internal fun failLabel(reason: FailReason): Int = when (reason) {
    FailReason.NO_INTERNET -> R.string.ui_models_failed_no_internet
    FailReason.NOT_ENOUGH_SPACE -> R.string.ui_models_failed_no_space
    FailReason.INTERRUPTED -> R.string.ui_models_failed_interrupted
    FailReason.FILE_CHECK_FAILED -> R.string.ui_models_failed_check
}

/**
 * Wires the stateless [ModelScreen] to [ModelDownloads], the Wi-Fi-only setting and connectivity; the [chosen] model and
 * a switch to another ready one ([onChoose]) are MainActivity's.
 */
@Composable
fun ModelsRoute(onBack: () -> Unit, chosen: ModelFile? = null, onChoose: ((ModelFile) -> Unit)? = null) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)

    var metered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        metered = withContext(Dispatchers.IO) {
            context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false
        }
    }
    var wifiOnly by remember { mutableStateOf(AppGraph.settings.wifiOnly) }
    // Catalog.all never changes, so each model's collector keeps its place in the composition.
    val rows = Catalog.all.mapNotNull { model ->
        val state = remember(model) { ModelDownloads.state(context, model) }.collectAsState(initial = null).value
        state?.let { ModelRow(model, it) }
    }

    // Cancel and delete write a tombstone before they return: off the main thread, with the model's buttons held meanwhile.
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf(emptySet<ModelFile>()) }
    fun offMain(model: ModelFile, call: () -> Boolean) {
        if (model in pending) return
        pending = pending + model
        scope.launch {
            val done = withContext(Dispatchers.IO) { call() }
            pending = pending - model
            if (!done) Toast.makeText(context, R.string.ui_models_in_use, Toast.LENGTH_SHORT).show()
        }
    }

    ModelScreen(
        rows = rows,
        metered = metered,
        onDownload = { model, wifi -> ModelDownloads.start(context, model, wifi) },
        onCancel = { model -> offMain(model) { ModelDownloads.cancel(context, model); true } },
        onDelete = { model -> offMain(model) { ModelDownloads.delete(context, model) } },
        pending = pending,
        wifiOnly = wifiOnly,
        onWifiOnly = { AppGraph.settings.wifiOnly = it; wifiOnly = it },
        onBack = onBack,
        chosen = chosen,
        onChoose = onChoose,
        onOpenStorage = {
            try {
                context.startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
            } catch (e: ActivityNotFoundException) {
                Log.w("ThumbFree", "no_activity storage")
            }
        },
    )
}
