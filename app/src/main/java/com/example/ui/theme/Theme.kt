package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val TvDarkColorScheme = darkColorScheme(
    primary = BroadcastCyan,
    onPrimary = OnCyanDark,
    primaryContainer = BroadcastCyanDeep,
    onPrimaryContainer = TextPrimary,
    secondary = SignalAmber,
    onSecondary = OnCyanDark,
    secondaryContainer = ElevatedSlate,
    onSecondaryContainer = TextPrimary,
    tertiary = ConnectedEmerald,
    onTertiary = OnCyanDark,
    background = ObsidianBase,
    onBackground = TextPrimary,
    surface = MidnightPanel,
    onSurface = TextPrimary,
    surfaceVariant = ElevatedSlate,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = ObsidianVoid,
    surfaceContainerLow = ObsidianBase,
    surfaceContainer = MidnightPanel,
    surfaceContainerHigh = ElevatedSlate,
    surfaceContainerHighest = HighlightSlate,
    outline = DividerSlate,
    outlineVariant = HighlightSlate,
    error = LiveCrimson,
    onError = TextPrimary
)

@Composable
fun TiviWaveTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = TvDarkColorScheme,
        typography = Typography,
        content = content
    )
}
