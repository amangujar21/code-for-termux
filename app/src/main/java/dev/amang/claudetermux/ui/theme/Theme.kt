package dev.amang.claudetermux.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Colours Material's scheme has no slot for. */
@Immutable
data class ExtraColors(
    val composer: Color,
    val composerBorder: Color,
    val chip: Color,
    val inlineCode: Color,
    val inlineCodeBackground: Color,
    val codeBackground: Color,
    val muted: Color,
    val divider: Color,
    val success: Color,
    val diffAdd: Color,
    val diffRemove: Color,
)

private val Terracotta = Color(0xFFC6613F)

private val DarkScheme = darkColorScheme(
    primary = Terracotta,
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFFD9A28E),
    background = Color(0xFF1F1E1D),
    onBackground = Color(0xFFF2F0EA),
    surface = Color(0xFF1F1E1D),
    onSurface = Color(0xFFF2F0EA),
    surfaceVariant = Color(0xFF30302E),
    onSurfaceVariant = Color(0xFFB8B5AD),
    surfaceContainer = Color(0xFF2A2927),
    surfaceContainerLow = Color(0xFF262624),
    surfaceContainerHigh = Color(0xFF30302E),
    surfaceContainerHighest = Color(0xFF3A3937),
    outline = Color(0xFF55534E),
    outlineVariant = Color(0xFF3D3C39),
    error = Color(0xFFE5796B),
    onError = Color(0xFF1F1E1D),
)

private val LightScheme = lightColorScheme(
    primary = Terracotta,
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF8A4A35),
    background = Color(0xFFFAF9F5),
    onBackground = Color(0xFF1F1E1D),
    surface = Color(0xFFFAF9F5),
    onSurface = Color(0xFF1F1E1D),
    surfaceVariant = Color(0xFFEDEAE2),
    onSurfaceVariant = Color(0xFF5F5C55),
    surfaceContainer = Color(0xFFF2F0EA),
    surfaceContainerLow = Color(0xFFF5F4EE),
    surfaceContainerHigh = Color(0xFFEDEAE2),
    surfaceContainerHighest = Color(0xFFE5E2D9),
    outline = Color(0xFFB8B5AD),
    outlineVariant = Color(0xFFDAD7CE),
    error = Color(0xFFB3392B),
)

private val DarkExtra = ExtraColors(
    composer = Color(0xFF2B2A28),
    composerBorder = Color(0xFF3D3C39),
    chip = Color(0xFF3A3937),
    inlineCode = Color(0xFF7DB3F2),
    inlineCodeBackground = Color(0xFF30302E),
    codeBackground = Color(0xFF171716),
    muted = Color(0xFF9A978F),
    divider = Color(0xFF34332F),
    success = Color(0xFF7FB77E),
    diffAdd = Color(0xFF22382A),
    diffRemove = Color(0xFF43262A),
)

private val LightExtra = ExtraColors(
    composer = Color(0xFFFFFFFF),
    composerBorder = Color(0xFFDAD7CE),
    chip = Color(0xFFEDEAE2),
    inlineCode = Color(0xFF1F5FB0),
    inlineCodeBackground = Color(0xFFEDEAE2),
    codeBackground = Color(0xFFF0EEE6),
    muted = Color(0xFF7A776F),
    divider = Color(0xFFE5E2D9),
    success = Color(0xFF3F7D3E),
    diffAdd = Color(0xFFDDF0DF),
    diffRemove = Color(0xFFF7DEDC),
)

val LocalExtraColors = staticCompositionLocalOf { DarkExtra }

private val AppTypography = Typography().run {
    copy(
        bodyLarge = bodyLarge.copy(fontSize = 17.sp, lineHeight = 26.sp),
        bodyMedium = bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
        titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun AppTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalExtraColors provides if (dark) DarkExtra else LightExtra) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = AppTypography,
            content = content,
        )
    }
}

val extraColors: ExtraColors
    @Composable get() = LocalExtraColors.current
