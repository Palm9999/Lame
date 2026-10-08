package dev.gridiron.core.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// A fixed, team-neutral palette by default: a data app should look the same whatever the wallpaper, and the heat
// scale is tuned against these surfaces. Wallpaper colors are the user's choice (Settings → Look).
private val Light = lightColorScheme(
    primary = Color(0xFF1E6B45),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB7F0CB),
    onPrimaryContainer = Color(0xFF002111),
    secondaryContainer = Color(0xFFD3E8D9),
    onSecondaryContainer = Color(0xFF0F1F15),
    // Amber: Questionable badges and other "watch this" accents, apart from the green brand and the red errors.
    tertiary = Color(0xFF8A5100),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDB8),
    onTertiaryContainer = Color(0xFF2C1600),
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
    tertiary = Color(0xFFFFB960),
    onTertiary = Color(0xFF4A2800),
    tertiaryContainer = Color(0xFF693C00),
    onTertiaryContainer = Color(0xFFFFDDB8),
    surface = Color(0xFF111412),
    surfaceContainer = Color(0xFF1D201E),
    surfaceContainerLow = Color(0xFF181B19),
    surfaceContainerHigh = Color(0xFF272B28),
    onSurface = Color(0xFFE1E3DF),
    onSurfaceVariant = Color(0xFFC0C9C0),
    outlineVariant = Color(0xFF414942),
)

@Composable
public fun GridironTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** Android's colors from the wallpaper (Android 12 and later; the app's own palette before). */
    wallpaper: Boolean = false,
    /** Black surfaces in dark mode, for OLED screens. */
    trueBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val base = when {
        wallpaper && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> Dark
        else -> Light
    }
    val scheme = if (darkTheme && trueBlack) {
        base.copy(
            surface = Color.Black,
            background = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color(0xFF0B0B0B),
            surfaceContainer = Color(0xFF121212),
            surfaceContainerHigh = Color(0xFF1B1B1B),
            surfaceContainerHighest = Color(0xFF242424),
        )
    } else {
        base
    }
    MaterialTheme(colorScheme = scheme, content = content)
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
