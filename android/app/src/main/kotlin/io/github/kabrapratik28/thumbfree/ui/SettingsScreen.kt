package io.github.kabrapratik28.thumbfree.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.kabrapratik28.thumbfree.R
import io.github.kabrapratik28.thumbfree.core.models.Catalog
import io.github.kabrapratik28.thumbfree.core.models.ModelFile
import io.github.kabrapratik28.thumbfree.core.models.repo
import io.github.kabrapratik28.thumbfree.core.session.BubbleStyle
import io.github.kabrapratik28.thumbfree.models.DownloadState
import io.github.kabrapratik28.thumbfree.data.Retention
import java.text.NumberFormat
import java.util.Locale

/** The project page (brand text stays out of res/values: BrandTest). */
const val WEBSITE = "https://kabrapratik28.github.io/ThumbFree"

/** The Android privacy policy's published page; docs/privacy-policy.md is its source text. */
const val PRIVACY_POLICY = "$WEBSITE/android-privacy.html"

// The multilingual model's CC BY 4.0 credit: NVIDIA's model at the revision its GGUF was converted from, and the licence.
private const val V3_SOURCE = "https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3/tree/6d590f77001d318fb17a0b5bf7ee329a91b52598"
private const val CC_BY_4 = "https://creativecommons.org/licenses/by/4.0/"

// Settings > About > Open-source licenses and credits, in order: the code the APK ships, then the models, then the data.
// THIRD_PARTY_NOTICES.md holds the full texts; LicensesTest keeps the two in step.
internal val LICENSES = listOf(
    R.string.ui_license_engine, R.string.ui_license_vad, R.string.ui_license_cleanup, R.string.ui_license_libcxx,
    R.string.ui_license_icons, R.string.ui_license_parakeet, R.string.ui_license_parakeet_v3, R.string.ui_license_canary,
    R.string.ui_license_wordfreq,
)

/**
 * Settings in sections, each with a line on what it is for: Setup (the Try card's items while one is missing, else one
 * line; the welcome screens again), Bubble (size, transparency, position), Speech model, Dictionary (a row to its tab), History (retention and the
 * storage it uses) and About. [showRetention] scrolls to History once, then calls [onRetentionShown].
 */
@Composable
fun SettingsScreen(
    setup: SetupState?, onFix: (SetupItem) -> Unit, onWelcome: () -> Unit,
    onChoose: (ModelFile) -> Unit, onManageModels: () -> Unit, downloads: Map<ModelFile, DownloadState> = emptyMap(),
    bubbleStyle: BubbleStyle, onBubbleStyle: (BubbleStyle) -> Unit,
    bubblePlaced: Boolean = false, bubbleSnap: Boolean = false, onBubbleSnap: (Boolean) -> Unit = {}, onResetBubblePosition: () -> Unit = {},
    wordCount: Int, onDictionary: () -> Unit,
    retention: Retention, storageBytes: Long, onRetention: (Retention) -> Unit,
    showRetention: Boolean, onRetentionShown: () -> Unit,
    version: String, onWebsite: () -> Unit, onLink: (String) -> Unit = {},
) {
    val retentionView = remember { BringIntoViewRequester() }
    LaunchedEffect(showRetention) {
        if (showRetention) {
            retentionView.bringIntoView()
            onRetentionShown()
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle(R.string.ui_settings)

        val app = stringResource(R.string.app_name)
        // Setup is prominent only while something a take needs is missing; then it folds into one line.
        SectionLabel(R.string.ui_setup, note = stringResource(R.string.ui_setup_note, app))
        SettingsCard {
            when {
                setup == null -> Unit
                setup.ready -> AllSet()
                else -> SetupRows(setup, SetupItem.entries, onFix, downloads[setup.chosen])
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            LinkRow(AppIcons.Refresh, R.string.ui_welcome_again, AppIcons.ChevronRight, onWelcome)
        }

        SectionLabel(R.string.ui_settings_bubble, note = stringResource(R.string.ui_bubble_note))
        SettingsCard { BubbleSection(bubbleStyle, onBubbleStyle, bubblePlaced, bubbleSnap, onBubbleSnap, onResetBubblePosition) }

        SectionLabel(R.string.ui_settings_model, note = stringResource(R.string.ui_settings_model_body))
        SettingsCard {
            ModelChoice(setup, downloads, onChoose)
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            LinkRow(AppIcons.Download, R.string.ui_manage_models, AppIcons.ChevronRight, onManageModels)
        }

        SectionLabel(R.string.ui_settings_dictionary, note = stringResource(R.string.ui_dictionary_note, app))
        SettingsCard {
            val detail = if (wordCount > 0) pluralStringResource(R.plurals.ui_words_count, wordCount, wordCount) else stringResource(R.string.ui_dictionary_none)
            LinkRow(AppIcons.Book, R.string.ui_dictionary_open, AppIcons.ChevronRight, onDictionary, detail = detail)
        }

        // The whole section, so the Change link from History shows its rules, not just its label.
        Column(Modifier.bringIntoViewRequester(retentionView)) {
            SectionLabel(R.string.ui_settings_history, note = stringResource(R.string.ui_history_note))
            SettingsCard {
                Text(stringResource(R.string.ui_keep_for), style = MaterialTheme.typography.titleMedium)
                Choices(Retention.DAYS, retention.maxDays, { daysLabel(it) }) { onRetention(retention.copy(maxDays = it)) }
                Text(stringResource(R.string.ui_keep_at_most), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
                Choices(Retention.TAKES, retention.maxTakes, { takesLabel(it) }) { onRetention(retention.copy(maxTakes = it)) }
                Text(
                    stringResource(R.string.ui_retention_body), Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.ui_storage_used, formatSize(storageBytes)), Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        SectionLabel(R.string.ui_settings_about, note = stringResource(R.string.ui_about_note))
        SettingsCard { About(version, onWebsite, onLink) }
        Spacer(Modifier.padding(bottom = 24.dp))
    }
}

/** Setup when nothing a take needs is missing: one line instead of the rows. */
@Composable
private fun AllSet() {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconBadge(AppIcons.Check, MaterialTheme.colorScheme.success, MaterialTheme.colorScheme.badge)
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(stringResource(R.string.ui_setup_all_set), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.ui_setup_all_set_body), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) = Card(
    Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
) { Column(Modifier.padding(16.dp), content = content) }

/** A tappable row: icon, label, and a trailing icon (a chevron, or open-in-new for a link out of the app). */
@Composable
private fun LinkRow(icon: ImageVector, @StringRes label: Int, trailing: ImageVector, onClick: () -> Unit, detail: String? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconBadge(icon, MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.badge)
        Column(Modifier.weight(1f)) {
            Text(stringResource(label), style = MaterialTheme.typography.titleSmall)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(trailing, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * One radio row per catalog model, the chosen one selected: its name, what it is for, and its size with its download
 * (Downloaded once verified, else as it goes, or Not downloaded). Only a verified model can be picked; a pick applies
 * from the next take. The multilingual model's languages fold out under its row.
 */
@Composable
private fun ModelChoice(setup: SetupState?, downloads: Map<ModelFile, DownloadState>, onChoose: (ModelFile) -> Unit) {
    Column(Modifier.selectableGroup()) {
        for (model in Catalog.all) {
            val offered = setup != null && model in setup.offered
            val chosen = setup?.chosen == model
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .selectable(chosen, enabled = offered, role = Role.RadioButton) { onChoose(model) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(chosen, onClick = null, enabled = offered) // the row takes the tap
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(stringResource(modelName(model)), style = MaterialTheme.typography.bodyLarge)
                    Text(modelDetail(model), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val download = downloads[model]
                    val state = when {
                        setup == null -> null // the check still runs
                        offered -> stringResource(R.string.ui_model_downloaded_size, formatSize(model.sizeBytes))
                        download != null && download != DownloadState.NotDownloaded && download != DownloadState.Ready -> downloadDetail(download)
                        else -> stringResource(R.string.ui_model_missing_size, formatSize(model.sizeBytes))
                    }
                    if (state != null) Text(state, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // Under the row's text (a radio button and its gap in), outside its tap.
            if (model.languages.size > 1) SupportedLanguages(model, Modifier.padding(start = 36.dp))
        }
    }
}

/** A model's name in Settings and on the models screen. */
@StringRes
fun modelName(model: ModelFile): Int = when (model) {
    Catalog.PARAKEET_TDT_V3_Q8 -> R.string.ui_model_multilingual
    Catalog.CANARY_180M_FLASH_Q8 -> R.string.ui_model_canary
    else -> R.string.ui_model_english
}

/** What a model is for, under its name. */
@Composable
internal fun modelDetail(model: ModelFile): String = when (model) {
    Catalog.PARAKEET_TDT_V3_Q8 -> stringResource(R.string.ui_model_multilingual_detail, model.languages.size)
    Catalog.CANARY_180M_FLASH_Q8 -> stringResource(R.string.ui_model_canary_detail)
    else -> stringResource(R.string.ui_model_english_detail)
}

/** The chosen model in plain words, on the setup rows: "Multilingual speech model". */
@StringRes
fun modelNoun(model: ModelFile): Int = when (model) {
    Catalog.PARAKEET_TDT_V3_Q8 -> R.string.ui_setup_model_multilingual
    Catalog.CANARY_180M_FLASH_Q8 -> R.string.ui_setup_model_canary
    else -> R.string.ui_setup_model
}

/**
 * The multilingual model's credit as CC BY 4.0 asks: what it is, its licence and how it was changed, with links (read out
 * by TalkBack, opened through [onLink]) to NVIDIA's model at the revision its GGUF was converted from, to that GGUF file
 * at its pinned revision, and to the licence.
 */
@Composable
private fun MultilingualCredit(onLink: (String) -> Unit) {
    val style = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    val model = Catalog.PARAKEET_TDT_V3_Q8
    val links = listOf(
        R.string.ui_license_link_model to V3_SOURCE,
        R.string.ui_license_link_gguf to "https://huggingface.co/${model.repo}/tree/${model.revision}",
        R.string.ui_license_link_license to CC_BY_4,
    ).map { (label, url) -> stringResource(label) to url }
    val text = buildAnnotatedString {
        append(stringResource(R.string.ui_license_parakeet_v3) + " " + stringResource(R.string.ui_license_links))
        links.forEachIndexed { i, (label, url) ->
            append(if (i == 0) " " else " · ")
            withLink(LinkAnnotation.Url(url, style) { onLink(url) }) { append(label) }
        }
    }
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

/** "See supported languages", folded until tapped, then the model's languages by name. TalkBack says which. */
@Composable
internal fun SupportedLanguages(model: ModelFile, modifier: Modifier = Modifier) {
    var open by rememberSaveable(model.id) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val state = stringResource(if (open) R.string.ui_expanded else R.string.ui_collapsed)
    val action = stringResource(if (open) R.string.ui_model_languages_hide else R.string.ui_model_languages_show)
    Column(modifier) {
        Row(
            Modifier.heightIn(min = 48.dp).clickable(onClickLabel = action, role = Role.Button) { open = !open }
                .semantics { stateDescription = state },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.ui_model_languages), style = MaterialTheme.typography.labelLarge, color = colors.primary)
            Icon(if (open) AppIcons.ExpandLess else AppIcons.ExpandMore, contentDescription = null, tint = colors.primary)
        }
        if (open) {
            Text(
                languageNames(model).joinToString(", "), Modifier.padding(bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
            )
        }
    }
}

// ponytail: English names, as every string of the app is English so far; the app's own locale once it is translated.
/** A model's languages by name, in the catalog's order (for the multilingual model, alphabetical in English). */
internal fun languageNames(model: ModelFile): List<String> =
    model.languages.map { Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH) }

/**
 * [options] as a grid of equal tiles, two to a row, [selected] filled. Two columns leave room for a whole word at 200%
 * font size, where four segments split "Forever" in the middle; a word still too wide for half the row (Recommended at
 * 200%) puts one tile on each row, so no word ever breaks.
 */
@Composable
internal fun <T> Choices(options: List<T>, selected: T, label: @Composable (T) -> String, minHeight: Dp = 48.dp, onPick: (T) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.labelLarge
    val labeled = options.map { it to label(it) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.padding(top = 8.dp)) {
        val room = with(density) { ((maxWidth - 8.dp) / 2 - 24.dp).roundToPx() }
        val widest = labeled.flatMap { it.second.split(' ', '\n') }.maxOf { measurer.measure(it, style).size.width }
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in labeled.chunked(if (widest <= room) 2 else 1)) {
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((option, text) in row) {
                        val on = option == selected
                        Box(
                            Modifier.weight(1f).fillMaxHeight().heightIn(min = minHeight).clip(MaterialTheme.shapes.medium)
                                .background(if (on) colors.chosen else Color.Transparent)
                                .border(1.dp, if (on) colors.chosen else colors.outline, MaterialTheme.shapes.medium)
                                .selectable(on, role = Role.RadioButton) { if (!on) onPick(option) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(text, style = style, textAlign = TextAlign.Center, color = if (on) colors.onChosen else colors.onSurface)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun daysLabel(days: Int?): String =
    if (days == null) stringResource(R.string.ui_forever) else pluralStringResource(R.plurals.ui_days, days, days)

@Composable
private fun takesLabel(takes: Int?): String =
    if (takes == null) stringResource(R.string.ui_unlimited) else NumberFormat.getIntegerInstance().format(takes)

@Composable
private fun About(version: String, onWebsite: () -> Unit, onLink: (String) -> Unit) {
    var licenses by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.bubble_idle), contentDescription = null, Modifier.size(56.dp))
        Column(Modifier.padding(start = 8.dp)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.ui_version, version), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconBadge(AppIcons.Lock, MaterialTheme.colorScheme.onPrimaryContainer, MaterialTheme.colorScheme.primaryContainer)
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.ui_privacy_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.ui_privacy_body), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
    LinkRow(AppIcons.Info, R.string.ui_licenses, if (licenses) AppIcons.ExpandLess else AppIcons.ExpandMore, { licenses = !licenses })
    if (licenses) {
        Column(Modifier.padding(start = 52.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (line in LICENSES) {
                if (line == R.string.ui_license_parakeet_v3) MultilingualCredit(onLink)
                else Text(stringResource(line), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    LinkRow(AppIcons.Lock, R.string.ui_privacy_policy, AppIcons.OpenInNew, { onLink(PRIVACY_POLICY) })
    LinkRow(AppIcons.Public, R.string.ui_website, AppIcons.OpenInNew, onWebsite, detail = WEBSITE.removePrefix("https://"))
}
