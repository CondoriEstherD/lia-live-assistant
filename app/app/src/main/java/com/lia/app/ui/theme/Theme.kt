package com.lia.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Immutable
data class LiaExtendedColors(
    val borderSubtle: Color,
    val borderStrong: Color,
    val surfaceElevated: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val pinkSoftBg: Color,
    val pinkAccent: Color,
    val pinkStrong: Color,
    val statusInCatalogBg: Color,
    val statusInCatalogText: Color,
    val statusSoldBg: Color,
    val statusSoldText: Color,
    val statusArchivedBg: Color,
    val statusArchivedText: Color
)

val LightExtendedColors = LiaExtendedColors(
    borderSubtle = LiaBorderLight,
    borderStrong = LiaBorderStrongLight,
    surfaceElevated = LiaWhite,
    textSecondary = LiaGray80,
    textMuted = LiaGray70,
    pinkSoftBg = LiaPink10,
    pinkAccent = LiaPink,
    pinkStrong = LiaPink,
    statusInCatalogBg = LiaPink10,
    statusInCatalogText = LiaPink,
    statusSoldBg = StatusSoldBgLight,
    statusSoldText = StatusSoldTextLight,
    statusArchivedBg = LiaLightSurfaceSubtle,
    statusArchivedText = LiaGray70
)

val DarkExtendedColors = LiaExtendedColors(
    borderSubtle = LiaBorderDark,
    borderStrong = LiaBorderStrongDark,
    surfaceElevated = LiaGray90,
    textSecondary = LiaText2Dark,
    textMuted = LiaText3Dark,
    pinkSoftBg = LiaPink90,
    pinkAccent = LiaPink,
    pinkStrong = LiaPink,
    statusInCatalogBg = LiaPink90,
    statusInCatalogText = LiaPink,
    statusSoldBg = StatusSoldBgDark,
    statusSoldText = StatusSoldTextDark,
    statusArchivedBg = LiaGray85,
    statusArchivedText = LiaGray50
)

val LocalLiaColors = staticCompositionLocalOf { LightExtendedColors }

private val LiaLightColorScheme = lightColorScheme(
    primary = LiaPink,
    onPrimary = LiaPureWhite,
    primaryContainer = LiaPink10,
    onPrimaryContainer = LiaPink,
    secondary = LiaPink20,
    onSecondary = LiaBlack,
    secondaryContainer = StatusSoldBgLight,
    onSecondaryContainer = LiaPink70,
    tertiary = LiaBurgundy,
    onTertiary = LiaPureWhite,
    background = LiaPureWhite,
    onBackground = LiaBlack,
    surface = LiaPureWhite,
    onSurface = LiaBlack,
    surfaceVariant = LiaLightSurfaceSubtle,
    onSurfaceVariant = LiaGray70,
    outline = LiaBorderStrongLight,
    outlineVariant = LiaBorderLight,
    error = ErrorLight,
    onError = LiaWhite,
    errorContainer = ErrorContainerLight,
    onErrorContainer = ErrorLight
)

private val LiaDarkColorScheme = darkColorScheme(
    primary = LiaPink,
    onPrimary = LiaPureWhite,
    primaryContainer = LiaPink90,
    onPrimaryContainer = LiaPink,
    secondary = LiaPink20,
    onSecondary = LiaBlack,
    secondaryContainer = StatusSoldBgDark,
    onSecondaryContainer = LiaPink20,
    tertiary = LiaPink70,
    onTertiary = LiaPureWhite,
    background = LiaBlack,
    onBackground = LiaWhite,
    surface = LiaGray88,
    onSurface = LiaWhite,
    surfaceVariant = LiaGray90,
    onSurfaceVariant = LiaText2Dark,
    outline = LiaBorderStrongDark,
    outlineVariant = LiaBorderDark,
    error = ErrorDark,
    onError = LiaBlack,
    errorContainer = ErrorContainerDark,
    onErrorContainer = ErrorDark
)

object LiaTheme {
    val colors: LiaExtendedColors
        @Composable
        get() = LocalLiaColors.current
}

@Composable
fun LiaAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) LiaDarkColorScheme else LiaLightColorScheme
    val extendedColors = if (darkTheme) DarkExtendedColors else LightExtendedColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = colorScheme.background.toArgb()
                window.navigationBarColor = colorScheme.surface.toArgb()
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
    }

    CompositionLocalProvider(LocalLiaColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = LiaTypography,
            shapes = LiaShapes,
            content = content
        )
    }
}
