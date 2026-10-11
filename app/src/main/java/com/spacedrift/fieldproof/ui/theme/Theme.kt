package com.spacedrift.fieldproof.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = FieldBlue,
    onPrimary = OnFieldBlue,
    primaryContainer = FieldBlueContainer,
    onPrimaryContainer = OnFieldBlueContainer,
    secondary = FieldSlate,
    onSecondary = OnFieldSlate,
    secondaryContainer = FieldSlateContainer,
    onSecondaryContainer = OnFieldSlateContainer,
    tertiary = FieldGreen,
    onTertiary = OnFieldGreen,
    tertiaryContainer = FieldGreenContainer,
    onTertiaryContainer = OnFieldGreenContainer,
    error = ErrorRed,
    onError = OnErrorRed,
    errorContainer = ErrorRedContainer,
    onErrorContainer = OnErrorRedContainer,
    background = NeutralBackground,
    onBackground = NeutralOnBackground,
    surface = NeutralSurface,
    onSurface = NeutralOnSurface,
    surfaceVariant = NeutralSurfaceVariant,
    onSurfaceVariant = NeutralOnSurfaceVariant,
    outline = NeutralOutline,
    outlineVariant = NeutralOutlineVariant,
    inverseSurface = NeutralInverseSurface,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F8FA),
    surfaceContainer = Color(0xFFF0F3F6),
    surfaceContainerHigh = Color(0xFFEAEDF1),
    surfaceContainerHighest = Color(0xFFE4E9EE)
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkFieldBlue,
    onPrimary = DarkOnFieldBlue,
    primaryContainer = DarkFieldBlueContainer,
    onPrimaryContainer = DarkOnFieldBlueContainer,
    secondary = DarkFieldSlate,
    onSecondary = DarkOnFieldSlate,
    secondaryContainer = DarkFieldSlateContainer,
    onSecondaryContainer = DarkOnFieldSlateContainer,
    tertiary = DarkFieldGreen,
    onTertiary = DarkOnFieldGreen,
    tertiaryContainer = DarkFieldGreenContainer,
    onTertiaryContainer = DarkOnFieldGreenContainer,
    error = DarkErrorRed,
    onError = DarkOnErrorRed,
    errorContainer = DarkErrorRedContainer,
    onErrorContainer = DarkOnErrorRedContainer,
    background = DarkNeutralBackground,
    onBackground = DarkNeutralOnBackground,
    surface = DarkNeutralSurface,
    onSurface = DarkNeutralOnSurface,
    surfaceVariant = DarkNeutralSurfaceVariant,
    onSurfaceVariant = DarkNeutralOnSurfaceVariant,
    outline = DarkNeutralOutline,
    outlineVariant = DarkNeutralOutlineVariant,
    inverseSurface = DarkNeutralInverseSurface,
    surfaceContainerLowest = Color(0xFF0C0F12),
    surfaceContainerLow = Color(0xFF15191C),
    surfaceContainer = Color(0xFF191E22),
    surfaceContainerHigh = Color(0xFF1E2428),
    surfaceContainerHighest = Color(0xFF292F34)
)

@Composable
fun FieldProofTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Brand palette is intentional; dynamic color is only for the launcher demo.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            }
        }
    }

    val statusColors = if (darkTheme) DarkStatusColors else LightStatusColors
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    CompositionLocalProvider(
        LocalStatusColors provides statusColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}