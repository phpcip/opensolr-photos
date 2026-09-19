package com.opensolr.photos.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.opensolr.photos.R

/**
 * The colours the app actually uses, beyond Material's scheme: the Opensolr editorial palette.
 * Flat surfaces, hairlines instead of shadows, one accent.
 */
@Immutable
data class Palette(
    val paper: Color,
    val band: Color,
    val chip: Color,
    val ink: Color,
    val muted: Color,
    val hairline: Color,
    val accent: Color,
    val onAccent: Color,
    /**
     * The accent as a filled surface behind text or an icon: a deeper tone than [accent], so
     * white on it clears 4.5:1 in both themes. [accent] itself stays for hairlines, rules,
     * icons and the slider, where it sits on paper and nothing has to be read out of it.
     */
    val accentFill: Color,
    val onAccentFill: Color,
    /**
     * The quiet fill behind the buttons over the grid and in the header (Cip, 2026-09-20): on
     * paper a bordered cell alone barely read as a button. On the dark theme it is the paper
     * itself, which already read well.
     */
    val buttonFill: Color,
)

private val LightPalette = Palette(
    paper = Color(0xFFFFFFFF),
    band = Color(0xFFFBFAF8),
    chip = Color(0xFFF4F1EC),
    ink = Color(0xFF111111),
    muted = Color(0xFF4A4540),
    hairline = Color(0xFFD9D4CC),
    accent = Color(0xFFC05520),
    onAccent = Color(0xFFFFFFFF),
    // White on #A8481B is 5.8:1; the brighter accent would have been 4.6:1, right on the line.
    accentFill = Color(0xFFA8481B),
    onAccentFill = Color(0xFFFFFFFF),
    buttonFill = Color(0xFFF3EFE9),
)

private val DarkPalette = Palette(
    paper = Color(0xFF111111),
    band = Color(0xFF181715),
    chip = Color(0xFF22201D),
    ink = Color(0xFFF4F1EC),
    muted = Color(0xFFB9B3A9),
    hairline = Color(0xFF3A3632),
    accent = Color(0xFFE0703A),
    // Was near-black on orange: it measured as passing but read badly (Cip, 2026-09-16). White
    // on the fill is 4.9:1 and looks like a button instead of a warning label.
    onAccent = Color(0xFFFFFFFF),
    accentFill = Color(0xFFB4551F),
    onAccentFill = Color(0xFFFFFFFF),
    buttonFill = Color(0xFF111111),
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

/**
 * Space Grotesk, bundled (SIL Open Font License, see third_party/space-grotesk/OFL.txt), as a
 * variable font driven to each weight.
 */
@OptIn(ExperimentalTextApi::class)
private val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.space_grotesk, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.space_grotesk, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.space_grotesk, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

private fun style(size: Int, weight: FontWeight, line: Double = 1.4, tracking: Double = 0.0) = TextStyle(
    fontFamily = SpaceGrotesk,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = (size * line).sp,
    letterSpacing = tracking.em,
)

private val AppTypography = Typography(
    displaySmall = style(34, FontWeight.Bold, 1.08, -0.03),
    headlineMedium = style(28, FontWeight.Bold, 1.1, -0.02),
    headlineSmall = style(22, FontWeight.Bold, 1.2, -0.01),
    titleLarge = style(20, FontWeight.Bold, 1.25),
    titleMedium = style(17, FontWeight.Bold, 1.3),
    titleSmall = style(15, FontWeight.SemiBold, 1.3),
    // One step heavier than regular (Cip, 2026-09-20): at 400 the text read hairline-thin.
    bodyLarge = style(17, FontWeight.Medium, 1.5),
    bodyMedium = style(15, FontWeight.Medium, 1.5),
    bodySmall = style(14, FontWeight.Medium, 1.45),
    labelLarge = style(15, FontWeight.Bold, 1.2),
    labelMedium = style(14, FontWeight.Bold, 1.2, 0.08),
    labelSmall = style(14, FontWeight.SemiBold, 1.2),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(2.dp),
    medium = RoundedCornerShape(2.dp),
    large = RoundedCornerShape(2.dp),
    extraLarge = RoundedCornerShape(2.dp),
)

/**
 * Maps the palette onto Material's colour roles so stock components match.
 */
private fun scheme(p: Palette, dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        // Stock components paint primary as a filled surface and write onPrimary on it, so the
        // pair that is readable in both themes goes here, not the bright accent.
        primary = p.accentFill,
        onPrimary = p.onAccentFill,
        primaryContainer = p.chip,
        onPrimaryContainer = p.ink,
        secondary = p.ink,
        onSecondary = p.paper,
        secondaryContainer = p.chip,
        onSecondaryContainer = p.ink,
        background = p.paper,
        onBackground = p.ink,
        surface = p.paper,
        onSurface = p.ink,
        surfaceVariant = p.band,
        onSurfaceVariant = p.muted,
        surfaceContainer = p.paper,
        surfaceContainerLow = p.paper,
        surfaceContainerHigh = p.band,
        surfaceContainerHighest = p.chip,
        surfaceContainerLowest = p.paper,
        outline = p.hairline,
        outlineVariant = p.hairline,
        error = p.accentFill,
        onError = p.onAccentFill,
    )
}

/**
 * The app theme, light or dark following the system.
 */
@Composable
fun OpensolrPhotosTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val palette = if (dark) DarkPalette else LightPalette
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(
            colorScheme = scheme(palette, dark),
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
