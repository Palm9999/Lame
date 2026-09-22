package dev.gridiron.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// A fixed, team-neutral palette. Not dynamic color: a data app should look the
// same whatever the wallpaper, and the heat scale is tuned against these surfaces.
private val Light = lightColorScheme(
    primary = Color(0xFF1E6B45),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB7F0CB),
    onPrimaryContainer = Color(0xFF002111),
    secondaryContainer = Color(0xFFD3E8D9),
    onSecondaryContainer = Color(0xFF0F1F15),
    surface = Color(0xFFF8FAF7),
    surfaceContainer = Color(0xFFECEFEA),
    surfaceContainerLow = Color(0xFFF2F5F0),
    surfaceContainerHigh = Color(0xFFE6E9E4),
    onSurface = Color(0xFF191C1A),
    onSurfaceVariant = Color(0xFF414942),
    outlineVariant = Color(0xFFC0C9C0),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8BD7A7),
    onPrimary = Color(0xFF003920),
    primaryContainer = Color(0xFF005231),
    onPrimaryContainer = Color(0xFFB7F0CB),
    secondaryContainer = Color(0xFF2B3D31),
    onSecondaryContainer = Color(0xFFD3E8D9),
    surface = Color(0xFF111412),
    surfaceContainer = Color(0xFF1D201E),
    surfaceContainerLow = Color(0xFF181B19),
    surfaceContainerHigh = Color(0xFF272B28),
    onSurface = Color(0xFFE1E3DF),
    onSurfaceVariant = Color(0xFFC0C9C0),
    outlineVariant = Color(0xFF414942),
)

@Composable
public fun GridironTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) Dark else Light, content = content)
}

/** Numbers in tables: tabular figures so digits line up column-wise. */
public val NumberStyle: TextStyle = TextStyle(
    fontSize = 14.sp,
    fontFeatureSettings = "tnum",
)

public val HeaderStyle: TextStyle = TextStyle(
    fontSize = 12.sp,
    fontWeight = FontWeight.SemiBold,
    letterSpacing = 0.3.sp,
)
