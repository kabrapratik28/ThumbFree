package io.github.kabrapratik28.thumbfree.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// The brand palette (docs/brand/common/README.md): sunflower yellows, the key's ink, recording red.
internal val SunflowerLight = Color(0xFFFFD35A)
internal val SunflowerDeep = Color(0xFFFFB61E)
internal val Sunflower = Color(0xFFFFC83D)
internal val Ink = Color(0xFF1F1B3A)
private val InkFace = Color(0xFF39335F)
val RecordingRed = Color(0xFFFF3B30)

// Light: warm paper, ink text and buttons, sunflower accents (the selected tab, cards that ask for attention).
private val LightColors = lightColorScheme(
    primary = InkFace, onPrimary = Color.White,
    primaryContainer = SunflowerLight, onPrimaryContainer = Ink,
    inversePrimary = Sunflower,
    secondary = Color(0xFF7A5A00), onSecondary = Color.White,
    secondaryContainer = SunflowerLight, onSecondaryContainer = Ink,
    tertiary = Color(0xFF7A5A00), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE9B0), onTertiaryContainer = Ink,
    background = Color(0xFFFFFBF5), onBackground = Ink,
    surface = Color(0xFFFFFBF5), onSurface = Ink,
    surfaceVariant = Color(0xFFF3E9D7), onSurfaceVariant = Color(0xFF4E4966),
    surfaceTint = InkFace,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFFF6E8),
    surfaceContainer = Color(0xFFFCF1DE), surfaceContainerHigh = Color(0xFFF7EBD6),
    surfaceContainerHighest = Color(0xFFF1E5CF),
    inverseSurface = Color(0xFF2F2B4A), inverseOnSurface = Color(0xFFF5F1FF),
    outline = Color(0xFF7C7791), outlineVariant = Color(0xFFDDD3C1),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
)

// Dark: the key's ink as the surfaces, sunflower buttons with ink text, the key's top face for containers.
private val DarkColors = darkColorScheme(
    primary = Sunflower, onPrimary = Ink,
    primaryContainer = InkFace, onPrimaryContainer = SunflowerLight,
    inversePrimary = Color(0xFF7A5A00),
    secondary = Color(0xFFF2CF72), onSecondary = Color(0xFF3A2C00),
    secondaryContainer = Color(0xFF4A4375), onSecondaryContainer = Color(0xFFFFE08A),
    tertiary = Color(0xFFF2CF72), onTertiary = Color(0xFF3A2C00),
    tertiaryContainer = Color(0xFF4A4375), onTertiaryContainer = Color(0xFFFFE08A),
    background = Color(0xFF15122B), onBackground = Color(0xFFEDE9F7),
    surface = Color(0xFF15122B), onSurface = Color(0xFFEDE9F7),
    surfaceVariant = Color(0xFF363156), onSurfaceVariant = Color(0xFFC9C3DC),
    surfaceTint = Sunflower,
    surfaceContainerLowest = Color(0xFF100E22), surfaceContainerLow = Color(0xFF1C1935),
    surfaceContainer = Color(0xFF211D3C), surfaceContainerHigh = Color(0xFF2A2647),
    surfaceContainerHighest = Color(0xFF353152),
    inverseSurface = Color(0xFFEDE9F7), inverseOnSurface = Color(0xFF2A2647),
    outline = Color(0xFF938DAA), outlineVariant = Color(0xFF443F60),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
)

private val TypeScale = Typography().run {
    copy(
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

// Rounded like the key in the icon: cards 20 dp, small parts 12 dp.
private val ShapeScale = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Follows the system's dark theme setting. */
@Composable
fun AppTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) =
    MaterialTheme(if (dark) DarkColors else LightColors, ShapeScale, TypeScale, content)

private val ColorScheme.dark get() = background.luminance() < 0.5f

/** Done, ready: Material 3 has no success role, so a green that reads on both schemes. */
val ColorScheme.success: Color get() = if (dark) Color(0xFF7DDBA3) else Color(0xFF1B7A4A)

/** Needs a look (the text may already be in the field): amber, not the error red. */
val ColorScheme.caution: Color get() = if (dark) Color(0xFFFFC83D) else Color(0xFF8A5A00)

/** The circle behind a row's icon: near white on light surfaces, a raised ink on dark ones. */
val ColorScheme.badge: Color get() = if (dark) surfaceContainerHighest else surfaceContainerLowest

/** A button that deletes: the error colour, so it never reads as the safe choice. */
@Composable
fun destructive(): ButtonColors =
    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)

/** A chosen option's fill and text: sunflower on light, the brighter sunflower on dark, where the tonal container is muted. */
val ColorScheme.chosen: Color get() = if (dark) primary else secondaryContainer
val ColorScheme.onChosen: Color get() = if (dark) onPrimary else onSecondaryContainer
