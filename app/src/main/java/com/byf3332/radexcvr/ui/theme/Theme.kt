package com.byf3332.radexcvr.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = SkyBlue,
    onPrimary = AppSurface,
    secondary = Aqua,
    onSecondary = AppText,
    tertiary = SkyBlueDark,
    background = AppBackground,
    onBackground = AppText,
    surface = AppSurface,
    onSurface = AppText,
    surfaceVariant = AppSurfaceAlt,
    onSurfaceVariant = AppTextMuted,
    outline = AppBorder
)

@Composable
fun RADEXCVRTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        content = content
    )
}
