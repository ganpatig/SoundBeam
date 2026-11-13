package dev.soundbeam.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

private val Light = lightColorScheme(
    primary = Color(0xFF1767A6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD4E7F8),
    onPrimaryContainer = Color(0xFF00284A),
    secondary = Color(0xFF4C6276),
    secondaryContainer = Color(0xFFDDE7F1),
    onSecondaryContainer = Color(0xFF0B1E2E),
    tertiary = Color(0xFF7A4E9C),
    background = Color(0xFFF6F8FB),
    onBackground = Color(0xFF181C20),
    surface = Color(0xFFF6F8FB),
    onSurface = Color(0xFF181C20),
    surfaceVariant = Color(0xFFE7ECF2),
    onSurfaceVariant = Color(0xFF4A5560),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF0F3F7),
    surfaceContainer = Color(0xFFEAEFF4),
    surfaceContainerHigh = Color(0xFFE4E9EF),
    surfaceContainerHighest = Color(0xFFDEE4EA),
    outlineVariant = Color(0xFFC7CFD8),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9DCBF5),
    onPrimary = Color(0xFF00325A),
    primaryContainer = Color(0xFF0D4A7D),
    onPrimaryContainer = Color(0xFFD4E7F8),
    secondary = Color(0xFFB4C9DD),
    secondaryContainer = Color(0xFF34495C),
    onSecondaryContainer = Color(0xFFD7E4F1),
    tertiary = Color(0xFFDDB8F7),
    background = Color(0xFF0F1317),
    onBackground = Color(0xFFE0E3E8),
    surface = Color(0xFF0F1317),
    onSurface = Color(0xFFE0E3E8),
    surfaceVariant = Color(0xFF2A3239),
    onSurfaceVariant = Color(0xFFBFC8D1),
    surfaceContainerLowest = Color(0xFF1A2127), // card colour in dark mode
    surfaceContainerLow = Color(0xFF171C21),
    surfaceContainer = Color(0xFF1B2126),
    surfaceContainerHigh = Color(0xFF252B31),
    surfaceContainerHighest = Color(0xFF30363C),
    outlineVariant = Color(0xFF3F4850),
    error = Color(0xFFF2B8B5),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

private val Base = Typography()
private val AppTypography = Base.copy(
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
)

@Composable
fun SoundBeamTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = AppTypography, content = content)
}
