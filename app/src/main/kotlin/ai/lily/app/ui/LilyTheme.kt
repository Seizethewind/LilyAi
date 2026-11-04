package ai.lily.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Lily's look: pond-green neutrals, a rose petal accent, and stamen gold for anything that needs the person.
 * Material's scheme carries background, text, the petal (primary) and errors; the rest lives here. Read it with [LilyUi.colors].
 */
@Immutable
class LilyColors(
    val dark: Boolean,
    /** Bars, sheets, dialogs and the message composer. */
    val raised: Color,
    /** Wells: text fields and segmented tracks. */
    val sunken: Color,
    val hairline: Color,
    val textDim: Color,
    val textFaint: Color,
    /** A petal tint for selected things. */
    val petalSoft: Color,
    val stamen: Color,
    val onStamen: Color,
    val stamenSoft: Color,
    val pad: Color,
    val padSoft: Color,
    val coral: Color,
    val coralSoft: Color,
)

private val PondColors = LilyColors(
    dark = true,
    raised = Color(0xFF122527),
    sunken = Color(0xFF0D1C1E),
    hairline = Color(0xFF244245),
    textDim = Color(0xFF93AEA7),
    textFaint = Color(0xFF668079),
    petalSoft = Color(0xFF3A2230),
    stamen = Color(0xFFE9C46A),
    onStamen = Color(0xFF2A2000),
    stamenSoft = Color(0xFF3A331A),
    pad = Color(0xFF86D3A3),
    padSoft = Color(0xFF163528),
    coral = Color(0xFFF28B82),
    coralSoft = Color(0xFF3F1F20),
)

private val PetalColors = LilyColors(
    dark = false,
    raised = Color(0xFFFFFFFF),
    sunken = Color(0xFFE8F0EB),
    hairline = Color(0xFFD0DED6),
    textDim = Color(0xFF4F6B65),
    textFaint = Color(0xFF6E8882),
    petalSoft = Color(0xFFF8DDE6),
    stamen = Color(0xFF7A5C00),
    onStamen = Color(0xFFFFFFFF),
    stamenSoft = Color(0xFFF7E8B5),
    pad = Color(0xFF1B6E45),
    padSoft = Color(0xFFD5EBDD),
    coral = Color(0xFFB3261E),
    coralSoft = Color(0xFFF9DAD7),
)

private val PondScheme = darkColorScheme(
    primary = Color(0xFFF2B5C7),
    onPrimary = Color(0xFF3B1022),
    primaryContainer = PondColors.petalSoft,
    onPrimaryContainer = Color(0xFFF2B5C7),
    secondary = PondColors.pad,
    onSecondary = Color(0xFF0B2A1A),
    secondaryContainer = PondColors.padSoft,
    onSecondaryContainer = PondColors.pad,
    tertiary = PondColors.stamen,
    onTertiary = PondColors.onStamen,
    tertiaryContainer = PondColors.stamenSoft,
    onTertiaryContainer = PondColors.stamen,
    background = Color(0xFF0B1819),
    onBackground = Color(0xFFE6F0EB),
    surface = Color(0xFF0B1819),
    onSurface = Color(0xFFE6F0EB),
    surfaceVariant = PondColors.sunken,
    onSurfaceVariant = PondColors.textDim,
    surfaceTint = Color(0xFFF2B5C7),
    outline = PondColors.textFaint,
    outlineVariant = PondColors.hairline,
    error = PondColors.coral,
    onError = Color(0xFF3A0B09),
    errorContainer = PondColors.coralSoft,
    onErrorContainer = PondColors.coral,
    surfaceContainerLowest = Color(0xFF0B1819),
    surfaceContainerLow = PondColors.raised,
    surfaceContainer = PondColors.raised,
    surfaceContainerHigh = PondColors.raised,
    surfaceContainerHighest = PondColors.raised,
)

private val PetalScheme = lightColorScheme(
    primary = Color(0xFFA3305A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = PetalColors.petalSoft,
    onPrimaryContainer = Color(0xFF7A1F42),
    secondary = PetalColors.pad,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = PetalColors.padSoft,
    onSecondaryContainer = PetalColors.pad,
    tertiary = PetalColors.stamen,
    onTertiary = PetalColors.onStamen,
    tertiaryContainer = PetalColors.stamenSoft,
    onTertiaryContainer = PetalColors.stamen,
    background = Color(0xFFF2F6F3),
    onBackground = Color(0xFF0F211E),
    surface = Color(0xFFF2F6F3),
    onSurface = Color(0xFF0F211E),
    surfaceVariant = PetalColors.sunken,
    onSurfaceVariant = PetalColors.textDim,
    surfaceTint = Color(0xFFA3305A),
    outline = PetalColors.textFaint,
    outlineVariant = PetalColors.hairline,
    error = PetalColors.coral,
    onError = Color(0xFFFFFFFF),
    errorContainer = PetalColors.coralSoft,
    onErrorContainer = PetalColors.coral,
    surfaceContainerLowest = Color(0xFFF2F6F3),
    surfaceContainerLow = PetalColors.raised,
    surfaceContainer = PetalColors.raised,
    surfaceContainerHigh = PetalColors.raised,
    surfaceContainerHighest = PetalColors.raised,
)

// Serif for titles (the name carries weight), a plain sans for everything people read in passing, mono for code.
private val Serif = FontFamily.Serif
private val Sans = FontFamily.SansSerif

private val LilyTypography = Typography(
    headlineMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.2).sp),
    headlineSmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.1).sp),
    titleLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 21.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.1.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp, letterSpacing = 0.1.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = 0.2.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.3.sp),
)

/** Corner radii by role: fields 14, cards 20, sheets 28. Buttons and chips are pills and use CircleShape directly. */
private val LilyShapes = Shapes(
    extraSmall = RoundedCornerShape(6),
    small = RoundedCornerShape(10),
    medium = RoundedCornerShape(14),
    large = RoundedCornerShape(20),
    extraLarge = RoundedCornerShape(28),
)

private val LocalLilyColors = staticCompositionLocalOf { PondColors }

/** Where the extra tokens are read from, next to MaterialTheme. */
object LilyUi {
    val colors: LilyColors
        @Composable
        @ReadOnlyComposable
        get() = LocalLilyColors.current
}

/** [dark] picks pond (dark) or petal (light). The whole app, including dialogs and sheets, is drawn inside this. */
@Composable
fun LilyTheme(dark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLilyColors provides if (dark) PondColors else PetalColors) {
        MaterialTheme(
            colorScheme = if (dark) PondScheme else PetalScheme,
            typography = LilyTypography,
            shapes = LilyShapes,
            content = content,
        )
    }
}
