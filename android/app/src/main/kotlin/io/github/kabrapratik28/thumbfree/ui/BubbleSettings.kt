package io.github.kabrapratik28.thumbfree.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.kabrapratik28.thumbfree.R
import io.github.kabrapratik28.thumbfree.app.AppGraph
import io.github.kabrapratik28.thumbfree.core.session.BubbleStyle
import io.github.kabrapratik28.thumbfree.core.session.PreviewPlace
import kotlin.math.roundToInt

/**
 * Settings > Bubble: a preview of the bubble idle and listening at [style], its size, how transparent it is while idle,
 * and Reset to recommended; then its position: where it is ([placed]: dropped by the owner, else beside the keyboard),
 * Snap to screen edge and Reset position. Each change reaches the floating bubble at once.
 */
@Composable
fun BubbleSection(
    style: BubbleStyle, onStyle: (BubbleStyle) -> Unit,
    placed: Boolean, snap: Boolean, onSnap: (Boolean) -> Unit, onResetPosition: () -> Unit,
) {
    BubblePreview(style)
    Text(stringResource(R.string.ui_bubble_size), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
    // One tile carries a second line (Recommended): the same height for all keeps the two rows even.
    Choices(BubbleStyle.Size.entries, style.size, { sizeLabel(it) }, minHeight = 64.dp) { onStyle(style.copy(size = it)) }

    // Shown as transparency: 0% (solid) to 70%, the other way round from the stored opacity (100 to 30%).
    val transparency = BubbleStyle.MAX_OPACITY - style.opacity
    Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.ui_bubble_transparency), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.ui_bubble_transparency_value, transparency), style = MaterialTheme.typography.titleMedium)
    }
    // Whole 5% steps, on a plain track (step marks would crowd it). TalkBack hears the percent itself, not where the thumb
    // sits in the range.
    val label = stringResource(R.string.ui_bubble_transparency)
    val spoken = stringResource(R.string.ui_bubble_transparency_spoken, transparency)
    Slider(
        value = transparency.toFloat(),
        onValueChange = { value ->
            (BubbleStyle.MAX_OPACITY - (value / 5).roundToInt() * 5).let { if (it != style.opacity) onStyle(style.copy(opacity = it)) }
        },
        modifier = Modifier.semantics { contentDescription = label; stateDescription = spoken },
        valueRange = 0f..(BubbleStyle.MAX_OPACITY - BubbleStyle.MIN_OPACITY).toFloat(),
    )
    Text(
        stringResource(R.string.ui_bubble_transparency_note, BubbleStyle.MAX_OPACITY - BubbleStyle.RECOMMENDED.opacity),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton({ onStyle(BubbleStyle.RECOMMENDED) }, Modifier.padding(top = 4.dp), enabled = style != BubbleStyle.RECOMMENDED) {
        Icon(AppIcons.Refresh, contentDescription = null, Modifier.size(18.dp))
        Text(stringResource(R.string.ui_bubble_reset), Modifier.padding(start = 6.dp))
    }

    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
    Text(stringResource(R.string.ui_bubble_position), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(if (placed) R.string.ui_bubble_position_placed else R.string.ui_bubble_position_auto),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(snap, role = Role.Switch, onValueChange = onSnap).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.ui_bubble_snap), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.ui_bubble_snap_note), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(snap, onCheckedChange = null) // the row takes the tap
    }
    TextButton(onResetPosition, enabled = placed) {
        Icon(AppIcons.Refresh, contentDescription = null, Modifier.size(18.dp))
        Text(stringResource(R.string.ui_bubble_position_reset), Modifier.padding(start = 6.dp))
    }
}

/** The bubble over a bit of app content, idle (at the opacity) and listening (always solid), at the chosen size. */
@Composable
private fun BubblePreview(style: BubbleStyle) {
    val description = stringResource(R.string.ui_bubble_preview, sizeName(style.size), BubbleStyle.MAX_OPACITY - style.opacity)
    Row(Modifier.clearAndSetSemantics { contentDescription = description }, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PreviewPane(R.string.ui_bubble_idle, R.drawable.bubble_idle, style, style.opacity / 100f, Modifier.weight(1f))
        PreviewPane(R.string.ui_bubble_listening, R.drawable.bubble_recording, style, 1f, Modifier.weight(1f))
    }
}

@Composable
private fun PreviewPane(label: Int, @DrawableRes art: Int, style: BubbleStyle, alpha: Float, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(124.dp).clip(MaterialTheme.shapes.medium).background(colors.surfaceContainerHigh)) {
            // Lines of text behind the bubble, so what the opacity lets through shows.
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                for (width in listOf(1f, 0.9f, 1f, 0.75f, 0.95f, 0.6f)) {
                    Box(Modifier.fillMaxWidth(width).height(8.dp).clip(MaterialTheme.shapes.extraLarge).background(colors.onSurfaceVariant.copy(alpha = 0.3f)))
                }
            }
            Image(
                painterResource(art), contentDescription = null,
                Modifier.align(Alignment.CenterEnd).padding(end = 10.dp).size(style.size.artDp.dp).alpha(alpha),
            )
        }
        Text(
            stringResource(label), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun sizeName(size: BubbleStyle.Size): String = stringResource(
    when (size) {
        BubbleStyle.Size.SMALL -> R.string.ui_bubble_small
        BubbleStyle.Size.MEDIUM -> R.string.ui_bubble_medium
        BubbleStyle.Size.LARGE -> R.string.ui_bubble_large
        BubbleStyle.Size.EXTRA_LARGE -> R.string.ui_bubble_extra_large
    },
)

/** The size's tile: its name, and Recommended under the recommended one. */
@Composable
private fun sizeLabel(size: BubbleStyle.Size): String =
    if (size == BubbleStyle.RECOMMENDED.size) sizeName(size) + "\n" + stringResource(R.string.ui_bubble_recommended) else sizeName(size)
