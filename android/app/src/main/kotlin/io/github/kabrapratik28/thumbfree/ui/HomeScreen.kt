package io.github.kabrapratik28.thumbfree.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kabrapratik28.thumbfree.R
import io.github.kabrapratik28.thumbfree.a11y.DictationAccessibilityService
import io.github.kabrapratik28.thumbfree.core.models.Catalog
import io.github.kabrapratik28.thumbfree.core.models.ModelFile
import io.github.kabrapratik28.thumbfree.core.models.ModelStatus
import io.github.kabrapratik28.thumbfree.core.models.ModelStore
import io.github.kabrapratik28.thumbfree.models.DownloadState

/** The four screens under the bottom bar. Home opens first. */
enum class Tab(@StringRes val label: Int, val icon: ImageVector) {
    HOME(R.string.ui_tab_home, AppIcons.Home),
    HISTORY(R.string.ui_tab_history, AppIcons.History),
    DICTIONARY(R.string.ui_tab_dictionary, AppIcons.Book),
    SETTINGS(R.string.ui_tab_settings, AppIcons.Settings),
}

/** What a take needs. Home's setup card, Settings and the welcome steps fix them. */
enum class SetupItem { MIC, SERVICE, MODEL }

/**
 * [model] is the [chosen] model's status and [offered] the models the owner can switch to. Both wait for [withModels],
 * whose ModelStore.status may hash whole files, so callers run it off main.
 */
data class SetupState(
    val micGranted: Boolean, val serviceEnabled: Boolean, val model: ModelStatus?,
    val chosen: ModelFile = Catalog.PARAKEET_UNIFIED_Q8, val offered: List<ModelFile> = emptyList(),
) {
    fun ok(item: SetupItem): Boolean = when (item) {
        SetupItem.MIC -> micGranted
        SetupItem.SERVICE -> serviceEnabled
        SetupItem.MODEL -> model == ModelStatus.VERIFIED
    }

    /** A take can run: the microphone, the service and the chosen model. */
    val ready: Boolean get() = SetupItem.entries.all(::ok)

    /**
     * The same with [next] chosen. Its status is known when it is the chosen one already or an offered (verified) one;
     * any other model's is checked again (null), so the old model's status never stands for it.
     */
    fun choosing(next: ModelFile): SetupState = copy(
        chosen = next,
        model = when (next) {
            chosen -> model
            in offered -> ModelStatus.VERIFIED
            else -> null
        },
    )

    /** Checks every catalog model: the model line shows [chosen]'s status, and only verified models are offered. */
    fun withModels(store: ModelStore): SetupState {
        val status = Catalog.all.associateWith { modelStatus(store, it) }
        return copy(model = status[chosen], offered = Catalog.all.filter { status[it] == ModelStatus.VERIFIED })
    }

    companion object {
        fun read(context: Context, model: ModelStatus?): SetupState {
            val ours = ComponentName(context, DictationAccessibilityService::class.java)
            // The system may store the short form (package/.Class), so compare parsed names, not strings.
            val services = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return SetupState(
                micGranted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                serviceEnabled = services.split(':').any { ComponentName.unflattenFromString(it) == ours },
                model = model,
            )
        }

        /**
         * [ModelStore.status], which may hash the whole file, so call it off main. A check that throws shows on the
         * model line: in the home screen's refresh it would reach the uncaught handler and end the app.
         */
        fun modelStatus(store: ModelStore, model: ModelFile): ModelStatus = try {
            store.status(model)
        } catch (e: Exception) {
            Log.w("ThumbFree", "model_status_error ${e.javaClass.name}") // the class only, as for every error line
            ModelStatus.CHECK_FAILED
        }
    }
}

/**
 * The bottom bar over [content], which gets the chosen tab. Content sits below the status bar and above the bar, and
 * above the keyboard when it shows (the bar hides behind it).
 */
@Composable
fun HomeScreen(tab: Tab, onTab: (Tab) -> Unit, snackbar: SnackbarHostState, content: @Composable (Tab) -> Unit) {
    Scaffold(
        bottomBar = {
            NavigationBar {
                for (each in Tab.entries) {
                    NavigationBarItem(
                        selected = each == tab,
                        onClick = { onTab(each) },
                        icon = { Icon(each.icon, contentDescription = null) }, // the label says it
                        // A whole word at any font size: the longest label (Dictionary) shrinks to fit a quarter of the
                        // bar at 200% instead of breaking in the middle.
                        label = {
                            Text(
                                stringResource(each.label), maxLines = 1,
                                autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = MaterialTheme.typography.labelMedium.fontSize),
                            )
                        },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = MAX_CONTENT_WIDTH)) { content(tab) } // a readable column on a tablet or in landscape
        }
    }
}

/** The widest a screen's content grows. */
val MAX_CONTENT_WIDTH = 640.dp

/** A screen's big title, a heading for TalkBack. */
@Composable
fun ScreenTitle(@StringRes text: Int, modifier: Modifier = Modifier) = Text(
    stringResource(text), modifier.semantics { heading() }.padding(top = 24.dp, bottom = 8.dp),
    style = MaterialTheme.typography.headlineMedium,
)

/** The small label over a group of cards, with an optional one-line [note] saying what the group is for. */
@Composable
fun SectionLabel(@StringRes text: Int, modifier: Modifier = Modifier, note: String? = null) {
    Column(modifier.padding(start = 4.dp, top = 24.dp, bottom = 8.dp)) {
        Text(
            stringResource(text), Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary,
        )
        if (note != null) {
            Text(note, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** [icon] in a tinted circle. */
@Composable
fun IconBadge(icon: ImageVector, tint: Color, background: Color, size: Int = 40) = Box(
    Modifier.size(size.dp).clip(CircleShape).background(background), contentAlignment = Alignment.Center,
) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size((size * 0.55).dp)) }

/**
 * One row per item of [items]: its icon, name and state. A missing item gets a button that fixes it ([onFix]). While the
 * chosen model downloads ([modelDownload]), its row shows the wait (with Use mobile data while it waits for Wi-Fi), the
 * percentage or the file check with a bar, and a failed download says why, with Try again.
 */
@Composable
fun SetupRows(setup: SetupState, items: List<SetupItem>, onFix: (SetupItem) -> Unit, modelDownload: DownloadState? = null, thinBar: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (item in items) {
            val ok = setup.ok(item)
            val download = modelDownload.takeIf { item == SetupItem.MODEL && !ok && it != DownloadState.NotDownloaded && it != DownloadState.Ready }
            val detail = when {
                download != null -> downloadDetail(download)
                item == SetupItem.MODEL && setup.model == ModelStatus.MISSING ->
                    stringResource(R.string.ui_model_missing_size, formatSize(setup.chosen.sizeBytes))
                else -> stringResource(setupDetail(item, setup))
            }
            val action = when {
                ok || (item == SetupItem.MODEL && setup.model == null) -> null // nothing to fix, or the check still runs
                download is DownloadState.Failed -> stringResource(R.string.ui_models_try_again)
                download is DownloadState.Queued && download.wifiOnly && !download.retrying -> stringResource(R.string.ui_use_mobile_data)
                download != null -> null // under way: the bar shows it
                else -> stringResource(setupFix(item))
            }
            SetupRow(
                icon = setupIcon(item), title = stringResource(setupTitle(item, setup.chosen)), detail = detail,
                ok = ok, action = action, onAction = { onFix(item) },
                progress = if (download == null || download is DownloadState.Failed) null else downloadFraction(download) ?: Float.NaN,
                thinBar = thinBar,
            )
        }
    }
}

/** A download in words: "Downloading · 42%", the wait or the file check, or why it failed. */
@Composable
fun downloadDetail(download: DownloadState): String = when (download) {
    is DownloadState.Downloading -> stringResource(R.string.ui_model_downloading, ((downloadFraction(download) ?: 0f) * 100).toInt())
    is DownloadState.Failed -> stringResource(failLabel(download.reason))
    else -> progressLabel(download)
}

@Composable
private fun SetupRow(icon: ImageVector, title: String, detail: String, ok: Boolean, action: String?, onAction: () -> Unit, progress: Float?, thinBar: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconBadge(if (ok) AppIcons.Check else icon, if (ok) colors.success else colors.onSurface, colors.badge)
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = if (ok) colors.success else colors.onSurfaceVariant)
            // NaN: a wait or the file check, which have no value to show.
            // The badge colour for the track: the default track is the sunflower of the setup card itself.
            val bar = Modifier.fillMaxWidth().padding(top = 6.dp)
            when {
                progress == null -> Unit
                // Home's row has a 2 dp line, on its own raised surface.
                thinBar && progress.isNaN() -> LinearProgressIndicator(bar.height(2.dp), trackColor = colors.secondary.copy(alpha = 0.2f), gapSize = 0.dp)
                thinBar -> LinearProgressIndicator(
                    { progress }, bar.height(2.dp), trackColor = colors.secondary.copy(alpha = 0.2f), gapSize = 0.dp, drawStopIndicator = {},
                )
                progress.isNaN() -> LinearProgressIndicator(bar, trackColor = colors.badge)
                else -> LinearProgressIndicator({ progress }, bar, trackColor = colors.badge)
            }
        }
        if (action != null) {
            Button(onAction, contentPadding = PaddingValues(horizontal = 16.dp), colors = ButtonDefaults.buttonColors()) { Text(action) }
        }
    }
}

private fun setupIcon(item: SetupItem): ImageVector = when (item) {
    SetupItem.MIC -> AppIcons.Mic
    SetupItem.SERVICE -> AppIcons.TouchApp
    SetupItem.MODEL -> AppIcons.Waveform
}

/** An item's name on its row; the model's names the [chosen] one ("Multilingual speech model"). */
@StringRes
fun setupTitle(item: SetupItem, chosen: ModelFile): Int = when (item) {
    SetupItem.MIC -> R.string.ui_setup_mic
    SetupItem.SERVICE -> R.string.ui_setup_service
    SetupItem.MODEL -> modelNoun(chosen)
}

@StringRes
fun setupDetail(item: SetupItem, setup: SetupState): Int = when (item) {
    SetupItem.MIC -> if (setup.micGranted) R.string.ui_setup_allowed else R.string.ui_setup_mic_missing
    SetupItem.SERVICE -> if (setup.serviceEnabled) R.string.ui_setup_service_ok else R.string.ui_setup_service_missing
    SetupItem.MODEL -> modelLabel(setup.model)
}

@StringRes
private fun setupFix(item: SetupItem): Int = when (item) {
    SetupItem.MIC -> R.string.ui_allow
    SetupItem.SERVICE -> R.string.ui_turn_on
    SetupItem.MODEL -> R.string.ui_get_model
}

@StringRes
fun modelLabel(status: ModelStatus?): Int = when (status) {
    null -> R.string.ui_model_checking
    ModelStatus.VERIFIED -> R.string.ui_model_verified
    ModelStatus.MISSING -> R.string.ui_model_missing
    ModelStatus.WRONG_SIZE -> R.string.ui_model_wrong_size
    ModelStatus.CORRUPT -> R.string.ui_model_corrupt
    ModelStatus.CHECK_FAILED -> R.string.ui_model_check_failed
}
