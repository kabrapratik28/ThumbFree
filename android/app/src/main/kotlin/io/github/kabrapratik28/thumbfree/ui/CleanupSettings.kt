package io.github.kabrapratik28.thumbfree.ui

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import io.github.kabrapratik28.thumbfree.R
import io.github.kabrapratik28.thumbfree.app.AppGraph
import io.github.kabrapratik28.thumbfree.app.GeminiCleanup
import io.github.kabrapratik28.thumbfree.core.text.CleanupStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Settings > Clean up (issue #1, a test build): whether Gemini Nano is on the phone, with its download; the sparkle on or
 * off; and the style a tap uses.
 */
@Composable
internal fun CleanupSettings() {
    val settings = AppGraph.settings
    var status by remember { mutableStateOf<Int?>(null) }
    var percent by remember { mutableStateOf<Int?>(null) }
    var on by remember { mutableStateOf(settings.cleanupOn) }
    var style by remember { mutableStateOf(settings.cleanupStyle) }
    val scope = rememberCoroutineScope()
    suspend fun check() {
        status = GeminiCleanup.status().also { AppGraph.ports.cleanup.status = it }
    }
    LaunchedEffect(Unit) { check() }

    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        val line = when {
            percent != null -> stringResource(R.string.ui_cleanup_downloading, percent!!)
            status == null -> stringResource(R.string.ui_cleanup_checking)
            status == FeatureStatus.AVAILABLE -> stringResource(R.string.ui_cleanup_ready)
            status == FeatureStatus.DOWNLOADABLE || status == FeatureStatus.DOWNLOADING -> stringResource(R.string.ui_cleanup_downloadable)
            else -> stringResource(R.string.ui_cleanup_unavailable)
        }
        Text(line, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        if (percent == null && (status == FeatureStatus.DOWNLOADABLE || status == FeatureStatus.DOWNLOADING)) {
            TextButton(onClick = {
                percent = 0
                scope.launch {
                    var total = 0L
                    try {
                        GeminiCleanup.download().collect {
                            when (it) {
                                is DownloadStatus.DownloadStarted -> total = it.bytesToDownload
                                is DownloadStatus.DownloadProgress -> if (total > 0) percent = (it.totalBytesDownloaded * 100 / total).toInt()
                                DownloadStatus.DownloadCompleted, is DownloadStatus.DownloadFailed -> Unit
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("ThumbFree", "cleanup_download_error ${e.javaClass.name}")
                    }
                    percent = null
                    check()
                }
            }) { Text(stringResource(R.string.ui_cleanup_download)) }
        }
    }
    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(on, role = Role.Switch) {
            on = it
            settings.cleanupOn = it
            if (!it) AppGraph.ports.cleanup.clear()
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.ui_cleanup_show), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        Switch(checked = on, onCheckedChange = null)
    }
    Text(stringResource(R.string.ui_cleanup_tap_uses), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
    Column {
        Choices(CleanupStyle.entries, style, { stringResource(styleName(it)) }) {
            style = it
            settings.cleanupStyle = it
        }
    }
    Text(
        stringResource(R.string.ui_cleanup_privacy), Modifier.padding(top = 12.dp),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
